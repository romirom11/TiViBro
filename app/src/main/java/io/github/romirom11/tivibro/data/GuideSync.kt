package io.github.romirom11.tivibro.data

import io.github.romirom11.tivibro.R
import io.github.romirom11.tivibro.plural
import io.github.romirom11.tivibro.str
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Keeps the blobs of epg.team's segmented guide on disk: today first, then +1, −1, +2, −2 … down to −[DAYS_BACK].
 * Manifest ordinals already run in that order (0 today, odd ahead, even back), and a day's schedule only points into
 * content layers of its own or a lower ordinal, so walking ordinals upwards makes each day usable as soon as its own
 * files are in. A day is switched to the new manifest's files only when they are all on disk; until then the old ones
 * stay bound, so the guide never goes blank during an update.
 *
 * Built to stay out of the way: called on a lowest-priority thread, one file at a time, at most [BYTES_PER_SEC], and
 * holding still whenever [holdReason] has one (the user is pressing keys, the player is buffering).
 */
class GuideSync(
    private val guide: Guide,
    private val http: OkHttpClient,
    private val holdReason: () -> String?,
    private val onStatus: (String?) -> Unit,
) {
    private class Ref(val url: String, val sha: String, val size: Long)
    private class Lay(val ordinal: Int, val day: Int, val schedule: Ref, val content: Ref, val deps: List<Int>)

    class Result(val revision: Long, val bytes: Long, val millis: Long, val todayMillis: Long)

    private var done = 0L
    private var total = 0L
    private var lastStatus = 0L
    private val pacer = Pacer(holdReason)
    private val paused get() = pacer.paused

    /** One pass. Throws when a file can't be had after retries; what was bound before stays bound. */
    fun run(): Result {
        val t0 = SystemClock.elapsedRealtime()
        val js = JSONObject(fetchManifest())
        val today = Guide.epochDay(js.getString("currentDate"))
        val revision = js.optLong("revision")
        val channels = ref(js.getJSONObject("channels"))
        val la = js.getJSONArray("layers")
        val layers = List(la.length()) { i ->
            val o = la.getJSONObject(i)
            val d = o.getJSONArray("contentDependencies")
            Lay(o.getInt("ordinal"), Guide.epochDay(o.getString("date")), ref(o.getJSONObject("schedule")), ref(o.getJSONObject("content")), List(d.length()) { d.getInt(it) })
        }.associateBy { it.ordinal }
        val wanted = layers.values.filter { it.day - today in -DAYS_BACK..DAYS_AHEAD }.sortedBy { it.ordinal }
        val wantedOrd = wanted.mapTo(HashSet()) { it.ordinal }
        val contentOrd = wanted.flatMapTo(HashSet()) { it.deps }
        val maxOrd = wanted.maxOfOrNull { it.ordinal } ?: return Result(revision, 0, 0, 0)

        // everything this manifest needs, in download order
        val plan = ArrayList<Pair<Ref, String>>()
        plan += channels to "tpc"
        for (o in 0..maxOrd) {
            val l = layers[o] ?: continue
            if (o in wantedOrd) plan += l.schedule to "tps"
            if (o in contentOrd) plan += l.content to "tpc"
        }
        val keep = plan.mapTo(HashSet()) { it.first.sha }
        val missing = plan.filter { (r, ext) -> !File(guide.blobs, "${r.sha}.$ext").exists() || (ext == "tpc" && r !== channels && !File(guide.blobs, "${r.sha}.tti").exists()) }
        done = 0
        total = missing.sumOf { (r, ext) -> if (File(guide.blobs, "${r.sha}.$ext").exists()) 0 else r.size }
        pacer.paused = 0
        val days = guide.days.toMutableMap()
        fun left() = wanted.count { days[it.day]?.schedule != it.schedule.sha }
        fun status() {
            if (missing.isEmpty()) return
            val pct = if (total > 0) (done * 100 / total).toInt() else 100
            val n = left()
            onStatus(
                if (days[today] == null) str(R.string.guide_dl_today, pct)
                else if (n == 0) null
                else str(R.string.guide_dl_more, daysWord(n), pct),
            )
        }
        Log.i(TAG, "guide: manifest rev $revision ${js.getString("currentDate")}: ${missing.size} of ${plan.size} files to fetch, ${total / 1024} KB")

        val cleanup = { // whatever neither the bound days nor this manifest still need
            val live = HashSet(keep)
            days.values.forEach { live += it.files() }
            guide.blobs.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in live) f.delete() }
        }
        cleanup()
        var todayAt = 0L
        status()
        progress = ::status
        try {
            ensure(channels, "tpc", content = false)
            for (o in 0..maxOrd) {
                val l = layers[o] ?: continue
                if (o in wantedOrd) ensure(l.schedule, "tps", content = false)
                if (o in contentOrd) ensure(l.content, "tpc", content = true)
                if (o !in wantedOrd) continue
                val gd = GuideDay(l.day, channels.sha, l.schedule.sha, List(o + 1) { k -> if (k in l.deps) layers.getValue(k).content.sha else "" })
                if (days[l.day]?.let { it.schedule == gd.schedule && it.content == gd.content && it.channels == gd.channels } == true) continue
                days[l.day] = gd
                days.keys.retainAll { it - today in -DAYS_BACK..DAYS_AHEAD } // older than a week: gone
                guide.bind(days)
                if (l.day == today) todayAt = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "guide: day ${l.day - today} bound (ordinal $o) after ${(SystemClock.elapsedRealtime() - t0) / 1000} s")
                status()
                cleanup()
            }
            days.keys.retainAll(wanted.mapTo(HashSet()) { it.day })
            guide.bind(days)
            keep.clear()
            cleanup()
        } finally {
            progress = {}
            onStatus(null)
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (missing.isNotEmpty()) Log.i(TAG, "guide: rev $revision done in ${ms / 1000} s (held ${paused / 1000} s), fetched ${done / 1024} KB")
        return Result(revision, done, ms, todayAt)
    }

    private var progress: () -> Unit = {}

    private fun ref(o: JSONObject) = Ref(o.getString("url"), o.getString("sha256"), o.getLong("sizeBytes"))

    private fun fetchManifest(): String {
        var attempt = 0
        while (true) {
            try {
                http.newCall(Request.Builder().url(BASE + MANIFEST).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("manifest HTTP ${r.code}")
                    return r.body!!.string()
                }
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
                Log.w(TAG, "guide: manifest attempt $attempt: $e")
                Thread.sleep(attempt * 5000L)
            }
        }
    }

    private fun ensure(r: Ref, ext: String, content: Boolean) {
        val f = File(guide.blobs, "${r.sha}.$ext")
        if (!f.exists()) download(r, f)
        if (content) {
            val t = File(guide.blobs, "${r.sha}.tti")
            if (!t.exists()) Guide.buildTitles(f, t) { hold() }
        }
    }

    /** Into `.part` (resumed with Range after a dropped connection), checked against the manifest's SHA-256, renamed. */
    private fun download(r: Ref, f: File) {
        val part = File(f.path + ".part")
        var attempt = 0
        while (true) {
            val before = if (part.exists()) part.length() else 0L
            try {
                fetch(r, part)
                if (part.length() != r.size || sha256(part) != r.sha) { part.delete(); throw IOException("checksum mismatch") }
                if (!part.renameTo(f)) throw IOException("rename failed")
                return
            } catch (e: IOException) {
                if (part.exists() && part.length() > before) attempt = 0 // got somewhere: a stall, not a dead server
                if (++attempt >= 5) throw e
                Log.w(TAG, "guide: ${f.name} attempt $attempt: $e")
                Thread.sleep(minOf(60_000L, 3000L shl (2 * (attempt - 1))))
            }
        }
    }

    private fun fetch(r: Ref, part: File) {
        var have = if (part.exists()) part.length() else 0L
        if (have >= r.size) return
        val req = Request.Builder().url(BASE + r.url)
        if (have > 0) req.header("Range", "bytes=$have-")
        http.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            if (have > 0 && resp.code != 206) { done -= have; have = 0 } // no Range: start over
            FileOutputStream(part, have > 0).use { out ->
                val src = resp.body!!.byteStream()
                val buf = ByteArray(16 * 1024)
                var winStart = SystemClock.elapsedRealtime()
                var winBytes = 0L
                while (true) {
                    if (hold()) { winStart = SystemClock.elapsedRealtime(); winBytes = 0 }
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    winBytes += n
                    val due = winStart + winBytes * 1000 / BYTES_PER_SEC
                    val now = SystemClock.elapsedRealtime()
                    if (due > now) Thread.sleep(due - now)
                    if (now - lastStatus > 1000) { lastStatus = now; progress() }
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                hold()
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hold() = pacer.hold()

    companion object {
        private const val TAG = "tivibro"
        private const val BASE = "https://epg.team"
        private const val MANIFEST = "/epg/segmented/v1/manifest.json"
        /** Download speed cap: the guide is never worth stealing bandwidth from the stream. */
        const val BYTES_PER_SEC = 1_500_000L
        const val DAYS_BACK = 7
        const val DAYS_AHEAD = 2

        /** 1 день, 2 дні, 5 днів. */
        fun daysWord(n: Int): String = plural(R.plurals.days, n)
    }
}

/**
 * Keeps background guide work out of the way: [hold] waits while [holdReason] has one (keys, a starving stream),
 * [pace] caps a byte stream at [bytesPerSec]. Used from one background thread.
 */
class Pacer(private val holdReason: () -> String?, private val bytesPerSec: Long = GuideSync.BYTES_PER_SEC) {
    /** Time spent holding, ms. */
    var paused = 0L
    private var winStart = SystemClock.elapsedRealtime()
    private var winBytes = 0L

    /** Waits while there is a reason to; true if it did. */
    fun hold(): Boolean {
        val why = holdReason() ?: return false
        val t0 = SystemClock.elapsedRealtime()
        Log.i("tivibro", "guide: paused ($why)")
        while (holdReason() != null) Thread.sleep(250)
        val ms = SystemClock.elapsedRealtime() - t0
        paused += ms
        Log.i("tivibro", "guide: resumed after $ms ms")
        winStart = SystemClock.elapsedRealtime(); winBytes = 0
        return true
    }

    /** Called after reading [n] bytes: sleeps as long as it takes to stay under the cap. */
    fun pace(n: Int) {
        winBytes += n
        val due = winStart + winBytes * 1000 / bytesPerSec
        val now = SystemClock.elapsedRealtime()
        if (due > now) Thread.sleep(due - now)
        if (now - winStart > 10_000) { winStart = now; winBytes = 0 }
    }

    /** [src] read through [hold] and [pace]. */
    fun wrap(src: java.io.InputStream, onBytes: (Int) -> Unit = {}): java.io.InputStream = object : java.io.FilterInputStream(src) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            hold()
            val n = super.read(b, off, len)
            if (n > 0) { pace(n); onBytes(n) }
            return n
        }
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }
    }
}
