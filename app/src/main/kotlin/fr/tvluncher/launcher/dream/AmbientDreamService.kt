package fr.tvluncher.launcher.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.Artworks
import fr.tvluncher.launcher.data.DisplayedArtwork
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Wallpapers
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screensaver. In gallery style it is a slideshow of the paintings and photos
 * already on disk, crossfading every couple of minutes with a slow Ken Burns
 * drift and the museum label; otherwise the chosen wallpaper drifts alone. The
 * clock sits bottom left either way.
 *
 * Only the two ImageViews ever animate (alpha, translation, scale on a hardware
 * layer), and only one bitmap is decoded at a time, because a screensaver runs
 * for hours unattended on a box with little memory.
 */
class AmbientDreamService : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var slideshow: Job? = null

    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var credit: View
    private lateinit var artist: TextView
    private lateinit var title: TextView

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

        front = findViewById(R.id.dreamWallpaper)
        back = findViewById(R.id.dreamWallpaperNext)
        clock = findViewById(R.id.dreamClock)
        date = findViewById(R.id.dreamDate)
        credit = findViewById(R.id.dreamCredit)
        artist = findViewById(R.id.dreamArtist)
        title = findViewById(R.id.dreamTitle)
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

        slideshow = scope.launch {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val images = if (Prefs.get(this@AmbientDreamService).galleryStyle) {
                Artworks.slideshow(this@AmbientDreamService)
            } else {
                emptyList()
            }

            if (images.isEmpty()) {
                front.setImageDrawable(Wallpapers.load(this@AmbientDreamService, width, height))
                drift(front, forward = true)
                return@launch
            }

            var index = 0
            var forward = true
            show(front, images[0], width, height)
            drift(front, forward)
            while (isActive && images.size > 1) {
                delay(SLIDE_MS)
                index = (index + 1) % images.size
                forward = !forward
                if (!show(back, images[index], width, height)) continue
                drift(back, forward)
                crossfade()
            }
        }
    }

    /** Decodes [image] into [view] and updates the label; false if it cannot be read. */
    private suspend fun show(view: ImageView, image: DisplayedArtwork, width: Int, height: Int): Boolean {
        val bitmap = withContext(Dispatchers.IO) {
            Wallpapers.decodeSampled(image.file.absolutePath, width, height)
        } ?: return false
        view.setImageDrawable(BitmapDrawable(resources, bitmap))
        val hasLabel = image.artwork.title.isNotBlank()
        credit.visibility = if (hasLabel) View.VISIBLE else View.GONE
        title.text = image.artwork.title
        artist.text = image.artwork.credit
        return true
    }

    private fun crossfade() {
        val outgoing = front
        back.animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction {
            outgoing.animate().cancel()
            // Release the old bitmap now rather than holding two full-screen images.
            outgoing.setImageDrawable(null)
        }.start()
        front = back
        back = outgoing
    }

    /** A very slow Ken Burns pan across one slide. */
    private fun drift(view: View, forward: Boolean) {
        view.scaleX = DRIFT_SCALE
        view.scaleY = DRIFT_SCALE
        view.translationX = if (forward) -DRIFT_DISTANCE else DRIFT_DISTANCE
        view.animate()
            .translationX(if (forward) DRIFT_DISTANCE else -DRIFT_DISTANCE)
            .setDuration(SLIDE_MS + FADE_MS)
            .withLayer()
            .start()
    }

    private fun renderTime() {
        val prefs = Prefs.get(this)
        val now = Date()
        clock.text = DateFormat.format(if (prefs.use24hClock) "HH:mm" else "h:mm", now)
        val locale = Locale.getDefault()
        date.text = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now)
            .toString()
            .replaceFirstChar { it.titlecase(locale) }
    }

    override fun onDreamingStopped() {
        runCatching { unregisterReceiver(timeReceiver) }
        slideshow?.cancel()
        front.animate().cancel()
        back.animate().cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DRIFT_SCALE = 1.08f
        private const val DRIFT_DISTANCE = 40f
        private const val SLIDE_MS = 90_000L
        private const val FADE_MS = 2_500L
    }
}
