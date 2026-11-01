package dev.romirom.tivibro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ControlTest {
    private val now = 1_793_500_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min

    @Test fun relativeSeeksLandWhereAsked() {
        assertEquals(now - 35 * min, Control.seekTarget(now - 30 * min, -5 * min, null, now, 3))
        assertEquals(now - 29 * min - 30_000, Control.seekTarget(now - 30 * min, 30_000, null, now, 3))
    }

    @Test fun anAbsoluteMomentWinsOverARelativeStep() =
        assertEquals(now - 2 * 3600_000L, Control.seekTarget(now - min, 30_000, now - 2 * 3600_000L, now, 3))

    @Test fun forwardPastTheBroadcastIsLive() {
        assertNull(Control.seekTarget(now - 2 * min, 5 * min, null, now, 3))
        assertNull(Control.seekTarget(now - 2 * min, null, now + day, now, 3))
        assertNull(Control.seekTarget(now - 10_000, 6_000, null, now, 3)) // within the live edge
    }

    @Test fun backPastTheArchiveStopsAtItsEdge() {
        assertEquals(now - 3 * day + Control.ARCHIVE_MARGIN, Control.seekTarget(now - 3 * day + 2 * min, -10 * min, null, now, 3))
        assertEquals(now - day + Control.ARCHIVE_MARGIN, Control.seekTarget(now, null, 0L, now, 1))
    }

    @Test fun aTimeOfDayIsTheMostRecentOne() {
        val tz = TimeZone.getTimeZone("Europe/Kyiv")
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        val at = { h: Int, m: Int -> Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        // an hour ago today; an hour from now is yesterday's
        val earlier = (h + 23) % 24
        val later = (h + 1) % 24
        val e = Control.localTime("%d:%02d".format(earlier, 5), now, tz)!!
        assertEquals(true, e <= now && now - e < day)
        assertEquals(at(later, 0) - if (at(later, 0) > now) day else 0, Control.localTime("%02d00".format(later), now, tz))
        assertEquals(e, Control.localTime(" %d.%02d ".format(earlier, 5), now, tz))
        assertNull(Control.localTime("25:00", now, tz))
        assertNull(Control.localTime("завтра", now, tz))
        assertNull(Control.localTime("1793477719916", now, tz))
    }

    @Test fun aFavouriteMovesPastItsVisibleNeighbour() {
        val favs = listOf("a", "h", "b", "c")
        val visible = setOf("a", "b", "c")
        assertEquals(listOf("h", "b", "a", "c"), Control.moveFav(favs, visible, "a", 1)) // past b, the hidden h stays first
        assertEquals(listOf("a", "h", "c", "b"), Control.moveFav(favs, visible, "c", -1))
        assertEquals(listOf("b", "a", "h", "c"), Control.moveFav(favs, visible, "b", -1))
        assertEquals(favs, Control.moveFav(favs, visible, "a", -1)) // already first
        assertEquals(favs, Control.moveFav(favs, visible, "c", 1)) // already last
        assertEquals(favs, Control.moveFav(favs, visible, "zz", 1))
    }
}
