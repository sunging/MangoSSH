package org.connectbot.sshlib.protocol

import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineConcurrencyTest {
    @Test fun `a second event waits for a suspended transition instead of reporting rejection`() = runTest {
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callbacks = Proxy.newProxyInstance(SshClientCallbacks::class.java.classLoader,
            arrayOf(SshClientCallbacks::class.java)) { _, method, arguments ->
            if (method.name == "sendKexInit") {
                @Suppress("UNCHECKED_CAST")
                val continuation = arguments!!.last() as Continuation<Unit>
                suspend { sending.complete(Unit); release.await() }.startCoroutineUninterceptedOrReturn(continuation)
            } else if (method.returnType == Boolean::class.javaPrimitiveType) false
            else if (arguments?.lastOrNull() is Continuation<*>) Unit else null
        } as SshClientCallbacks
        val machine = SshClientStateMachine(callbacks)
        assertTrue(machine.connect())
        val first = async { machine.receiveVersion(IdBanner()) }
        sending.await()
        val second = async { machine.disconnect() }
        try {
            runCurrent()
            assertFalse(second.isCompleted)
        } finally { release.complete(Unit) }
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(machine.isDisconnected())
        assertFalse(machine.authorizeAuthenticatedPacket())
    }
}
