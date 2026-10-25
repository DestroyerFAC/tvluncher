package fr.tvluncher.launcher.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppInfo
import fr.tvluncher.launcher.data.AppRepository

/**
 * Every outward jump goes through here.
 *
 * A launcher's app list is always slightly stale — a package can be removed
 * between the moment a tile is drawn and the moment it is clicked. Letting that
 * throw would crash the home screen, so failures become a toast plus a refresh.
 */
object Launch {

    private const val TAG = "Launch"

    fun app(context: Context, app: AppInfo): Boolean =
        start(context, app.launchIntent()) {
            Toast.makeText(context, R.string.error_app_unavailable, Toast.LENGTH_SHORT).show()
            AppRepository.get(context).refresh()
        }

    fun appDetails(context: Context, packageName: String) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun uninstall(context: Context, packageName: String) {
        start(
            context,
            Intent(Intent.ACTION_DELETE)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens system settings, trying the TV-specific panel before the phone one. */
    fun systemSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            if (start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onError = null)) return
        }
        Toast.makeText(context, R.string.error_no_settings, Toast.LENGTH_SHORT).show()
    }

    /**
     * Opens the screen where the home app is chosen. Android TV hides the phone
     * "default apps" panel on some builds, so we try the specific one first and
     * fall back to plain settings.
     */
    fun homeSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        )
        for (intent in candidates) {
            if (start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onError = null)) return
        }
        systemSettings(context)
    }

    /** Opens the screensaver picker, where the ambient dream can be selected. */
    fun screensaverSettings(context: Context) {
        val intent = Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, intent, onError = null)) systemSettings(context)
    }

    /**
     * Starts the screensaver right now. There is no public API for this; the
     * Somnambulator activity is what the system's own "Start now" uses and exists
     * on stock Android TV builds. Elsewhere, the picker is the next best thing.
     */
    fun screensaverNow(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, intent, onError = null)) screensaverSettings(context)
    }

    private fun start(
        context: Context,
        intent: Intent,
        onError: (() -> Unit)? = {
            Toast.makeText(context, R.string.error_app_unavailable, Toast.LENGTH_SHORT).show()
        },
    ): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent")
        onError?.invoke()
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start $intent", e)
        onError?.invoke()
        false
    } catch (e: Exception) {
        Log.w(TAG, "Failed to start $intent", e)
        onError?.invoke()
        false
    }
}
