package fr.tvluncher.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart

/**
 * All persisted state. Backed by [SharedPreferences] rather than DataStore so the
 * home screen can read favourites synchronously during the first layout pass and
 * still draw a populated row on its very first frame.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("tvluncher", Context.MODE_PRIVATE)

    // --- Favourites -------------------------------------------------------

    /** Ordered app ids shown on the home row. Empty means "pick a sensible default". */
    var favourites: List<String>
        get() = sp.getString(KEY_FAVOURITES, null)?.splitIds() ?: emptyList()
        set(value) = sp.edit { putString(KEY_FAVOURITES, value.joinIds()) }

    fun isFavourite(id: String): Boolean = favourites.contains(id)

    fun addFavourite(id: String) {
        val current = favourites
        if (current.contains(id)) return
        favourites = current + id
    }

    fun removeFavourite(id: String) {
        favourites = favourites.filterNot { it == id }
    }

    /** Moves a favourite by [delta] positions, clamped to the ends of the row. */
    fun moveFavourite(id: String, delta: Int) {
        val current = favourites.toMutableList()
        val from = current.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return
        current.removeAt(from)
        current.add(to, id)
        favourites = current
    }

    // --- Hidden apps ------------------------------------------------------

    /** Packages kept out of the all-apps grid (bloatware, duplicate stores...). */
    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        set(value) = sp.edit { putStringSet(KEY_HIDDEN, value) }

    fun toggleHidden(packageName: String) {
        val current = hidden.toMutableSet()
        if (!current.remove(packageName)) current.add(packageName)
        hidden = current
    }

    // --- Appearance -------------------------------------------------------

    /** Id of a built-in wallpaper, or [WALLPAPER_CUSTOM] when the user picked a file. */
    var wallpaperId: String
        get() = sp.getString(KEY_WALLPAPER, Wallpapers.DEFAULT_ID) ?: Wallpapers.DEFAULT_ID
        set(value) = sp.edit { putString(KEY_WALLPAPER, value) }

    /** Absolute path of the user-picked wallpaper copied into app storage. */
    var customWallpaperPath: String?
        get() = sp.getString(KEY_WALLPAPER_PATH, null)
        set(value) = sp.edit { putString(KEY_WALLPAPER_PATH, value) }

    var showStatusPill: Boolean
        get() = sp.getBoolean(KEY_SHOW_PILL, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_PILL, value) }

    var use24hClock: Boolean
        get() = sp.getBoolean(KEY_CLOCK_24H, true)
        set(value) = sp.edit { putBoolean(KEY_CLOCK_24H, value) }

    /** Focus scale/shadow animations. Off gives instant focus on very slow boxes. */
    var animationsEnabled: Boolean
        get() = sp.getBoolean(KEY_ANIMATIONS, true)
        set(value) = sp.edit { putBoolean(KEY_ANIMATIONS, value) }

    /** Shows app names under the tiles. Off is closer to the Apple TV look. */
    var showLabels: Boolean
        get() = sp.getBoolean(KEY_SHOW_LABELS, false)
        set(value) = sp.edit { putBoolean(KEY_SHOW_LABELS, value) }

    // --- Gallery ----------------------------------------------------------

    /** Painting of the day plus the quick panel. Off is the plain wallpaper home. */
    var galleryStyle: Boolean
        get() = sp.getBoolean(KEY_GALLERY, true)
        set(value) = sp.edit { putBoolean(KEY_GALLERY, value) }

    /** Added to the day number, so "next artwork" skips ahead and still rotates daily. */
    var artworkOffset: Int
        get() = sp.getInt(KEY_ARTWORK_OFFSET, 0)
        set(value) = sp.edit { putInt(KEY_ARTWORK_OFFSET, value) }

    /** JSON of the painting on screen, so it can be redrawn offline at boot. */
    var artworkCurrent: String?
        get() = sp.getString(KEY_ARTWORK_CURRENT, null)
        set(value) = sp.edit { putString(KEY_ARTWORK_CURRENT, value) }

    // --- Weather ----------------------------------------------------------

    var weatherEnabled: Boolean
        get() = sp.getBoolean(KEY_WEATHER, false)
        set(value) = sp.edit { putBoolean(KEY_WEATHER, value) }

    var weatherCity: String
        get() = sp.getString(KEY_WEATHER_CITY, "") ?: ""
        set(value) = sp.edit { putString(KEY_WEATHER_CITY, value) }

    var weatherLatitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_WEATHER_LAT, 0L))
        set(value) = sp.edit { putLong(KEY_WEATHER_LAT, java.lang.Double.doubleToRawLongBits(value)) }

    var weatherLongitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_WEATHER_LON, 0L))
        set(value) = sp.edit { putLong(KEY_WEATHER_LON, java.lang.Double.doubleToRawLongBits(value)) }

    val hasWeatherLocation: Boolean
        get() = weatherCity.isNotEmpty() && (weatherLatitude != 0.0 || weatherLongitude != 0.0)

    /** Last reading, so the pill can draw immediately instead of waiting on the network. */
    var cachedWeather: String?
        get() = sp.getString(KEY_WEATHER_CACHE, null)
        set(value) = sp.edit { putString(KEY_WEATHER_CACHE, value) }

    var cachedWeatherAt: Long
        get() = sp.getLong(KEY_WEATHER_CACHE_AT, 0L)
        set(value) = sp.edit { putLong(KEY_WEATHER_CACHE_AT, value) }

    // --- First run --------------------------------------------------------

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDED, value) }

    // --- Observation ------------------------------------------------------

    /** Emits every time any preference changes, so screens can re-read what they need. */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        // conflate() must sit directly on the callbackFlow so it fuses with its
        // internal channel and raises the capacity: at the default RENDEZVOUS,
        // trySend from the listener drops changes whenever nobody is mid-receive.
        .conflate()
        .onStart { emit(Unit) }

    private fun String.splitIds(): List<String> =
        split(SEPARATOR).filter { it.isNotBlank() }

    private fun List<String>.joinIds(): String = joinToString(SEPARATOR)

    companion object {
        const val WALLPAPER_CUSTOM = "custom"

        // A pipe cannot occur in a package or class name, so ids round-trip safely.
        private const val SEPARATOR = "|"

        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_WALLPAPER_PATH = "wallpaper_path"
        private const val KEY_SHOW_PILL = "show_pill"
        private const val KEY_CLOCK_24H = "clock_24h"
        private const val KEY_ANIMATIONS = "animations"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_GALLERY = "gallery"
        private const val KEY_ARTWORK_OFFSET = "artwork_offset"
        private const val KEY_ARTWORK_CURRENT = "artwork_current"
        private const val KEY_WEATHER = "weather"
        private const val KEY_WEATHER_CITY = "weather_city"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_WEATHER_CACHE = "weather_cache"
        private const val KEY_WEATHER_CACHE_AT = "weather_cache_at"
        private const val KEY_ONBOARDED = "onboarded"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
