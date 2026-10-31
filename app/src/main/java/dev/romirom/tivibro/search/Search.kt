package dev.romirom.tivibro.search

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.DAY
import dev.romirom.tivibro.data.Programme
import dev.romirom.tivibro.data.epgName
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.text.Normalizer

/**
 * What text is searched by: lower case, accents dropped (Café → cafe), the Cyrillic letters people type either way
 * folded together (й → и, ё → е, ї → і, ґ → г), apostrophes dropped (м'ясо → мясо), anything else that is not a letter,
 * digit or «+» a space. «+» stays a letter: «1+1» and «2+2» are channel names, not two words.
 * Queries and titles go through the same function, so the folds only ever widen a match.
 */
object SearchText {
    fun norm(s: String): String {
        // NFD only when something composed is there: most titles are plain Cyrillic or ASCII, and this runs per title
        val src = if (s.any { it.code in 0xC0..0x36F || it.code in 0x1E00..0x1EFF }) Normalizer.normalize(s, Normalizer.Form.NFD) else s
        val b = StringBuilder(src.length)
        var space = true
        for (c0 in src) {
            val c = when (val l = c0.lowercaseChar()) {
                'й' -> 'и'; 'ё' -> 'е'; 'ї' -> 'і'; 'ґ' -> 'г'; 'ł' -> 'l'; 'ø' -> 'o'; 'đ' -> 'd'
                else -> l
            }
            when {
                c.code in 0x300..0x36F -> Unit // combining accents left by NFD
                c == '\'' || c == '’' || c == 'ʼ' || c == '`' || c == '´' -> Unit
                c == 'ß' -> { b.append("ss"); space = false }
                c.isLetterOrDigit() || c == '+' -> { b.append(c); space = false }
                !space -> { b.append(' '); space = true }
            }
        }
        if (b.isNotEmpty() && b[b.length - 1] == ' ') b.setLength(b.length - 1)
        return b.toString()
    }

    fun tokens(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() }

    /**
     * A normalized string as one byte per character, for the programme index: ASCII as is, Cyrillic (а…џ and the
     * Kazakh letters) and Greek folded into 0x80…0xD8, anything else 0xFF (an «other letter» that only matches another
     * such). Half the memory of a UTF-16 String for the ~250 000 titles a full guide has, and ' ' / '\n' stay themselves.
     */
    fun encode(s: String): ByteArray = ByteArray(s.length) { i -> code(s[i]) }

    private val KAZAKH = charArrayOf('ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ')

    private fun code(c: Char): Byte = when (c.code) {
        in 0..0x7F -> c.code
        in 0x430..0x44F -> 0x80 + (c.code - 0x430)
        in 0x450..0x45F -> 0xA0 + (c.code - 0x450)
        in 0x3B1..0x3C9 -> 0xC0 + (c.code - 0x3B1)
        else -> KAZAKH.indexOf(c).let { if (it >= 0) 0xB0 + it else 0xFF }
    }.toByte()

    /** Where [p] occurs in [b] between [from] and [to] (exclusive), or -1. */
    fun indexOf(b: ByteArray, p: ByteArray, from: Int, to: Int = b.size): Int {
        if (p.isEmpty()) return from
        val first = p[0]
        val last = to - p.size
        var i = from
        outer@ while (i <= last) {
            if (b[i] != first) { i++; continue }
            for (k in 1 until p.size) if (b[i + k] != p[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** [p] starts a word in [b] between [from] and [to]: at [from] or after a space. */
    fun atWordStart(b: ByteArray, p: ByteArray, from: Int, to: Int): Boolean {
        var i = indexOf(b, p, from, to)
        while (i >= 0) {
            if (i == from || b[i - 1] == SPACE) return true
            i = indexOf(b, p, i + 1, to)
        }
        return false
    }

    private const val SPACE = ' '.code.toByte()

    /** [tok] starts a word of the normalized [s] (not just somewhere inside one). */
    fun atWordStart(s: String, tok: String): Boolean {
        var i = s.indexOf(tok)
        while (i >= 0) {
            if (i == 0 || s[i - 1] == ' ') return true
            i = s.indexOf(tok, i + 1)
        }
        return false
    }
}

/** Where a programme stands relative to now: what the remote can do with it. */
enum class When { NOW, ARCHIVE, UPCOMING }

data class ChannelHit(val ch: Channel, val rank: Int)

data class ProgrammeHit(val ch: Channel, val p: Programme, val kind: When)

/**
 * Channels by name or number. A number matches the channel's number exactly; a name is compared without the
 * provider's «UA:» prefix and «HD» suffix ([epgName]). Best first: the number, the whole name, the start of the name,
 * the query as a phrase at a word start, every word at a word start, every word somewhere; then by number.
 */
fun searchChannels(channels: List<Channel>, query: String, limit: Int = 20): List<ChannelHit> {
    val q = SearchText.norm(query)
    if (q.isEmpty()) return emptyList()
    val toks = q.split(' ')
    val num = query.trim().toIntOrNull()
    val out = ArrayList<ChannelHit>()
    for (ch in channels) {
        val rank = if (num != null && ch.num == num) 0 else {
            val n = SearchText.norm(epgName(ch.name))
            when {
                n == q -> 1
                n.startsWith(q) -> 2
                SearchText.atWordStart(n, q) -> 3
                toks.all { SearchText.atWordStart(n, it) } -> 4
                toks.all { it in n } -> 5
                else -> continue
            }
        }
        out += ChannelHit(ch, rank)
    }
    out.sortWith(compareBy<ChannelHit> { it.rank }.thenBy { it.ch.num })
    return if (out.size > limit) out.subList(0, limit).toList() else out
}

/** Titles by number: in memory right after a build, read from the titles file once [ProgrammeIndex.load]ed. */
fun interface TitleSource {
    fun title(i: Int): String
}

/**
 * The guide's programmes of the playlist's channels, kept for search: the archive each channel actually has, what is
 * on, and what is coming; nothing older. Built off the main thread by [Builder], immutable after.
 *
 * Compact on purpose: a full epg.team guide is ~800 000 programmes and ~250 000 distinct titles, on a TV with a 192 MB
 * heap. Each title is stored once; the normalized titles sit in one byte array ([norm], one byte per character, see
 * [SearchText.encode]) that a query scans; the display titles stay on disk (the titles file) and are read only for the
 * rows a query returns. Per programme: start (epoch seconds), duration (seconds, 16 bits) and title number, sorted by
 * channel and start ([chAt] gives each channel's range), plus a title → programmes table ([titleAt], [byTitle]).
 * About 20 MB for a full guide, against ~55 MB for the same as Strings and per-field arrays.
 */
class ProgrammeIndex private constructor(
    val channels: List<Channel>,
    val titleCount: Int,
    private val titles: TitleSource,
    /** '\n', then every normalized title followed by '\n': a hit never runs into the next title. */
    private val norm: ByteArray,
    /** Where title i starts in [norm]; [titleCount] + 1 entries, the last one [norm]'s size. */
    private val normAt: IntArray,
    /** Programmes of channel c: [chAt] (c) until [chAt] (c + 1). */
    private val chAt: IntArray,
    private val pStart: IntArray,
    private val pDur: CharArray,
    private val pTitle: IntArray,
    /** Programmes of title i: [byTitle] from [titleAt] (i) until [titleAt] (i + 1). */
    private val titleAt: IntArray,
    private val byTitle: IntArray,
) {
    val size get() = pStart.size
    private val chIdx: Map<String, Int> = HashMap<String, Int>(channels.size * 2).also { m -> channels.forEachIndexed { i, c -> m[c.id] = i } }

    private fun end(i: Int) = pStart[i] + pDur[i].code
    private fun prog(i: Int) = Programme(pStart[i] * 1000L, end(i) * 1000L, titles.title(pTitle[i]), "")

    /** The channel of programme [i]: the last channel whose range starts at or before it. */
    private fun chOf(i: Int): Int {
        var lo = 0
        var hi = channels.size - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (chAt[m] <= i) lo = m else hi = m - 1 }
        return lo
    }

    /**
     * Programmes whose title has every word of [query] (at a word start, or anywhere in a word), on channels that pass
     * [visible]. Order: matches at word starts before ones inside words; then what is on now, the archive (most recent
     * first), what is coming (soonest first). Past programmes outside their channel's archive are left out.
     */
    fun search(query: String, t: Long, limit: Int = 30, visible: (Channel) -> Boolean = { true }): List<ProgrammeHit> {
        val toks = SearchText.tokens(query)
        if (toks.isEmpty() || toks.sumOf { it.length } < 2 || titleCount == 0) return emptyList()
        val enc = toks.map(SearchText::encode)
        val anchor = enc.maxBy { it.size }
        val keys = ArrayList<Long>()
        val ts = (t / 1000).toInt()
        var from = 0
        while (true) {
            val at = SearchText.indexOf(norm, anchor, from)
            if (at < 0) break
            val ti = titleOf(at)
            val a = normAt[ti]
            val z = normAt[ti + 1] - 1
            from = z // on to the next title: one hit per title is enough
            if (enc.any { SearchText.indexOf(norm, it, a, z) < 0 }) continue
            val inner = if (enc.all { SearchText.atWordStart(norm, it, a, z) }) 0L else 1L
            for (k in titleAt[ti] until titleAt[ti + 1]) {
                val i = byTitle[k]
                val ch = channels[chOf(i)]
                val kind = when {
                    pStart[i] > ts -> When.UPCOMING
                    end(i) > ts -> When.NOW
                    ch.archiveDays > 0 && pStart[i] * 1000L >= t - ch.archiveDays * DAY -> When.ARCHIVE
                    else -> continue
                }
                if (!visible(ch)) continue
                // inner-word matches last; within each, NOW < ARCHIVE (newest first) < UPCOMING (soonest first)
                val order = when (kind) {
                    When.NOW -> 0L
                    When.ARCHIVE -> (1L shl 32) + (Int.MAX_VALUE - pStart[i])
                    When.UPCOMING -> (2L shl 32) + pStart[i]
                }
                keys += (inner shl 62) or (order shl 23) or i.toLong()
            }
        }
        keys.sort()
        val out = ArrayList<ProgrammeHit>(minOf(limit, keys.size))
        for (k in keys) {
            if (out.size >= limit) break
            val i = (k and 0x7FFFFF).toInt()
            val kind = when (((k ushr 23) and 0x7FFFFFFFFFL) ushr 32) { 0L -> When.NOW; 1L -> When.ARCHIVE; else -> When.UPCOMING }
            out += ProgrammeHit(channels[chOf(i)], prog(i), kind)
        }
        return out
    }

    /** The programme on [ch] at [t] (if the guide has one) and the [next] after it. */
    fun whatsOn(ch: Channel, t: Long, next: Int = 2): List<ProgrammeHit> {
        val c = chIdx[ch.id] ?: return emptyList()
        val ts = (t / 1000).toInt()
        var lo = chAt[c]
        var hi = chAt[c + 1]
        while (lo < hi) { val m = (lo + hi) ushr 1; if (end(m) <= ts) lo = m + 1 else hi = m } // first not ended
        val out = ArrayList<ProgrammeHit>()
        var i = lo
        while (i < chAt[c + 1] && out.size < next + 1) {
            out += ProgrammeHit(ch, prog(i), if (pStart[i] <= ts) When.NOW else When.UPCOMING)
            i++
        }
        return out
    }

    /** The guide's programme of [ch] that starts at [start] (ms), if the index has it. */
    fun find(ch: Channel, start: Long): Programme? {
        val c = chIdx[ch.id] ?: return null
        val s = (start / 1000).toInt()
        for (i in chAt[c] until chAt[c + 1]) if (pStart[i] == s) return prog(i)
        return null
    }

    private fun titleOf(pos: Int): Int {
        var lo = 0
        var hi = titleCount - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (normAt[m] <= pos) lo = m else hi = m - 1 }
        return lo
    }

    // ------------------------------------------------------------------ on disk

    /**
     * Writes the index to [file] under [key] and the display titles to [titlesFile] (UTF-8, back to back), which
     * [load] then reads row by row. [titles] must still be in memory (a fresh [Builder.build]).
     */
    fun save(file: File, titlesFile: File, key: String) {
        val offs = IntArray(titleCount + 1)
        titlesFile.outputStream().buffered(1 shl 16).use { o ->
            var n = 0
            for (i in 0 until titleCount) { offs[i] = n; val b = titles.title(i).toByteArray(); o.write(b); n += b.size }
            offs[titleCount] = n
        }
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeUTF(key)
            o.writeUTF(titlesFile.name)
            o.writeInt(channels.size)
            for (c in channels) o.writeUTF(c.id)
            o.writeInt(titleCount)
            o.writeInt(norm.size); o.write(norm)
            for (a in listOf(normAt, chAt, pStart, pTitle, titleAt, byTitle, offs)) writeInts(o, a)
            val cb = ByteBuffer.allocate(2 * pDur.size); cb.asCharBuffer().put(pDur)
            o.writeInt(pDur.size); o.write(cb.array())
        }
    }

    /**
     * Collects programmes in any order, from any number of guide days. [t] is the build time: a programme that already
     * ended outside its channel's archive is dropped at once, so channels without an archive keep only now and later.
     * Duplicates (guide days overlap at midnight) are folded in [build], keeping the longest copy.
     */
    class Builder(val channels: List<Channel>, private val t: Long) {
        private val ids = HashMap<String, Int>()
        private val titles = ArrayList<String>()
        private var n = 0
        private var ch = IntArray(1 shl 14)
        private var st = IntArray(1 shl 14)
        private var en = IntArray(1 shl 14)
        private var ti = IntArray(1 shl 14)
        private val keepFrom = LongArray(channels.size) { c ->
            val a = channels[c].archiveDays
            if (a > 0) (t - a * DAY) / 1000 else t / 1000
        }

        /** The title's number, for [add]: equal titles share one. */
        fun title(s: String): Int = ids.getOrPut(s) { titles += s; titles.size - 1 }

        /** Whether anything of channel [c] ending at [endSec] would be kept: lets a reader skip decoding titles. */
        fun wants(c: Int, endSec: Long) = endSec > keepFrom[c]

        fun add(c: Int, startSec: Long, endSec: Long, title: Int) {
            // ended before the archive reaches (or, without one, before now): never playable, never shown
            if (endSec <= keepFrom[c] || endSec <= startSec) return
            if (n == ch.size) { val m = n * 2; ch = ch.copyOf(m); st = st.copyOf(m); en = en.copyOf(m); ti = ti.copyOf(m) }
            ch[n] = c; st[n] = startSec.toInt(); en[n] = endSec.toInt(); ti[n] = title
            n++
        }

        fun add(c: Int, startSec: Long, endSec: Long, title: String) {
            if (title.isBlank() || !wants(c, endSec)) return
            add(c, startSec, endSec, title(title))
        }

        fun build(): ProgrammeIndex {
            // sorted by channel and start through packed longs (channel 19 bits, start from the earliest 22 bits, entry
            // 22 bits): no boxing of ~800 000 entries on a weak TV. Then one pass keeps the longest of every (channel, start).
            var minSt = Int.MAX_VALUE
            for (i in 0 until n) if (st[i] < minSt) minSt = st[i]
            val packed = LongArray(n) { i -> (ch[i].toLong() shl 44) or ((st[i] - minSt).toLong().coerceAtMost(0x3FFFFF) shl 22) or i.toLong() }
            packed.sort()
            val keep = IntArray(n)
            var m = 0
            for (k in packed) {
                val i = (k and 0x3FFFFF).toInt()
                if (m > 0 && ch[keep[m - 1]] == ch[i] && st[keep[m - 1]] == st[i]) { if (en[i] > en[keep[m - 1]]) keep[m - 1] = i; continue }
                keep[m++] = i
            }
            val pSt = IntArray(m); val pDur = CharArray(m); val pTi = IntArray(m)
            val chAt = IntArray(channels.size + 1)
            val used = BooleanArray(titles.size)
            for (k in 0 until m) {
                val i = keep[k]
                pSt[k] = st[i]; pDur[k] = (en[i] - st[i]).coerceAtMost(0xFFFF).toChar(); pTi[k] = ti[i]; used[ti[i]] = true
                chAt[ch[i] + 1]++
            }
            for (c in 0 until channels.size) chAt[c + 1] += chAt[c]
            // only titles something still points at, renumbered densely
            val remap = IntArray(titles.size) { -1 }
            val outTitles = ArrayList<String>()
            for (i in titles.indices) if (used[i]) { remap[i] = outTitles.size; outTitles += titles[i] }
            for (k in 0 until m) pTi[k] = remap[pTi[k]]
            val nb = ByteArrayOutputStream(outTitles.size * 24).apply { write('\n'.code) }
            val normAt = IntArray(outTitles.size + 1)
            for ((i, s) in outTitles.withIndex()) { normAt[i] = nb.size(); nb.write(SearchText.encode(SearchText.norm(s))); nb.write('\n'.code) }
            normAt[outTitles.size] = nb.size()
            val titleAt = IntArray(outTitles.size + 1)
            for (k in 0 until m) titleAt[pTi[k] + 1]++
            for (i in outTitles.indices) titleAt[i + 1] += titleAt[i]
            val fill = titleAt.copyOf()
            val byTitle = IntArray(m)
            for (k in 0 until m) byTitle[fill[pTi[k]]++] = k
            return ProgrammeIndex(channels, outTitles.size, { outTitles[it] }, nb.toByteArray(), normAt, chAt, pSt, pDur, pTi, titleAt, byTitle)
        }
    }

    companion object {
        private const val MAGIC = 0x54564233 // "TVB3"
        val EMPTY = Builder(emptyList(), 0).build()

        /** The key [file] was saved under, without reading the rest; null if it is not an index file. */
        fun keyOf(file: File): String? = runCatching {
            DataInputStream(file.inputStream().buffered(4096)).use { if (it.readInt() == MAGIC) it.readUTF() else null }
        }.getOrNull()

        /**
         * The index saved in [file] under [key], for [channels] (the same ids in the same order, or null). Its display
         * titles are read from the titles file next to it, which stays open for the index's lifetime.
         */
        fun load(file: File, key: String, channels: List<Channel>): ProgrammeIndex? {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { r ->
                if (r.readInt() != MAGIC || r.readUTF() != key) return null
                val titlesFile = File(file.parentFile, r.readUTF())
                if (r.readInt() != channels.size) return null
                for (c in channels) if (r.readUTF() != c.id) return null
                val titleCount = r.readInt()
                val norm = ByteArray(r.readInt()).also { r.readFully(it) }
                val normAt = readInts(r); val chAt = readInts(r); val pStart = readInts(r); val pTitle = readInts(r)
                val titleAt = readInts(r); val byTitle = readInts(r); val offs = readInts(r)
                val pDur = CharArray(r.readInt())
                ByteArray(2 * pDur.size).also { r.readFully(it); ByteBuffer.wrap(it).asCharBuffer().get(pDur) }
                val raf = RandomAccessFile(titlesFile, "r")
                val fc = raf.channel
                val src = TitleSource { i ->
                    runCatching {
                        val b = ByteBuffer.allocate(offs[i + 1] - offs[i])
                        var p = offs[i].toLong()
                        while (b.hasRemaining()) { val k = fc.read(b, p); if (k < 0) break; p += k }
                        String(b.array(), 0, b.position(), Charsets.UTF_8)
                    }.getOrDefault("")
                }
                return ProgrammeIndex(channels, titleCount, src, norm, normAt, chAt, pStart, pDur, pTitle, titleAt, byTitle)
            }
        }

        private fun writeInts(o: DataOutputStream, a: IntArray) {
            val b = ByteBuffer.allocate(4 * a.size)
            b.asIntBuffer().put(a)
            o.writeInt(a.size)
            o.write(b.array())
        }

        private fun readInts(r: DataInputStream): IntArray {
            val a = IntArray(r.readInt())
            val b = ByteArray(4 * a.size)
            r.readFully(b)
            ByteBuffer.wrap(b).asIntBuffer().get(a)
            return a
        }
    }
}
