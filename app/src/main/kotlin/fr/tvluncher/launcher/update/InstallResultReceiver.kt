package fr.tvluncher.launcher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import fr.tvluncher.launcher.R

/** Receives PackageInstaller's verdict for a self-update session. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's own confirmation screen. This receiver is not
                // exported and only our PendingIntent targets it, so the intent
                // comes from PackageInstaller, not from another app.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w(TAG, "Could not show install confirmation", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The process restarts on the new build.
            else -> {
                Log.w(TAG, "Install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "InstallResult"
        const val STATUS_UNKNOWN = -999
    }
}
