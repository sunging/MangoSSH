package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun optionalVPrefixParsesIdentically() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
    }

    @Test
    fun rejectsAmbiguousOrMalformedTags() {
        val invalid = listOf(
            "v1.2.3-rc1",
            "v1.2.3+meta",
            "1.2",
            "v01.2.3",
            "manual-a1b2c3d4e5f6",
            "",
            "   ",
            "latest",
            "v2101.0.0",
            "v0.1000.0",
            "v0.0.1000",
        )
        invalid.forEach { raw -> assertNull("expected null for '$raw'", AppVersion.parse(raw)) }
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun orderingFollowsSemverPrecedence() {
        val versions = listOf(
            AppVersion(2, 0, 0),
            AppVersion(1, 0, 0),
            AppVersion(1, 1, 0),
            AppVersion(1, 0, 1),
        ).sorted()

        assertEquals(
            listOf(AppVersion(1, 0, 0), AppVersion(1, 0, 1), AppVersion(1, 1, 0), AppVersion(2, 0, 0)),
            versions,
        )
        assertTrue(AppVersion(1, 0, 0) == AppVersion(1, 0, 0))
    }

    @Test
    fun versionCodeMatchesTheGradleParserFormula() {
        // Mirrors app/build.gradle.kts: versionCode = major*1_000_000 + minor*1_000 + patch.
        val table = listOf(
            Triple(0, 1, 0) to 1_000L,
            Triple(1, 0, 0) to 1_000_000L,
            Triple(1, 4, 2) to 1_004_002L,
            Triple(2_100, 999, 999) to 2_100_999_999L,
            Triple(0, 0, 1) to 1L,
        )
        table.forEach { (triple, expected) ->
            val (major, minor, patch) = triple
            assertEquals(expected, AppVersion(major, minor, patch).versionCode)
        }
    }

    @Test
    fun toStringRendersPlainSemver() {
        assertEquals("1.4.2", AppVersion(1, 4, 2).toString())
    }
}
