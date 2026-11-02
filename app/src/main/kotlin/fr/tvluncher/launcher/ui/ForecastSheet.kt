package fr.tvluncher.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.WeatherRepository
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** Five days of weather in a sheet, opened from the quick panel's Weather pill. */
object ForecastSheet {

    fun show(activity: AppCompatActivity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_forecast, null, false)
        view.findViewById<TextView>(R.id.forecastTitle).text =
            activity.getString(R.string.forecast_title, Prefs.get(activity).weatherCity)
        val dialog = AlertDialog.Builder(activity, R.style.Theme_TvLuncher_Dialog).setView(view).create()
        dialog.show()

        activity.lifecycleScope.launch {
            val days = WeatherRepository.forecast(activity)
            if (!dialog.isShowing) return@launch
            if (days.isNullOrEmpty()) {
                view.findViewById<View>(R.id.forecastEmpty).visibility = View.VISIBLE
                return@launch
            }
            val row = view.findViewById<LinearLayout>(R.id.forecastDays)
            val inflater = LayoutInflater.from(activity)
            val locale = Locale.getDefault()
            val today = LocalDate.now()
            for (day in days) {
                val item = inflater.inflate(R.layout.item_forecast_day, row, false)
                item.findViewById<TextView>(R.id.day).text =
                    if (day.date == today) {
                        activity.getString(R.string.forecast_today)
                    } else {
                        day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                            .replaceFirstChar { it.titlecase(locale) }
                    }
                item.findViewById<ImageView>(R.id.icon).setImageResource(day.iconRes)
                item.findViewById<TextView>(R.id.condition).setText(day.labelRes)
                item.findViewById<TextView>(R.id.temps).text = "${day.maxCelsius}° / ${day.minCelsius}°"
                row.addView(item)
            }
        }
    }
}
