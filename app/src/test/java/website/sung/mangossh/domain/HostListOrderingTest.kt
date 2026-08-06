package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostListOrderingTest {
    @Test
    fun manualComparatorOrdersByPosition() {
        val profiles = listOf(profile("c", position = 2), profile("a", position = 0), profile("b", position = 1))

        val ordered = profiles.sortedWith(HostSortMode.MANUAL.comparator())

        assertEquals(listOf("a", "b", "c"), ordered.map { it.id })
    }

    @Test
    fun labelComparatorIsCaseInsensitive() {
        val profiles = listOf(profile("a", label = "bravo"), profile("b", label = "Alpha"), profile("c", label = "charlie"))

        val ordered = profiles.sortedWith(HostSortMode.LABEL.comparator())

        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun recentComparatorOrdersByLastConnectedDescendingThenLabel() {
        val profiles = listOf(
            profile("stale", label = "zulu", lastConnectedAtEpochMillis = 100L),
            profile("fresh", label = "alpha", lastConnectedAtEpochMillis = 200L),
            profile("never-b", label = "bravo", lastConnectedAtEpochMillis = 0L),
            profile("never-a", label = "alpha-two", lastConnectedAtEpochMillis = 0L),
        )

        val ordered = profiles.sortedWith(HostSortMode.RECENT.comparator())

        assertEquals(listOf("fresh", "stale", "never-a", "never-b"), ordered.map { it.id })
    }

    @Test
    fun matchesHostQueryMatchesLabelHostnameAndUsernameCaseInsensitively() {
        val host = profile("a", label = "Prod Web", hostname = "web01.example.test", username = "deploy")

        assertTrue(host.matchesHostQuery(""))
        assertTrue(host.matchesHostQuery("prod"))
        assertTrue(host.matchesHostQuery("WEB01"))
        assertTrue(host.matchesHostQuery("deploy"))
        assertFalse(host.matchesHostQuery("staging"))
    }

    @Test
    fun reorderedByRenumbersToMatchGivenOrderAndAppendsUnknownIds() {
        val profiles = listOf(profile("a", position = 0), profile("b", position = 1), profile("c", position = 2))

        val reordered = profiles.reorderedBy(listOf("c", "a"))

        assertEquals(listOf("c", "a", "b"), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2), reordered.map { it.position })
    }

    @Test
    fun reorderedByIgnoresIdsNotInTheList() {
        val profiles = listOf(profile("a", position = 0), profile("b", position = 1))

        val reordered = profiles.reorderedBy(listOf("ghost", "b", "a"))

        assertEquals(listOf("b", "a"), reordered.map { it.id })
    }

    @Test
    fun normalizedPositionsCollapsesGapsPreservingOrder() {
        val profiles = listOf(profile("a", position = 5), profile("b", position = 10))

        val normalized = profiles.normalizedPositions()

        assertEquals(listOf("a", "b"), normalized.map { it.id })
        assertEquals(listOf(0, 1), normalized.map { it.position })
    }

    private fun profile(
        id: String,
        label: String = id,
        hostname: String = "$id.invalid",
        username: String = "user",
        position: Int = 0,
        lastConnectedAtEpochMillis: Long = 0L,
    ) = ConnectionProfile(
        id = id,
        label = label,
        hostname = hostname,
        username = username,
        position = position,
        lastConnectedAtEpochMillis = lastConnectedAtEpochMillis,
    )
}
