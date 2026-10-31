package dev.romirom.tivibro.data

import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingDeque
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * A guide imported from an XMLTV file, for sources other than epg.team's segmented one. On disk as `guide.dat`, a run
 * of deflated chunks (one channel's programmes in file order: start, duration, title, description), and `guide.idx`,
 * guide id → its chunks. Only the active playlist's channels are kept.
 *
 * Read like [Guide]: the getters only look into memory and queue what is missing for the decode thread.
 */
class XmltvGuide(private val dir: File, private val onChanged: (id: String?, old: List<Programme>?, new: List<Programme>?) -> Unit) : ProgrammeGuide {
    class Meta(val url: String, val playlist: String, val at: Long, val channels: Int, val programmes: Int)

    init { dir.mkdirs() }

    @Volatile var meta: Meta? = readMeta(); private set
    /** guide id → (offset, length) pairs into guide.dat. */
    @Volatile private var index: Map<String, LongArray> = emptyMap()

    override val hasData get() = index.isNotEmpty()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cache = LinkedHashMap<String, Pair<List<Programme>, Int>>(64, .75f, true)
    private var cacheBytes = 0
    private val pending = HashSet<String>()
    private val queue = LinkedBlockingDeque<Any>()
    private var dat: RandomAccessFile? = null

    init {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            runCatching { loadIndex() }.onFailure { Log.w(TAG, "xmltv: index not loaded", it) }
            while (true) {
                val t = queue.take()
                try {
                    when (t) {
                        is String -> decode(t)
                        is Install -> install(t)
                        CLEAR -> drop()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "xmltv: decode failed", e)
                }
            }
        }, "xmltv-decode").start()
    }

    override fun list(ch: Channel, around: Long?): List<Programme> {
        val id = ch.epgId
        synchronized(lock) {
            cache[id]?.let { return it.first }
            if (!index.containsKey(id) || !pending.add(id)) return emptyList()
        }
        queue.addFirst(id)
        while (queue.size > 120) { val x = queue.pollLast() ?: break; if (x is String) synchronized(lock) { pending.remove(x) } }
        return emptyList()
    }

    override fun complete(ch: Channel) = !index.containsKey(ch.epgId) || synchronized(lock) { cache.containsKey(ch.epgId) }

    override fun desc(p: Programme): String = p.desc

    private fun decode(id: String) {
        synchronized(lock) { if (!pending.remove(id)) return }
        val chunks = index[id] ?: return
        val f = dat ?: RandomAccessFile(File(dir, DAT), "r").also { dat = it }
        val all = ArrayList<Programme>()
        for (k in chunks.indices step 2) {
            val buf = ByteArray(chunks[k + 1].toInt())
            f.seek(chunks[k]); f.readFully(buf)
            DataInputStream(InflaterInputStream(buf.inputStream())).use { r ->
                repeat(r.readInt()) {
                    val s = r.readLong(); val d = r.readInt()
                    // epg.team's « ┊ » before an episode number: the segmented guide shows it as « · » too
                    all += Programme(s * 1000, (s + d) * 1000, r.readUTF().replace(" ┊ ", " · "), r.readUTF())
                }
            }
        }
        all.sortWith(compareBy<Programme> { it.start }.thenByDescending { it.end })
        val list = ArrayList<Programme>(all.size)
        for (p in all) if (list.isEmpty() || list.last().start != p.start) list += p
        // some sources nest a long block over the slots inside it (02:50–06:00 over 02:55, 03:35…): end each programme
        // where the next one starts, so one programme is on air at a time
        for (i in 0 until list.size - 1) if (list[i].end > list[i + 1].start) list[i] = list[i].copy(end = list[i + 1].start)
        var bytes = 200
        for (p in list) bytes += 96 + 2 * (p.title.length + p.desc.length)
        synchronized(lock) {
            cache.put(id, list to bytes)?.let { cacheBytes -= it.second }
            cacheBytes += bytes
            val it = cache.entries.iterator()
            while (cacheBytes > BUDGET && it.hasNext()) {
                val e = it.next()
                if (e.key == id) continue
                cacheBytes -= e.value.second
                it.remove()
            }
        }
        main.post { onChanged(id, null, list) }
    }

    private class Install(val dat: File, val idx: File, val meta: Meta, val done: () -> Unit)

    /** Replaces the store with a finished import (on the decode thread, so no read sees half of it). */
    fun install(dat: File, idx: File, meta: Meta, done: () -> Unit) = queue.addLast(Install(dat, idx, meta, done))

    private fun install(t: Install) {
        runCatching { dat?.close() }
        dat = null
        check(t.dat.renameTo(File(dir, DAT)) && t.idx.renameTo(File(dir, IDX))) { "xmltv: rename failed" }
        File(dir, META).writeText(
            JSONObject().put("url", t.meta.url).put("pl", t.meta.playlist).put("at", t.meta.at)
                .put("ch", t.meta.channels).put("n", t.meta.programmes).toString(),
        )
        meta = t.meta
        synchronized(lock) { cache.clear(); cacheBytes = 0; pending.clear() }
        loadIndex()
        t.done()
    }

    /** Drops the store (another source was chosen): frees the disk. */
    fun clear() = queue.addLast(CLEAR)

    private fun drop() {
        runCatching { dat?.close() }
        dat = null
        listOf(DAT, IDX, META).forEach { File(dir, it).delete() }
        meta = null
        synchronized(lock) { cache.clear(); cacheBytes = 0; pending.clear() }
        index = emptyMap()
        main.post { onChanged(null, null, null) }
    }

    private fun loadIndex() {
        val f = File(dir, IDX)
        if (!f.exists() || meta == null) { index = emptyMap(); main.post { onChanged(null, null, null) }; return }
        val out = HashMap<String, LongArray>()
        DataInputStream(f.inputStream().buffered()).use { r ->
            check(r.readInt() == MAGIC)
            repeat(r.readInt()) {
                val id = r.readUTF()
                val n = r.readInt()
                out[id] = LongArray(2 * n).also { a -> for (i in 0 until n) { a[2 * i] = r.readLong(); a[2 * i + 1] = r.readInt().toLong() } }
            }
        }
        index = out
        main.post { onChanged(null, null, null) }
    }

    private fun readMeta(): Meta? = runCatching {
        val o = JSONObject(File(dir, META).readText())
        Meta(o.getString("url"), o.getString("pl"), o.getLong("at"), o.optInt("ch"), o.optInt("n"))
    }.getOrNull()?.takeIf { it.url.isNotEmpty() && File(dir, IDX).exists() && File(dir, DAT).exists() }

    // ------------------------------------------------------------------ import (guide-sync thread)

    class Result(val channels: Int, val programmes: Int, val bytes: Long, val millis: Long)

    /**
     * Downloads [url] (XMLTV, gzipped or not) and keeps what [channels] need: the stream is parsed as it arrives, a
     * channel's run of programmes is deflated and appended to the new store as soon as the next channel starts, so
     * memory holds one channel at a time. Paced by [pacer] (download speed, held while the user is at the remote).
     */
    fun import(http: OkHttpClient, url: String, playlist: String, channels: List<Channel>, pacer: Pacer, onStatus: (String?) -> Unit): Result {
        val t0 = SystemClock.elapsedRealtime()
        val byKey = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        for (ch in channels) {
            for (k in listOfNotNull(ch.id.takeUnless { it.startsWith(UNNAMED) }, ch.epgId, urlChannelId(ch.url)).distinct()) {
                byKey.getOrPut(k) { ArrayList(1) }.let { if (ch.epgId !in it) it += ch.epgId }
            }
            byName.getOrPut(epgName(ch.name)) { ArrayList(1) }.let { if (ch.epgId !in it) it += ch.epgId }
        }
        val tmpDat = File(dir, "$DAT.new")
        val tmpIdx = File(dir, "$IDX.new")
        val chunks = HashMap<String, ArrayList<Long>>()
        var programmes = 0
        var bytesIn = 0L
        var lastStatus = 0L
        val from = now() / 1000 - 8 * 86400
        val to = now() / 1000 + 8 * 86400

        val req = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val raw = BufferedInputStream(pacer.wrap(resp.body!!.byteStream()) { n ->
                bytesIn += n
                val t = SystemClock.elapsedRealtime()
                if (t - lastStatus > 1000) { lastStatus = t; onStatus(str(R.string.guide_dl_mb, (bytesIn shr 20).toInt())) }
            }, 64 * 1024)
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val src = if (gz) GZIPInputStream(raw, 64 * 1024) else raw
            val out = DataOutputStream(BufferedOutputStream(FileOutputStream(tmpDat), 64 * 1024))
            var offset = 0L
            out.use {
                val xp = Xml.newPullParser()
                xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                xp.setInput(src, null)

                val xmap = HashMap<String, List<String>>()
                val taken = HashSet<String>()
                val waiting = ArrayList<Pair<String, List<String>>>() // <channel>s not yet resolved: id, names
                fun resolve() { // ids first, then names for whatever no id claimed
                    for ((xid, _) in waiting) {
                        val t = byKey[xid] ?: byKey[normalizeTvgId(xid)] ?: continue
                        xmap[xid] = t; taken += t
                    }
                    for ((xid, names) in waiting) {
                        if (xid in xmap) continue
                        val t = names.firstNotNullOfOrNull { byName[epgName(it)] }?.filter { it !in taken } ?: continue
                        if (t.isNotEmpty()) { xmap[xid] = t; taken += t }
                    }
                    waiting.clear()
                }

                var curX: String? = null
                var curT: List<String> = emptyList()
                val buf = ArrayList<LongArray>() // start, duration per programme; texts alongside
                val texts = ArrayList<String>()
                fun flush() {
                    if (buf.isNotEmpty()) {
                        val bo = ByteArrayOutputStream(buf.size * 200)
                        DataOutputStream(DeflaterOutputStream(bo, Deflater(5))).use { d ->
                            d.writeInt(buf.size)
                            for (i in buf.indices) {
                                d.writeLong(buf[i][0]); d.writeInt(buf[i][1].toInt())
                                d.writeUTF(texts[2 * i]); d.writeUTF(texts[2 * i + 1])
                            }
                        }
                        val bytes = bo.toByteArray()
                        out.write(bytes)
                        for (t in curT) chunks.getOrPut(t) { ArrayList() }.apply { add(offset); add(bytes.size.toLong()) }
                        offset += bytes.size
                    }
                    buf.clear(); texts.clear()
                }

                while (true) {
                    val ev = xp.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev != XmlPullParser.START_TAG) continue
                    when (xp.name) {
                        "channel" -> {
                            val xid = xp.getAttributeValue(null, "id") ?: continue
                            val names = ArrayList<String>(2)
                            readChildren(xp) { tag -> if (tag == "display-name") names += xp.nextText().trim() else skip(xp) }
                            waiting += xid to names
                        }
                        "programme" -> {
                            if (waiting.isNotEmpty()) resolve()
                            val xid = xp.getAttributeValue(null, "channel") ?: continue
                            val targets = xmap.getOrPut(xid) { byKey[xid] ?: byKey[normalizeTvgId(xid)] ?: emptyList() }
                            if (targets.isEmpty()) { skip(xp); continue }
                            val s = xmltvTime(xp.getAttributeValue(null, "start"))
                            val e = xmltvTime(xp.getAttributeValue(null, "stop"))
                            var title = ""
                            var desc = ""
                            readChildren(xp) { tag ->
                                when (tag) {
                                    "title" -> xp.nextText().trim().let { if (title.isEmpty()) title = it }
                                    "desc" -> xp.nextText().trim().let { if (desc.isEmpty()) desc = it.take(DESC_MAX) }
                                    else -> skip(xp)
                                }
                            }
                            if (s == null || e == null || e <= s || e < from || s > to) continue
                            if (xid != curX) { flush(); curX = xid; curT = targets }
                            buf += longArrayOf(s, e - s)
                            texts += title; texts += desc
                            if (++programmes % 2000 == 0) pacer.hold()
                        }
                    }
                }
                if (waiting.isNotEmpty()) resolve()
                flush()
            }
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmpIdx))).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(chunks.size)
            for ((id, a) in chunks) {
                o.writeUTF(id)
                o.writeInt(a.size / 2)
                for (i in a.indices step 2) { o.writeLong(a[i]); o.writeInt(a[i + 1].toInt()) }
            }
        }
        val lock = Object()
        var installed = false
        install(tmpDat, tmpIdx, Meta(url, playlist, now(), chunks.size, programmes)) { synchronized(lock) { installed = true; lock.notifyAll() } }
        synchronized(lock) { while (!installed) lock.wait(30_000).also { if (!installed) Log.w(TAG, "xmltv: install is slow") } }
        onStatus(null)
        return Result(chunks.size, programmes, bytesIn, SystemClock.elapsedRealtime() - t0)
    }

    private inline fun readChildren(xp: XmlPullParser, onTag: (String) -> Unit) {
        val depth = xp.depth
        while (true) {
            val ev = xp.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && xp.depth == depth)) return
            if (ev == XmlPullParser.START_TAG) onTag(xp.name)
        }
    }

    /** Past the end of the element whose start tag the parser is on. */
    private fun skip(xp: XmlPullParser) {
        var d = 1
        while (d > 0) when (xp.next()) {
            XmlPullParser.START_TAG -> d++
            XmlPullParser.END_TAG -> d--
            XmlPullParser.END_DOCUMENT -> return
        }
    }

    companion object {
        private const val TAG = "tivibro"
        private val CLEAR = Any()
        private const val DAT = "guide.dat"
        private const val IDX = "guide.idx"
        private const val META = "meta.json"
        private const val MAGIC = 0x58545631 // "XTV1"
        private const val BUDGET = 6 shl 20
        private const val DESC_MAX = 600

        /** "20261030214750 +0200" → unix seconds; no offset means UTC. Null if it isn't one. */
        fun xmltvTime(s: String?): Long? {
            if (s == null) return null
            val t = s.trim()
            if (t.length < 12 || !t.substring(0, 12).all(Char::isDigit)) return null
            val day = Guide.epochDay("${t.substring(0, 4)}-${t.substring(4, 6)}-${t.substring(6, 8)}").toLong()
            val sec = if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) t.substring(12, 14).toLong() else 0
            var v = day * 86400 + t.substring(8, 10).toLong() * 3600 + t.substring(10, 12).toLong() * 60 + sec
            val z = t.substringAfter(' ', "").trim()
            if (z.length >= 5 && (z[0] == '+' || z[0] == '-') && z.substring(1, 5).all(Char::isDigit)) {
                val off = z.substring(1, 3).toLong() * 3600 + z.substring(3, 5).toLong() * 60
                v -= if (z[0] == '+') off else -off
            }
            return v
        }
    }
}
