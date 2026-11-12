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
class Prefs private constructor(private val context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("tvluncher", Context.MODE_PRIVATE)

    // --- Profiles ---------------------------------------------------------

    /**
     * Never empty: the first profile is created on demand, and keeps the
     * pre-profile keys, so a dock arranged before profiles existed is still there.
     */
    var profiles: List<Profile>
        get() = Profile.listFromJson(sp.getString(KEY_PROFILES, null)).ifEmpty {
            listOf(Profile(Profile.DEFAULT_ID, context.getString(fr.tvluncher.launcher.R.string.profile_default_name), 0))
        }
        set(value) = sp.edit { putString(KEY_PROFILES, Profile.listToJson(value)) }

    var currentProfileId: String
        get() = sp.getString(KEY_CURRENT_PROFILE, null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        set(value) = sp.edit { putString(KEY_CURRENT_PROFILE, value) }

    val currentProfile: Profile
        get() = currentProfileId.let { id -> profiles.first { it.id == id } }

    /** Adds a profile starting from a copy of the current dock, and returns it. */
    fun addProfile(name: String): Profile {
        val list = profiles
        val id = "p" + System.currentTimeMillis().toString(36)
        val profile = Profile(id, name.take(Profile.MAX_NAME), list.size % Profile.PALETTE.size)
        favouritesFor(id, favourites)
        profiles = list + profile
        return profile
    }

    fun updateProfile(profile: Profile) {
        profiles = profiles.map { if (it.id == profile.id) profile else it }
    }

    /** Removes a profile and its data. The last profile cannot be removed. */
    fun removeProfile(id: String) {
        val list = profiles
        if (list.size <= 1) return
        profiles = list.filterNot { it.id == id }
        sp.edit {
            remove(scoped(KEY_FAVOURITES, id))
            remove(scoped(KEY_HIDDEN, id))
            remove(scoped(KEY_DOCK_STREAMING, id))
        }
        if (sp.getString(KEY_CURRENT_PROFILE, null) == id) currentProfileId = profiles.first().id
    }

    /** The default profile keeps the original keys; others get their own. */
    private fun scoped(key: String, profileId: String): String =
        if (profileId == Profile.DEFAULT_ID) key else "$key@$profileId"

    private fun favouritesFor(profileId: String, value: List<String>) =
        sp.edit { putString(scoped(KEY_FAVOURITES, profileId), value.joinIds()) }

    fun hiddenFor(profileId: String): Set<String> =
        sp.getStringSet(scoped(KEY_HIDDEN, profileId), emptySet()) ?: emptySet()

    fun setHiddenFor(profileId: String, value: Set<String>) =
        sp.edit { putStringSet(scoped(KEY_HIDDEN, profileId), value) }

    /** Dimmer background and interface from 21:00 to 07:00. */
    var nightMode: Boolean
        get() = sp.getBoolean(KEY_NIGHT, true)
        set(value) = sp.edit { putBoolean(KEY_NIGHT, value) }

    /** Fade everything but the background after a minute without a key press. */
    var autoHideUi: Boolean
        get() = sp.getBoolean(KEY_AUTO_HIDE, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_HIDE, value) }

    /** Show "Who's watching?" at boot and on waking from standby. */
    var askProfileOnWake: Boolean
        get() = sp.getBoolean(KEY_ASK_PROFILE, true)
        set(value) = sp.edit { putBoolean(KEY_ASK_PROFILE, value) }

    /** Set once the current profile's dock has had its streaming apps put first. */
    var dockStreamingDone: Boolean
        get() = sp.getBoolean(scoped(KEY_DOCK_STREAMING, currentProfileId), false)
        set(value) = sp.edit { putBoolean(scoped(KEY_DOCK_STREAMING, currentProfileId), value) }

    // --- Favourites -------------------------------------------------------

    /** Ordered app ids on the current profile's dock. Empty means "pick a sensible default". */
    var favourites: List<String>
        get() = sp.getString(scoped(KEY_FAVOURITES, currentProfileId), null)?.splitIds() ?: emptyList()
        set(value) = favouritesFor(currentProfileId, value)

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

    /** Packages the current profile does not see (bloatware, apps for someone else...). */
    var hidden: Set<String>
        get() = hiddenFor(currentProfileId)
        set(value) = setHiddenFor(currentProfileId, value)

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

    /** Image of the day plus the quick panel. Off is the plain wallpaper home. */
    var galleryStyle: Boolean
        get() = sp.getBoolean(KEY_GALLERY, true)
        set(value) = sp.edit { putBoolean(KEY_GALLERY, value) }

    /** [ImageSource] key: paintings, nature, space or a mix of the three. */
    var imageSource: String
        get() = sp.getString(KEY_IMAGE_SOURCE, ImageSource.MIX.key) ?: ImageSource.MIX.key
        set(value) = sp.edit { putString(KEY_IMAGE_SOURCE, value) }

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

    // --- Updates ----------------------------------------------------------

    var lastUpdateCheckAt: Long
        get() = sp.getLong(KEY_UPDATE_CHECK_AT, 0L)
        set(value) = sp.edit { putLong(KEY_UPDATE_CHECK_AT, value) }

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

        private const val KEY_PROFILES = "profiles"
        private const val KEY_ASK_PROFILE = "ask_profile_on_wake"
        private const val KEY_AUTO_HIDE = "auto_hide_ui"
        private const val KEY_NIGHT = "night_mode"
        private const val KEY_CURRENT_PROFILE = "current_profile"
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_HIDDEN = "hidden"
        // v2: the first pass ran while preinstalled apps were still undiscovered.
        private const val KEY_DOCK_STREAMING = "dock_streaming_v2"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_WALLPAPER_PATH = "wallpaper_path"
        private const val KEY_SHOW_PILL = "show_pill"
        private const val KEY_CLOCK_24H = "clock_24h"
        private const val KEY_ANIMATIONS = "animations"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_GALLERY = "gallery"
        private const val KEY_IMAGE_SOURCE = "image_source"
        private const val KEY_ARTWORK_OFFSET = "artwork_offset"
        private const val KEY_ARTWORK_CURRENT = "artwork_current"
        private const val KEY_WEATHER = "weather"
        private const val KEY_WEATHER_CITY = "weather_city"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_WEATHER_CACHE = "weather_cache"
        private const val KEY_WEATHER_CACHE_AT = "weather_cache_at"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_UPDATE_CHECK_AT = "update_check_at"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
