package fr.tvluncher.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.IconLoader
import fr.tvluncher.launcher.ui.HomeActivity
import fr.tvluncher.launcher.util.CrashGuard

class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this, HomeActivity::class.java)
        // Warm the app list before the home screen asks for it. The package manager
        // query costs tens of milliseconds; doing it here means the first frame of
        // the launcher already has a populated row instead of an empty one.
        AppRepository.get(this).refresh()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Artwork is the largest thing we hold and the cheapest to rebuild.
            IconLoader.clear()
        }
    }
}
