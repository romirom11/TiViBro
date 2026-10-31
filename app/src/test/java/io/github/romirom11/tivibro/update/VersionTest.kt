package io.github.romirom11.tivibro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun parsesTags() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(0, 1, 0), Version.parse("0.1"))
        assertEquals(Version(2, 0, 0), Version.parse("V2"))
        assertEquals(Version(1, 4, 0), Version.parse("v1.4.0-beta.2"))
        assertEquals(Version(10, 20, 30), Version.parse(" v10.20.30 "))
    }

    @Test fun rejectsGarbage() {
        assertNull(Version.parse(null))
        assertNull(Version.parse(""))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("release-1.2"))
        assertNull(Version.parse("v"))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(Version.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Version.isNewer("v1.2.10", "1.2.9"))
        assertTrue(Version.isNewer("v2.0.0", "1.99.99"))
        assertTrue(Version.isNewer("v0.1.1", "0.1"))
        assertFalse(Version.isNewer("v1.2.3", "1.2.3"))
        assertFalse(Version.isNewer("v1.2.2", "1.2.3"))
        assertFalse(Version.isNewer("v0.1", "0.1.0"))
    }

    @Test fun unknownVersionsAreHandledSafely() {
        assertFalse(Version.isNewer("nightly", "1.0.0")) // a tag we can't read never offers an update
        assertTrue(Version.isNewer("v1.0.0", "dev")) // an unreadable local version takes any real release
    }
}
