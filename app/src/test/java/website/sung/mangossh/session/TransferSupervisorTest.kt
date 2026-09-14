package website.sung.mangossh.session

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransferSupervisorTest {
    @Test fun waitingSiblingDoesNotConsumeGlobalSlotAndCancellationReleasesIt() = runBlocking {
        val supervisor = TransferSupervisor()
        val release = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val siblingEntered = CompletableDeferred<Unit>()
        val otherEntered = CompletableDeferred<Unit>()
        val first = launch { supervisor.run("same") { firstEntered.complete(Unit); release.await() } }
        firstEntered.await()
        val sibling = launch { supervisor.run("same") { siblingEntered.complete(Unit) } }
        yield()
        assertFalse(siblingEntered.isCompleted)
        val other = launch { supervisor.run("other") { otherEntered.complete(Unit); awaitCancellation() } }
        withTimeout(2_000) { otherEntered.await() }
        other.cancelAndJoin()
        first.cancelAndJoin()
        withTimeout(2_000) { siblingEntered.await(); sibling.join() }
    }
}
