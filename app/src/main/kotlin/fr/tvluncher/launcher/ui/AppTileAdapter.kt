package fr.tvluncher.launcher.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppIcon
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.IconLoader
import fr.tvluncher.launcher.util.FocusEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders app tiles for both the home row and the all-apps grid.
 *
 * Artwork is fetched asynchronously per holder and the in-flight job is cancelled
 * on recycle, so a fast scroll never leaves a stale icon on a reused tile and
 * never keeps decoding artwork that has already scrolled away.
 */
class AppTileAdapter(
    @LayoutRes private val layoutRes: Int,
    private val scope: CoroutineScope,
    private val animations: () -> Boolean,
    private val showLabels: () -> Boolean,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, AppTileAdapter.Holder>(DIFF) {

    var favourites: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_FAVOURITE)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_FAVOURITE)) {
            holder.bindFavourite(getItem(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tile: View = itemView.findViewById(R.id.tile)
        private val artwork: ImageView = itemView.findViewById(R.id.artwork)
        private val label: TextView = itemView.findViewById(R.id.label)
        private val badge: ImageView = itemView.findViewById(R.id.favouriteBadge)

        // One mutated copy per holder, reused across every app that lands here.
        private val tileBackground: GradientDrawable =
            (ContextCompat.getDrawable(itemView.context, R.drawable.bg_tile)!!.mutate()
                as GradientDrawable).also { tile.background = it }

        private var loadJob: Job? = null

        init {
            FocusEffects.bind(itemView, animate = animations)
            itemView.setOnClickListener {
                currentItem()?.let(onClick)
            }
            itemView.setOnLongClickListener {
                currentItem()?.let(onLongClick)
                true
            }
        }

        private fun currentItem(): AppInfo? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) }

        fun bind(app: AppInfo) {
            loadJob?.cancel()

            label.text = app.label
            label.visibility = if (showLabels()) View.VISIBLE else View.GONE
            itemView.contentDescription = app.label
            bindFavourite(app)

            val width = tile.layoutParams.width
            val height = tile.layoutParams.height

            // A cache hit paints on this frame; only a miss shows the placeholder.
            val cached = IconLoader.cached(app, width)
            if (cached != null) {
                apply(cached)
                return
            }

            artwork.setImageDrawable(null)
            tileBackground.setColor(
                ContextCompat.getColor(itemView.context, R.color.tile_placeholder)
            )

            loadJob = scope.launch {
                val icon = IconLoader.load(itemView.context, app, width, height) ?: return@launch
                // The holder may have been rebound to another app while we decoded.
                if (currentItem()?.id != app.id) return@launch
                apply(icon)
            }
        }

        private fun apply(icon: AppIcon) {
            // A banner is authored full-bleed at 16:9 and fills the tile exactly; a
            // square icon is centred on its own accent colour rather than stretched.
            artwork.scaleType =
                if (icon.isBanner) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
            tileBackground.setColor(icon.accent)
            artwork.setImageBitmap(icon.bitmap)
        }

        fun bindFavourite(app: AppInfo) {
            badge.visibility =
                if (favourites.contains(app.id)) View.VISIBLE else View.GONE
        }

        fun recycle() {
            loadJob?.cancel()
            loadJob = null
            artwork.setImageDrawable(null)
        }
    }

    companion object {
        private const val PAYLOAD_FAVOURITE = "favourite"

        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(old: AppInfo, new: AppInfo) = old.id == new.id
            override fun areContentsTheSame(old: AppInfo, new: AppInfo) = old == new
        }
    }
}
