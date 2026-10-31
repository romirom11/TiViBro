package dev.romirom.tivibro.data

/** What the `#EXTM3U` line says about the whole playlist. */
data class PlaylistHeader(
    val name: String? = null,
    val logo: String? = null,
    /** XMLTV sources from `url-tvg` / `x-tvg-url` (a comma-separated list is split). */
    val tvgUrls: List<String> = emptyList(),
    val catchupType: String? = null,
    val catchupSource: String? = null,
    /** Default archive depth from `catchup-days`, else `catchup-time` (seconds). */
    val catchupDays: Int? = null,
)

class ParsedPlaylist(val header: PlaylistHeader, val channels: List<Channel>)

/**
 * M3U parser for what IPTV providers actually write: attributes with or without the space after the duration
 * (`#EXTINF:0tvg-id=`), durations of 0 or -1, groups from `group-title` or `#EXTGRP`, archive depth from any of
 * `catchup-days` > `timeshift` > `tvg-rec` > the header's default, and catch-up defaults from the header.
 */
object M3u {
    private val ATTR = Regex("""([A-Za-z][\w-]*)\s*=\s*"([^"]*)"""")
    private val DURATION = Regex("""^\s*-?\d+(\.\d+)?""")
    private val COUNTRY = Regex("""^[A-Z]{2}: """)
    private val QUALITY = Regex("""\s+(F?HD|UHD|4K|HDR)$""")
    private val GROUP_NUM = Regex("""^\d+\.\s*""")

    fun parse(text: String): ParsedPlaylist {
        var header = PlaylistHeader()
        val out = ArrayList<Channel>(4096)
        var attrs: Map<String, String>? = null
        var title = ""
        var extgrp = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> header = header(line.substring(7))
                line.startsWith("#EXTINF:") -> {
                    val body = line.substring(8).replaceFirst(DURATION, "")
                    val comma = titleComma(body)
                    val head = if (comma < 0) body else body.substring(0, comma)
                    attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                    title = if (comma < 0) "" else body.substring(comma + 1).trim()
                    extgrp = ""
                }
                line.startsWith("#EXTGRP:") -> extgrp = line.substring(8).trim()
                line.startsWith("#") -> Unit
                else -> {
                    val a = attrs ?: emptyMap() // a bare URL without #EXTINF is still a channel
                    out += channel(a, title, extgrp, line, out.size + 1, header)
                    attrs = null; title = ""; extgrp = ""
                }
            }
        }
        return ParsedPlaylist(header, out)
    }

    /** The comma before the title: the first one outside quotes (titles may contain commas, attributes may too). */
    private fun titleComma(s: String): Int {
        var quoted = false
        for (i in s.indices) when (s[i]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) return i
        }
        return -1
    }

    fun header(s: String): PlaylistHeader {
        val a = ATTR.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val tvg = (a["url-tvg"] ?: a["x-tvg-url"]).orEmpty().split(',').map { it.trim() }.filter { it.startsWith("http") }
        val days = a["catchup-days"]?.toIntOrNull() ?: a["catchup-time"]?.toLongOrNull()?.let { (it / 86400).toInt() }
        return PlaylistHeader(
            name = a["playlist-name"]?.takeIf { it.isNotBlank() },
            logo = a["playlist-logo"]?.takeIf { it.isNotBlank() },
            tvgUrls = tvg,
            catchupType = a["catchup-type"]?.takeIf { it.isNotBlank() } ?: a["catchup"]?.takeIf { it.isNotBlank() },
            catchupSource = a["catchup-source"]?.takeIf { it.isNotBlank() },
            catchupDays = days,
        )
    }

    private fun channel(a: Map<String, String>, title: String, extgrp: String, url: String, num: Int, h: PlaylistHeader): Channel {
        fun attr(k: String) = a[k]?.takeIf { it.isNotBlank() }
        val tvgName = attr("tvg-name")
        // the title after the comma is the display name; tvg-name only where there is none (some formats put the id there)
        val full = title.ifBlank { tvgName ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/') }
        val noCountry = full.replace(COUNTRY, "")
        val tvgId = attr("tvg-id")
        val urlId = urlChannelId(url)
        val depth = attr("catchup-days")?.toIntOrNull()
            ?: attr("timeshift")?.toIntOrNull()
            ?: attr("tvg-rec")?.toIntOrNull()
            ?: h.catchupDays
            ?: 0
        return Channel(
            id = tvgId ?: urlId ?: (UNNAMED + epgName(full)),
            num = num,
            name = noCountry.replace(QUALITY, "").trim().ifEmpty { full },
            group = (attr("group-title") ?: extgrp).replace(GROUP_NUM, "").ifBlank { OTHER_GROUP },
            logo = attr("tvg-logo"),
            archiveDays = depth.coerceAtLeast(0),
            url = url,
            hd = QUALITY.containsMatchIn(noCountry),
            catchupType = attr("catchup-type") ?: attr("catchup") ?: h.catchupType,
            catchupSource = attr("catchup-source") ?: h.catchupSource,
            epgId = tvgId?.let(::normalizeTvgId) ?: urlId ?: tvgName?.let(::epgName) ?: epgName(full),
        )
    }
}
