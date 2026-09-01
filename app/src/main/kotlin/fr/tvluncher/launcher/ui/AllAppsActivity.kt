package fr.tvluncher.launcher.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import fr.tvluncher.launcher.databinding.ActivityAllAppsBinding
import fr.tvluncher.launcher.util.Launch
import fr.tvluncher.launcher.util.dpInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Every launchable app on the box, including the sideloaded ones that only declare
 * CATEGORY_LAUNCHER and are therefore invisible to the stock TV launcher.
 */
class AllAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllAppsBinding
    private lateinit var prefs: Prefs
    private lateinit var repository: AppRepository
    private lateinit var adapter: AppTileAdapter

    private var showHidden = false
    private var shownApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        repository = AppRepository.get(this)

        setUpGrid()
        updateHiddenToggle()
        binding.btnToggleHidden.setOnClickListener {
            showHidden = !showHidden
            updateHiddenToggle()
            render(repository.apps.value)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.apps, prefs.changes()) { apps, _ -> apps }
                    .collect { render(it) }
            }
        }
    }

    private fun setUpGrid() {
        adapter = AppTileAdapter(
            layoutRes = R.layout.item_app_grid,
            scope = lifecycleScope,
            animations = { prefs.animationsEnabled },
            showLabels = { true },
            onClick = { app -> Launch.app(this, app) },
            onLongClick = { app -> showMenu(app) },
        )

        val grid = binding.grid
        grid.layoutManager = GridLayoutManager(this, columnCount())
        grid.setHasFixedSize(true)
        grid.itemAnimator = null
        // Two extra rows of holders ready before they are scrolled into view: the
        // difference between a grid that glides and one that hitches every row.
        grid.setItemViewCacheSize(columnCount() * 2)
        grid.adapter = adapter
    }

    /** Fits as many tiles per row as the panel allows, never fewer than three. */
    private fun columnCount(): Int {
        val margin = resources.getDimensionPixelSize(R.dimen.screen_margin_horizontal)
        val usable = resources.displayMetrics.widthPixels - 2 * margin
        val tile = resources.getDimensionPixelSize(R.dimen.grid_tile_width) + dpInt(18f)
        return (usable / tile).coerceIn(3, 8)
    }

    private fun render(apps: List<AppInfo>) {
        val hidden = prefs.hidden
        shownApps = if (showHidden) apps else apps.filterNot { hidden.contains(it.packageName) }

        adapter.favourites = prefs.favourites.toSet()
        adapter.submitList(shownApps) {
            if (currentFocus == null) focusFirstTile()
        }

        binding.subtitle.text = getString(R.string.all_apps_count, shownApps.size)
        binding.emptyLabel.visibility = if (shownApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateHiddenToggle() {
        binding.btnToggleHidden.setText(
            if (showHidden) R.string.all_apps_hide_hidden else R.string.all_apps_show_hidden
        )
    }

    private fun focusFirstTile() {
        binding.grid.post {
            binding.grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showMenu(app: AppInfo) {
        val isFavourite = prefs.isFavourite(app.id)
        val isHidden = prefs.hidden.contains(app.packageName)

        val items = buildList {
            add(Sheet.Item(getString(R.string.action_open), R.drawable.ic_open) {
                Launch.app(this@AllAppsActivity, app)
            })
            if (isFavourite) {
                add(Sheet.Item(getString(R.string.action_remove_favourite), R.drawable.ic_star_border) {
                    prefs.removeFavourite(app.id)
                })
            } else {
                add(Sheet.Item(getString(R.string.action_add_favourite), R.drawable.ic_star) {
                    prefs.addFavourite(app.id)
                })
            }
            add(
                Sheet.Item(
                    getString(if (isHidden) R.string.action_unhide else R.string.action_hide),
                    if (isHidden) R.drawable.ic_show else R.drawable.ic_hide,
                ) { prefs.toggleHidden(app.packageName) }
            )
            add(Sheet.Item(getString(R.string.action_app_info), R.drawable.ic_info) {
                Launch.appDetails(this@AllAppsActivity, app.packageName)
            })
            if (!app.isSystem) {
                add(Sheet.Item(getString(R.string.action_uninstall), R.drawable.ic_delete) {
                    Launch.uninstall(this@AllAppsActivity, app.packageName)
                })
            }
        }
        Sheet.show(this, app.label, app.packageName, items)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val drawable = Wallpapers.load(
                this@AllAppsActivity,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
            binding.wallpaper.setImageDrawable(drawable)
        }
        repository.refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            val position = focused?.let { binding.grid.findContainingViewHolder(it) }
                ?.bindingAdapterPosition
            if (position != null && position != RecyclerView.NO_POSITION && position < shownApps.size) {
                showMenu(shownApps[position])
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
