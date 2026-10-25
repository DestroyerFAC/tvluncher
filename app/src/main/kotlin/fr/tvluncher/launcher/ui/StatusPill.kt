package fr.tvluncher.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.WeatherRepository
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives the clock/date/weather chip.
 *
 * The clock is refreshed by ACTION_TIME_TICK, which the system already broadcasts
 * on the minute boundary. A Handler ticking every second would wake the CPU sixty
 * times more often to redraw the same two digits.
 */
class StatusPill(
    private val clockView: TextView,
    private val dateView: TextView,
    private val weatherIcon: ImageView,
    private val weatherTemp: TextView,
    private val scope: CoroutineScope,
) {
    private val context: Context = clockView.context
    private val prefs = Prefs.get(context)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderTime()
    }

    private var registered = false

    fun start() {
        if (!registered) {
            // Every action here is a protected system broadcast, so the receiver
            // never needs to be exported.
            ContextCompat.registerReceiver(
                context,
                timeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        renderTime()
        refreshWeather()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(timeReceiver) }
        registered = false
    }

    fun renderTime() {
        val locale = Locale.getDefault()
        val now = Date()

        val timePattern = if (prefs.use24hClock) "HH:mm" else "h:mm a"
        clockView.text = DateFormat.format(timePattern, now)

        // A localised skeleton, so French gets "Vendredi 23 octobre" and English
        // "Friday, October 23" without hardcoding either.
        val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        dateView.text = DateFormat.format(datePattern, now).toString()
            .replaceFirstChar { it.titlecase(locale) }
    }

    fun refreshWeather() {
        if (!prefs.weatherEnabled || !prefs.hasWeatherLocation) {
            weatherIcon.visibility = View.GONE
            weatherTemp.visibility = View.GONE
            return
        }
        scope.launch {
            val weather = WeatherRepository.current(context)
            if (weather == null) {
                weatherIcon.visibility = View.GONE
                weatherTemp.visibility = View.GONE
                return@launch
            }
            weatherIcon.setImageResource(weather.iconRes)
            weatherIcon.visibility = View.VISIBLE
            weatherTemp.text = context.getString(
                fr.tvluncher.launcher.R.string.weather_line,
                weather.temperatureCelsius,
                context.getString(weather.labelRes),
            )
            weatherTemp.visibility = View.VISIBLE
        }
    }
}
