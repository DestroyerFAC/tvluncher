package fr.tvluncher.launcher.data

import android.content.ComponentName
import android.content.Intent

/**
 * One launchable entry point. Immutable so it can be diffed and handed between
 * threads without copying or locking.
 */
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** True when the app publishes a LEANBACK_LAUNCHER entry (a real TV app). */
    val isLeanback: Boolean,
    val isGame: Boolean,
    val isSystem: Boolean,
    val lastUpdateTime: Long,
) {
    /** Stable identity used for favourites, hidden apps and icon cache keys. */
    val id: String get() = "$packageName/$activityName"

    val component: ComponentName get() = ComponentName(packageName, activityName)

    /** Sort key: accent-insensitive so "Édition" lands next to "Edition". */
    val sortKey: String = Normalizer.key(label)

    /**
     * An explicit component, so the intent resolves to this exact entry point even
     * if another app publishes a matching filter. The flags are the ones every
     * launcher uses: start a new task, and bring an existing one forward rather
     * than stacking a second copy of the app.
     */
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
}

private object Normalizer {
    fun key(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
}
