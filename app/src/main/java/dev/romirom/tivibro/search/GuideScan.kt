package dev.romirom.tivibro.search

import dev.romirom.tivibro.data.BlobFile
import dev.romirom.tivibro.data.Cursor
import dev.romirom.tivibro.data.Guide
import dev.romirom.tivibro.data.GuideDay
import dev.romirom.tivibro.data.XmltvGuide
import dev.romirom.tivibro.data.epgKeys
import java.util.concurrent.CancellationException
import dev.romirom.tivibro.data.i32
import dev.romirom.tivibro.data.i64
import dev.romirom.tivibro.data.u16
import dev.romirom.tivibro.data.u32
import dev.romirom.tivibro.data.unpack
import dev.romirom.tivibro.data.zigzag
import java.io.File

/**
 * Reads every programme of the playlist's channels into a [ProgrammeIndex.Builder], straight from the guide's files
 * and on the caller's (background) thread. Not through [Guide.list]: that decodes one channel at a time into a 6 MB LRU
 * and reads every title with two positional reads, fine for a screenful of rows but minutes for the whole guide.
 * Here each day's schedule tables are read once, and each content layer's `.tti` title file is read whole, once.
 */
object GuideScan {
    /** epg.team's segmented guide: day by day, every channel of [b] the day's channels file knows. */
    fun segmented(guide: Guide, b: ProgrammeIndex.Builder, cancelled: () -> Boolean) {
        val days = guide.days.values.toList()
        val titles = HashMap<String, Titles>()
        val indexes = HashMap<String, Map<String, Int>>()
        val keys = b.channels.map { it.epgKeys() }
        for (gd in days) {
            if (cancelled()) throw CancellationException()
            // a day whose files went away (a sync re-bound it while this ran) is skipped: a new request follows anyway
            runCatching { day(guide.blobs, gd, b, titles, indexes, keys) }
                .onFailure { android.util.Log.w("tivibro", "search: day ${gd.day} skipped: $it") }
        }
    }

    private class Titles(val raw: ByteArray, val n: Int, val ids: IntArray) {
        fun id(rec: Int, b: ProgrammeIndex.Builder): Int {
            if (rec !in 0 until n) return -1
            ids[rec].let { if (it != NONE) return it }
            val a = raw.i32(8 + 4 * rec)
            val z = raw.i32(12 + 4 * rec)
            val base = 8 + 4 * (n + 1)
            val s = String(raw, base + a, z - a, Charsets.UTF_8)
            return (if (s.isBlank()) -1 else b.title(s)).also { ids[rec] = it }
        }
    }

    private const val NONE = -2

    private fun titlesOf(dir: File, sha: String, cache: HashMap<String, Titles>): Titles? {
        if (sha.isEmpty()) return null
        cache[sha]?.let { return it }
        val raw = File(dir, "$sha.tti").readBytes()
        require(String(raw, 0, 4, Charsets.US_ASCII) == "TTI1")
        val n = raw.i32(4)
        return Titles(raw, n, IntArray(n) { NONE }).also { cache[sha] = it }
    }

    private fun day(dir: File, gd: GuideDay, b: ProgrammeIndex.Builder, titles: HashMap<String, Titles>, indexes: HashMap<String, Map<String, Int>>, keys: List<List<String>>) {
        val index = indexes.getOrPut(gd.channels) { BlobFile(File(dir, "${gd.channels}.tpc")).use { Guide.channelIndex(it) } }
        BlobFile(File(dir, "${gd.schedule}.tps")).use { s ->
            val h = s.header
            check(h.magic == "TEPGSCH")
            val s1 = h.secs.getValue(1); val s2 = h.secs.getValue(2); val s3 = h.secs.getValue(3)
            val dirs = s.read(s2.off, (48 * h.secondary).toInt())
            val refs = s.read(s1.off, s1.size.toInt())
            for (c in b.channels.indices) {
                val idx = keys[c].firstNotNullOfOrNull { index[it] } ?: continue
                if (idx >= h.secondary) continue
                val e = 48 * idx
                if (dirs.i32(e) == 0) continue
                val raw = unpack(s.read(s3.off + dirs.i64(e + 8), dirs.i32(e + 16)), dirs.i32(e + 4), dirs.i32(e + 20), dirs.u32(e + 24))
                val cur = Cursor(raw, 4)
                var t = h.tmin
                repeat(raw.i32(0)) {
                    t += zigzag(cur.uvar())
                    val dur = cur.uvar()
                    val r = 8 * cur.uvar().toInt()
                    if (!b.wants(c, t + dur)) return@repeat
                    val tf = titlesOf(dir, gd.content.getOrNull(refs.u16(r)).orEmpty(), titles) ?: return@repeat
                    val id = tf.id(refs.i32(r + 4), b)
                    if (id >= 0) b.add(c, t, t + dur, id)
                }
            }
        }
    }

    /** An XMLTV import: the playlist's channels by their guide id. */
    fun xmltv(guide: XmltvGuide, b: ProgrammeIndex.Builder) {
        val byId = HashMap<String, MutableList<Int>>()
        for ((c, ch) in b.channels.withIndex()) byId.getOrPut(ch.epgId) { ArrayList(1) } += c
        guide.scan(byId.keys) { id, start, end, title ->
            byId[id]?.forEach { c -> b.add(c, start / 1000, end / 1000, title.replace(" ┊ ", " · ")) }
        }
    }
}
