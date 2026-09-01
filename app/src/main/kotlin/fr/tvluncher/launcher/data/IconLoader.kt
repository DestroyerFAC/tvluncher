package fr.tvluncher.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.annotation.ColorInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** A ready-to-draw app artwork plus the accent colour extracted from it. */
class AppIcon(
    val bitmap: Bitmap,
    /** True for a 16:9 TV banner, false for a square app icon. */
    val isBanner: Boolean,
    @ColorInt val accent: Int,
)

/**
 * Decodes app banners/icons off the main thread and keeps them in a memory-bounded
 * cache.
 *
 * Two things here matter for smoothness on a low-RAM box: artwork is downscaled to
 * the size it is actually drawn at (a 512px icon in a 160px tile costs 10x the
 * memory bandwidth for nothing), and decoding never touches the main thread, so
 * scrolling the row never waits on the package manager.
 */
object IconLoader {

    private const val MAX_PARALLEL_DECODES = 3

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_DECODES)

    private val cache = object : LruCache<String, AppIcon>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: AppIcon): Int = value.bitmap.byteCount
    }

    private fun cacheSizeBytes(): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        // An eighth of the heap: generous enough to hold a full grid of artwork,
        // small enough to leave room for wallpapers on a 1 GB device.
        return max(4L * 1024 * 1024, min(maxHeap / 8, 24L * 1024 * 1024)).toInt()
    }

    fun cached(app: AppInfo, widthPx: Int): AppIcon? = cache.get(key(app, widthPx))

    suspend fun load(context: Context, app: AppInfo, widthPx: Int, heightPx: Int): AppIcon? {
        val key = key(app, widthPx)
        cache.get(key)?.let { return it }
        val icon = withContext(decodeDispatcher) {
            decode(context, app, widthPx, heightPx)
        } ?: return null
        cache.put(key, icon)
        return icon
    }

    fun clear() = cache.evictAll()

    private fun key(app: AppInfo, widthPx: Int) = "${app.id}@$widthPx"

    private fun decode(context: Context, app: AppInfo, widthPx: Int, heightPx: Int): AppIcon? {
        val pm = context.packageManager
        val activity = try {
            pm.getActivityInfo(app.component, 0)
        } catch (e: Exception) {
            null
        }

        // Banner first: it is the artwork TV apps are designed around and it fills
        // a 16:9 tile edge to edge, the way the Apple TV home screen looks.
        val banner: Drawable? = runCatching { activity?.loadBanner(pm) }.getOrNull()
            ?: runCatching { activity?.applicationInfo?.loadBanner(pm) }.getOrNull()
            ?: runCatching { pm.getApplicationInfo(app.packageName, 0).loadBanner(pm) }.getOrNull()

        if (banner != null) {
            val bmp = banner.toBitmap(widthPx, heightPx) ?: return null
            return AppIcon(bmp, isBanner = true, accent = dominantColor(bmp))
        }

        val icon: Drawable = runCatching { activity?.loadIcon(pm) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
            ?: return null

        // Square artwork is drawn centred on a tile tinted with its own accent
        // colour, which is what keeps a row of mixed banner/icon apps coherent.
        val side = (min(widthPx, heightPx) * 0.62f).toInt().coerceAtLeast(1)
        val bmp = icon.toBitmap(side, side) ?: return null
        return AppIcon(bmp, isBanner = false, accent = dominantColor(bmp))
    }

    /**
     * Always rasterises into a bitmap we own. Reusing a [BitmapDrawable]'s own
     * bitmap would be cheaper, but that memory belongs to the other app's
     * resources and can be freed underneath the cache when it is uninstalled.
     */
    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                val previous = copyBounds()
                setBounds(0, 0, width, height)
                draw(Canvas(bmp))
                // The package manager hands back shared drawable instances.
                bounds = previous
            }
        }.getOrNull()
    }

    /**
     * Picks the tile tint for an icon-only app.
     *
     * Quantises to a 4-bit-per-channel histogram over a 24x24 sample, ignores
     * transparent and near-grey pixels, then takes the most common bucket. Cheap
     * enough to run on every icon without a palette library.
     */
    @ColorInt
    private fun dominantColor(source: Bitmap): Int {
        val sample = runCatching {
            Bitmap.createScaledBitmap(source, 24, 24, true)
        }.getOrNull() ?: return FALLBACK_ACCENT

        val counts = HashMap<Int, Int>(64)
        var bestBucket = -1
        var bestCount = 0
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var opaque = 0

        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== source) sample.recycle()

        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            opaque++
            rSum += r
            gSum += g
            bSum += b

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            // Skip greys, near-white and near-black: they say nothing about the brand.
            if (maxC - minC < 32) continue
            if (maxC < 40 || minC > 225) continue

            val bucket = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            val count = (counts[bucket] ?: 0) + 1
            counts[bucket] = count
            if (count > bestCount) {
                bestCount = count
                bestBucket = bucket
            }
        }

        if (opaque == 0) return FALLBACK_ACCENT
        if (bestBucket < 0) {
            // Fully desaturated artwork: use its average, darkened for contrast.
            return darken(
                Color.rgb((rSum / opaque).toInt(), (gSum / opaque).toInt(), (bSum / opaque).toInt())
            )
        }
        val r = ((bestBucket shr 8) and 0xF) * 17
        val g = ((bestBucket shr 4) and 0xF) * 17
        val b = (bestBucket and 0xF) * 17
        return darken(Color.rgb(r, g, b))
    }

    /** Pulls a colour toward a tile-friendly luminance so white logos stay readable. */
    @ColorInt
    private fun darken(@ColorInt color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = min(1f, hsv[1] * 1.05f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.62f)
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private val FALLBACK_ACCENT = Color.rgb(0x3A, 0x3F, 0x4B)
}
