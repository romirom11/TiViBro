package dev.romirom.tivibro.remote

import dev.romirom.tivibro.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class BrowseTest {
    private fun ch(id: String, num: Int, group: String) = Channel(id, num, "Ch $num", group, null, 3, "http://x/$id", false)

    private val news1 = ch("a", 1, "Новини")
    private val film1 = ch("b", 2, "Кіно")
    private val news2 = ch("c", 3, "Новини")
    private val film2 = ch("d", 4, "Кіно")
    private val adult = ch("e", 5, "Adult")
    private val all = listOf(news1, film1, news2, film2, adult)
    // «Adult» switched off, film2 hidden; the favourites in the user's order, one of them hidden
    private val st = Remote.State("pl", all, setOf(news1, film1, news2).map { it.id }.toSet(), listOf("c", "d", "a"))

    @Test fun favouritesFirstThenGroupsInPlaylistOrderWithoutHidden() {
        val g = Browse.groups(st, "Обрані")
        assertEquals(listOf(Browse.FAV, "Новини", "Кіно"), g.map { it.id })
        assertEquals(listOf(news2, news1), g[0].channels) // the user's order, the hidden favourite left out
        assertEquals(listOf(news1, news2), g[1].channels)
        assertEquals(listOf(film1), g[2].channels)
    }

    @Test fun noFavouritesNoFavouritesGroup() {
        val s = Remote.State("pl", all, all.map { it.id }.toSet(), emptyList())
        assertEquals(listOf("Новини", "Кіно", "Adult"), Browse.groups(s, "Обрані").map { it.id })
    }

    @Test fun aGroupIsFoundByIdOrLooselyByName() {
        assertEquals(listOf(film1), Browse.channels(st, "Кіно"))
        assertEquals(listOf(film1), Browse.channels(st, "кіно"))
        assertEquals(listOf(news1, news2), Browse.channels(st, "нов"))
        assertEquals(listOf(news2, news1), Browse.channels(st, Browse.FAV))
        assertNull(Browse.channels(st, "Adult")) // switched off: as if it were not there
        assertNull(Browse.channels(st, ""))
    }

    @Test fun daysReachBackAsFarAsTheArchiveAndOnToTomorrow() {
        val kyiv = TimeZone.getTimeZone("Europe/Kyiv")
        val t = 1_761_900_000_000L // 2025-10-31 10:40 in Kyiv
        val d = Browse.days(3, t, kyiv)
        assertEquals(listOf(-3, -2, -1, 0, 1), d.map { it.first })
        assertEquals(1_761_861_600_000L, d[3].second) // today's midnight, EET (+2)
        // 2025-10-26 is the switch from summer time: that day is 25 hours long, and midnights still line up
        val w = Browse.days(6, t, kyiv)
        assertEquals(-5, w[1].first)
        assertEquals(25 * 3_600_000L, w[2].second - w[1].second)
        assertEquals(listOf(0, 1), Browse.days(0, t, kyiv).map { it.first })
        assertEquals(-Browse.MAX_BACK, Browse.days(30, t, kyiv).first().first)
    }
}
