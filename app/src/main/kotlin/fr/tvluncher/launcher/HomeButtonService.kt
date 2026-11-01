package fr.tvluncher.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import fr.tvluncher.launcher.ui.HomeActivity

/**
 * Makes the remote's HOME button land on TV Luncher on boxes that never let the
 * user pick a home app (the Free Player among them, where HOME is hard-wired to
 * the stock launcher and removing it needs ADB from a computer).
 *
 * The service only watches which app comes to the foreground. When it is another
 * home app, TV Luncher is brought up over it. It reads the package name of the
 * new window and nothing else: no window content, no text, no key events.
 */
class HomeButtonService : AccessibilityService() {

    /** Packages that answer the HOME intent, other than us. Resolved once per connection. */
    private var otherHomes: Set<String> = emptySet()
    private var lastLaunchAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        otherHomes = otherHomePackages(this)
        Log.i(TAG, "Watching for stock home apps: $otherHomes")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in otherHomes) return

        // The stock launcher fires several window events as it settles; one
        // launch is enough, and a burst would stack copies of the transition.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < DEBOUNCE_MS) return
        lastLaunchAt = now

        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // A bound accessibility service is exempt from background-launch limits.
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not bring TV Luncher up", it) }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "HomeButton"
        private const val DEBOUNCE_MS = 1_500L

        /**
         * Settings' own fallback home only shows during boot and setup; replacing
         * it would get in the way of the first-run wizard.
         */
        private val NEVER_REPLACE = setOf("com.android.settings", "com.android.tv.settings")

        fun otherHomePackages(context: Context): Set<String> {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .filter { it != context.packageName && it !in NEVER_REPLACE }
                .toSet()
        }

        /** True when the system resolves HOME straight to TV Luncher. */
        fun isDefaultHome(context: Context): Boolean {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            return resolved?.activityInfo?.packageName == context.packageName
        }

        /** True when the user has switched the service on in accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, HomeButtonService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == self
            }
        }
    }
}
