package website.sung.mangossh.session.ssh

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnedSshOperationTest {
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
