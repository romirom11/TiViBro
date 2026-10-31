package io.github.romirom11.tivibro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchupTest {
    private fun ch(url: String, days: Int = 7, type: String? = null, source: String? = null) =
        Channel("c", 1, "C", "G", null, days, url, false, catchupType = type, catchupSource = source)

    private val start = 1_761_800_000L // 2025-10-30 04:53:20 UTC
    private val now = start + 86_400

    @Test fun flussonicKeepsNameAndQuery() {
        assertEquals("https://h.example/ch1/mono-$start-3600.m3u8?token=T", Catchup.url(ch("https://h.example/ch1/mono.m3u8?token=T"), start, 3600, now))
        assertEquals("https://h.example/ch1/index-$start-3600.m3u8?token=T", Catchup.url(ch("https://h.example/ch1/index.m3u8?token=T"), start, 3600, now))
        assertEquals("https://h.example/ch1/video-$start-60.m3u8", Catchup.url(ch("https://h.example/ch1/video.m3u8", type = "fs"), start, 60, now))
    }

    @Test fun flussonicOpenEnded() {
        assertEquals("https://h.example/ch1/mono-$start-now.m3u8?token=T", Catchup.url(ch("https://h.example/ch1/mono.m3u8?token=T"), start, null, now))
    }

    @Test fun flussonicTs() {
        assertEquals("http://h.example/ch1/timeshift_abs-$start.ts?t=1", Catchup.url(ch("http://h.example/ch1/mpegts?t=1", type = "flussonic-ts"), start, 60, now))
    }

    @Test fun shift() {
        val c = ch("https://h.example/ch1/mono.m3u8?token=T", type = "shift")
        assertEquals("https://h.example/ch1/mono.m3u8?token=T&utc=$start&lutc=$now", Catchup.url(c, start, 3600, now))
        assertEquals("https://h.example/ch1/mono.m3u8?token=T&utc=$start", Catchup.url(c, start, null, now))
        assertEquals("http://h.example/s?utc=$start&lutc=$now", Catchup.url(ch("http://h.example/s", type = "timeshift"), start, 10, now))
        assertEquals(3 * 3600L, Catchup.maxWindowSec(c))
    }

    @Test fun autoDetect() {
        assertEquals(Catchup.Kind.FLUSSONIC, Catchup.kind(ch("http://h.example/a/playlist.m3u8")))
        assertEquals(Catchup.Kind.SHIFT, Catchup.kind(ch("http://h.example/a/stream.m3u8")))
        assertEquals(Catchup.Kind.NONE, Catchup.kind(ch("http://h.example/a/stream.m3u8", days = 0)))
        assertEquals(Catchup.Kind.XC, Catchup.kind(ch("http://h.example:8080/live/u/p/123.ts")))
        assertNull(Catchup.url(ch("http://h.example/a/mono.m3u8", days = 0), start, 60, now))
    }

    @Test fun xc() {
        // 2025-10-30 04:53 UTC, 90 min
        assertEquals(
            "http://h.example:8080/timeshift/u/p/90/2025-10-30:04-53/123.ts",
            Catchup.url(ch("http://h.example:8080/live/u/p/123.ts", type = "xc"), start, 5400, now),
        )
        assertEquals(
            "http://h.example/timeshift/u/p/1/2025-10-30:04-53/7.m3u8",
            Catchup.url(ch("http://h.example/u/p/7.m3u8", type = "xc"), start, 30, now),
        )
    }

    @Test fun templateDefault() {
        val c = ch(
            "http://h.example/live/1.m3u8?token=T", type = "default",
            source = "http://arc.example/1/{utc}-{utcend}/{lutc}/{duration}/{duration:60}/{offset}/{Y}{m}{d}{H}{M}{S}.m3u8",
        )
        val end = start + 3600
        // the template has no query of its own: the live one (the token) is kept
        assertEquals("http://arc.example/1/$start-$end/$now/3600/60/86400/20251030045320.m3u8?token=T", Catchup.url(c, start, 3600, now))
    }

    @Test fun templateDollarForms() {
        val c = ch("http://h.example/1.m3u8", source = "http://arc.example/x?s=\${start}&e=\${end}&ts=\${timestamp}&d=\${duration}&b={start}&n={now}&f={end}")
        assertEquals("http://arc.example/x?s=$start&e=${start + 60}&ts=$now&d=60&b=$start&n=$now&f=${start + 60}", Catchup.url(c, start, 60, now))
    }

    @Test fun templateAppend() {
        val c = ch("http://h.example/1.m3u8?token=T", type = "append", source = "?utc={utc}&lutc={lutc}")
        assertEquals("http://h.example/1.m3u8?token=T&utc=$start&lutc=$now", Catchup.url(c, start, 60, now))
        val rel = ch("http://h.example/1.m3u8", source = "?utc={utc}")
        assertEquals("http://h.example/1.m3u8?utc=$start", Catchup.url(rel, start, 60, now))
    }

    @Test fun templateOpenEndedEndsNow() {
        val c = ch("http://h.example/1.m3u8", type = "default", source = "http://arc.example/{start}/{end}/{duration}")
        assertEquals("http://arc.example/$start/$now/86400", Catchup.url(c, start, null, now))
    }

    @Test fun xmltvTime() {
        assertEquals(1_761_800_000L, XmltvGuide.xmltvTime("20251030045320 +0000"))
        assertEquals(1_761_800_000L, XmltvGuide.xmltvTime("20251030065320 +0200"))
        assertEquals(1_761_800_000L, XmltvGuide.xmltvTime("20251030005320 -0400"))
        assertEquals(1_761_800_000L, XmltvGuide.xmltvTime("20251030045320"))
        assertNull(XmltvGuide.xmltvTime("bad"))
    }
}
