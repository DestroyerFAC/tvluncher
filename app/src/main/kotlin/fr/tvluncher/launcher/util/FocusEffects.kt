package fr.tvluncher.launcher.util

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * The one animation this launcher spends frames on: the focused tile grows,
 * lifts and brightens, everything else recedes.
 *
 * Only scale, alpha and elevation are animated. All three are handled entirely by
 * the render thread once the view is promoted to a hardware layer, so a focus move
 * costs no measure/layout pass and holds 60 fps on an Amlogic set-top box.
 */
object FocusEffects {

    /** Matches the Apple TV "settle": quick to leave, slow to arrive. */
    private val INTERPOLATOR: Interpolator = PathInterpolator(0.25f, 0.1f, 0.0f, 1.0f)

    private const val DURATION_MS = 170L
    const val FOCUSED_SCALE = 1.12f
    private const val UNFOCUSED_ALPHA = 0.88f
    private const val FOCUSED_ELEVATION_DP = 22f

    /**
     * Wires [target] to react to focus changes on [view].
     *
     * They are usually the same view; passing a child lets a tile keep its focus
     * highlight while an outer container handles clipping.
     */
    fun bind(view: View, target: View = view, animate: () -> Boolean = { true }) {
        applyImmediately(target, view.isFocused)
        view.setOnFocusChangeListener { _, hasFocus ->
            apply(target, hasFocus, animate())
        }
    }

    fun apply(target: View, focused: Boolean, animate: Boolean) {
        if (!animate) {
            applyImmediately(target, focused)
            return
        }
        val scale = if (focused) FOCUSED_SCALE else 1f
        val alpha = if (focused) 1f else UNFOCUSED_ALPHA
        val elevation = if (focused) target.dp(FOCUSED_ELEVATION_DP) else 0f

        target.animate().cancel()
        target.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .translationZ(elevation)
            .setDuration(DURATION_MS)
            .setInterpolator(INTERPOLATOR)
            // withLayer() promotes the tile to a texture for the duration of the
            // animation, so the row never re-rasterises the artwork mid-move.
            .withLayer()
            .start()
    }

    fun applyImmediately(target: View, focused: Boolean) {
        target.animate().cancel()
        val scale = if (focused) FOCUSED_SCALE else 1f
        target.scaleX = scale
        target.scaleY = scale
        target.alpha = if (focused) 1f else UNFOCUSED_ALPHA
        target.translationZ = if (focused) target.dp(FOCUSED_ELEVATION_DP) else 0f
    }

    /** Gives a view a rounded shadow that matches its rounded background. */
    fun roundedOutline(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }
}
