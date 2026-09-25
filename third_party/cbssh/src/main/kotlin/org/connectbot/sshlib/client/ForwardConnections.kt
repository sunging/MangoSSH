/*
 * ConnectBot SSH Library
 * Copyright 2025 Kenny Root
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

import org.connectbot.sshlib.PortForwardActivity

/**
 * The data connections ([DataForwarder]s) of one forward, with the counts reported as [PortForwardActivity].
 *
 * A finished connection removes itself, so a long-lived forward does not keep every
 * connection it ever carried. Registration and the stop snapshot share one lock: a
 * connection accepted while the forward stops is either aborted by the stop or refused.
 */
internal class ForwardConnections<T : Any> {
    private val lock = Any()
    private val live = mutableListOf<T>()
    private var total = 0L

    @Volatile
    private var lastActivity = 0L

    /** Registers [forwarder] while [stillActive] holds; returns false when it must be aborted. */
    fun add(forwarder: T, stillActive: () -> Boolean): Boolean = synchronized(lock) {
        if (!stillActive()) return false
        live += forwarder
        total++
        touch()
        true
    }

    fun remove(forwarder: T) {
        synchronized(lock) { live.remove(forwarder) }
    }

    /** Called for every chunk; a single volatile write keeps it cheap. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    fun snapshot(): List<T> = synchronized(lock) { live.toList() }

    fun activity(): PortForwardActivity = synchronized(lock) {
        PortForwardActivity(live.size, total, lastActivity.takeIf { it > 0 })
    }
}
