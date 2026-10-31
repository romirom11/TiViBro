package dev.romirom.tivibro.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun home() {
        assertEquals(DeepLink.Home, DeepLink.parse("tivibro://home"))
        assertEquals(DeepLink.Home, DeepLink.parse("TIVIBRO://HOME"))
        assertNull(DeepLink.parse("tivibro://home/extra"))
    }

    @Test fun live() {
        assertEquals(DeepLink.Live("1", "ch1014"), DeepLink.parse("tivibro://live/1/ch1014"))
        assertNull(DeepLink.parse("tivibro://live/1"))
        assertNull(DeepLink.parse("tivibro://live/1/ch1/more"))
    }

    @Test fun archive() {
        assertEquals(DeepLink.Archive("1", "ch5", 1761900000000, 540000), DeepLink.parse("tivibro://archive/1/ch5/1761900000000?position=540000"))
        assertEquals(DeepLink.Archive("1", "ch5", 1761900000000, 0), DeepLink.parse("tivibro://archive/1/ch5/1761900000000"))
        assertNull(DeepLink.parse("tivibro://archive/1/ch5/notanumber"))
        assertNull(DeepLink.parse("tivibro://archive/1/ch5/-5"))
        assertNull(DeepLink.parse("tivibro://archive/1/ch5/1761900000000?position=abc"))
        assertNull(DeepLink.parse("tivibro://archive/1/ch5/1761900000000?position=-1"))
    }

    @Test fun anythingElseIsRejected() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("https://home"))
        assertNull(DeepLink.parse("tivibro://settings"))
        assertNull(DeepLink.parse("tivibro://live/1/" + "x".repeat(300)))
        assertNull(DeepLink.parse("tivibro://live/1/%"))
        assertNull(DeepLink.parse("not a uri at all"))
    }

    @Test fun idsSurviveTheRoundTrip() {
        // channel ids are tvg-ids: any string, made-up ones start with «n:»
        for (l in listOf(
            DeepLink.Live("a1b2", "n:BBC One HD"),
            DeepLink.Live("7", "ch/with slash & ampersand"),
            DeepLink.Archive("7", "Канал+1", 1761900000000, 1234),
            DeepLink.Archive("7", "100%", 1761900000000, 0),
            DeepLink.Home,
        )) assertEquals(l, DeepLink.parse(l.uri()))
    }
}
