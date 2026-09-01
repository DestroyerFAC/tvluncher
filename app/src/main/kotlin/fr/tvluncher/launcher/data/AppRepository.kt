package fr.tvluncher.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single source of truth for the installed app list.
 *
 * Everything here runs off the main thread: [PackageManager] queries take tens of
 * milliseconds on a set-top box and are the classic cause of a janky first frame
 * in a launcher. The result is cached and only recomputed when a package
 * broadcast tells us it changed.
 */
class AppRepository private constructor(private val context: Context) {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())

    /** Every launchable app, alphabetical, self excluded. Never null, may start empty. */
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val reloadMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Reloads in the background; safe to call from anywhere, including a receiver. */
    fun refresh() {
        scope.launch { reload() }
    }

    suspend fun reload() = reloadMutex.withLock {
        val loaded = withContext(Dispatchers.IO) { query() }
        _apps.value = loaded
    }

    /** Blocking variant for the very first load, called off the main thread. */
    private fun query(): List<AppInfo> {
        val pm = context.packageManager
        val self = context.packageName
        // Package name -> entry. A TV app that also ships a phone launcher entry
        // must appear once, and the leanback entry is the one that has a banner.
        val byPackage = LinkedHashMap<String, AppInfo>()

        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (e: Exception) {
                // A broken package can make the whole query throw. Losing one
                // category is survivable; crashing the home screen is not.
                Log.w(TAG, "queryIntentActivities($category) failed", e)
                emptyList()
            }
            for (info in resolved) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName ?: continue
                if (pkg == self) continue
                // A leanback entry always wins over a phone entry for the same app.
                val existing = byPackage[pkg]
                if (existing != null && (existing.isLeanback || !leanback)) continue

                val appInfo = activity.applicationInfo
                val label = runCatching { info.loadLabel(pm)?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: pkg

                byPackage[pkg] = AppInfo(
                    packageName = pkg,
                    activityName = activity.name,
                    label = label,
                    isLeanback = leanback,
                    isGame = appInfo?.isGame() == true,
                    isSystem = appInfo != null &&
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    lastUpdateTime = lastUpdateTime(pm, pkg),
                )
            }
        }

        // Leanback first so TV entries take precedence in the dedupe above.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, leanback = true)
        // Then phone apps: on the Free Player TV a lot of useful apps are
        // sideloaded and never show up in the stock launcher because they only
        // declare CATEGORY_LAUNCHER. Surfacing them is half the point of this app.
        collect(Intent.CATEGORY_LAUNCHER, leanback = false)

        return byPackage.values.sortedWith(
            compareBy({ !it.isLeanback }, { it.sortKey })
        )
    }

    private fun lastUpdateTime(pm: PackageManager, pkg: String): Long = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0).lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    } catch (e: Exception) {
        0L
    }

    private fun ApplicationInfo.isGame(): Boolean =
        category == ApplicationInfo.CATEGORY_GAME

    /** Resolves ids (package/activity) to apps, dropping anything uninstalled. */
    fun resolve(ids: List<String>): List<AppInfo> {
        val index = _apps.value.associateBy { it.id }
        val byPackage = _apps.value.associateBy { it.packageName }
        return ids.mapNotNull { id ->
            // Fall back to package match: an app update can move its main activity,
            // and a favourite should survive that instead of silently vanishing.
            index[id] ?: byPackage[id.substringBefore('/')]
        }
    }

    companion object {
        private const val TAG = "AppRepository"

        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** Keeps the cached list honest when apps are installed, updated or removed. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppRepository.get(context).refresh()
    }
}
