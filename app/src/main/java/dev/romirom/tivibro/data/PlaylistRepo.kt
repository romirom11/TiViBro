package dev.romirom.tivibro.data

import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the guide comes from for a playlist. */
enum class GuideMode { AUTO, PLAYLIST, CUSTOM }

/**
 * One saved playlist. [name] is the user's, [autoName] the playlist's own (`playlist-name`, else the host); [tvgUrl]
 * the first `url-tvg` of the last download, [guideUrl] the user's XMLTV link for [GuideMode.CUSTOM].
 */
data class PlaylistInfo(
    val id: String,
    val url: String,
    val name: String? = null,
    val autoName: String = hostOf(url),
    val count: Int = 0,
    val updatedAt: Long = 0,
    val tvgUrl: String? = null,
    val guideMode: GuideMode = GuideMode.AUTO,
    val guideUrl: String? = null,
) {
    val title get() = name?.takeIf { it.isNotBlank() } ?: autoName

    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("name", name).put("auto", autoName)
        .put("n", count).put("at", updatedAt).put("tvg", tvgUrl).put("gm", guideMode.name).put("gu", guideUrl)

    companion object {
        fun fromJson(o: JSONObject) = PlaylistInfo(
            id = o.getString("id"),
            url = o.getString("url"),
            name = o.optString("name").takeIf { o.has("name") && !o.isNull("name") && it.isNotBlank() },
            autoName = o.optString("auto").ifBlank { hostOf(o.getString("url")) },
            count = o.optInt("n"),
            updatedAt = o.optLong("at"),
            tvgUrl = o.optString("tvg").takeIf { o.has("tvg") && !o.isNull("tvg") && it.isNotBlank() },
            guideMode = runCatching { GuideMode.valueOf(o.optString("gm")) }.getOrDefault(GuideMode.AUTO),
            guideUrl = o.optString("gu").takeIf { o.has("gu") && !o.isNull("gu") && it.isNotBlank() },
        )

        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("www.").ifBlank { L10n.strOr(R.string.playlist_fallback, "Playlist") }
    }
}

/**
 * The saved playlists and the last good copy of each on disk (`playlists/<id>.m3u`). Thread-safe; the list itself
 * lives in [Prefs] as JSON. Network and file calls must be made off the main thread.
 */
class PlaylistRepo(private val ctx: Context, private val http: OkHttpClient, private val prefs: Prefs) {
    private val dir = File(ctx.filesDir, "playlists").apply { mkdirs() }
    private val lock = Any()
    private var items: List<PlaylistInfo> = load()

    private fun file(id: String) = File(dir, "$id.m3u")

    private fun load(): List<PlaylistInfo> {
        prefs.playlistsJson?.let { js ->
            return runCatching { JSONArray(js).let { a -> List(a.length()) { PlaylistInfo.fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        }
        // single-playlist days: that link becomes playlist «0», its settings stay where they are (see Prefs.scope)
        val legacy = prefs.legacyPlaylistUrl ?: return emptyList()
        File(ctx.filesDir, "playlist.m3u").takeIf { it.exists() }?.renameTo(file(LEGACY_ID))
        val list = listOf(PlaylistInfo(LEGACY_ID, legacy))
        save(list)
        prefs.activePlaylist = LEGACY_ID
        return list
    }

    private fun save(list: List<PlaylistInfo>) {
        prefs.playlistsJson = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }

    fun all(): List<PlaylistInfo> = synchronized(lock) { items }
    fun get(id: String?): PlaylistInfo? = synchronized(lock) { items.firstOrNull { it.id == id } }

    fun update(id: String, f: (PlaylistInfo) -> PlaylistInfo): PlaylistInfo? = synchronized(lock) {
        var out: PlaylistInfo? = null
        items = items.map { if (it.id == id) f(it).also { n -> out = n } else it }
        save(items)
        out
    }

    /** A new entry for [url] (or the one that already has it). */
    fun add(url: String): PlaylistInfo = synchronized(lock) {
        items.firstOrNull { it.url == url }?.let { return it }
        val id = ((items.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        PlaylistInfo(id, url).also { items = items + it; save(items) }
    }

    fun remove(id: String) {
        synchronized(lock) { items = items.filter { it.id != id }; save(items) }
        file(id).delete()
    }

    fun cached(id: String): ParsedPlaylist? = file(id).takeIf { it.exists() }?.let { M3u.parse(it.readText()) }

    /** Downloads [id]'s playlist, keeps it on disk and updates its entry. Throws when it can't be had or is empty. */
    fun download(id: String): ParsedPlaylist {
        val info = get(id) ?: error(str(R.string.err_playlist_deleted))
        val body = http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body!!.string()
        }
        val parsed = M3u.parse(body)
        check(parsed.channels.isNotEmpty()) { str(R.string.err_playlist_empty) }
        val tmp = File(dir, "$id.m3u.tmp")
        tmp.writeText(body)
        tmp.renameTo(file(id))
        synchronized(lock) {
            // several links of one provider share a host: «hls.gd», «hls.gd (2)»… tell them apart in the list
            val taken = items.filter { it.id != id }.map { it.title }.toSet()
            update(id) { p ->
                val h = p.withHeader(parsed)
                var name = h.autoName
                var n = 2
                while (name in taken) name = "${h.autoName} (${n++})"
                h.copy(autoName = name, updatedAt = now())
            }
        }
        return parsed
    }

    companion object {
        const val LEGACY_ID = "0"

        fun PlaylistInfo.withHeader(p: ParsedPlaylist) = copy(
            autoName = p.header.name ?: PlaylistInfo.hostOf(url),
            count = p.channels.size,
            tvgUrl = p.header.tvgUrls.firstOrNull(),
        )
    }
}
