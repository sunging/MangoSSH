package website.sung.mangossh.session.ssh

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnedSshOperationTest {
    @Test fun connectionShutdownReportsClosedToAnActiveCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { awaitOwnedSshOperation(scope) { started.complete(Unit); awaitCancellation() } }
        }
        try {
            started.await()
            scope.cancel()
            val failure = withTimeout(1000) { caller.await() }.exceptionOrNull()
            assertFalse("A closed connection must not cancel its caller", caller.isCancelled)
            assertTrue("Connection shutdown must remain retryable", failure is SshFailure)
            assertEquals(SshFailure.Category.CLOSED, (failure as SshFailure).category)
        } finally { scope.cancel(); caller.cancelAndJoin() }
    }

    @Test fun operationCancellationWithALiveOwnerIsNotAConnectionFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val failure = runCatching {
                awaitOwnedSshOperation<Unit>(scope) { throw CancellationException("synthetic prompt cancellation") }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(scope.isActive)
        } finally { scope.cancel() }
    }

    @Test fun callerCancellationWinsConcurrentConnectionShutdown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitOwnedSshOperation(scope) { started.complete(Unit); awaitCancellation() } }
            catch (error: Throwable) { observed.complete(error); throw error }
        }
        try {
            started.await()
            caller.cancel()
            scope.cancel()
            caller.join()
            assertTrue(withTimeout(1000) { observed.await() } is CancellationException)
        } finally { scope.cancel(); caller.cancelAndJoin() }
    }

    @Test fun callerCancellationLeavesHandshakeOwnedAndClosesItsLateResultOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        var closes = 0
        try {
            val caller = launch {
                awaitOwnedSshOperation(scope, finishOnCancellation = true, onDiscard = { _: Unit ->
                    closes++; disposed.complete(Unit)
                }) { started.complete(Unit); reply.await() }
            }
            started.await()
            withTimeout(1000) { caller.cancelAndJoin() }
            assertEquals(0, closes)
            reply.complete(Unit)
            withTimeout(1000) { disposed.await() }
            assertEquals(1, closes)
        } finally { scope.cancel() }
    }

    @Test fun connectionShutdownStillCancelsAnUnansweredHandshake() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val caller = launch {
            awaitOwnedSshOperation(scope, finishOnCancellation = true) {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        try {
            started.await()
            caller.cancelAndJoin()
            assertFalse(stopped.isCompleted)
            scope.cancel()
            withTimeout(1000) { stopped.await() }
        } finally { scope.cancel(); caller.cancelAndJoin() }
    }

    @Test fun ordinaryOperationsStillCancelWithTheirCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val caller = launch {
            awaitOwnedSshOperation(scope) {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        try {
            started.await()
            caller.cancelAndJoin()
            withTimeout(1000) { stopped.await() }
        } finally { scope.cancel(); caller.cancelAndJoin() }
    }

    @Test fun deliveredResultBelongsToTheCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var closes = 0
        try {
            val result = awaitOwnedSshOperation(scope, true, onDiscard = { _: Int -> closes++ }) { 7 }
            assertEquals(7, result)
            assertEquals(0, closes)
        } finally { scope.cancel() }
    }
}
