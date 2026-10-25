package fr.tvluncher.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import fr.tvluncher.launcher.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A built-in background. All of them are vectors: crisp at 4K, ~2 kB, no decode cost. */
class Wallpaper(
    val id: String,
    @DrawableRes val drawableRes: Int,
    @StringRes val labelRes: Int,
)

object Wallpapers {

    const val DEFAULT_ID = "valley"

    val builtIn: List<Wallpaper> = listOf(
        Wallpaper("valley", R.drawable.wp_valley, R.string.wallpaper_valley),
        Wallpaper("dusk", R.drawable.wp_dusk, R.string.wallpaper_dusk),
        Wallpaper("aurora", R.drawable.wp_aurora, R.string.wallpaper_aurora),
        Wallpaper("midnight", R.drawable.wp_midnight, R.string.wallpaper_midnight),
        Wallpaper("slate", R.drawable.wp_slate, R.string.wallpaper_slate),
    )

    fun byId(id: String): Wallpaper =
        builtIn.firstOrNull { it.id == id } ?: builtIn.first()

    /** Where a user-picked image is copied to, so it survives the source being deleted. */
    fun customFile(context: Context): File = File(context.filesDir, "wallpaper.jpg")

    /**
     * Loads the configured background. Runs on IO because a user-picked photo can be
     * a 12 MP JPEG, and decoding that on the main thread is a guaranteed stall.
     */
    suspend fun load(context: Context, widthPx: Int, heightPx: Int): Drawable? =
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            if (prefs.wallpaperId == Prefs.WALLPAPER_CUSTOM) {
                val path = prefs.customWallpaperPath
                if (path != null) {
                    decodeSampled(path, widthPx, heightPx)?.let { bitmap ->
                        return@withContext BitmapDrawable(context.resources, bitmap)
                    }
                }
                // The file went missing: fall through to a built-in rather than
                // leaving the user staring at a black screen.
            }
            ContextCompat.getDrawable(context, byId(prefs.wallpaperId).drawableRes)
        }

    /**
     * Copies a picked image into app storage, downscaled to at most 1920x1080.
     *
     * Keeping a bounded copy means the home screen never re-decodes a huge original
     * and never depends on a content URI permission that can be revoked on reboot.
     */
    suspend fun importCustom(context: Context, source: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = customFile(context)
                context.contentResolver.openInputStream(source)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false

                val reduced = decodeSampled(target.absolutePath, 1920, 1080)
                    ?: return@runCatching false
                target.outputStream().use { out ->
                    reduced.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                reduced.recycle()

                Prefs.get(context).apply {
                    customWallpaperPath = target.absolutePath
                    wallpaperId = Prefs.WALLPAPER_CUSTOM
                }
                true
            }.getOrDefault(false)
        }

    /** Two-pass decode: measure first, then load at the smallest sufficient sample size. */
    fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth &&
            bounds.outHeight / (sample * 2) >= reqHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 16-bit colour halves wallpaper memory. On a gradient-heavy photo the
            // banding is visible, so keep 8888 and pay the memory instead.
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
    }
}
