package fr.tvluncher.launcher.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.databinding.ActivitySearchBinding
import fr.tvluncher.launcher.util.Launch
import java.text.Normalizer
import kotlinx.coroutines.launch

/**
 * Instant app search with a tvOS-style one-line keyboard. Results update on
 * every key; a hardware or Bluetooth keyboard types straight into it too.
 *
 * Matching is accent- and case-insensitive on word starts ("pr" finds Prime
 * Video, "vid" finds it too), over the current profile's visible apps only.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var prefs: Prefs
    private lateinit var results: AppTileAdapter
    private var allApps: List<AppInfo> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        setUpKeyboard()

        results = AppTileAdapter(
            layoutRes = R.layout.item_home_grid,
            scope = lifecycleScope,
            animations = { prefs.animationsEnabled },
            showLabels = { true },
            onClick = { app -> Launch.app(this, app) },
            onLongClick = { app -> Launch.appDetails(this, app.packageName) },
        )
        binding.results.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.results.itemAnimator = null
        binding.results.adapter = results

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppRepository.get(this@SearchActivity).apps.collect { apps ->
                    val hidden = prefs.hidden
                    allApps = apps.filter { it.packageName !in hidden && it.packageName != packageName }
                    render()
                }
            }
        }
    }

    private fun setUpKeyboard() {
        val keys = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } +
            listOf(KEY_SPACE, KEY_DELETE)
        binding.keyboard.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.keyboard.adapter = KeyAdapter(keys) { key ->
            when (key) {
                KEY_DELETE -> query = query.dropLast(1)
                KEY_SPACE -> if (query.isNotEmpty() && !query.endsWith(' ')) query += " "
                else -> if (query.length < MAX_QUERY) query += key.lowercase()
            }
            render()
        }
        binding.keyboard.post { binding.keyboard.getChildAt(0)?.requestFocus() }
    }

    private fun render() {
        binding.query.text = query.replaceFirstChar { it.titlecase() }
        val matches = if (query.isBlank()) allApps else allApps.filter { matches(it, query) }
        binding.resultsTitle.setText(if (query.isBlank()) R.string.search_suggestions else R.string.search_results)
        binding.empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        results.submitList(matches)
    }

    /** Hardware keyboards, and the remote's back key to delete before leaving. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val char = event.unicodeChar.takeIf { it > 0 }?.let { Char(it) }
        if (char != null && char.isLetterOrDigit() && query.length < MAX_QUERY) {
            query += char.lowercaseChar()
            render()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && query.isNotEmpty()) {
            query = query.dropLast(1)
            render()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private class KeyAdapter(
        private val keys: List<String>,
        private val onKey: (String) -> Unit,
    ) : RecyclerView.Adapter<KeyAdapter.Holder>() {

        inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_key, parent, false) as TextView
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val key = keys[position]
            val context = holder.view.context
            holder.view.text = when (key) {
                KEY_SPACE -> "␣"
                KEY_DELETE -> "⌫"
                else -> key
            }
            holder.view.contentDescription = when (key) {
                KEY_SPACE -> context.getString(R.string.key_space)
                KEY_DELETE -> context.getString(R.string.key_delete)
                else -> key
            }
            holder.view.setOnClickListener { onKey(key) }
        }

        override fun getItemCount(): Int = keys.size
    }

    companion object {
        private const val COLUMNS = 5
        private const val MAX_QUERY = 24
        private const val KEY_SPACE = "space"
        private const val KEY_DELETE = "delete"

        private fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        /** Every typed word must start a word of the label. */
        fun matches(app: AppInfo, query: String): Boolean {
            val words = normalize(app.label).split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() }
            return normalize(query).split(' ').filter { it.isNotEmpty() }.all { part ->
                words.any { it.startsWith(part) }
            }
        }
    }
}
