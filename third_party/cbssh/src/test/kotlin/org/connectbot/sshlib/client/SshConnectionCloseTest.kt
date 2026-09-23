/*
 * ConnectBot SSH Library
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.sshlib.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PingResult
import org.connectbot.sshlib.transport.PipedTransport
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionCloseTest {

    @Test
    fun `transport close releases a blocked writer without acquiring its lock`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val transport = object : Transport {
            override val isConnected get() = !released.isCompleted
            override suspend fun read(count: Int): ByteArray = error("unused")
            override suspend fun write(data: ByteArray) { writing.complete(Unit); released.await() }
            override suspend fun close() { released.complete(Unit) }
        }
        val connection = connection(transport, StandardTestDispatcher(testScheduler))
        val writer = async { connection.sendChannelClose(0) }
        writing.await()
        kotlinx.coroutines.withTimeout(1_000) { connection.close() }
        writer.await()
        assertEquals(true, released.isCompleted)
    }

    @Test
    fun `concurrent close closes transport once`() = runTest {
        val transport = RecordingTransport(suspendClose = true)
        val connection = connection(transport, StandardTestDispatcher(testScheduler))

        val first = async { connection.close() }
        transport.closeStarted.await()
        val second = async { connection.close() }
        yield()

        transport.allowClose.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `write after close fails without reaching transport`() = runTest {
        val transport = RecordingTransport()
        val connection = connection(transport, StandardTestDispatcher(testScheduler))

        connection.close()

        assertFailsWith<TransportException> {
            connection.sendChannelClose(recipientChannel = 0)
        }
        assertEquals(0, transport.writeCalls)
    }

    @Test
    fun `rejected direct tcpip channel does not close its jump connection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (clientTransport, serverTransport) = PipedTransport.create()
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start()
        val verifier = object : HostKeyVerifier {
            override suspend fun verify(key: org.connectbot.sshlib.PublicKey): Boolean = true
        }
        val parent = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = verifier,
            coroutineDispatcher = dispatcher,
        )
        try {
            val connectingParent = backgroundScope.async(dispatcher) { parent.connect() }
            yield()
            assertEquals(ConnectResult.Success, connectingParent.await())
            val authentication = backgroundScope.async(dispatcher) { parent.authenticatePassword("jump", "password") }
            server.awaitUserauthRequest()
            server.sendUserauthSuccess()
            assertIs<AuthResult.Success>(authentication.await())

            val opening = backgroundScope.async(dispatcher) {
                parent.openDirectTcpipChannel("unreachable", 22, "127.0.0.1", 0)
            }
            val open = server.awaitChannelOpen()
            server.sendChannelOpenFailure(open.senderChannel().toInt())
            assertEquals(null, opening.await())
            assertIs<PingResult.Success>(parent.ping())
        } finally {
            parent.close()
        }
    }

    @Test
    fun `cancelling a writer inside the transport keeps the encrypted stream usable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (pipedClient, serverTransport) = PipedTransport.create()
        val clientTransport = GatedTransport(pipedClient)
        val server = FakeSshServer(serverTransport, backgroundScope, dispatcher)
        server.start()
        val parent = SshConnection(
            transport = clientTransport,
            hostKeyVerifier = object : HostKeyVerifier {
                override suspend fun verify(key: org.connectbot.sshlib.PublicKey): Boolean = true
            },
            coroutineDispatcher = dispatcher,
        )
        try {
            val connecting = backgroundScope.async(dispatcher) { parent.connect() }
            assertEquals(ConnectResult.Success, connecting.await())
            val authentication = backgroundScope.async(dispatcher) { parent.authenticatePassword("jump", "password") }
            server.awaitUserauthRequest()
            server.sendUserauthSuccess()
            assertIs<AuthResult.Success>(authentication.await())

            val opening = backgroundScope.async(dispatcher) {
                parent.openDirectTcpipChannel("target", 22, "127.0.0.1", 0)
            }
            val open = server.awaitChannelOpen()
            server.sendChannelOpenConfirmation(open.senderChannel().toInt(), senderChannel = 7)
            val channel = requireNotNull(opening.await())

            val gate = CompletableDeferred<Unit>()
            clientTransport.gate = gate
            val cancelled = backgroundScope.async(dispatcher) { channel.sendData("first".toByteArray()) }
            clientTransport.reachedGate.await()
            cancelled.cancel()
            yield()
            gate.complete(Unit)

            channel.sendData("second".toByteArray())
            val delivered = kotlinx.coroutines.withTimeout(5_000) {
                buildList {
                    while (lastOrNull() != "second") add(server.awaitChannelData().data().data().decodeToString())
                }
            }
            assertEquals("second", delivered.last())
        } finally {
            parent.close()
        }
    }

    private class GatedTransport(private val delegate: Transport) : Transport by delegate {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val reachedGate = CompletableDeferred<Unit>()

        override suspend fun write(data: ByteArray) {
            gate?.let {
                reachedGate.complete(Unit)
                it.await()
            }
            delegate.write(data)
        }
    }

    private fun connection(transport: Transport, dispatcher: CoroutineDispatcher) = SshConnection(
        transport = transport,
        hostKeyVerifier = object : HostKeyVerifier {
            override suspend fun verify(key: org.connectbot.sshlib.PublicKey): Boolean = true
        },
        coroutineDispatcher = dispatcher,
    )

    private class RecordingTransport(
        private val suspendClose: Boolean = false,
    ) : Transport {
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        var closeCalls = 0
        var writeCalls = 0

        override val isConnected: Boolean
            get() = closeCalls == 0

        override suspend fun read(count: Int): ByteArray = throw UnsupportedOperationException()

        override suspend fun write(data: ByteArray) {
            writeCalls++
        }

        override suspend fun close() {
            closeCalls++
            closeStarted.complete(Unit)
            if (suspendClose) allowClose.await()
        }
    }
}
