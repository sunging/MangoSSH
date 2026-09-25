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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwardConnectionsTest {
    @Test
    fun finishedConnectionsLeaveButStayCounted() {
        val connections = ForwardConnections<String>()
        assertNull(connections.activity().lastActivityEpochMillis)

        assertTrue(connections.add("a") { true })
        assertTrue(connections.add("b") { true })
        connections.remove("a")

        val activity = connections.activity()
        assertEquals(1, activity.activeConnections)
        assertEquals(2, activity.totalConnections)
        assertNotNull(activity.lastActivityEpochMillis)
        assertEquals(listOf("b"), connections.snapshot())
    }

    @Test
    fun aStoppedForwardRefusesNewConnections() {
        val connections = ForwardConnections<String>()
        assertFalse(connections.add("late") { false })
        assertEquals(0, connections.activity().activeConnections)
        assertEquals(0, connections.activity().totalConnections)
    }
}
