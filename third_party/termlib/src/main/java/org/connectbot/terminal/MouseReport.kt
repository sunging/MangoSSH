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
package org.connectbot.terminal

/**
 * Encodes pointer input as terminal mouse-report escape sequences.
 *
 * libvterm exposes only *whether* the remote program is tracking the mouse
 * (`VTERM_PROP_MOUSE`), not which encoding it negotiated (X10 vs. SGR/1006), so
 * this uses the original X10 byte encoding: `ESC [ M` followed by three bytes
 * `(32 + button)`, `(32 + column + 1)`, `(32 + row + 1)`. tmux, vim, and less all
 * accept that encoding regardless of the extended modes they also enable, which
 * makes it the safe common denominator for a touch swipe.
 *
 * The single-byte fields cap coordinates at 223 (`255 - 32`), so both row and
 * column are clamped; a swipe near the bottom-right of a very wide terminal is
 * reported slightly off but never corrupts the stream.
 */
internal object MouseReport {
    private const val ESC = 0x1B.toByte()

    /** X10 wheel button codes: 64 = wheel up, 65 = wheel down. */
    private const val WHEEL_UP = 64
    private const val WHEEL_DOWN = 65

    /** Largest value the `(32 + n + 1)` coordinate byte can hold without overflow. */
    private const val MAX_COORD = 222

    /**
     * Build one wheel-tick report for the given cell position.
     *
     * @param scrollUp true for a wheel-up tick (content moves toward older lines)
     * @param row zero-based terminal row under the pointer
     * @param col zero-based terminal column under the pointer
     */
    fun wheel(scrollUp: Boolean, row: Int, col: Int): ByteArray {
        val button = if (scrollUp) WHEEL_UP else WHEEL_DOWN
        val clampedCol = col.coerceIn(0, MAX_COORD)
        val clampedRow = row.coerceIn(0, MAX_COORD)
        return byteArrayOf(
            ESC,
            '['.code.toByte(),
            'M'.code.toByte(),
            (32 + button).toByte(),
            (32 + clampedCol + 1).toByte(),
            (32 + clampedRow + 1).toByte(),
        )
    }
}
