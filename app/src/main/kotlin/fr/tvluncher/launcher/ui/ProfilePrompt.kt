package fr.tvluncher.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fr.tvluncher.launcher.data.Prefs

/**
 * Decides when "Who's watching?" opens by itself: when the box has just booted,
 * and each time it wakes from standby. Only with several profiles, and only if
 * the user left the option on.
 */
object ProfilePrompt {

    /** A process started this soon after boot is the boot, not a restart. */
    private const val BOOT_WINDOW_MS = 5 * 60 * 1000L

    @Volatile
    private var pending = false

    /** Called once from Application.onCreate. */
    fun install(context: Context) {
        // The launcher process can also be recreated hours later (low memory,
        // crash guard); asking then would interrupt someone mid-evening.
        pending = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    pending = true
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** True once per boot or wake-up, when the picker should be shown now. */
    fun consume(context: Context): Boolean {
        if (!pending) return false
        pending = false
        val prefs = Prefs.get(context)
        return prefs.askProfileOnWake && prefs.profiles.size > 1
    }
}
