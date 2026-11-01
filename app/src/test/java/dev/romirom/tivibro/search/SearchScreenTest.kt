package dev.romirom.tivibro.search

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.HOUR
import dev.romirom.tivibro.data.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScreenTest {
    private val t = 1_761_900_000_000L
    private fun ch(id: String, num: Int, name: String) = Channel(id, num, name, "UA", null, 7, "http://x/$id", false)
    private val sport = ch("a", 1, "Футбол 1")
    private val news = ch("b", 2, "Новини")
    private val hidden = ch("c", 3, "Футбол 2")
    private val all = listOf(sport, news, hidden)

    private fun index() = ProgrammeIndex.Builder(all, t).apply {
        add(0, (t - 10 * MIN) / 1000, (t + HOUR) / 1000, "Футбол. Live")
        add(1, (t - 2 * HOUR) / 1000, (t - HOUR) / 1000, "Футбол: огляд")
        add(2, (t - 10 * MIN) / 1000, (t + HOUR) / 1000, "Футбол прихований")
    }.build()

    @Test fun blankQueryShowsTheRecentOnes() {
        val r = SearchScreen.run("  ", all, setOf("a", "b"), index(), listOf("футбол", "новини"), t)
        assertEquals(listOf(SearchItem.Recent("футбол"), SearchItem.Recent("новини")), r.items)
    }

    @Test fun channelsThenProgrammesWithoutHiddenOnes() {
        val visible = listOf(sport, news)
        val r = SearchScreen.run("футбол", visible, setOf("a", "b"), index(), listOf("x"), t)
        assertTrue(r.recent.isEmpty())
        assertEquals(listOf(sport), r.channels.map { it.ch })
        assertEquals("Футбол. Live", r.channels[0].now?.title)
        assertEquals(listOf("Футбол. Live", "Футбол: огляд"), r.programmes.map { it.hit.p.title })
        assertEquals(r.channels + r.programmes, r.items)
        assertNull(SearchScreen.run("новини", visible, setOf("a", "b"), index(), emptyList(), t).channels[0].now)
    }

    @Test fun rememberPutsTheQueryFirstOnce() {
        assertEquals(listOf("Шерлок", "футбол"), SearchScreen.remember(listOf("футбол", "шерлок"), "  Шерлок "))
        assertEquals(listOf("a"), SearchScreen.remember(listOf("a"), "   "))
        val many = (1..20).map { "q$it" }
        assertEquals(SearchScreen.RECENT, SearchScreen.remember(many, "new").size)
    }
}
