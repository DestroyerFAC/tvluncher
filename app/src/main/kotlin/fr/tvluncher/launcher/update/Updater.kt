package fr.tvluncher.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import fr.tvluncher.launcher.BuildConfig
import fr.tvluncher.launcher.data.Prefs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A build published by CI, as described by its version.json. */
data class Release(val versionCode: Long, val versionName: String)

/**
 * Self-update from the repository's latest GitHub release.
 *
 * CI publishes the APK and a version.json next to it. The launcher checks that
 * file at most every few hours, downloads the APK over HTTPS when it is newer,
 * and hands it to PackageInstaller. Android itself refuses an update signed with
 * a different key, which is the real guard against a swapped file; the checks
 * here only stop us from offering something that cannot install.
 */
object Updater {

    private const val TAG = "Updater"
    private const val BASE = "https://github.com/DestroyerFAC/tvluncher/releases/latest/download/"
    private const val VERSION_URL = BASE + "version.json"
    private const val APK_URL = BASE + "tvluncher.apk"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 15_000
    private const val MAX_APK_BYTES = 40L * 1024 * 1024

    sealed class Outcome {
        /** The system installer now asks the user to confirm. */
        data object Started : Outcome()
        /** "Install unknown apps" is off for TV Luncher; its settings page was opened. */
        data object NeedsPermission : Outcome()
        data object Failed : Outcome()
    }

    /** A newer release, or null. Throttled unless [force]; never throws. */
    suspend fun check(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheckAt in 0 until CHECK_INTERVAL_MS) return@withContext null
        prefs.lastUpdateCheckAt = now

        val body = fetch(VERSION_URL, maxBytes = 4 * 1024)?.toString(Charsets.UTF_8) ?: return@withContext null
        val release = runCatching {
            val o = JSONObject(body)
            Release(o.getLong("versionCode"), o.optString("versionName"))
        }.getOrNull() ?: return@withContext null
        release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    suspend fun install(context: Context, release: Release): Outcome = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return@withContext Outcome.NeedsPermission
        }

        val file = File(context.cacheDir, "update.apk")
        try {
            val bytes = fetch(APK_URL, MAX_APK_BYTES) ?: return@withContext Outcome.Failed
            file.writeBytes(bytes)

            // Make sure this is really our next build before bothering the user.
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
            if (info == null || info.packageName != context.packageName ||
                PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE
            ) {
                Log.w(TAG, "Downloaded APK is not a newer TV Luncher: ${info?.packageName}")
                return@withContext Outcome.Failed
            }
            commit(context, file)
            Outcome.Started
        } catch (e: IOException) {
            Log.w(TAG, "Update failed", e)
            Outcome.Failed
        } catch (e: SecurityException) {
            Log.w(TAG, "Update refused", e)
            Outcome.Failed
        } finally {
            file.delete()
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets Android 12+ skip the prompt once TV Luncher is its own installer.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // Mutable: the installer adds the status and confirmation intent as extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun fetch(url: String, maxBytes: Long): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TVLuncher/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > maxBytes) return null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
