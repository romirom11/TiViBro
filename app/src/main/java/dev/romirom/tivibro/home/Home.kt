package dev.romirom.tivibro.home

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.Mark
import dev.romirom.tivibro.data.Programme

/** The rows of the Home page, top to bottom. [SETUP] is the «Налаштувати джерела» button shown without sources. */
enum class HomeKind { CONTINUE, NOW, STARTED, SOON, FILMS, SERIES, SETUP }

/**
 * One card on Home. [key] names "the same thing" across recomputations (a bookmark, a channel's live slot, one
 * programme), so that focus follows the card rather than its position while rows fill in.
 */
data class HomeCard(val key: String, val kind: HomeKind, val ch: Channel, val p: Programme, val mark: Mark? = null)

data class HomeRow(val kind: HomeKind, val cards: List<HomeCard>)

object HomeRows {
    fun continueKey(ch: Channel, p: Programme) = "c:${ch.id}:${p.start}"
    fun nowKey(ch: Channel) = "n:${ch.id}"
    fun progKey(kind: HomeKind, ch: Channel, p: Programme) = "${kind.ordinal}:${ch.id}:${p.start}"

    /**
     * Folds freshly built rows into the ones on screen without reshuffling them: rows keep the [HomeKind] order, cards
     * already shown keep their places (updated in place), new cards go at the end of their row, and cards that went
     * away disappear, except [keep] (the focused card), which stays until focus leaves it.
     */
    fun merge(old: List<HomeRow>, new: List<HomeRow>, keep: String?): List<HomeRow> {
        val oldBy = old.associateBy { it.kind }
        val newBy = new.associateBy { it.kind }
        val out = ArrayList<HomeRow>()
        for (kind in HomeKind.entries) {
            val o = oldBy[kind]
            val n = newBy[kind]
            val cards = when {
                o == null -> n?.cards.orEmpty()
                n == null -> o.cards.filter { it.key == keep }
                else -> {
                    val fresh = n.cards.associateBy { it.key }
                    val had = o.cards.mapTo(HashSet()) { it.key }
                    o.cards.mapNotNull { c -> fresh[c.key] ?: c.takeIf { it.key == keep } } + n.cards.filter { it.key !in had }
                }
            }
            if (cards.isNotEmpty() || (kind == HomeKind.SETUP && n != null)) out += HomeRow(kind, cards)
        }
        return out
    }
}
