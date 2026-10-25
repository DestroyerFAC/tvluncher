package fr.tvluncher.launcher.ui

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.Artworks
import fr.tvluncher.launcher.data.DisplayedArtwork
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import fr.tvluncher.launcher.databinding.ActivityHomeBinding
import fr.tvluncher.launcher.databinding.ViewQuickPillBinding
import fr.tvluncher.launcher.util.CrashGuard
import fr.tvluncher.launcher.util.Launch
import fr.tvluncher.launcher.util.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: a painting (or a wallpaper), the clock, a quick panel and one
 * row of favourites in a glass dock.
 *
 * Deliberately a single activity with no fragments and no nested scrolling. Every
 * d-pad press has to resolve into one focus change and one scroll, and the fewer
 * view layers there are between the remote and the tile, the less there is to drop
 * a frame over.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private lateinit var repository: AppRepository
    private lateinit var adapter: AppTileAdapter
    private var statusPill: StatusPill? = null
    private lateinit var volume: Volume

    /** imageId of the painting on screen, so a refresh never re-decodes the same file. */
    private var shownArtworkId: String? = null
    private var wallpaperJob: Job? = null

    /** "Show only the artwork": everything but the painting and its label is hidden. */
    private var uiHidden = false

    /** Cached so the tile menu can act on positions without re-reading prefs. */
    private var favouriteApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        repository = AppRepository.get(this)

        // A launcher is what the user stares at; letting the panel dim mid-browse
        // is a worse experience than the negligible power it saves.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setUpRow()
        setUpPill()
        setUpButtons()
        setUpQuickPanel()
        observe()

        // Back on the home screen means "already home": swallowing it stops the
        // system from finishing the activity and leaving a black screen behind.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (uiHidden) {
                    setUiHidden(false)
                    return
                }
                binding.favouritesRow.scrollToPosition(0)
                focusFirstTile()
            }
        })

        lifecycleScope.launch {
            // Survived long enough to be considered a good build.
            delay(HEALTHY_AFTER_MS)
            CrashGuard.markHealthy(this@HomeActivity)
        }
    }

    private fun setUpRow() {
        adapter = AppTileAdapter(
            layoutRes = R.layout.item_app_tile,
            scope = lifecycleScope,
            animations = { prefs.animationsEnabled },
            showLabels = { prefs.showLabels },
            onClick = { app -> Launch.app(this, app) },
            onLongClick = { app -> showTileMenu(app) },
        )

        val row = binding.favouritesRow
        row.layoutManager = CenteringLayoutManager(this)
        row.setHasFixedSize(true)
        // The row holds a handful of tiles; keeping them all alive removes the
        // rebind (and the icon lookup) from every single d-pad press.
        row.setItemViewCacheSize(12)
        // Nothing on this row is inserted or removed while it is on screen, and a
        // change animation would only fight the focus animation for frames.
        row.itemAnimator = null
        row.adapter = adapter
    }

    private fun setUpPill() {
        statusPill = StatusPill(
            clockView = binding.clock,
            dateView = binding.date,
            weatherIcon = binding.weatherIcon,
            weatherTemp = binding.weatherTemp,
            scope = lifecycleScope,
        )
    }

    private fun setUpButtons() {
        binding.btnAllApps.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnNextArt.setOnClickListener {
            if (!prefs.galleryStyle) prefs.galleryStyle = true
            loadWallpaper(next = true)
        }
        binding.btnScreensaver.setOnClickListener { Launch.screensaverNow(this) }
        binding.btnRefresh.setOnClickListener {
            repository.refresh()
            statusPill?.refreshWeather()
            loadWallpaper()
        }
        binding.btnHideUi.setOnClickListener { setUiHidden(true) }
    }

    private fun setUpQuickPanel() {
        binding.pillStyle.setUp(R.drawable.ic_palette, R.string.pill_style) {
            prefs.galleryStyle = !prefs.galleryStyle
            loadWallpaper()
            // Classic collapses the panel, taking this pill with it.
            if (!prefs.galleryStyle) focusFirstTile()
        }
        binding.pillClock.setUp(R.drawable.ic_clock, R.string.pill_clock) {
            prefs.use24hClock = !prefs.use24hClock
            statusPill?.renderTime()
        }
        binding.pillWeather.setUp(R.drawable.ic_weather_partly_cloudy, R.string.pill_weather) {
            if (!prefs.hasWeatherLocation) {
                // Nothing to toggle until a city is chosen, which needs the keyboard.
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                prefs.weatherEnabled = !prefs.weatherEnabled
                statusPill?.refreshWeather()
            }
        }
        binding.pillLabels.setUp(R.drawable.ic_title, R.string.pill_labels) {
            prefs.showLabels = !prefs.showLabels
            // Same list, different look: submitList alone would not rebind.
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            // The row has a fixed size for d-pad speed; its height changes here.
            binding.favouritesRow.requestLayout()
        }

        volume = Volume(this)
        binding.volumePill.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val up = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_LEFT -> false
                else -> return@setOnKeyListener false
            }
            volume.step(up)
            renderVolume()
            true
        }
        binding.volumePill.setOnClickListener {
            volume.step(up = true)
            renderVolume()
        }

        binding.deviceName.text = deviceName()
    }

    private fun ViewQuickPillBinding.setUp(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        onClick: () -> Unit,
    ) {
        this.icon.setImageResource(icon)
        this.title.setText(title)
        root.contentDescription = root.context.getString(title)
        root.setOnClickListener { onClick() }
    }

    private fun renderQuickPanel() {
        binding.pillStyle.value.setText(
            if (prefs.galleryStyle) R.string.pill_style_gallery else R.string.pill_style_classic
        )
        binding.pillClock.value.setText(
            if (prefs.use24hClock) R.string.pill_clock_24 else R.string.pill_clock_12
        )
        binding.pillWeather.value.text = when {
            !prefs.hasWeatherLocation -> getString(R.string.settings_weather_city_none)
            prefs.weatherEnabled -> prefs.weatherCity
            else -> getString(R.string.off)
        }
        binding.pillLabels.value.setText(
            if (prefs.showLabels) R.string.pill_labels_shown else R.string.pill_labels_hidden
        )

        // Classic keeps only the first row (all apps, settings) so both stay reachable.
        val gallery = prefs.galleryStyle
        for (i in 1 until binding.quickPanel.childCount) {
            binding.quickPanel.getChildAt(i).visibility = if (gallery) View.VISIBLE else View.GONE
        }
        if (gallery) renderVolume()
    }

    private fun renderVolume() {
        if (!volume.adjustable) {
            binding.volumePill.visibility = View.GONE
            return
        }
        binding.volumePill.visibility = View.VISIBLE
        val max = volume.max
        val level = volume.current
        binding.volumeValue.text = level.toString()
        binding.volumeFill.background.setLevel(level * MAX_DRAWABLE_LEVEL / max)
    }

    /** The name the user gave the box ("Player TV Free"), else its model. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        val visibility = if (hidden) View.GONE else View.VISIBLE
        binding.clockBlock.visibility = visibility
        binding.rowContainer.visibility = visibility
        binding.scrimTop.visibility = visibility
        binding.scrim.visibility = visibility
        binding.quickPanel.visibility = visibility
        binding.hiddenHint.visibility = if (hidden) View.VISIBLE else View.GONE
        if (!hidden) focusFirstTile()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While only the painting shows, the first key press brings the UI back
        // and is not passed on, so it cannot also launch whatever had focus.
        if (uiHidden && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_HOME
        ) {
            setUiHidden(false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.apps, prefs.changes()) { apps, _ -> apps }
                    .collect { apps -> render(apps) }
            }
        }
    }

    private fun render(apps: List<AppInfo>) {
        if (apps.isEmpty()) return

        val ids = prefs.favourites.ifEmpty { seedFavourites(apps) }
        favouriteApps = repository.resolve(ids)

        adapter.favourites = favouriteApps.map { it.id }.toSet()
        adapter.submitList(favouriteApps) {
            if (binding.favouritesRow.findFocus() == null && currentFocus == null) {
                focusFirstTile()
            }
        }

        val empty = favouriteApps.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.favouritesRow.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        if (!uiHidden) {
            binding.clockBlock.visibility = if (prefs.showStatusPill) View.VISIBLE else View.GONE
        }
        renderQuickPanel()
    }

    /**
     * First run has nothing to show, so the row is seeded with the TV apps already
     * on the box. Persisted immediately, which makes it a starting point the user
     * edits rather than a list that reshuffles itself on every install.
     */
    private fun seedFavourites(apps: List<AppInfo>): List<String> {
        if (prefs.onboardingDone) return emptyList()
        val seed = apps.filter { it.isLeanback }
            .take(SEED_FAVOURITE_COUNT)
            .map { it.id }
        prefs.favourites = seed
        prefs.onboardingDone = true
        return seed
    }

    private fun showTileMenu(app: AppInfo) {
        val index = favouriteApps.indexOfFirst { it.id == app.id }
        val items = buildList {
            add(Sheet.Item(getString(R.string.action_open), R.drawable.ic_open) {
                Launch.app(this@HomeActivity, app)
            })
            if (index > 0) {
                add(Sheet.Item(getString(R.string.action_move_left), R.drawable.ic_chevron_left) {
                    prefs.moveFavourite(app.id, -1)
                })
            }
            if (index in 0 until favouriteApps.lastIndex) {
                add(Sheet.Item(getString(R.string.action_move_right), R.drawable.ic_chevron_right) {
                    prefs.moveFavourite(app.id, 1)
                })
            }
            add(Sheet.Item(getString(R.string.action_remove_favourite), R.drawable.ic_star_border) {
                prefs.removeFavourite(app.id)
            })
            add(Sheet.Item(getString(R.string.action_app_info), R.drawable.ic_info) {
                Launch.appDetails(this@HomeActivity, app.packageName)
            })
        }
        Sheet.show(this, app.label, app.packageName, items)
    }

    private fun focusFirstTile() {
        binding.favouritesRow.post {
            val holder = binding.favouritesRow.findViewHolderForAdapterPosition(0)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                binding.btnAllApps.requestFocus()
            }
        }
    }

    /**
     * Gallery: the last painting goes up at once from disk, then today's replaces
     * it if it differs. Any failure leaves whatever is on screen, and with nothing
     * cached the vector wallpaper stands in, so the home screen is never black.
     */
    private fun loadWallpaper(next: Boolean = false) {
        wallpaperJob?.cancel()
        wallpaperJob = lifecycleScope.launch {
            val width = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

            if (!prefs.galleryStyle) {
                shownArtworkId = null
                binding.artCredit.visibility = View.GONE
                binding.wallpaper.setImageDrawable(Wallpapers.load(this@HomeActivity, width, height))
                return@launch
            }

            if (shownArtworkId == null) {
                val cached = Artworks.cached(this@HomeActivity)
                if (cached == null || !showArtwork(cached, width, height)) {
                    binding.wallpaper.setImageDrawable(Wallpapers.load(this@HomeActivity, width, height))
                }
            }

            val fresh = if (next) Artworks.next(this@HomeActivity) else Artworks.today(this@HomeActivity)
            if (fresh != null && fresh.artwork.imageId != shownArtworkId) {
                showArtwork(fresh, width, height)
            }
        }
    }

    private suspend fun showArtwork(displayed: DisplayedArtwork, width: Int, height: Int): Boolean {
        val bitmap = withContext(Dispatchers.IO) {
            Wallpapers.decodeSampled(displayed.file.absolutePath, width, height)
        } ?: return false
        binding.wallpaper.setImageDrawable(BitmapDrawable(resources, bitmap))
        shownArtworkId = displayed.artwork.imageId
        binding.artTitle.text = displayed.artwork.title
        binding.artArtist.text = displayed.artwork.credit
        binding.artArtist.visibility = if (displayed.artwork.credit.isBlank()) View.GONE else View.VISIBLE
        binding.artCredit.visibility = View.VISIBLE
        return true
    }

    override fun onStart() {
        super.onStart()
        loadWallpaper()
        statusPill?.start()
        // Catches installs that happened while the launcher was in the background,
        // and any package broadcast the system dropped under memory pressure.
        repository.refresh()
    }

    override fun onStop() {
        statusPill?.stop()
        super.onStop()
    }

    /**
     * Pressing HOME while already home. The expectation is "put everything back",
     * not "do nothing".
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (uiHidden) setUiHidden(false)
        binding.favouritesRow.stopScroll()
        binding.favouritesRow.scrollToPosition(0)
        focusFirstTile()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Many TV remotes have a dedicated menu key; treat it as a long press on
        // whatever tile is focused.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus ?: return super.onKeyDown(keyCode, event)
            val position = binding.favouritesRow.findContainingViewHolder(focused)
                ?.bindingAdapterPosition
                ?: return super.onKeyDown(keyCode, event)
            if (position != RecyclerView.NO_POSITION && position < favouriteApps.size) {
                showTileMenu(favouriteApps[position])
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val SEED_FAVOURITE_COUNT = 6
        private const val HEALTHY_AFTER_MS = 10_000L
        private const val MAX_DRAWABLE_LEVEL = 10_000
    }
}
