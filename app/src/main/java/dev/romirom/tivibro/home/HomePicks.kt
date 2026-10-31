package dev.romirom.tivibro.home

import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.data.Programme
import java.util.Locale

/** What a source channel has around now: its programmes from a little before now to later today. */
class Slot(val ch: Channel, val progs: List<Programme>) {
    /** The programme on at [t], if any (binary search: the list is sorted by start). */
    fun at(t: Long): Programme? {
        var lo = 0
        var hi = progs.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            when {
                progs[m].end <= t -> lo = m + 1
                progs[m].start > t -> hi = m - 1
                else -> return progs[m]
            }
        }
        return null
    }
}

/** Titles that are not programmes: ads, shopping, breaks, test cards, «no programme». Kept small on purpose. */
object Filler {
    /** A normalized title equal to one of these is filler. */
    private val EXACT = setOf(
        "реклама", "тест", "test", "анонс", "анонсы", "анонси", "заставка", "promo", "tba", "перерыв", "перерва",
        "профилактика", "профілактика", "werbung", "reklama", "publicidad", "advertising", "advertisement", "commercials",
        "off air", "sendepause", "пауза",
    )
    /** A normalized title containing one of these is filler. */
    private val PARTS = listOf(
        "телемагазин", "телепродаж", "магазин на диване", "teleshopping", "infomercial", "paid programming", "teletienda",
        "telezakupy", "технический перерыв", "технічна перерва", "перерыв в вещании", "перерва в мовленні",
        "настроечная таблица", "настроювальна таблиця", "test card", "testbild", "нет программы", "немає програми",
        "программа отсутствует", "програма відсутня", "без программы", "no programme", "no program", "no information",
        "to be announced", "конец эфира", "кінець ефіру", "окончание вещания", "закінчення мовлення", "end of broadcast",
        "рекламный блок", "рекламний блок",
    )

    fun isFiller(title: String): Boolean {
        val n = normTitle(title)
        if (n.length < 2) return true
        return n in EXACT || PARTS.any { it in n }
    }
}

private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]""")
private val AGE = Regex("""(^|\s)\d{1,2}\+""")
private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

/** «Новини (16+)» → «новини»: what titles are compared and de-duplicated by. */
fun normTitle(t: String): String =
    t.lowercase(Locale.ROOT).replace('ё', 'е').replace(BRACKETS, " ").replace(AGE, " ").replace(NON_WORD, " ").trim()

object HomePicks {
    /** «Щойно почалось»: started at most this long ago (and at least a minute ago). */
    const val STARTED_WITHIN = 20 * MIN
    /** …and runs at least this long: a five-minute weather bulletin is not worth starting over. */
    const val STARTED_MIN_DURATION = 20 * MIN
    /** «Скоро»: starts within this. */
    const val SOON_WITHIN = 30 * MIN

    /** «Зараз в ефірі»: one card per channel, in the sources' order. */
    fun now(slots: List<Slot>, t: Long, max: Int): List<HomeCard> {
        val out = ArrayList<HomeCard>()
        for (s in slots) {
            val p = s.at(t) ?: continue
            if (Filler.isFiller(p.title)) continue
            out += HomeCard(HomeRows.nowKey(s.ch), HomeKind.NOW, s.ch, p)
            if (out.size >= max) break
        }
        return out
    }

    /** «Щойно почалось, дивись з початку»: on a channel with archive, long enough, not filler; the latest start first,
     *  one card per title (the same news on several channels shows once). */
    fun started(slots: List<Slot>, t: Long, canArchive: (Channel) -> Boolean, max: Int): List<HomeCard> =
        slots.mapNotNull { s ->
            val p = s.at(t) ?: return@mapNotNull null
            val ago = t - p.start
            if (ago < MIN || ago > STARTED_WITHIN || p.duration < STARTED_MIN_DURATION) return@mapNotNull null
            if (Filler.isFiller(p.title) || !canArchive(s.ch)) return@mapNotNull null
            HomeCard(HomeRows.progKey(HomeKind.STARTED, s.ch, p), HomeKind.STARTED, s.ch, p)
        }.sortedByDescending { it.p.start }.distinctBy { normTitle(it.p.title) }.take(max)

    /** «Скоро»: starting within [SOON_WITHIN], soonest first, one card per title. */
    fun soon(slots: List<Slot>, t: Long, max: Int): List<HomeCard> =
        slots.mapNotNull { s ->
            val p = s.progs.firstOrNull { it.start > t } ?: return@mapNotNull null
            if (p.start - t > SOON_WITHIN || Filler.isFiller(p.title)) return@mapNotNull null
            HomeCard(HomeRows.progKey(HomeKind.SOON, s.ch, p), HomeKind.SOON, s.ch, p)
        }.sortedBy { it.p.start }.distinctBy { normTitle(it.p.title) }.take(max)
}
