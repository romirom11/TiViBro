package dev.romirom.tivibro.tmdb

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** What a guide title says about the programme: a film or a series (from a prefix or an episode marker), or neither. */
enum class Kind { FILM, SERIES }

/** A guide title made ready for a search: the bare name, a year if it had one, and the kind if it said. */
data class CleanTitle(val query: String, val year: Int?, val kind: Kind?)

/** One search result, as much of it as matching and the cards need. [type]: "movie" or "tv". */
data class TmdbItem(
    val id: Int,
    val type: String,
    val title: String,
    val original: String,
    val year: Int?,
    val votes: Int,
    val rating: Double,
    val poster: String?,
    val backdrop: String?,
    val overview: String,
)

object TmdbTitle {
    /** Prefixes that say "film" or "series" (Russian and Ukrainian guides): «х/ф», «т/с», «м/ф», «худ. фильм»… */
    private val PREFIXES: List<Pair<Regex, Kind>> = listOf(
        """(х|x)\s*[/\\.]\s*ф""" to Kind.FILM,
        """м\s*[/\\.]\s*ф""" to Kind.FILM,
        """д\s*[/\\.]\s*ф""" to Kind.FILM,
        """т\s*[/\\.]\s*с""" to Kind.SERIES,
        """м\s*[/\\.]\s*с""" to Kind.SERIES,
        """д\s*[/\\.]\s*с""" to Kind.SERIES,
        """худ(ожественный|ожній)?\.?\s*(фильм|фільм)""" to Kind.FILM,
        """(художественный|художній|документальный|документальний|анимационный|анімаційний|многосерийный|багатосерійний)\s+(фильм|фільм)""" to Kind.FILM,
        """(мультфильм|мультфільм|фильм|фільм|кинофильм|кінофільм)""" to Kind.FILM,
        """(телесериал|телесеріал|сериал|серіал|мультсериал|мультсеріал)""" to Kind.SERIES,
        """(movie|film|spielfilm)\s*:""" to Kind.FILM,
        """(series|serie)\s*:""" to Kind.SERIES,
    ).map { (p, k) -> Regex("""^\s*(?:$p)(?=[\s:.«"„“'\-–]|$)[\s:.\-–]*""", RegexOption.IGNORE_CASE) to k }

    private const val N = """\d{1,4}"""
    /** Season and episode markers; everything from the first one on is cut off. */
    private val MARKERS = listOf(
        """\bs\d{1,2}\s*e\d{1,3}\b""",
        """\b\d{1,2}x\d{1,3}\b""",
        """\b$N\s*-?\s*(й|я|ий|ый|а)?\s*(сезон|серия|серії|серія|серии|эпизод|епізод|выпуск|випуск|бөлім|маусым)""",
        """(сезон|серия|серії|серія|серии|эпизод|епізод|выпуск|випуск|season|staffel|temporada|sezon|saison|episode|folge|odcinek|episodio|capítulo|cap\.|ep\.|бөлім|маусым)\s*№?\s*$N""",
        """\b$N\s*(серий|серії|серій|серия)\b""",
        """\(\s*$N\s*(с\.|с|сер\.?|ser\.?)\s*\)""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    /** «Назва · 3», «Назва. 12 с.», «Назва (12)»: a bare episode number at the end. */
    private val TAIL_NUMBER = Regex("""\s*(·|┊|\||,|\.|-|–)\s*\d{1,3}\s*(с\.|с|сер\.?)?\s*$|\s*\(\s*\d{1,3}\s*\)\s*$""")
    private val YEAR = Regex("""\(\s*((?:19|20)\d{2})\s*\)|[\s,.\-–]((?:19|20)\d{2})\s*$""")
    private val QUOTES = Regex("""[«»"„“”]""")
    private val AGE = Regex("""\(\s*\d{1,2}\s*\+\s*\)|\s\d{1,2}\+(?=\s|$)""")
    private val SPACES = Regex("""\s+""")
    private val SEPARATORS = Regex("""\s*[-–:.,·|┊/(\[]+\s*$""")

    fun clean(raw: String): CleanTitle {
        var t = raw.replace(' ', ' ').trim()
        var kind: Kind? = null
        for ((re, k) in PREFIXES) {
            val m = re.find(t) ?: continue
            kind = k
            t = t.substring(m.range.last + 1)
            break
        }
        t = t.replace(AGE, " ")
        val marker = MARKERS.mapNotNull { it.find(t) }.minByOrNull { it.range.first }
        if (marker != null) {
            kind = Kind.SERIES
            val before = t.substring(0, marker.range.first)
            t = if (before.isNotBlank()) before else t.substring(marker.range.last + 1)
        }
        var year: Int? = null
        YEAR.findAll(t).lastOrNull()?.let { m ->
            val y = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            // «Blade Runner 2049»: a year still to come is part of the title
            if (m.range.first > 0 && y <= java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1) { year = y; t = t.removeRange(m.range) }
        }
        TAIL_NUMBER.find(t)?.let { if (it.range.first > 0) { t = t.substring(0, it.range.first); if (kind == null) kind = Kind.SERIES } }
        t = t.replace(QUOTES, "").replace(SPACES, " ").trim().replace(SEPARATORS, "").trim()
        return CleanTitle(t, year, kind)
    }

    private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

    /** What two titles are compared by: lower case, ё as е, & as and, no punctuation. */
    fun norm(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е').replace("&", " and ").replace(NON_WORD, " ").trim().replace(SPACES, " ")

    /** 1 for the same normalized title, falling with the edit distance. */
    fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Titles too general to be a film or a show of their own («Новини», «Погода», «Football»…): never looked up. */
    private val GENERIC = setOf(
        "новости", "новини", "news", "погода", "weather", "спорт", "sport", "футбол", "football", "кино", "кіно", "фильм",
        "фільм", "film", "movie", "мультфильмы", "мультфільми", "мультфильм", "мультфільм", "cartoons", "музыка", "музика",
        "music", "documentary", "документальный фильм", "документальний фільм", "nachrichten", "wetter", "tagesschau",
        "wiadomości", "pogoda", "noticias", "el tiempo", "жаңалықтар", "ауа райы", "утро", "ранок", "morning", "teleshopping",
        "live", "прямой эфир", "пряма трансляція", "концерт", "concert", "итоги", "підсумки", "время", "час",
    )

    /** Worth a search: a few letters, not a generic word. */
    fun searchable(c: CleanTitle): Boolean {
        val n = norm(c.query)
        return n.length >= 3 && n.any { it.isLetter() } && n !in GENERIC
    }
}

/**
 * Picks the result a guide programme most likely is, or nothing. A wrong poster is worse than none, so this is strict:
 * the title has to match closely (exactly when nothing else backs it up), the kind has to agree with what the guide
 * said, the year too when there is one, and two equally good candidates mean neither.
 */
object TmdbMatcher {
    /** Similarity needed when the guide's kind or year backs the match up. */
    const val CLOSE = 0.86
    /** Needed when nothing does: practically the same title. */
    const val EXACT = 0.97

    fun pick(c: CleanTitle, results: List<TmdbItem>, durationMin: Int): TmdbItem? {
        data class Cand(val item: TmdbItem, val sim: Double)
        val cands = results.mapNotNull { r ->
            if (r.type != "movie" && r.type != "tv") return@mapNotNull null
            if (c.kind == Kind.FILM && r.type != "movie") return@mapNotNull null
            if (c.kind == Kind.SERIES && r.type != "tv") return@mapNotNull null
            val sim = max(TmdbTitle.similarity(c.query, r.title), TmdbTitle.similarity(c.query, r.original))
            val y = c.year
            val yearOk = when {
                y == null -> null
                r.year == null -> false
                r.type == "movie" -> kotlin.math.abs(r.year - y) <= 1
                else -> r.year <= y + 1 // a series' year in a guide is the episode's, never before the show began
            }
            if (yearOk == false) return@mapNotNull null
            val backed = c.kind != null || yearOk == true
            if (sim < (if (backed) CLOSE else EXACT)) return@mapNotNull null
            if (!backed) {
                // nothing but the title. Guides mark films (a year, «х/ф»), so an unmarked title that equals a film's is
                // far more often something else of that name (BBC's «Final Score» is football results, not the 2018
                // film): only shows, known well enough, in a slot a show fits
                if (r.type == "movie" || durationMin > 150 || r.votes < 30) return@mapNotNull null
            }
            Cand(r, sim)
        }
        if (cands.isEmpty()) return null
        val best = cands.maxWith(compareBy<Cand> { it.sim }.thenBy { it.item.votes })
        // another candidate about as close and about as known: can't tell which one this is
        val rival = cands.any { it !== best && best.sim - it.sim < 0.03 && it.item.votes * 3 >= best.item.votes }
        return if (rival && c.year == null) null else best.item
    }
}
