package fr.tvluncher.launcher.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the focused tile in the middle of the row, the way a TV row is expected to
 * behave: the selection stays put and the content slides underneath it.
 *
 * This replaces RecyclerView's default "scroll just enough to make it visible",
 * which on a d-pad leaves the selection pinned to the screen edge. Overriding
 * [requestChildRectangleOnScreen] rather than calling smoothScrollToPosition from
 * a focus listener matters: the two would otherwise fight over the same scroll and
 * visibly stutter.
 */
class CenteringLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val viewportStart = parent.paddingStart
        val viewportEnd = parent.width - parent.paddingEnd
        val parentCenter = viewportStart + (viewportEnd - viewportStart) / 2
        val childCenter = child.left + child.width / 2
        val dx = childCenter - parentCenter
        if (dx == 0) return false

        // scrollBy clamps at the content edges, so a row that already fits on
        // screen simply never moves.
        if (immediate) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0)
        return true
    }
}
