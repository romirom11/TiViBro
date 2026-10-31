package dev.romirom.tivibro.home

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.data.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    private val t = 1_761_900_000_000L
    private fun ch(id: String) = Channel(id, 1, "Channel $id", "UK", null, 7, "http://x/$id", false)
    private fun p(fromMin: Int, lenMin: Int, title: String) = Programme(t + fromMin * MIN, t + (fromMin + lenMin) * MIN, title, "")

    // ------------------------------------------------------------------ filler

    @Test fun fillerIsRecognised() {
        for (s in listOf("Реклама", "РЕКЛАМА", "Телемагазин «Мир покупок»", "Teleshopping", "Paid Programming", "Перерыв в вещании",
            "Профілактика", "Test card F", "Настроечная таблица", "Нет программы", "No information", "Анонс", "Werbung", "", " - ")) {
            assertTrue(s, Filler.isFiller(s))
        }
    }

    @Test fun realProgrammesAreNotFiller() {
        for (s in listOf("Новини", "Тест-драйв", "The Test", "Реклама и жизнь: документальный фильм", "Shopping Mall Heist",
            "Friends", "Перерва на каву", "Анонимный звонок")) {
            assertFalse(s, Filler.isFiller(s))
        }
    }

    @Test fun titlesNormalize() {
        assertEquals("новости", normTitle("Новости (16+)"))
        assertEquals("елки 2", normTitle("Ёлки-2"))
        assertEquals("the office", normTitle("  The   Office [HD] "))
    }

    // ------------------------------------------------------------------ rows

    @Test fun nowSkipsFillerAndKeepsSourceOrder() {
        val slots = listOf(
            Slot(ch("a"), listOf(p(-10, 30, "News"))),
            Slot(ch("b"), listOf(p(-5, 20, "Реклама"))),
            Slot(ch("c"), listOf(p(5, 30, "Later"))),
            Slot(ch("d"), listOf(p(-50, 120, "Film"))),
        )
        assertEquals(listOf("News", "Film"), HomePicks.now(slots, t, 10).map { it.p.title })
    }

    @Test fun startedWantsArchiveLengthAndRecentStart() {
        val slots = listOf(
            Slot(ch("a"), listOf(p(-5, 60, "Fresh"))),
            Slot(ch("b"), listOf(p(-30, 60, "Too old"))),
            Slot(ch("c"), listOf(p(-3, 10, "Too short"))),
            Slot(ch("d"), listOf(p(-12, 45, "No archive"))),
            Slot(ch("e"), listOf(p(-8, 90, "Fresh"))), // the same title elsewhere: once
            Slot(ch("f"), listOf(p(0, 60, "Just now"))), // under a minute: not yet
            Slot(ch("g"), listOf(p(-15, 50, "Earlier"))),
        )
        val got = HomePicks.started(slots, t, { it.id != "d" }, 10)
        assertEquals(listOf("Fresh", "Earlier"), got.map { it.p.title })
        assertEquals("a", got[0].ch.id)
    }

    @Test fun soonIsWithinHalfAnHourSoonestFirst() {
        val slots = listOf(
            Slot(ch("a"), listOf(p(-20, 40, "Now A"), p(20, 30, "Soon A"))),
            Slot(ch("b"), listOf(p(-10, 15, "Now B"), p(5, 30, "Soon B"))),
            Slot(ch("c"), listOf(p(-10, 60, "Now C"), p(50, 30, "Too late"))),
            Slot(ch("d"), listOf(p(-10, 12, "Now D"), p(2, 30, "Реклама"))),
        )
        assertEquals(listOf("Soon B", "Soon A"), HomePicks.soon(slots, t, 10).map { it.p.title })
    }

    @Test fun slotFindsTheProgrammeOnAir() {
        val s = Slot(ch("a"), listOf(p(-60, 30, "A"), p(-30, 30, "B"), p(0, 30, "C"), p(40, 30, "D")))
        assertEquals("C", s.at(t)?.title)
        assertEquals("B", s.at(t - MIN)?.title)
        assertEquals(null, s.at(t + 35 * MIN))
    }

    // ------------------------------------------------------------------ merging while Home is open

    private fun card(kind: HomeKind, id: String, title: String = id) = HomeCard("$kind:$id", kind, ch(id), p(0, 30, title))

    @Test fun mergeKeepsPlacesAndAppendsNewCards() {
        val old = listOf(HomeRow(HomeKind.NOW, listOf(card(HomeKind.NOW, "a"), card(HomeKind.NOW, "b"))))
        val new = listOf(
            HomeRow(HomeKind.SOON, listOf(card(HomeKind.SOON, "x"))),
            HomeRow(HomeKind.NOW, listOf(card(HomeKind.NOW, "c"), card(HomeKind.NOW, "b", "B updated"), card(HomeKind.NOW, "a"))),
        )
        val m = HomeRows.merge(old, new, keep = null)
        assertEquals(listOf(HomeKind.NOW, HomeKind.SOON), m.map { it.kind })
        assertEquals(listOf("a", "b", "c"), m[0].cards.map { it.ch.id })
        assertEquals("B updated", m[0].cards[1].p.title)
    }

    @Test fun mergeKeepsTheFocusedCardThatWentAway() {
        val old = listOf(HomeRow(HomeKind.STARTED, listOf(card(HomeKind.STARTED, "a"), card(HomeKind.STARTED, "b"))))
        val new = listOf(HomeRow(HomeKind.STARTED, listOf(card(HomeKind.STARTED, "a"))))
        assertEquals(listOf("a", "b"), HomeRows.merge(old, new, keep = "STARTED:b")[0].cards.map { it.ch.id })
        assertEquals(listOf("a"), HomeRows.merge(old, new, keep = null)[0].cards.map { it.ch.id })
        // a whole row gone: only the focused card stays, until focus leaves it
        assertEquals(listOf("b"), HomeRows.merge(old, emptyList(), keep = "STARTED:b").single().cards.map { it.ch.id })
        assertTrue(HomeRows.merge(old, emptyList(), keep = null).isEmpty())
    }

    @Test fun mergeUpdatesNowWatchingInPlaceOnTop() {
        fun w(id: String, title: String) = HomeCard(HomeRows.WATCHING_KEY, HomeKind.WATCHING, ch(id), p(0, 30, title))
        val old = listOf(HomeRow(HomeKind.WATCHING, listOf(w("a", "A"))), HomeRow(HomeKind.NOW, listOf(card(HomeKind.NOW, "x"))))
        // the next programme, focus on the card: still one card, the new one, and the row stays first
        val new = listOf(HomeRow(HomeKind.NOW, listOf(card(HomeKind.NOW, "x"))), HomeRow(HomeKind.WATCHING, listOf(w("a", "B"))))
        val m = HomeRows.merge(old, new, keep = HomeRows.WATCHING_KEY)
        assertEquals(listOf(HomeKind.WATCHING, HomeKind.NOW), m.map { it.kind })
        assertEquals(listOf("B"), m[0].cards.map { it.p.title })
    }
}
