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
// Added by MangoSSH in 2026 to keep Windows ConPTY history intact across resizes.
package org.connectbot.terminal

import java.nio.ByteBuffer

/**
 * Recognizes a Windows ConPTY peer from the output stream.
 *
 * ConPTY (Windows OpenSSH, and anything else hosted by conhost) owns its own
 * screen model. After every window-size change it repaints the whole viewport
 * from the home position, and when the window grows it keeps the viewport top
 * fixed and extends it downward. A terminal that refills the top of a taller
 * screen from scrollback therefore has those restored lines overwritten by the
 * repaint, and since they already left scrollback, they are lost for good.
 *
 * ConPTY announces itself by enabling win32-input-mode (`ESC [ ? 9001 h`) as
 * the first output of a session; no other host sends that mode. Detection is
 * sticky for the emulator's lifetime: a session that started under ConPTY keeps
 * ConPTY in the path even when the user connects onward from it.
 *
 * [observe] is called from the transport thread only; [detected] may be read
 * from any thread.
 */
internal class ConptyDetector {
    /** True once the ConPTY announcement has been seen in this stream. */
    @Volatile
    var detected = false
        private set

    // Length of the announcement prefix matched at the end of the bytes seen so far.
    private var matched = 0

    /** Scans [length] bytes of [data] starting at [offset]. */
    fun observe(data: ByteArray, offset: Int, length: Int) {
        if (detected) return
        for (i in offset until offset + length) {
            if (advance(data[i])) return
        }
    }

    /** Scans the next [length] bytes of [buffer] without moving its position. */
    fun observe(buffer: ByteBuffer, length: Int) {
        if (detected) return
        val start = buffer.position()
        for (i in start until start + length) {
            if (advance(buffer.get(i))) return
        }
    }

    private fun advance(byte: Byte): Boolean {
        matched = when (byte) {
            ANNOUNCEMENT[matched] -> matched + 1
            // ESC appears only at the start of the announcement, so a mismatched
            // ESC can only begin a new match, never continue an older one.
            ESC -> 1
            else -> 0
        }
        if (matched == ANNOUNCEMENT.size) {
            detected = true
            return true
        }
        return false
    }

    private companion object {
        const val ESC: Byte = 0x1B
        val ANNOUNCEMENT = "\u001B[?9001h".toByteArray(Charsets.US_ASCII)
    }
}
