package website.sung.mangossh.session

/** Keeps protocol assertions inside the fixture's owning coroutine. */
internal suspend fun <T : Throwable> assertSuspendingThrows(type: Class<T>, action: suspend () -> Unit): T {
    try {
        action()
    } catch (failure: Throwable) {
        if (type.isInstance(failure)) return type.cast(failure)!!
        throw AssertionError("Unexpected failure category", failure)
    }
    throw AssertionError("Expected ${type.simpleName}")
}
