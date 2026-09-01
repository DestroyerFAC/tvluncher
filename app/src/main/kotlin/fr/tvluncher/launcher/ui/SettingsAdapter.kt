package fr.tvluncher.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.util.dp

/** One line of the settings screen. */
sealed class SettingRow {
    data class Header(val title: String) : SettingRow()

    data class Action(
        val title: String,
        val summary: String? = null,
        val value: String? = null,
        @DrawableRes val icon: Int? = null,
        val onClick: () -> Unit,
    ) : SettingRow()

    data class Toggle(
        val title: String,
        val summary: String? = null,
        val checked: Boolean,
        @DrawableRes val icon: Int? = null,
        val onToggle: (Boolean) -> Unit,
    ) : SettingRow()
}

/**
 * A plain adapter rather than androidx.preference: the preference library's rows
 * are sized for a phone held at arm's length and its focus handling was never
 * meant for a d-pad.
 */
class SettingsAdapter(private val animations: () -> Boolean) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<SettingRow> = emptyList()

    fun submit(newRows: List<SettingRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is SettingRow.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_setting_header, parent, false))
        } else {
            RowHolder(inflater.inflate(R.layout.item_setting, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SettingRow.Header -> (holder as HeaderHolder).bind(row)
            is SettingRow.Action -> (holder as RowHolder).bind(row)
            is SettingRow.Toggle -> (holder as RowHolder).bind(row)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val header = view as TextView
        fun bind(row: SettingRow.Header) {
            header.text = row.title
        }
    }

    private inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val summary: TextView = view.findViewById(R.id.summary)
        private val value: TextView = view.findViewById(R.id.value)
        private val trailing: ImageView = view.findViewById(R.id.trailingIcon)

        init {
            // A settings row lifts instead of scaling: a full-width row growing
            // 12% would push its own text off the edge of the screen.
            view.setOnFocusChangeListener { v, focused ->
                v.animate().cancel()
                if (animations()) {
                    v.animate().translationZ(if (focused) v.dp(8f) else 0f)
                        .setDuration(140).withLayer().start()
                } else {
                    v.translationZ = if (focused) v.dp(8f) else 0f
                }
            }
        }

        private fun common(
            titleText: String,
            summaryText: String?,
            @DrawableRes iconRes: Int?,
        ) {
            title.text = titleText
            summary.text = summaryText.orEmpty()
            summary.visibility = if (summaryText.isNullOrBlank()) View.GONE else View.VISIBLE
            if (iconRes != null) {
                icon.setImageResource(iconRes)
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.GONE
            }
        }

        fun bind(row: SettingRow.Action) {
            common(row.title, row.summary, row.icon)
            value.text = row.value.orEmpty()
            value.visibility = if (row.value.isNullOrBlank()) View.GONE else View.VISIBLE
            trailing.setImageResource(R.drawable.ic_chevron_right)
            trailing.visibility = View.VISIBLE
            itemView.setOnClickListener { row.onClick() }
        }

        fun bind(row: SettingRow.Toggle) {
            common(row.title, row.summary, row.icon)
            value.visibility = View.GONE
            trailing.setImageResource(
                if (row.checked) R.drawable.ic_check else R.drawable.ic_chevron_right
            )
            trailing.alpha = if (row.checked) 1f else 0.35f
            trailing.visibility = View.VISIBLE
            itemView.setOnClickListener { row.onToggle(!row.checked) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }
}
