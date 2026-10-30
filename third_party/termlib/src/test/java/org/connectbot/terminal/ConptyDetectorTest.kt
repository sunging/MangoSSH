/*
 * ConnectBot Terminal
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
// Added by MangoSSH in 2026 to cover Windows ConPTY detection.
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ConptyDetectorTest {

    @Test
    fun `detects the session preamble windows openssh sends`() {
        val detector = ConptyDetector()
        detector.feed("\u001B[?9001h\u001B[?1004h\u001B[?25l\u001B[2J\u001B[m\u001B[H")
        assertTrue(detector.detected)
    }

    @Test
    fun `detects an announcement split across reads`() {
        val detector = ConptyDetector()
        detector.feed("prompt\u001B[?90")
        assertFalse(detector.detected)
        detector.feed("0")
        detector.feed("1h")
        assertTrue(detector.detected)
    }

    @Test
    fun `an escape inside a partial match restarts it`() {
        val detector = ConptyDetector()
        detector.feed("\u001B[?\u001B[?9001h")
        assertTrue(detector.detected)
    }

    @Test
    fun `ignores other modes that share a prefix`() {
        val detector = ConptyDetector()
        detector.feed("\u001B[?9001l\u001B[?900h\u001B[?90011h\u001B[9001h\u001B[?1049h")
        assertFalse(detector.detected)
    }

    @Test
    fun `stays detected once seen`() {
        val detector = ConptyDetector()
        detector.feed("\u001B[?9001h")
        detector.feed("\u001B[?9001l plain output")
        assertTrue(detector.detected)
    }

    @Test
    fun `scans only the requested slice of an array`() {
        val bytes = "xx\u001B[?9001hyy".toByteArray()
        val outside = ConptyDetector()
        outside.observe(bytes, offset = 3, length = bytes.size - 3)
        assertFalse(outside.detected)

        val inside = ConptyDetector()
        inside.observe(bytes, offset = 2, length = 8)
        assertTrue(inside.detected)
    }

    @Test
    fun `scans a byte buffer from its position without consuming it`() {
        val buffer = ByteBuffer.wrap("skip\u001B[?9001h".toByteArray())
        buffer.position(4)
        val detector = ConptyDetector()
        detector.observe(buffer, length = 8)
        assertTrue(detector.detected)
        assertEquals(4, buffer.position())
    }

    private fun ConptyDetector.feed(text: String) {
        val bytes = text.toByteArray()
        observe(bytes, 0, bytes.size)
    }
}
