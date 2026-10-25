package fr.tvluncher.launcher.data

import android.content.Context
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import fr.tvluncher.launcher.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WeatherState(
    val temperatureCelsius: Int,
    val wmoCode: Int,
    val isDay: Boolean,
) {
    @get:DrawableRes
    val iconRes: Int get() = iconFor(wmoCode, isDay)

    @get:StringRes
    val labelRes: Int get() = labelFor(wmoCode)

    fun serialise(): String = "$temperatureCelsius;$wmoCode;${if (isDay) 1 else 0}"

    companion object {
        fun parse(raw: String?): WeatherState? {
            val parts = raw?.split(';') ?: return null
            if (parts.size != 3) return null
            return runCatching {
                WeatherState(parts[0].toInt(), parts[1].toInt(), parts[2] == "1")
            }.getOrNull()
        }

        /** WMO weather interpretation codes, collapsed to the icons we ship. */
        @DrawableRes
        fun iconFor(code: Int, isDay: Boolean): Int = when (code) {
            0 -> if (isDay) R.drawable.ic_weather_sun else R.drawable.ic_weather_moon
            1, 2 -> R.drawable.ic_weather_partly_cloudy
            3 -> R.drawable.ic_weather_cloud
            45, 48 -> R.drawable.ic_weather_fog
            in 51..57, in 61..67, in 80..82 -> R.drawable.ic_weather_rain
            in 71..77, 85, 86 -> R.drawable.ic_weather_snow
            in 95..99 -> R.drawable.ic_weather_storm
            else -> R.drawable.ic_weather_cloud
        }

        /** Same buckets as [iconFor], so the word always matches the picture. */
        @StringRes
        fun labelFor(code: Int): Int = when (code) {
            0 -> R.string.weather_clear
            1, 2 -> R.string.weather_partly
            3 -> R.string.weather_cloudy
            45, 48 -> R.string.weather_fog
            in 51..57, in 61..67, in 80..82 -> R.string.weather_rain
            in 71..77, 85, 86 -> R.string.weather_snow
            in 95..99 -> R.string.weather_storm
            else -> R.string.weather_cloudy
        }
    }
}

data class GeoResult(
    val name: String,
    val admin: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
) {
    val display: String
        get() = listOf(name, admin, country).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * Weather for the status pill, from Open-Meteo.
 *
 * Chosen because it needs no account, no API key and sets no cookies, which keeps
 * the promise that this launcher talks to exactly one server and only when the
 * user has switched the feature on. Failures are silent: a missing temperature is
 * never worth a dialog on a TV.
 */
object WeatherRepository {

    private const val TAG = "Weather"
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 6_000

    /** Returns the cached reading immediately if it is fresh, otherwise refetches. */
    suspend fun current(context: Context, force: Boolean = false): WeatherState? {
        val prefs = Prefs.get(context)
        if (!prefs.weatherEnabled || !prefs.hasWeatherLocation) return null

        val cached = WeatherState.parse(prefs.cachedWeather)
        val age = System.currentTimeMillis() - prefs.cachedWeatherAt
        if (!force && cached != null && age in 0 until REFRESH_INTERVAL_MS) return cached

        val fresh = fetch(prefs.weatherLatitude, prefs.weatherLongitude)
        if (fresh != null) {
            prefs.cachedWeather = fresh.serialise()
            prefs.cachedWeatherAt = System.currentTimeMillis()
            return fresh
        }
        // Network down: a slightly stale temperature beats an empty pill.
        return cached
    }

    private suspend fun fetch(latitude: Double, longitude: Double): WeatherState? =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,is_day,weather_code" +
                "&timezone=auto"
            val body = get(url) ?: return@withContext null
            runCatching {
                val current = JSONObject(body).getJSONObject("current")
                WeatherState(
                    temperatureCelsius = Math.round(current.getDouble("temperature_2m")).toInt(),
                    wmoCode = current.optInt("weather_code", 3),
                    isDay = current.optInt("is_day", 1) == 1,
                )
            }.getOrElse {
                Log.w(TAG, "Unexpected forecast payload", it)
                null
            }
        }

    /** City search for the settings screen. */
    suspend fun search(query: String): List<GeoResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=8&language=fr&format=json"
        val body = get(url) ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).map { index ->
                val item = results.getJSONObject(index)
                GeoResult(
                    name = item.optString("name"),
                    admin = item.optString("admin1"),
                    country = item.optString("country"),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                )
            }
        }.getOrElse {
            Log.w(TAG, "Unexpected geocoding payload", it)
            emptyList()
        }
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "Request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
