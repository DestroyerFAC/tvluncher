package fr.tvluncher.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import fr.tvluncher.launcher.databinding.ActivityHomeBinding
import fr.tvluncher.launcher.util.CrashGuard
import fr.tvluncher.launcher.util.Launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The home screen: a wallpaper, a status chip and one row of favourites.
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
        observe()

        // Back on the home screen means "already home": swallowing it stops the
        // system from finishing the activity and leaving a black screen behind.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
        binding.statusPill.visibility = if (prefs.showStatusPill) View.VISIBLE else View.GONE
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

    private fun loadWallpaper() {
        lifecycleScope.launch {
            val width = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val drawable = Wallpapers.load(this@HomeActivity, width, height)
            binding.wallpaper.setImageDrawable(drawable)
        }
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
    }
}
