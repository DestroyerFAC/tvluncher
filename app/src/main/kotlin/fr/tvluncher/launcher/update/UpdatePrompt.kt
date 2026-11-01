package fr.tvluncher.launcher.update

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.ui.Sheet
import kotlinx.coroutines.launch

/** The "update available" sheet and what its button does. */
object UpdatePrompt {

    fun show(activity: AppCompatActivity, release: Release) {
        Sheet.show(
            activity,
            activity.getString(R.string.update_available, release.versionName),
            items = listOf(
                Sheet.Item(activity.getString(R.string.update_install), R.drawable.ic_refresh) {
                    install(activity, release)
                },
                Sheet.Item(activity.getString(R.string.update_later)) { },
            ),
        )
    }

    private fun install(activity: AppCompatActivity, release: Release) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val message = when (Updater.install(activity.applicationContext, release)) {
                Updater.Outcome.Started -> return@launch
                Updater.Outcome.NeedsPermission -> R.string.update_needs_permission
                Updater.Outcome.Failed -> R.string.update_failed
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
