package fr.tvluncher.launcher.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * A tint for the glass surfaces taken from the background picture, so the dock
 * and the panel look like part of the painting rather than grey slabs on it.
 *
 * The picture is averaged from a 24x14 thumbnail: a few hundred pixels, under a
 * millisecond, and an average is calmer than a "dominant" colour, which tends to
 * pick a loud detail. The result is then pushed into a narrow, dark-ish band so
 * white text on it always stays readable.
 */
object Accent {

    private const val SAMPLE_W = 24
    private const val SAMPLE_H = 14

    fun from(drawable: Drawable?, night: Boolean): Int? {
        drawable ?: return null
        // A private copy: toBitmap() moves the drawable's bounds while it draws,
        // and the original is on screen, owned by the main thread.
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: return null
        val sample = runCatching { copy.toBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        var r = 0L
        var g = 0L
        var b = 0L
        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        sample.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        if (sample !== (copy as? BitmapDrawable)?.bitmap) sample.recycle()

        val n = pixels.size
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceIn(0.12f, 0.55f)
        hsv[2] = hsv[2].coerceIn(0.22f, 0.42f) * if (night) 0.7f else 1f
        return Color.HSVToColor(hsv)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
