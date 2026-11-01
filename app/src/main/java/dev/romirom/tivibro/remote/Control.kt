package dev.romirom.tivibro.remote

import java.util.Calendar
import java.util.TimeZone

/**
 * The arithmetic of what the phone and the assistant ask for, kept apart from the view model so it is testable without
 * a player: where a seek lands, which local time «to 21:30» means, how a favourite moves. The view model only applies
 * the answers, through the same functions its own keys use.
 */
object Control {
    /** How close to the broadcast a seek counts as «live»: closer than this, the player rejoins live instead. */
    const val LIVE_EDGE = 5_000L

    /** Kept off the archive's far edge: the provider drops the oldest segments on its own clock, not ours. */
    const val ARCHIVE_MARGIN = 60_000L

    private const val DAY = 24 * 3600_000L

    /**
     * Where a seek lands, epoch ms; null means «live». [to] (an absolute moment) wins over [byMs] (relative to [base],
     * the position on screen). The result is clamped into what the channel's archive holds; anything at or past the
     * live edge is live, so «+5 min» near the end of a timeshift simply rejoins the broadcast.
     */
    fun seekTarget(base: Long, byMs: Long?, to: Long?, now: Long, archiveDays: Int): Long? {
        val t = to ?: (base + (byMs ?: 0L))
        if (t >= now - LIVE_EDGE) return null
        val earliest = now - archiveDays * DAY + ARCHIVE_MARGIN
        return t.coerceIn(minOf(earliest, now - LIVE_EDGE), now - LIVE_EDGE)
    }

    /**
     * «21:30», «9:05», «2130» as the most recent such moment on the TV's clock (today, or yesterday when today's is still
     * to come): what a person means by «go back to 21:30». Null when [s] is not a time of day.
     */
    fun localTime(s: String, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val m = Regex("""^\s*(\d{1,2})[:.h]?(\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    /**
     * The favourites after moving [id] one step ([dir] -1 up, +1 down) past its nearest neighbour the user can see
     * ([visible]): a favourite on a hidden channel keeps its place but is not what a step jumps over, since the phone
     * does not show it. Unchanged when [id] is not a favourite or is already at that end.
     */
    fun moveFav(favs: List<String>, visible: Set<String>, id: String, dir: Int): List<String> {
        val i = favs.indexOf(id)
        if (i < 0 || dir == 0) return favs
        var j = i + if (dir > 0) 1 else -1
        while (j in favs.indices && favs[j] !in visible) j += if (dir > 0) 1 else -1
        if (j !in favs.indices) return favs
        val out = favs.toMutableList()
        out.removeAt(i)
        out.add(j, id)
        return out
    }
}
