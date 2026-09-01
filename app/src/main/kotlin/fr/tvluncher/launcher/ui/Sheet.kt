package fr.tvluncher.launcher.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import fr.tvluncher.launcher.R

/**
 * The one dialog shape this launcher uses: a titled sheet of large focusable rows.
 *
 * Built from plain views instead of a RecyclerView because these lists are short
 * and a d-pad needs every row reachable in one focus search, with no recycling to
 * reason about.
 */
object Sheet {

    class Item(
        val label: String,
        @DrawableRes val icon: Int? = null,
        val checked: Boolean = false,
        val onSelect: () -> Unit,
    )

    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        items: List<Item>,
    ): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_sheet, null, false)
        view.findViewById<TextView>(R.id.sheetTitle).text = title
        view.findViewById<TextView>(R.id.sheetSubtitle).apply {
            if (subtitle.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = subtitle
                visibility = View.VISIBLE
            }
        }

        val container = view.findViewById<LinearLayout>(R.id.sheetContainer)
        val dialog = AlertDialog.Builder(context, R.style.Theme_TvLuncher_Dialog)
            .setView(view)
            .create()

        val inflater = LayoutInflater.from(context)
        var firstFocusable: View? = null
        for (item in items) {
            val row = inflater.inflate(R.layout.item_action, container, false)
            row.findViewById<TextView>(R.id.actionLabel).text = item.label
            row.findViewById<ImageView>(R.id.actionIcon).apply {
                if (item.icon != null) {
                    setImageResource(item.icon)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.INVISIBLE
                }
            }
            row.findViewById<ImageView>(R.id.actionCheck).visibility =
                if (item.checked) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog.dismiss()
                item.onSelect()
            }
            container.addView(row)
            if (firstFocusable == null) firstFocusable = row
        }

        dialog.setOnShowListener {
            // Without this the d-pad has nothing to act on when the sheet opens.
            firstFocusable?.requestFocus()
        }
        dialog.show()
        return dialog
    }
}
