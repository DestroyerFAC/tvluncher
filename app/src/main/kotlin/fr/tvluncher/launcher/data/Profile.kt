package fr.tvluncher.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Someone who uses the TV. Each profile has its own dock and its own set of
 * visible apps; everything else (background, weather, clock) is shared.
 */
data class Profile(
    val id: String,
    val name: String,
    /** Index into [PALETTE]. */
    val color: Int,
) {
    val initial: String
        get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    val colorValue: Int
        get() = PALETTE[Math.floorMod(color, PALETTE.size)]

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color)

    companion object {
        const val DEFAULT_ID = "default"
        const val MAX_NAME = 16

        /** Dark enough that a white initial keeps at least 4.5:1 contrast. */
        val PALETTE = intArrayOf(
            0xFF2F6BDB.toInt(), // blue
            0xFFC2185B.toInt(), // raspberry
            0xFF1E7F48.toInt(), // green
            0xFFB25E00.toInt(), // orange
            0xFF6A4BC4.toInt(), // violet
            0xFF4A5260.toInt(), // graphite
        )

        fun listFromJson(raw: String?): List<Profile> = runCatching {
            val array = JSONArray(raw ?: return emptyList())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Profile(id, o.optString("name").take(MAX_NAME), o.optInt("color"))
            }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<Profile>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}
