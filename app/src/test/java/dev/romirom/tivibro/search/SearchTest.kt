package dev.romirom.tivibro.search

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.DAY
import dev.romirom.tivibro.data.HOUR
import dev.romirom.tivibro.data.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val t = 1_761_900_000_000L // «now» of every test
    private fun ch(id: String, num: Int, name: String, archive: Int = 7) = Channel(id, num, name, "UA", null, archive, "http://x/$id", false)

    private val oneplus = ch("ch1", 1, "UA: 1+1 Україна HD")
    private val ictv = ch("ch2", 2, "ICTV")
    private val eleven = ch("ch3", 11, "11 Канал")
    private val disc = ch("ch4", 40, "Discovery Channel")
    private val live = ch("ch5", 5, "Live Only", archive = 0)
    private val shallow = ch("ch6", 6, "Shallow", archive = 1)
    private val all = listOf(oneplus, ictv, eleven, disc, live, shallow)

    // ------------------------------------------------------------------ text

    @Test fun normFoldsCaseAccentsAndCyrillicVariants() {
        assertEquals("cafe creme", SearchText.norm("Café  Crème!"))
        assertEquals("иолки", SearchText.norm("Йолки"))
        assertEquals("елки 2", SearchText.norm("Ёлки-2"))
        assertEquals("іграшка", SearchText.norm("Їграшка"))
        assertEquals("мясо", SearchText.norm("М'ясо"))
        assertEquals("мясо", SearchText.norm("Мʼясо"))
        assertEquals("гудзик", SearchText.norm("Ґудзик"))
        assertEquals("1+1 украіна", SearchText.norm("1+1 Україна")) // ї folded to і
    }

    // ------------------------------------------------------------------ channels

    @Test fun channelByNameIgnoresPrefixAndQuality() {
        val r = searchChannels(all, "1+1")
        assertEquals(oneplus, r.first().ch)
        assertEquals(oneplus, searchChannels(all, "україна").first().ch)
        assertEquals(oneplus, searchChannels(all, "1+1 Україна").first().ch)
    }

    @Test fun channelByNumberComesFirst() {
        assertEquals(eleven, searchChannels(all, "11").first().ch)
        assertEquals(ictv, searchChannels(all, "2").first().ch)
    }

    @Test fun channelWordPrefixBeatsSubstring() {
        val r = searchChannels(all, "chan")
        assertEquals(listOf(disc), r.map { it.ch })
        assertTrue(searchChannels(all, "zzz").isEmpty())
        assertEquals(disc, searchChannels(all, "disc").first().ch)
        assertEquals(disc, searchChannels(all, "covery").first().ch) // inside a word: still found
    }

    // ------------------------------------------------------------------ programmes

    private fun sec(ms: Long) = ms / 1000

    private fun index(): ProgrammeIndex {
        val b = ProgrammeIndex.Builder(all, t)
        fun add(c: Channel, from: Long, len: Long, title: String) = b.add(all.indexOf(c), sec(t + from), sec(t + from + len), title)
        add(oneplus, -30 * MIN, HOUR, "Футбол. Ліга чемпіонів")                 // on now
        add(ictv, -2 * DAY, HOUR, "Футбол. Огляд туру")                       // archive, 2 days ago
        add(ictv, -1 * DAY, HOUR, "Футбол. Огляд туру")                       // archive, yesterday
        add(eleven, 3 * HOUR, HOUR, "Футбольна ніч")                          // upcoming
        add(disc, -10 * DAY, HOUR, "Футбол стародавній")                      // past the 7-day archive: dropped
        add(live, -3 * HOUR, HOUR, "Футбол без архіву")                       // ended, no archive: dropped
        add(live, 10 * MIN, HOUR, "Мініфутбол")                               // inside a word
        add(shallow, -10 * MIN, 20 * MIN, "Шерлок")
        add(shallow, -10 * MIN, 30 * MIN, "Шерлок")                           // same start: longest kept
        add(shallow, 20 * MIN, 30 * MIN, "Новини")
        return b.build()
    }

    @Test fun programmesRankNowThenRecentArchiveThenUpcoming() {
        val r = index().search("футбол", t)
        assertEquals(listOf(When.NOW, When.ARCHIVE, When.ARCHIVE, When.UPCOMING, When.UPCOMING), r.map { it.kind })
        assertEquals(oneplus, r[0].ch)
        assertTrue(r[1].p.start > r[2].p.start) // most recent archive first
        assertEquals("Футбольна ніч", r[3].p.title)
        assertEquals("Мініфутбол", r[4].p.title) // inner-word match last
    }

    @Test fun programmesOutsideTheArchiveAreLeftOut() {
        val titles = index().search("футбол", t).map { it.p.title }
        assertTrue("Футбол стародавній" !in titles)
        assertTrue("Футбол без архіву" !in titles)
    }

    @Test fun everyWordMustMatch() {
        val r = index().search("ліга футбол", t)
        assertEquals(listOf("Футбол. Ліга чемпіонів"), r.map { it.p.title })
        assertTrue(index().search("ліга огляд", t).isEmpty())
    }

    @Test fun hiddenChannelsAreFilteredAndLimitApplies() {
        val r = index().search("футбол", t, limit = 2) { it != oneplus }
        assertEquals(2, r.size)
        assertTrue(r.none { it.ch == oneplus })
    }

    @Test fun overlappingDaysKeepTheLongestCopy() {
        val idx = index()
        val r = idx.search("шерлок", t)
        assertEquals(1, r.size)
        assertEquals(30 * MIN, r[0].p.duration)
    }

    @Test fun whatsOnGivesCurrentAndNext() {
        val idx = index()
        val w = idx.whatsOn(shallow, t)
        assertEquals(listOf("Шерлок", "Новини"), w.map { it.p.title })
        assertEquals(listOf(When.NOW, When.UPCOMING), w.map { it.kind })
        assertTrue(idx.whatsOn(ch("zz", 99, "Unknown"), t).isEmpty())
        assertEquals("Футбол. Огляд туру", idx.find(ictv, (t - DAY) / 1000 * 1000)?.title)
        assertNull(idx.find(ictv, t))
    }

    @Test fun shortOrEmptyQueriesFindNothing() {
        assertTrue(index().search("", t).isEmpty())
        assertTrue(index().search("ф", t).isEmpty())
        assertTrue(index().search("!!", t).isEmpty())
        assertEquals(0, ProgrammeIndex.EMPTY.search("футбол", t).size)
    }

    @Test fun savedIndexLoadsBackTheSame() {
        val dir = java.nio.file.Files.createTempDirectory("idx").toFile()
        try {
            val f = java.io.File(dir, "index.bin")
            index().save(f, java.io.File(dir, "titles-1.bin"), "k1")
            assertEquals("k1", ProgrammeIndex.keyOf(f))
            assertNull(ProgrammeIndex.load(f, "other", all))
            assertNull(ProgrammeIndex.load(f, "k1", all.drop(1)))
            val back = ProgrammeIndex.load(f, "k1", all)!!
            assertEquals(index().search("футбол", t), back.search("футбол", t))
            assertEquals(index().whatsOn(shallow, t), back.whatsOn(shallow, t))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun encodedSearchHandlesOtherScripts() {
        val kz = ch("ch9", 9, "Qazaqstan")
        val b = ProgrammeIndex.Builder(listOf(kz), t)
        b.add(0, sec(t), sec(t + HOUR), "Қазақстан жаңалықтары")
        b.add(0, sec(t + HOUR), sec(t + 2 * HOUR), "Ελληνικά νέα")
        b.add(0, sec(t + 2 * HOUR), sec(t + 3 * HOUR), "Straße 日本")
        val idx = b.build()
        assertEquals("Қазақстан жаңалықтары", idx.search("жаңа", t).single().p.title)
        assertEquals("Ελληνικά νέα", idx.search("νέα", t).single().p.title)
        assertEquals("Straße 日本", idx.search("strasse", t).single().p.title)
    }
}
