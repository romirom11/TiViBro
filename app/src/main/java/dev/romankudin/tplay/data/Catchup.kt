package dev.romankudin.tplay.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Catch-up URLs for the schemes IPTV playlists use. The kind comes from the channel's `catchup-type` (or the header's),
 * else from the URL: Flussonic's `/name.m3u8` → [Kind.FLUSSONIC], otherwise [Kind.SHIFT] when the channel has an
 * archive at all. An explicit `catchup-source` template wins over every built-in scheme.
 *
 * The live URL's query (the provider's token) is kept on every built-in scheme.
 */
object Catchup {
    enum class Kind { FLUSSONIC, SHIFT, XC, TEMPLATE, NONE }

    private val FLUSSONIC_PATH = Regex("""/(mono|index|video|playlist|archive)\.m3u8$""")
    private val FLUSSONIC_ANY = Regex("""/([^/]+)\.m3u8$""")
    private val FLUSSONIC_TS = Regex("""/mpegts$|/([^/]+)\.ts$""")
    private val XC = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""")
    private val XC_HINT = Regex("""/live/[^/]+/[^/]+/\d+(\.\w+)?$""")

    fun kind(ch: Channel): Kind {
        if (!ch.catchupSource.isNullOrBlank()) return Kind.TEMPLATE
        val path = ch.url.substringBefore('?')
        return when (ch.catchupType?.trim()?.lowercase()) {
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> Kind.FLUSSONIC
            "shift", "timeshift" -> Kind.SHIFT
            "xc" -> Kind.XC
            "default", "append" -> Kind.NONE // without a source there is nothing to fill in
            else -> when {
                ch.archiveDays <= 0 -> Kind.NONE
                FLUSSONIC_PATH.containsMatchIn(path) -> Kind.FLUSSONIC
                XC_HINT.containsMatchIn(path) -> Kind.XC
                else -> Kind.SHIFT
            }
        }
    }

    /** The longest window one request may ask for: the provider's shift playlists stop after 3 h whatever is asked. */
    fun maxWindowSec(ch: Channel): Long = if (kind(ch) == Kind.SHIFT) 3 * 3600L else 6 * 3600L

    /**
     * The archive from [startSec] (unix seconds) for [durationSec], or open-ended (a playlist that keeps growing with
     * the broadcast: paused live) when that is null. Null when the channel has no catch-up scheme.
     */
    fun url(ch: Channel, startSec: Long, durationSec: Long?, nowSec: Long = now() / 1000): String? {
        val live = ch.url
        val q = live.indexOf('?')
        val path = if (q < 0) live else live.substring(0, q)
        val query = if (q < 0) "" else live.substring(q) // with the '?'
        return when (kind(ch)) {
            Kind.NONE -> null
            Kind.TEMPLATE -> template(ch, startSec, durationSec, nowSec)
            Kind.FLUSSONIC -> {
                val tail = durationSec?.toString() ?: "now"
                val ts = FLUSSONIC_TS.find(path)
                when {
                    ts != null -> path.substring(0, ts.range.first) + "/timeshift_abs-$startSec.ts" + query
                    else -> {
                        val m = FLUSSONIC_ANY.find(path)
                        val name = m?.groupValues?.get(1) ?: "index"
                        val base = if (m != null) path.substring(0, m.range.first) else path.trimEnd('/')
                        "$base/$name-$startSec-$tail.m3u8$query"
                    }
                }
            }
            Kind.SHIFT -> {
                val sep = if (q < 0) "?" else "&"
                if (durationSec == null) "$live${sep}utc=$startSec" else "$live${sep}utc=$startSec&lutc=$nowSec"
            }
            Kind.XC -> {
                val m = XC.find(path) ?: return null
                val (host, user, pass, id) = m.destructured
                val ext = m.groupValues[5].ifEmpty { ".ts" }
                val minutes = ((durationSec ?: (nowSec - startSec)) + 59) / 60
                "$host/timeshift/$user/$pass/$minutes/${fmt(startSec, "Y-m-d:H-M")}/$id$ext$query"
            }
        }
    }

    private val PLACEHOLDER = Regex("""\$?\{([^{}]+)\}""")

    /** `catchup-source` with its placeholders filled in; `append` (or a relative source) goes after the live URL. */
    fun template(ch: Channel, startSec: Long, durationSec: Long?, nowSec: Long): String {
        val src = ch.catchupSource!!.trim()
        val end = if (durationSec != null) startSec + durationSec else nowSec
        val dur = end - startSec
        val filled = PLACEHOLDER.replace(src) { m ->
            val key = m.groupValues[1]
            val name = key.substringBefore(':')
            val div = key.substringAfter(':', "").toLongOrNull()?.takeIf { it > 0 }
            when (name) {
                "utc", "start", "timestamp_start" -> startSec.toString()
                "utcend", "end", "timestamp_end" -> end.toString()
                "lutc", "now", "timestamp" -> nowSec.toString()
                "duration" -> (if (div != null) dur / div else dur).toString()
                "offset" -> ((nowSec - startSec).let { if (div != null) it / div else it }).toString()
                "Y", "m", "d", "H", "M", "S" -> fmt(startSec, name)
                else -> if (name.startsWith("(b)") || name.startsWith("(e)")) {
                    fmt(if (name[1] == 'b') startSec else end, name.substring(3))
                } else m.value
            }
        }
        val q = ch.url.indexOf('?')
        val append = ch.catchupType?.trim()?.lowercase() == "append" || !filled.startsWith("http")
        if (append) return ch.url + if (q >= 0 && filled.startsWith("?")) "&" + filled.substring(1) else filled
        return if (q >= 0 && '?' !in filled) filled + ch.url.substring(q) else filled
    }

    /** "Y-m-d:H-M" → "2026-10-30:21-05" (UTC). Letters other than Y m d H M S are kept as they are. */
    fun fmt(sec: Long, pattern: String): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sec * 1000 }
        val sb = StringBuilder()
        for (ch in pattern) sb.append(
            when (ch) {
                'Y' -> c.get(Calendar.YEAR).toString()
                'm' -> pad(c.get(Calendar.MONTH) + 1)
                'd' -> pad(c.get(Calendar.DAY_OF_MONTH))
                'H' -> pad(c.get(Calendar.HOUR_OF_DAY))
                'M' -> pad(c.get(Calendar.MINUTE))
                'S' -> pad(c.get(Calendar.SECOND))
                else -> ch.toString()
            },
        )
        return sb.toString()
    }

    private fun pad(v: Int) = if (v < 10) "0$v" else v.toString()
}
