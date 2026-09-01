package fr.tvluncher.launcher.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The ambient screensaver: the chosen wallpaper, slowly drifting, with the time in
 * the corner.
 *
 * The drift is a single long translation/scale animation on one ImageView, so the
 * whole thing costs one composited layer and nothing else. That matters more than
 * usual here, because a screensaver runs for hours unattended.
 */
class AmbientDreamService : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var wallpaper: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // Dimming is what a screensaver is for; the system handles it better than
        // we could by dimming the view hierarchy.
        isScreenBright = false
        setContentView(R.layout.dream_ambient)

        wallpaper = findViewById(R.id.dreamWallpaper)
        clock = findViewById(R.id.dreamClock)
        date = findViewById(R.id.dreamDate)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()

        ContextCompat.registerReceiver(
            this,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        renderTime()

        scope.launch {
            val drawable = Wallpapers.load(
                this@AmbientDreamService,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
            wallpaper.setImageDrawable(drawable)
            startDrift(wallpaper)
        }
    }

    /** A very slow Ken Burns pan, restarted in the reverse direction each time. */
    private fun startDrift(view: View) {
        view.scaleX = DRIFT_SCALE
        view.scaleY = DRIFT_SCALE
        view.translationX = -DRIFT_DISTANCE

        fun animate(toX: Float) {
            view.animate()
                .translationX(toX)
                .setDuration(DRIFT_DURATION_MS)
                .withLayer()
                .withEndAction { animate(-toX) }
                .start()
        }
        animate(DRIFT_DISTANCE)
    }

    private fun renderTime() {
        val prefs = Prefs.get(this)
        val now = Date()
        clock.text = DateFormat.format(if (prefs.use24hClock) "HH:mm" else "h:mm", now)
        date.text = DateFormat.format(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"),
            now,
        )
    }

    override fun onDreamingStopped() {
        runCatching { unregisterReceiver(timeReceiver) }
        wallpaper.animate().cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DRIFT_SCALE = 1.08f
        private const val DRIFT_DISTANCE = 36f
        private const val DRIFT_DURATION_MS = 60_000L
    }
}
