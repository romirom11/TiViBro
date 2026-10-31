package dev.romirom.tivibro.tmdb

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque

/** What Home shows for a matched programme. [type]: "movie" or "tv". */
data class TmdbInfo(
    val id: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val rating: Double,
    val votes: Int,
    val poster: String?,
    val backdrop: String?,
    val overview: String,
    val genres: List<String>,
    val runtime: Int?,
) {
    val posterUrl get() = poster?.let { "$IMG/w342$it" }
    val backdropUrl get() = backdrop?.let { "$IMG/w780$it" }

    companion object { const val IMG = "https://image.tmdb.org/t/p" }
}

/**
 * TMDB lookups for guide titles: search, pick the match strictly ([TmdbMatcher]), then the details (genres, runtime,
 * an overview in a fallback language when the UI language has none). One request at a time on a low-priority thread,
 * at most a few per second; every answer, a miss included, is kept on disk for [TTL] and in memory for the session.
 *
 * [get] only looks into memory (the main thread calls it while drawing); [want] queues a lookup, newest request first,
 * and [onChanged] runs on the main thread when results land.
 */
class Tmdb(
    private val http: OkHttpClient,
    private val dir: File,
    private val token: () -> String?,
    private val onChanged: () -> Unit,
) {
    /** A programme to look up: its guide title (cleaned here) and length. */
    data class Ask(val title: String, val durationMin: Int)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    /** Cleaned key → result; null value: looked up, no match. */
    private val mem = object : LinkedHashMap<String, TmdbInfo?>(128, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, TmdbInfo?>) = size > MEM_MAX
    }
    private val pending = HashSet<String>()
    private val queue = LinkedBlockingDeque<Pair<String, Ask>>()
    @Volatile var lang = "en-US"
    private var lastAt = 0L
    private var bump = false

    init {
        dir.mkdirs()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { prune() }
            while (true) {
                val (key, ask) = queue.take()
                try {
                    val r = lookup(key, ask)
                    synchronized(lock) { mem[key] = r; pending.remove(key) }
                    changed()
                } catch (e: Exception) {
                    // offline, a server error, a bad token: forget the request, it is asked again next time
                    synchronized(lock) { pending.remove(key) }
                    Log.w(TAG, "tmdb: ${e.javaClass.simpleName}: ${e.message}")
                    if (e is AuthException) { synchronized(lock) { pending.clear() }; queue.clear() }
                }
            }
        }, "tmdb").apply { isDaemon = true }.start()
    }

    /** title → its key without the language ("" when not worth a search): Home asks about the same titles again and
     *  again, and cleaning one takes a dozen regular expressions. */
    private val keys = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > KEYS_MAX
    }

    /** The cache key of a title in the current language; null when the title is not worth a search. */
    fun keyOf(title: String): String? {
        val k = synchronized(lock) { keys[title] } ?: run {
            val c = TmdbTitle.clean(title)
            (if (!TmdbTitle.searchable(c)) "" else "${c.kind ?: ""}|${TmdbTitle.norm(c.query)}|${c.year ?: ""}").also { synchronized(lock) { keys[title] = it } }
        }
        return if (k.isEmpty()) null else "$MATCHER|$lang|$k"
    }

    /** What is known about [title] in memory: the match, or null (no match, or not looked up yet). */
    fun get(title: String): TmdbInfo? {
        val k = keyOf(title) ?: return null
        return synchronized(lock) { mem[k] }
    }

    /** Whether [title] was looked up (or needs no lookup). */
    fun known(title: String): Boolean {
        val k = keyOf(title) ?: return true
        return synchronized(lock) { mem.containsKey(k) }
    }

    /** Queues lookups; [first] ones jump the queue (the focused card). Returns how many were new. */
    fun want(asks: List<Ask>, first: Boolean = false): Int {
        if (token().isNullOrBlank()) return 0
        var n = 0
        for (a in asks) {
            val k = keyOf(a.title) ?: continue
            synchronized(lock) {
                if (mem.containsKey(k) || !pending.add(k)) return@synchronized
                n++
                if (first) queue.addFirst(k to a) else queue.addLast(k to a)
            }
        }
        while (queue.size > QUEUE_MAX) queue.pollLast()?.let { (k, _) -> synchronized(lock) { pending.remove(k) } }
        return n
    }

    /** Drops what is in memory (the language changed: titles and overviews come in another one). */
    fun reset() {
        queue.clear()
        synchronized(lock) { mem.clear(); pending.clear() }
    }

    private fun changed() {
        synchronized(lock) { if (bump) return; bump = true }
        main.postDelayed({ synchronized(lock) { bump = false }; onChanged() }, 250)
    }

    // ------------------------------------------------------------------ the lookup thread

    private fun lookup(key: String, ask: Ask): TmdbInfo? {
        readDisk(key)?.let { return it.getOrNull() }
        val c = TmdbTitle.clean(ask.title)
        val q = c.query
        val path = when (c.kind) { Kind.FILM -> "search/movie"; Kind.SERIES -> "search/tv"; null -> "search/multi" }
        val res = api("$path?query=${enc(q)}&include_adult=false&language=$lang&page=1")
        val type = when (c.kind) { Kind.FILM -> "movie"; Kind.SERIES -> "tv"; null -> null }
        val items = parseResults(res, type)
        val hit = TmdbMatcher.pick(c, items, ask.durationMin)
        val info = hit?.let { details(it) }
        writeDisk(key, info)
        Log.i(TAG, "tmdb: \"$q\"${c.year?.let { " ($it)" } ?: ""} ${c.kind ?: ""}: " + (info?.let { "${it.type} ${it.id} \"${it.title}\"" } ?: "no match of ${items.size}"))
        return info
    }

    /** Genres and runtime; an overview from a fallback language when this one has none. */
    private fun details(r: TmdbItem): TmdbInfo {
        val o = api("${r.type}/${r.id}?language=$lang")
        val genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name") }.filter { it.isNotBlank() } }.orEmpty()
        val runtime = if (r.type == "movie") o.optInt("runtime").takeIf { it > 0 }
        else o.optJSONArray("episode_run_time")?.takeIf { it.length() > 0 }?.optInt(0)?.takeIf { it > 0 }
        var overview = o.optString("overview").ifBlank { r.overview }
        if (overview.isBlank()) for (fb in fallbacks(lang)) {
            overview = runCatching { api("${r.type}/${r.id}?language=$fb").optString("overview") }.getOrDefault("")
            if (overview.isNotBlank()) break
        }
        return TmdbInfo(
            r.id, r.type, o.optString(if (r.type == "movie") "title" else "name").ifBlank { r.title }, r.year,
            o.optDouble("vote_average", r.rating).takeIf { !it.isNaN() } ?: r.rating, o.optInt("vote_count", r.votes),
            o.optString("poster_path").takeIf { it.startsWith("/") } ?: r.poster,
            o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: r.backdrop,
            overview.take(OVERVIEW_MAX), genres, runtime,
        )
    }

    private fun api(path: String): JSONObject {
        val t = token()?.takeIf { it.isNotBlank() } ?: throw AuthException()
        // a few requests a second at most: Home asks for dozens of titles at once
        val wait = lastAt + GAP - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAt = System.currentTimeMillis()
        val req = Request.Builder().url("$BASE/$path").header("Authorization", "Bearer $t").header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw AuthException()
            if (r.code == 429) { lastAt = System.currentTimeMillis() + 10_000; error("rate limited") }
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return JSONObject(r.body!!.string())
        }
    }

    private class AuthException : Exception("no valid token")

    // ------------------------------------------------------------------ disk cache: one small file per key

    private fun file(key: String) = File(dir, sha1(key) + ".json")

    /** null: nothing (or too old) on disk; a Result of null: a cached miss. */
    private fun readDisk(key: String): Result<TmdbInfo?>? = runCatching {
        val f = file(key)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) return null
        val o = JSONObject(f.readText())
        if (o.optBoolean("none")) return Result.success(null)
        val g = o.optJSONArray("genres")
        Result.success(
            TmdbInfo(
                o.getInt("id"), o.getString("type"), o.getString("title"), o.optInt("year").takeIf { it > 0 },
                o.optDouble("rating", 0.0), o.optInt("votes"), o.optString("poster").ifBlank { null }, o.optString("backdrop").ifBlank { null },
                o.optString("overview"), g?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), o.optInt("runtime").takeIf { it > 0 },
            ),
        )
    }.getOrNull()

    private fun writeDisk(key: String, i: TmdbInfo?) = runCatching {
        val o = if (i == null) JSONObject().put("none", true) else JSONObject()
            .put("id", i.id).put("type", i.type).put("title", i.title).put("year", i.year ?: 0).put("rating", i.rating).put("votes", i.votes)
            .put("poster", i.poster.orEmpty()).put("backdrop", i.backdrop.orEmpty()).put("overview", i.overview)
            .put("genres", JSONArray(i.genres)).put("runtime", i.runtime ?: 0)
        val tmp = File(dir, "${file(key).name}.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(key))
    }

    /** Old answers go; past [DISK_MAX] files the oldest go too. */
    private fun prune() {
        val fs = dir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        val keep = fs.filter { now - it.lastModified() <= TTL || !it.delete() }
        if (keep.size > DISK_MAX) keep.sortedBy { it.lastModified() }.take(keep.size - DISK_MAX).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "tivibro"
        private const val BASE = "https://api.themoviedb.org/3"
        const val TTL = 7 * 24 * 3600 * 1000L
        private const val GAP = 250L
        private const val MEM_MAX = 600
        private const val KEYS_MAX = 4000
        /** Bumped when matching changes, so that answers of the old rules on disk are not used. */
        private const val MATCHER = 2
        private const val QUEUE_MAX = 150
        private const val DISK_MAX = 4000
        private const val OVERVIEW_MAX = 700

        /** The UI language as TMDB wants it: uk-UA, en-US, ru-RU, de-DE, pl-PL, es-ES, kk-KZ. */
        fun tmdbLang(lang: String): String = when (lang) {
            "uk" -> "uk-UA"; "ru" -> "ru-RU"; "de" -> "de-DE"; "pl" -> "pl-PL"; "es" -> "es-ES"; "kk" -> "kk-KZ"; else -> "en-US"
        }

        /** Where an overview missing in [lang] is looked for: Kazakh readers get Russian before English. */
        fun fallbacks(lang: String): List<String> = when {
            lang.startsWith("en") -> emptyList()
            lang.startsWith("kk") -> listOf("ru-RU", "en-US")
            else -> listOf("en-US")
        }

        /** Search results as [TmdbItem]s; [type] for search/movie and search/tv, whose results don't say. */
        fun parseResults(o: JSONObject, type: String?): List<TmdbItem> {
            val a = o.optJSONArray("results") ?: return emptyList()
            return List(a.length()) { a.getJSONObject(it) }.mapNotNull { r ->
                val t = r.optString("media_type").ifBlank { type ?: "" }
                if (t != "movie" && t != "tv") return@mapNotNull null
                val movie = t == "movie"
                val date = r.optString(if (movie) "release_date" else "first_air_date")
                TmdbItem(
                    r.optInt("id"), t, r.optString(if (movie) "title" else "name"), r.optString(if (movie) "original_title" else "original_name"),
                    date.take(4).toIntOrNull(), r.optInt("vote_count"), r.optDouble("vote_average", 0.0),
                    r.optString("poster_path").takeIf { it.startsWith("/") }, r.optString("backdrop_path").takeIf { it.startsWith("/") },
                    r.optString("overview"),
                )
            }
        }

        /** Whether TMDB accepts [token] (an API Read Access Token). Blocking: call off the main thread. */
        fun check(http: OkHttpClient, token: String): Boolean = runCatching {
            val req = Request.Builder().url("$BASE/authentication").header("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
