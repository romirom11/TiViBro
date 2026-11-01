package dev.romirom.tivibro.remote

import dev.romirom.tivibro.data.Channel
import java.util.Calendar
import java.util.TimeZone

/**
 * What the phone's «Канали» tab walks through, worked out from [Remote.State] alone so it is testable without a TV:
 * the favourites first, then the provider's groups in playlist order, each with only what the user did not hide (a
 * group whose channels are all hidden, or that is switched off, is not there at all, as in the app's group rail).
 * Groups are addressed by name: names are what the playlist and the app key them by, and they are unique.
 */
object Browse {
    /** The favourites' id: not a group name a playlist would use. */
    const val FAV = "*fav"

    class Group(val id: String, val name: String, val channels: List<Channel>)

    /** Favourites (when there are any) under [favName], then every group with something visible. */
    fun groups(st: Remote.State, favName: String): List<Group> {
        val out = ArrayList<Group>()
        favourites(st).takeIf { it.isNotEmpty() }?.let { out += Group(FAV, favName, it) }
        for ((name, chs) in st.groups) out += Group(name, name, chs)
        return out
    }

    /** The channels of group [id] ([FAV] or a name), else of the one whose name matches it ignoring case (what an
     *  assistant passes), else of the first one containing it; null when nothing matches. */
    fun channels(st: Remote.State, id: String): List<Channel>? {
        val q = id.trim()
        if (q == FAV) return favourites(st)
        st.groups[q]?.let { return it }
        return st.groups.entries.firstOrNull { it.key.equals(q, ignoreCase = true) }?.value
            ?: st.groups.entries.firstOrNull { q.isNotEmpty() && it.key.contains(q, ignoreCase = true) }?.value
    }

    fun favourites(st: Remote.State): List<Channel> = st.favs.mapNotNull(st.byId::get).filter { it.id in st.shown }

    /**
     * The days a channel's schedule offers, as local midnights (epoch ms) with their offset from today: back as far as
     * its archive reaches (at most [MAX_BACK]), today and tomorrow. Calendar arithmetic, not 24 h steps, so a DST change
     * in between still lands on midnight.
     */
    fun days(archiveDays: Int, t: Long, tz: TimeZone = TimeZone.getDefault()): List<Pair<Int, Long>> =
        (-archiveDays.coerceIn(0, MAX_BACK)..1).map { d -> d to midnight(t, d, tz) }

    /** Local midnight [d] days from [t]'s day. */
    fun midnight(t: Long, d: Int, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, d)
    }.timeInMillis

    /** Providers keep up to two weeks; a playlist that claims more would only list empty days. */
    const val MAX_BACK = 14
}
