package fr.tvluncher.launcher.data

/**
 * Decides what goes on a dock nobody has arranged yet: the streaming apps people
 * actually open first, then the remaining TV apps.
 *
 * Matching is on the visible label rather than package names, which differ by
 * country and box (and are unknown for operator apps such as OQEE), so a keyword
 * like "prime video" finds the app whatever its package is called.
 */
object DockSeeder {

    /** In dock order. Lower-case, accent-free, as [AppInfo.sortKey] is. */
    private val STREAMING = listOf(
        "oqee", "netflix", "prime video", "youtube", "disney", "canal",
        "apple tv", "max", "paramount", "france.tv", "tf1", "m6",
        "molotov", "arte", "plex", "spotify", "deezer", "twitch",
    )

    /** Word-start matches only, so "arte" does not catch "Carte SD". */
    private val PATTERNS = STREAMING.map { Regex("(^|[^a-z0-9])" + Regex.escape(it)) }

    const val MAX_DOCK = 10

    /** Installed streaming apps, most popular first. Kids variants come after. */
    fun streaming(apps: List<AppInfo>): List<AppInfo> =
        apps.mapNotNull { app ->
            val rank = PATTERNS.indexOfFirst { it.containsMatchIn(app.sortKey) }
            if (rank < 0) null else app to rank
        }
            .sortedWith(compareBy({ it.second }, { it.first.sortKey.contains("kids") }, { it.first.sortKey }))
            .map { it.first }
            .distinctBy { it.packageName }

    /** A first dock: streaming apps, then other TV apps, up to [MAX_DOCK]. */
    fun initial(apps: List<AppInfo>): List<String> =
        (streaming(apps) + apps.filter { it.isLeanback })
            .distinctBy { it.packageName }
            .take(MAX_DOCK)
            .map { it.id }

    /**
     * For a dock seeded alphabetically by an earlier version: puts the missing
     * streaming apps in front, keeping everything already there.
     */
    fun withStreamingFirst(current: List<String>, apps: List<AppInfo>): List<String> {
        val onDock = current.map { it.substringBefore('/') }.toSet()
        val missing = streaming(apps).filter { it.packageName !in onDock }.map { it.id }
        return (missing + current).take(MAX_DOCK.coerceAtLeast(current.size))
    }
}
