package fr.tvluncher.launcher.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import fr.tvluncher.launcher.BuildConfig
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.GeoResult
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import fr.tvluncher.launcher.data.WeatherRepository
import fr.tvluncher.launcher.databinding.ActivitySettingsBinding
import fr.tvluncher.launcher.util.Launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: SettingsAdapter

    private var searchJob: Job? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = Wallpapers.importCustom(this@SettingsActivity, uri)
            if (!ok) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_wallpaper_custom_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
            rebuild()
            loadWallpaper()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        adapter = SettingsAdapter { prefs.animationsEnabled }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.itemAnimator = null
        binding.list.adapter = adapter

        rebuild()
        binding.list.post {
            binding.list.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        loadWallpaper()
    }

    private fun loadWallpaper() {
        lifecycleScope.launch {
            binding.wallpaper.setImageDrawable(
                Wallpapers.load(
                    this@SettingsActivity,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                )
            )
        }
    }

    /** Rebuilds the whole list after any change; it is 20 rows, not a feed. */
    private fun rebuild() {
        val rows = buildList {
            add(SettingRow.Header(getString(R.string.settings_section_appearance)))
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_wallpaper),
                    value = currentWallpaperLabel(),
                    onClick = ::showWallpaperPicker,
                )
            )
            add(
                SettingRow.Toggle(
                    title = getString(R.string.settings_show_pill),
                    checked = prefs.showStatusPill,
                ) { prefs.showStatusPill = it; rebuild() }
            )
            add(
                SettingRow.Toggle(
                    title = getString(R.string.settings_show_labels),
                    checked = prefs.showLabels,
                ) { prefs.showLabels = it; rebuild() }
            )
            add(
                SettingRow.Toggle(
                    title = getString(R.string.settings_clock_24h),
                    checked = prefs.use24hClock,
                ) { prefs.use24hClock = it; rebuild() }
            )
            add(
                SettingRow.Toggle(
                    title = getString(R.string.settings_animations),
                    summary = getString(R.string.settings_animations_summary),
                    checked = prefs.animationsEnabled,
                ) { prefs.animationsEnabled = it; rebuild() }
            )

            add(SettingRow.Header(getString(R.string.settings_section_home)))
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_manage_favourites),
                    summary = getString(
                        R.string.settings_manage_favourites_summary,
                        prefs.favourites.size,
                    ),
                    icon = R.drawable.ic_star,
                ) { startActivity(Intent(this@SettingsActivity, AllAppsActivity::class.java)) }
            )
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_hidden_apps),
                    summary = getString(R.string.settings_hidden_apps_summary, prefs.hidden.size),
                    icon = R.drawable.ic_hide,
                    onClick = ::showHiddenApps,
                )
            )

            add(SettingRow.Header(getString(R.string.settings_weather)))
            add(
                SettingRow.Toggle(
                    title = getString(R.string.settings_weather),
                    summary = getString(R.string.settings_weather_summary),
                    checked = prefs.weatherEnabled,
                ) { prefs.weatherEnabled = it; rebuild() }
            )
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_weather_city),
                    value = prefs.weatherCity.ifBlank {
                        getString(R.string.settings_weather_city_none)
                    },
                    onClick = ::showCitySearch,
                )
            )

            add(SettingRow.Header(getString(R.string.settings_section_system)))
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_default_launcher),
                    summary = if (isDefaultHome()) {
                        getString(R.string.settings_default_launcher_active)
                    } else {
                        getString(R.string.settings_default_launcher_summary)
                    },
                    icon = R.drawable.ic_home,
                ) { Launch.homeSettings(this@SettingsActivity) }
            )
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_screensaver),
                    summary = getString(R.string.settings_screensaver_summary),
                ) { Launch.screensaverSettings(this@SettingsActivity) }
            )
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_system_settings),
                    icon = R.drawable.ic_settings,
                ) { Launch.systemSettings(this@SettingsActivity) }
            )

            add(SettingRow.Header(getString(R.string.settings_section_about)))
            add(
                SettingRow.Action(
                    title = getString(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    summary = getString(R.string.settings_about_privacy),
                    icon = R.drawable.ic_info,
                ) { }
            )
        }
        adapter.submit(rows)
    }

    private fun currentWallpaperLabel(): String =
        if (prefs.wallpaperId == Prefs.WALLPAPER_CUSTOM) {
            getString(R.string.wallpaper_custom_label)
        } else {
            getString(Wallpapers.byId(prefs.wallpaperId).labelRes)
        }

    private fun showWallpaperPicker() {
        val items = Wallpapers.builtIn.map { wallpaper ->
            Sheet.Item(
                label = getString(wallpaper.labelRes),
                checked = prefs.wallpaperId == wallpaper.id,
            ) {
                prefs.wallpaperId = wallpaper.id
                rebuild()
                loadWallpaper()
            }
        } + Sheet.Item(
            label = getString(R.string.settings_wallpaper_custom),
            checked = prefs.wallpaperId == Prefs.WALLPAPER_CUSTOM,
        ) {
            // Plenty of TV boxes ship without any document picker at all.
            runCatching { pickImage.launch("image/*") }.onFailure {
                Toast.makeText(this, R.string.settings_wallpaper_no_picker, Toast.LENGTH_LONG).show()
            }
        }
        Sheet.show(this, getString(R.string.settings_wallpaper), items = items)
    }

    private fun showHiddenApps() {
        val hidden = prefs.hidden
        val apps = AppRepository.get(this).apps.value.filter { hidden.contains(it.packageName) }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.all_apps_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val items = apps.map { app ->
            Sheet.Item(app.label, R.drawable.ic_show) {
                prefs.toggleHidden(app.packageName)
                rebuild()
            }
        }
        Sheet.show(this, getString(R.string.settings_hidden_apps), items = items)
    }

    private fun showCitySearch() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_city_search, null, false)
        val query = view.findViewById<EditText>(R.id.query)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val results = view.findViewById<LinearLayout>(R.id.results)

        val dialog: Dialog = AlertDialog.Builder(this, R.style.Theme_TvLuncher_Dialog)
            .setView(view)
            .create()

        fun render(list: List<GeoResult>) {
            results.removeAllViews()
            val inflater = LayoutInflater.from(this)
            if (list.isEmpty()) {
                val empty = inflater.inflate(R.layout.item_action, results, false)
                empty.findViewById<TextView>(R.id.actionLabel)
                    .setText(R.string.settings_weather_no_result)
                empty.findViewById<ImageView>(R.id.actionIcon).visibility = View.INVISIBLE
                empty.isFocusable = false
                results.addView(empty)
                return
            }
            for (result in list) {
                val row = inflater.inflate(R.layout.item_action, results, false)
                row.findViewById<TextView>(R.id.actionLabel).text = result.display
                row.findViewById<ImageView>(R.id.actionIcon).visibility = View.INVISIBLE
                row.setOnClickListener {
                    prefs.weatherCity = result.name
                    prefs.weatherLatitude = result.latitude
                    prefs.weatherLongitude = result.longitude
                    prefs.weatherEnabled = true
                    // The cached reading belongs to the old city.
                    prefs.cachedWeather = null
                    prefs.cachedWeatherAt = 0L
                    dialog.dismiss()
                    rebuild()
                }
                results.addView(row)
            }
        }

        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                val text = s?.toString().orEmpty()
                if (text.length < 2) {
                    results.removeAllViews()
                    progress.visibility = View.GONE
                    return
                }
                searchJob = lifecycleScope.launch {
                    // Debounce: an on-screen keyboard fires a lot of edits, and the
                    // geocoding endpoint should not see one request per keypress.
                    delay(SEARCH_DEBOUNCE_MS)
                    progress.visibility = View.VISIBLE
                    val found = WeatherRepository.search(text)
                    progress.visibility = View.GONE
                    render(found)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        dialog.setOnDismissListener { searchJob?.cancel() }
        dialog.show()
        query.requestFocus()
    }

    /** True when this app currently owns the home role. */
    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == packageName
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
