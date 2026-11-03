package dev.romirom.tivibro.remote

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

/**
 * The TV's own volume (the media stream, what the remote's volume keys change), for the phone and the assistant. Every
 * change shows the system's volume bar on the TV, as the remote's keys do. Called on the main thread ([Remote.onMain]).
 */
class Volume(app: Context) {
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Sets the level to [level] (0..max) or moves it by [by] steps, then mutes or unmutes when [mute] is given; nothing
     * given: only the state. Android unmutes on any level above 0 and mutes at 0, and reads a muted stream as 0. Answers `level`, `max`, `percent`,
     * `muted`; `ok:false` with an error (and the state) when the TV's volume is fixed.
     */
    fun apply(level: Int?, by: Int?, mute: Boolean?): JSONObject {
        val changing = level != null || by != null || mute != null
        if (changing && am.isVolumeFixed) {
            return state(false).put("error", "The TV's volume is fixed (an external amplifier or the TV's own settings decide it): TiViBro cannot change it.")
        }
        val max = am.getStreamMaxVolume(STREAM)
        if (level != null || by != null) {
            // muted, the stream reads 0: a step goes from the level unmuting brings back, as the remote's keys do
            if (level == null && mute != true && am.isStreamMute(STREAM)) am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            val to = (level ?: (am.getStreamVolume(STREAM) + by!!)).coerceIn(0, max)
            am.setStreamVolume(STREAM, to, AudioManager.FLAG_SHOW_UI)
        }
        if (mute != null) am.adjustStreamVolume(STREAM, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return state(true)
    }

    /** [apply] in percent, as the assistant speaks: [set] 0..100, [change] relative (at least one step when not 0). */
    fun applyPercent(set: Int?, change: Int?, mute: Boolean?): JSONObject {
        val max = am.getStreamMaxVolume(STREAM)
        val by = change?.let { c -> Math.round(c * max / 100f).let { if (it == 0 && c != 0) Integer.signum(c) else it } }
        return apply(set?.let { Math.round(it.coerceIn(0, 100) * max / 100f) }, by, mute)
    }

    private fun state(ok: Boolean): JSONObject {
        val max = am.getStreamMaxVolume(STREAM)
        val level = am.getStreamVolume(STREAM)
        return JSONObject().put("ok", ok).put("level", level).put("max", max).put("percent", if (max > 0) Math.round(level * 100f / max) else 0)
            .put("muted", am.isStreamMute(STREAM)).put("fixed", am.isVolumeFixed)
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
