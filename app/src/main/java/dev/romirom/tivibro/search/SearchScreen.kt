package dev.romirom.tivibro.search

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.Programme

/** A row of the TV's search screen. The screen moves through one flat list: recent queries, channels, programmes. */
sealed interface SearchItem {
    data class Recent(val query: String) : SearchItem
    /** [now]: what is on, when the guide has it. */
    data class Ch(val ch: Channel, val now: Programme?) : SearchItem
    data class Prog(val hit: ProgrammeHit) : SearchItem
}

/** What the screen shows for [query]: the recent queries while it is blank, else channels and then programmes. */
class SearchResults(val query: String, val recent: List<SearchItem.Recent>, val channels: List<SearchItem.Ch>, val programmes: List<SearchItem.Prog>) {
    val items: List<SearchItem> = recent + channels + programmes

    companion object {
        val EMPTY = SearchResults("", emptyList(), emptyList(), emptyList())
    }
}

/**
 * The TV search screen's logic, apart from the screen: the same search as the phone page (channels by name or number,
 * programmes of the guide index, hidden ones left out), sized for a TV list.
 */
object SearchScreen {
    const val CHANNELS = 12
    const val PROGRAMMES = 40
    const val RECENT = 8

    /** Off the main thread: a programme query scans the whole index (tens of ms on a slow TV). */
    fun run(query: String, visible: List<Channel>, shown: Set<String>, index: ProgrammeIndex, recent: List<String>, t: Long): SearchResults {
        if (query.isBlank()) return SearchResults(query, recent.take(RECENT).map(SearchItem::Recent), emptyList(), emptyList())
        val chs = searchChannels(visible, query, CHANNELS).map { h ->
            SearchItem.Ch(h.ch, index.whatsOn(h.ch, t, 0).firstOrNull()?.takeIf { it.kind == When.NOW }?.p)
        }
        val progs = index.search(query, t, PROGRAMMES) { it.id in shown }.map(SearchItem::Prog)
        return SearchResults(query, emptyList(), chs, progs)
    }

    /** [recent] with [query] in front, once (ignoring case and spacing), at most [RECENT]. */
    fun remember(recent: List<String>, query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return recent
        return (listOf(q) + recent.filter { !it.equals(q, ignoreCase = true) }).take(RECENT)
    }
}
