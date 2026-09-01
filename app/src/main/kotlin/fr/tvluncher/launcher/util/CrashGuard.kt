package fr.tvluncher.launcher.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Keeps a crash from leaving the TV on a black screen.
 *
 * A launcher is the only app the user cannot back out of: when it dies, the box
 * shows nothing and the remote does nothing. So an uncaught exception restarts
 * the home screen instead of ending the process — unless it happens again within
 * [RESTART_COOLDOWN_MS], which means the crash is deterministic. Then we let the
 * process die so the system falls back to another home app rather than spinning
 * in a restart loop the user cannot escape.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val RESTART_COOLDOWN_MS = 20_000L
    private const val PREFS = "crash_guard"
    private const val KEY_LAST_CRASH = "last_crash_at"

    fun install(app: Application, homeActivity: Class<*>) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", error)

            val now = System.currentTimeMillis()
            val sinceLast = now - prefs.getLong(KEY_LAST_CRASH, 0L)
            // commit(), not apply(): the process is about to go away.
            prefs.edit().putLong(KEY_LAST_CRASH, now).commit()

            if (sinceLast in 0 until RESTART_COOLDOWN_MS) {
                Log.e(TAG, "Second crash in ${sinceLast}ms, handing over to the system")
                previous?.uncaughtException(thread, error)
                Process.killProcess(Process.myPid())
                exitProcess(2)
            }

            runCatching {
                app.startActivity(
                    Intent(app, homeActivity).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                )
            }
            Process.killProcess(Process.myPid())
            exitProcess(2)
        }
    }

    /** Called once the home screen has been up long enough to be considered healthy. */
    fun markHealthy(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CRASH, 0L).apply()
    }
}
