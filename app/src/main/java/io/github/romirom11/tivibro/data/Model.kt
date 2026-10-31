package io.github.romirom11.tivibro.data

const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR

fun now() = System.currentTimeMillis()

/**
 * One playlist entry. [id] is the playlist's tvg-id (chNNN with TeamPlay; any string elsewhere), which favourites,
 * hidden channels and bookmarks are keyed by. [epgId] is the id the guide is looked up by: tvg-id normalized
 * (tvg_NNN → chNNN), else the /chNNN/ of the stream URL, else tvg-name, else the display name (see [epgKeys]).
 * [catchupType] and [catchupSource] are the playlist's own (attribute or header default), null when it gives none.
 */
data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val archiveDays: Int,
    val url: String,
    val hd: Boolean,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val epgId: String = id,
)

/** [desc] is filled only where the programme came with one (bookmarks have none); guide programmes carry [ref], the
 *  content record the description is read from on demand (see Guide.desc). */
data class Programme(val start: Long, val end: Long, val title: String, val desc: String, val ref: Long = -1) {
    val duration get() = end - start
    fun isPast(t: Long = now()) = end <= t
    fun isNow(t: Long = now()) = start <= t && end > t
}

/** A playlist group as shown in the rail. [marks] is set only for «Продовжити перегляд». */
data class Group(val id: String, val name: String, val channels: List<Channel>, val marks: List<Mark>? = null)

/** Where the user stopped inside an archived programme. */
data class Mark(val ch: Channel, val p: Programme, val pos: Long, val at: Long) {
    val watched get() = ((pos - p.start).toFloat() / p.duration).coerceIn(0f, 1f)
}

fun Channel.inArchive(p: Programme, t: Long = now()) = archiveDays > 0 && p.start >= t - archiveDays * DAY

// ---------------------------------------------------------------- guide ids

private val URL_CH = Regex("""/(ch\d+)/""")
private val TVG_NUM = Regex("""^tvg_(\d+)$""", RegexOption.IGNORE_CASE)
private val NAME_COUNTRY = Regex("""^[A-Za-z]{2}:\s*""")
private val NAME_QUALITY = Regex("""\s+(F?HD|UHD|4K)$""", RegexOption.IGNORE_CASE)

/** "UA: 1+1 Україна HD" → "1+1 україна": what a display name is compared by. */
fun epgName(name: String): String =
    name.trim().replace(NAME_COUNTRY, "").replace(NAME_QUALITY, "").trim().lowercase().replace(Regex("""\s+"""), " ")

/** tvg_NNN → chNNN (SmartIPTV writes the provider's ids that way); anything else as is. */
fun normalizeTvgId(id: String): String = TVG_NUM.matchEntire(id.trim())?.let { "ch" + it.groupValues[1] } ?: id.trim()

fun urlChannelId(url: String): String? = URL_CH.find(url)?.groupValues?.get(1)

/** The keys a guide may know this channel by, best first: tvg-id as given, normalized, the URL's /chNNN/, the name. */
fun Channel.epgKeys(): List<String> =
    listOfNotNull(id.takeUnless { it.startsWith(UNNAMED) }, epgId, urlChannelId(url), epgName(name).takeIf { it.isNotEmpty() }).distinct()

/** Prefix of ids made up for entries without tvg-id or /chNNN/: never a guide key. */
const val UNNAMED = "n:"
