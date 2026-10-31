package dev.romirom.tivibro.tvhome

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The links the Android TV home screen opens the app with (preview channel programmes, Watch Next):
 * `tivibro://home`, `tivibro://live/{playlistId}/{channelId}` and
 * `tivibro://archive/{playlistId}/{channelId}/{startUtcMs}?position={ms into the programme}`.
 * Ids are path segments, percent-encoded (a channel id is the playlist's tvg-id: any string).
 */
sealed class DeepLink {
    data object Home : DeepLink()
    data class Live(val playlist: String, val channel: String) : DeepLink()
    data class Archive(val playlist: String, val channel: String, val start: Long, val position: Long) : DeepLink()

    fun uri(): String = when (this) {
        Home -> "$SCHEME://home"
        is Live -> "$SCHEME://live/${enc(playlist)}/${enc(channel)}"
        is Archive -> "$SCHEME://archive/${enc(playlist)}/${enc(channel)}/$start" + if (position > 0) "?position=$position" else ""
    }

    companion object {
        const val SCHEME = "tivibro"
        private const val MAX_ID = 200

        /** null for anything that is not one of the three, or is malformed. */
        fun parse(s: String?): DeepLink? {
            if (s.isNullOrBlank() || s.length > 2048) return null
            val u = runCatching { URI(s) }.getOrNull() ?: return null
            if (!SCHEME.equals(u.scheme, ignoreCase = true)) return null
            // the raw path: decoding it first would split an id with an encoded '/' in two
            val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { dec(it) ?: return null }
            if (segs.any { it.isBlank() || it.length > MAX_ID }) return null
            return when (u.host?.lowercase()) {
                "home" -> if (segs.isEmpty()) Home else null
                "live" -> if (segs.size == 2) Live(segs[0], segs[1]) else null
                "archive" -> {
                    if (segs.size != 3) return null
                    val start = segs[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
                    val pos = query(u.rawQuery, "position")?.let { it.toLongOrNull()?.takeIf { p -> p >= 0 } ?: return null } ?: 0L
                    Archive(segs[0], segs[1], start, pos)
                }
                else -> null
            }
        }

        private fun query(raw: String?, key: String): String? =
            raw?.split('&')?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String): String? = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}
