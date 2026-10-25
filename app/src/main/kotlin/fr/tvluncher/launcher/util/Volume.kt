package fr.tvluncher.launcher.util

import android.content.Context
import android.media.AudioManager

/**
 * Media volume for the quick panel. Needs no permission. On boxes that leave the
 * volume to the TV over HDMI-CEC the stream is fixed, and the control hides itself.
 */
class Volume(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val adjustable: Boolean
        get() = !audio.isVolumeFixed && max > 0

    val max: Int
        get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val current: Int
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Steps by one notch; returns false when the system refused (e.g. DND rules). */
    fun step(up: Boolean): Boolean = runCatching {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            // Our own bar shows the level; the system overlay would cover the panel.
            0,
        )
        true
    }.getOrDefault(false)
}
