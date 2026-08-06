package website.sung.mangossh.domain

/** User-selectable ordering for the host list; persisted as a device-local UI preference. */
enum class HostSortMode {
    MANUAL,
    LABEL,
    RECENT,
    MOST_USED,
    ;

    /** Comparator matching this mode's ordering rule. */
    fun comparator(): Comparator<ConnectionProfile> = when (this) {
        MANUAL -> compareBy { it.position }
        LABEL -> compareBy { it.label.lowercase() }
        RECENT -> compareByDescending<ConnectionProfile> { it.lastConnectedAtEpochMillis }
            .thenBy { it.label.lowercase() }
        MOST_USED -> compareByDescending<ConnectionProfile> { it.connectionCount }
            .thenByDescending { it.lastConnectedAtEpochMillis }
            .thenBy { it.label.lowercase() }
    }
}

/** Matches free-text search against the fields visible on a host card. */
fun ConnectionProfile.matchesHostQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return label.contains(query, ignoreCase = true) ||
        hostname.contains(query, ignoreCase = true) ||
        username.contains(query, ignoreCase = true) ||
        endpoint.contains(query, ignoreCase = true)
}

/**
 * Reorders this list to match [orderedIds], renumbering [ConnectionProfile.position] densely
 * from zero. Any id missing from [orderedIds] keeps its relative position and is appended after
 * the ordered entries, so a profile added or removed concurrently with a drag is never dropped.
 */
fun List<ConnectionProfile>.reorderedBy(orderedIds: List<String>): List<ConnectionProfile> {
    val byId = associateBy { it.id }
    val ordered = orderedIds.mapNotNull { byId[it] }
    val orderedIdSet = orderedIds.toHashSet()
    val remaining = filterNot { it.id in orderedIdSet }
    return (ordered + remaining).mapIndexed { index, profile -> profile.copy(position = index) }
}

/** Renumbers [ConnectionProfile.position] densely from zero, preserving current manual order. */
fun List<ConnectionProfile>.normalizedPositions(): List<ConnectionProfile> =
    sortedBy { it.position }.mapIndexed { index, profile -> profile.copy(position = index) }
