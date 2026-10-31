package dev.romirom.tivibro.data

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * One UTC day of epg.team's segmented guide («tepg-segmented-1», see tools/epg/tepgseg.py), as bound on disk: the
 * schedule, the channels file its channel numbers index, and the content layer of every manifest ordinal its
 * programmes point into (`""` where none). All from one manifest: record numbers mean nothing across manifests.
 */
class GuideDay(val day: Int, val channels: String, val schedule: String, val content: List<String>) {
    fun files() = listOf(channels, schedule) + content.filter { it.isNotEmpty() }
}

/**
 * The programme guide, decoded on demand from the blob files. Nothing here runs on the main thread except the getters,
 * which only look into memory: whatever is not decoded yet comes back empty (or stale) and is queued for the decode
 * thread; [onChanged] runs on the main thread when it lands.
 *
 * Titles are read from a per-content-blob title index ([buildTitles], `.tti`): a channel's programmes are spread over
 * ~10 of a layer's 390 KB content blocks, too much to inflate per list row. Descriptions are only needed for the one
 * programme in the detail pane and come from the content blocks themselves ([desc]).
 */
class Guide(private val dir: File, private val onChanged: (id: String?, old: List<Programme>?, new: List<Programme>?) -> Unit) : ProgrammeGuide {
    val blobs = File(dir, "blobs").apply { mkdirs() }

    /** Bound days by epoch day (UTC). Replaced whole by [bind], read by any thread. */
    @Volatile var days: Map<Int, GuideDay> = emptyMap(); private set

    override val hasData get() = days.isNotEmpty()

    private class Part(val schedule: String, val progs: List<Programme>)
    private class ChannelGuide(val parts: Map<Int, Part>, val list: List<Programme>, val bytes: Int)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cache = LinkedHashMap<String, ChannelGuide>(64, .75f, true)
    private var cacheBytes = 0
    private val pending = HashMap<String, MutableSet<Int>>()
    private val descs = object : LinkedHashMap<Long, String>(64, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, String>) = size > 400
    }
    private val descPending = HashSet<Long>()
    private val queue = LinkedBlockingDeque<Any>()
    /** Guide id → the keys to try for it, best first ([epgKeys]); filled by the getters. */
    private val keys = HashMap<String, List<String>>()

    init {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            runCatching { load() }.onFailure { Log.w(TAG, "guide: bindings not loaded", it) }
            while (true) {
                val t = queue.take()
                try {
                    when (t) {
                        is String -> decodeChannel(t)
                        is Long -> decodeDesc(t)
                        GC -> gc()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "guide: decode failed", e)
                }
            }
        }, "guide-decode").start()
    }

    // ------------------------------------------------------------------ main-thread getters

    /** What is decoded of channel [ch] right now, merged over days. Asks for the days around [around] (or all bound
     *  days when null) that are missing or stale. */
    override fun list(ch: Channel, around: Long?): List<Programme> {
        val id = ch.epgId
        val b = days
        val cg = synchronized(lock) { cache[id] }
        var miss: HashSet<Int>? = null
        fun want(d: Int) {
            val gd = b[d] ?: return
            if (cg?.parts?.get(d)?.schedule != gd.schedule) (miss ?: HashSet<Int>().also { miss = it }).add(d)
        }
        if (around == null) b.keys.forEach(::want)
        else Math.floorDiv(around, DAY).toInt().let { d -> want(d - 1); want(d); want(d + 1) }
        miss?.let { synchronized(lock) { keys.getOrPut(id) { ch.epgKeys() } }; request(id, it) }
        return cg?.list ?: emptyList()
    }

    /** True when every bound day of [ch] is decoded and current. */
    override fun complete(ch: Channel): Boolean {
        val b = days
        val cg = synchronized(lock) { cache[ch.epgId] } ?: return b.isEmpty()
        return b.all { (d, gd) -> cg.parts[d]?.schedule == gd.schedule }
    }

    /** The description of [p], or null while it is being read. */
    override fun desc(p: Programme): String? {
        if (p.ref < 0) return p.desc
        synchronized(lock) {
            descs[p.ref]?.let { return it }
            if (!descPending.add(p.ref)) return null
        }
        push(p.ref)
        return null
    }

    private fun request(id: String, d: Set<Int>) {
        synchronized(lock) {
            val p = pending[id]
            if (p != null) { p.addAll(d); return } // already queued: it'll pick these up too
            pending[id] = d.toHashSet()
        }
        push(id)
    }

    /** Newest first: what the user looks at now beats what scrolled away. Old requests fall off the end. */
    private fun push(t: Any) {
        queue.addFirst(t)
        while (queue.size > QUEUE_MAX) {
            val x = queue.pollLast() ?: break
            synchronized(lock) { if (x is String) pending.remove(x) else if (x is Long) descPending.remove(x) }
        }
    }

    // ------------------------------------------------------------------ bindings (download thread)

    /** Switches to [d] (complete days only) and saves it for the next start. */
    fun bind(d: Map<Int, GuideDay>) {
        days = d.toSortedMap()
        save()
        push(GC)
        main.post { onChanged(null, null, null) }
    }

    private fun save() {
        val a = JSONArray()
        for (g in days.values) a.put(JSONObject().put("day", g.day).put("ch", g.channels).put("s", g.schedule).put("c", JSONArray(g.content)))
        val tmp = File(dir, "days.json.tmp")
        tmp.writeText(JSONObject().put("days", a).toString())
        tmp.renameTo(File(dir, "days.json"))
    }

    private fun load() {
        val f = File(dir, "days.json")
        if (!f.exists()) return
        val a = JSONObject(f.readText()).getJSONArray("days")
        val out = HashMap<Int, GuideDay>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val c = o.getJSONArray("c")
            val g = GuideDay(o.getInt("day"), o.getString("ch"), o.getString("s"), List(c.length()) { c.getString(it) })
            // the system may have dropped files (or a sync died between download and bind): keep only complete days
            val ok = File(blobs, "${g.channels}.tpc").exists() && File(blobs, "${g.schedule}.tps").exists() &&
                g.content.all { it.isEmpty() || (File(blobs, "$it.tpc").exists() && File(blobs, "$it.tti").exists()) }
            if (ok) out[g.day] = g
        }
        days = out.toSortedMap()
        if (out.isNotEmpty()) main.post { onChanged(null, null, null) }
    }

    // ------------------------------------------------------------------ decode thread

    private val files = HashMap<String, BlobFile>()
    private val titleFiles = HashMap<String, TitleFile>()
    private val chanIdx = HashMap<String, Map<String, Int>>()
    private val contentDirs = HashMap<String, ByteArray>()
    private val blocks = LinkedHashMap<String, ByteArray>(16, .75f, true)
    private var blockBytes = 0
    private val refShas = ArrayList<String>()
    private val refIds = HashMap<String, Int>()

    private fun blob(sha: String, ext: String) = files.getOrPut("$sha.$ext") { BlobFile(File(blobs, "$sha.$ext")) }

    private fun decodeChannel(id: String) {
        val want = synchronized(lock) { pending.remove(id) } ?: return
        val b = days
        val old = synchronized(lock) { cache[id] }
        val parts = HashMap<Int, Part>()
        old?.parts?.forEach { (d, p) -> if (b[d]?.schedule == p.schedule) parts[d] = p }
        var changed = false
        for (d in want) {
            val gd = b[d] ?: continue
            if (parts[d] != null) continue
            // a failed day is kept empty until its binding changes rather than retried on every recomposition
            val progs = try { decodeDay(id, gd) } catch (e: Exception) { Log.w(TAG, "guide: $id day $d: $e"); emptyList() }
            parts[d] = Part(gd.schedule, progs)
            changed = true
        }
        if (!changed && old != null && parts.size == old.parts.size) return
        // layers overlap at midnight and epg.team repeats some slots (same start, one copy cut short): keep the longest
        val all = parts.values.flatMap { it.progs }.sortedWith(compareBy<Programme> { it.start }.thenByDescending { it.end })
        val merged = ArrayList<Programme>(all.size)
        for (p in all) if (merged.isEmpty() || merged.last().start != p.start) merged += p
        var bytes = 200
        for (p in merged) bytes += 96 + 2 * p.title.length
        val cg = ChannelGuide(parts, merged, bytes)
        synchronized(lock) {
            cache.put(id, cg)?.let { cacheBytes -= it.bytes }
            cacheBytes += bytes
            val it = cache.entries.iterator()
            while (cacheBytes > LIST_BUDGET && it.hasNext()) {
                val e = it.next()
                if (e.key == id) continue
                cacheBytes -= e.value.bytes
                it.remove()
            }
        }
        main.post { onChanged(id, old?.list, merged) }
    }

    private fun decodeDay(id: String, gd: GuideDay): List<Programme> {
        val index = chanIdx.getOrPut(gd.channels) { channelIndex(blob(gd.channels, "tpc")) }
        val idx = (synchronized(lock) { keys[id] } ?: listOf(id)).firstNotNullOfOrNull { index[it] } ?: return emptyList()
        val s = blob(gd.schedule, "tps")
        val h = s.header
        check(h.magic == "TEPGSCH")
        if (idx >= h.secondary) return emptyList()
        val s1 = h.secs.getValue(1); val s2 = h.secs.getValue(2); val s3 = h.secs.getValue(3)
        val e = s.read(s2.off + 48L * idx, 48)
        if (e.i32(0) == 0) return emptyList()
        val raw = unpack(s.read(s3.off + e.i64(8), e.i32(16)), e.i32(4), e.i32(20), e.u32(24))
        val n = raw.i32(0)
        val c = Cursor(raw, 4)
        var t = h.tmin
        val out = ArrayList<Programme>(n)
        repeat(n) {
            t += zigzag(c.uvar())
            val dur = c.uvar()
            val r = s.read(s1.off + 8 * c.uvar(), 8)
            val sha = gd.content.getOrNull(r.u16(0)).orEmpty()
            val rec = r.i32(4)
            val title = if (sha.isEmpty()) "" else title(sha, rec)
            out += Programme(t * 1000, (t + dur) * 1000, title, "", if (sha.isEmpty()) -1 else (refId(sha).toLong() shl 32) or rec.toLong())
        }
        return out
    }

    private fun title(sha: String, rec: Int): String {
        val f = titleFiles.getOrPut(sha) { TitleFile(File(blobs, "$sha.tti")) }
        return f.get(rec)
    }

    private fun refId(sha: String) = refIds.getOrPut(sha) { refShas += sha; refShas.size - 1 }

    private fun decodeDesc(ref: Long) {
        val sha = refShas.getOrNull((ref ushr 32).toInt())
        val text = try {
            if (sha == null) "" else descOf(sha, (ref and 0xFFFFFFFFL).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "guide: desc: $e"); ""
        }
        synchronized(lock) { descs[ref] = text; descPending.remove(ref) }
        main.post { onChanged(null, null, null) }
    }

    private fun descOf(sha: String, rec: Int): String {
        val f = blob(sha, "tpc")
        val h = f.header
        val dir = contentDirs.getOrPut(sha) { f.read(h.secs.getValue(1).off, 32 * h.secondary.toInt()) }
        var lo = 0
        var hi = dir.size / 32 - 1
        var i = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val first = dir.i32(32 * m)
            when {
                rec < first -> hi = m - 1
                rec >= first + dir.i32(32 * m + 4) -> lo = m + 1
                else -> { i = m; break }
            }
        }
        if (i < 0) return ""
        val o = 32 * i
        val key = "$sha:$i"
        val raw = blocks[key] ?: unpack(f.read(h.secs.getValue(2).off + dir.i64(o + 8), dir.i32(o + 16)), dir.i32(o + 28), dir.i32(o + 20), dir.u32(o + 24)).also {
            blocks[key] = it
            blockBytes += it.size
            val iter = blocks.entries.iterator()
            while (blockBytes > BLOCK_BUDGET && iter.hasNext()) {
                val e = iter.next()
                if (e.key == key) continue
                blockBytes -= e.value.size
                iter.remove()
            }
        }
        val n = raw.i32(0)
        val c = Cursor(raw, 4 + 4 * (n + 1) + raw.i32(4 + 4 * (rec - dir.i32(o))))
        c.skip() // title
        val len = c.uvar().toInt()
        return String(raw, c.p, len, Charsets.UTF_8).take(DESC_MAX)
    }

    /** Drops open files and caches of blobs that are no longer bound (their files are about to go). */
    private fun gc() {
        val live = days.values.flatMapTo(HashSet()) { it.files() }
        fun dead(name: String) = name.substringBefore('.') !in live
        files.entries.removeAll { (k, v) -> dead(k).also { if (it) v.close() } }
        titleFiles.entries.removeAll { (k, v) -> dead(k).also { if (it) v.close() } }
        chanIdx.keys.removeAll(::dead)
        contentDirs.keys.removeAll(::dead)
        blocks.entries.removeAll { (k, v) -> dead(k).also { if (it) blockBytes -= v.size } }
    }

    companion object {
        const val TAG = "tivibro"
        private val GC = Any()
        private const val QUEUE_MAX = 120
        /** Decoded programme lists (≈40 KB per channel for 10 days) and inflated content blocks (≈390 KB each). */
        private const val LIST_BUDGET = 6 shl 20
        private const val BLOCK_BUDGET = 6 shl 20
        private const val DESC_MAX = 600

        /** channel id → its position in the channels file, which is the index into a schedule's channel directory.
         *  Names ([epgName]) are keys too, below the ids: for playlists that only name their channels. */
        fun channelIndex(f: BlobFile): Map<String, Int> {
            val h = f.header
            check(h.magic == "TEPGCHN")
            val s = h.secs.getValue(1)
            val raw = f.read(s.off, s.size.toInt())
            val d = if (s.flags and 1 != 0) InflaterInputStream(raw.inputStream()).use { it.readBytes() } else raw
            val c = Cursor(d, 0)
            val out = HashMap<String, Int>(h.primary.toInt() * 3)
            val names = HashMap<String, Int>(h.primary.toInt() * 2)
            for (i in 0 until h.primary.toInt()) {
                val id = c.str()
                out[id] = i
                names.getOrPut(epgName(c.str())) { i }
            }
            for ((n, i) in names) out.getOrPut(n) { i }
            return out
        }

        /** Writes the title of every record of content blob [src] to [dst]: "TTI1", u32 n, (n+1) u32 offsets, UTF-8.
         *  [pause] is called between blocks. */
        fun buildTitles(src: File, dst: File, pause: () -> Unit) {
            BlobFile(src).use { f ->
                val h = f.header
                check(h.magic == "TEPGCNT")
                val n = h.primary.toInt()
                val nb = h.secondary.toInt()
                val dir = f.read(h.secs.getValue(1).off, 32 * nb)
                val base = h.secs.getValue(2).off
                val offs = IntArray(n + 1)
                val data = ByteArrayOutputStream(n * 48)
                var rec = 0
                for (i in (0 until nb).sortedBy { dir.i32(32 * it) }) {
                    pause()
                    val o = 32 * i
                    val first = dir.i32(o)
                    val raw = unpack(f.read(base + dir.i64(o + 8), dir.i32(o + 16)), dir.i32(o + 28), dir.i32(o + 20), dir.u32(o + 24))
                    val m = raw.i32(0)
                    val body = 4 + 4 * (m + 1)
                    while (rec < first && rec < n) offs[rec++] = data.size()
                    for (j in 0 until m) {
                        if (first + j >= n) break
                        val c = Cursor(raw, body + raw.i32(4 + 4 * j))
                        val len = c.uvar().toInt()
                        offs[rec++] = data.size()
                        // as is, unless it has the provider's " ┊ " (E2 94 8A): no String per title for the rest
                        if (!hasSeparator(raw, c.p, len)) data.write(raw, c.p, len)
                        else data.write(String(raw, c.p, len, Charsets.UTF_8).replace(" ┊ ", " · ").toByteArray(Charsets.UTF_8))
                    }
                }
                while (rec <= n) offs[rec++] = data.size()
                val head = ByteBuffer.allocate(8 + 4 * (n + 1)).order(ByteOrder.LITTLE_ENDIAN)
                head.put("TTI1".toByteArray()).putInt(n)
                for (x in offs) head.putInt(x)
                val tmp = File(dst.path + ".tmp")
                tmp.outputStream().use { it.write(head.array()); data.writeTo(it) }
                check(tmp.renameTo(dst))
            }
        }

        private fun hasSeparator(b: ByteArray, from: Int, len: Int): Boolean {
            for (i in from until from + len - 2) if (b[i] == 0xE2.toByte() && b[i + 1] == 0x94.toByte() && b[i + 2] == 0x8A.toByte()) return true
            return false
        }

        /** "2026-10-31" → days since 1970-01-01 (no java.time on minSdk 23). */
        fun epochDay(date: String): Int {
            val y0 = date.substring(0, 4).toInt(); val m = date.substring(5, 7).toInt(); val d = date.substring(8, 10).toInt()
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }
    }
}

/**
 * What the app reads programmes through: epg.team's segmented guide ([Guide]) or an XMLTV import ([XmltvGuide]).
 * Every call is made on the main thread and only looks into memory; what is missing is queued and announced later.
 */
interface ProgrammeGuide {
    /** Something is on disk to read from. */
    val hasData: Boolean
    fun list(ch: Channel, around: Long?): List<Programme>
    fun complete(ch: Channel): Boolean
    fun desc(p: Programme): String?
}

// ---------------------------------------------------------------------- tepg-segmented-1 file access

/** Section entry of the common 256-byte header. */
class Sec(val flags: Int, val off: Long, val size: Long)

class Header(b: ByteArray) {
    init {
        require(b.size >= 256) { "short header" }
        require(CRC32().apply { update(b, 0, 252) }.value == b.u32(252)) { "header crc" }
    }
    val magic = String(b, 0, 7, Charsets.US_ASCII)
    val primary = b.i64(0x28)
    val secondary = b.i64(0x30)
    val tmin = b.i64(0x40)
    val secs: Map<Int, Sec> = (0 until b.i32(24)).associate { i -> val o = 0x60 + 32 * i; b.i32(o) to Sec(b.i32(o + 4), b.i64(o + 8), b.i64(o + 16)) }
}

/** Positional reads from one blob: thread-safe, no mapping (a 32-bit TV has little address space to spare). */
class BlobFile(f: File) : Closeable {
    private val raf = RandomAccessFile(f, "r")
    private val ch = raf.channel
    val header by lazy { Header(read(0, 256)) }

    fun read(pos: Long, n: Int): ByteArray {
        val b = ByteBuffer.allocate(n)
        var p = pos
        while (b.hasRemaining()) {
            val r = ch.read(b, p)
            if (r < 0) throw EOFException("$pos+$n")
            p += r
        }
        return b.array()
    }

    override fun close() = raf.close()
}

/** A `.tti` title index written by [Guide.buildTitles]. */
private class TitleFile(f: File) : Closeable {
    private val b = BlobFile(f)
    private val n = b.read(0, 8).also { require(String(it, 0, 4, Charsets.US_ASCII) == "TTI1") }.i32(4)
    private val data = 8L + 4L * (n + 1)

    fun get(rec: Int): String {
        if (rec !in 0 until n) return ""
        val o = b.read(8 + 4L * rec, 8)
        val a = o.i32(0)
        return String(b.read(data + a, o.i32(4) - a), Charsets.UTF_8)
    }

    override fun close() = b.close()
}

/** Inflates a block (flag bit 0 = zlib) and checks its CRC. */
internal fun unpack(src: ByteArray, flags: Int, rawSize: Int, crc: Long): ByteArray {
    val out = if (flags and 1 == 0) src else ByteArray(rawSize).also { out ->
        val inf = Inflater()
        try {
            inf.setInput(src)
            var n = 0
            while (n < rawSize) {
                val r = inf.inflate(out, n, rawSize - n)
                if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            check(n == rawSize) { "inflated $n of $rawSize" }
        } finally {
            inf.end()
        }
    }
    check(CRC32().apply { update(out) }.value == crc) { "block crc" }
    return out
}

internal class Cursor(private val b: ByteArray, var p: Int) {
    fun uvar(): Long {
        var v = 0L
        var s = 0
        while (true) {
            val c = b[p++].toInt() and 0xFF
            v = v or ((c and 0x7F).toLong() shl s)
            if (c < 0x80) return v
            s += 7
        }
    }

    /** Past a varint-length string. (Not `p += uvar()`: that adds to `p` as it was before the varint.) */
    fun skip() {
        val n = uvar().toInt()
        p += n
    }

    fun str(): String {
        val n = uvar().toInt()
        return String(b, p, n, Charsets.UTF_8).also { p += n }
    }
}

internal fun zigzag(v: Long) = (v ushr 1) xor -(v and 1)
internal fun ByteArray.u16(p: Int) = (this[p].toInt() and 0xFF) or ((this[p + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.i32(p: Int) = u16(p) or (u16(p + 2) shl 16)
internal fun ByteArray.u32(p: Int) = i32(p).toLong() and 0xFFFFFFFFL
internal fun ByteArray.i64(p: Int) = u32(p) or (u32(p + 4) shl 32)
