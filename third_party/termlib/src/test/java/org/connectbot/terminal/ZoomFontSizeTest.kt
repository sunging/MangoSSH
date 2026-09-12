/*
 * ConnectBot Terminal
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
// Added by MangoSSH in 2026 to cover persistent pinch-to-zoom font size commits.
package org.connectbot.terminal

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [commitZoomFontSize], the pure function behind persistent pinch-to-zoom. */
class ZoomFontSizeTest {

    private val minFontSize = 8.sp
    private val maxFontSize = 24.sp

    @Test
    fun `rounds to the nearest whole sp`() {
        // 12sp * 1.24 = 14.88sp, rounds up to 15sp rather than truncating to 14sp.
        val result = commitZoomFontSize(12.sp, 1.24f, minFontSize, maxFontSize)
        assertEquals(15.sp, result)
    }

    @Test
    fun `a scale of exactly one is a no-op`() {
        val result = commitZoomFontSize(12.sp, 1f, minFontSize, maxFontSize)
        assertEquals(12.sp, result)
    }

    @Test
    fun `clamps to the maximum font size`() {
        val result = commitZoomFontSize(20.sp, 3f, minFontSize, maxFontSize)
        assertEquals(maxFontSize, result)
    }

    @Test
    fun `clamps to the minimum font size`() {
        val result = commitZoomFontSize(10.sp, 0.1f, minFontSize, maxFontSize)
        assertEquals(minFontSize, result)
    }

    @Test
    fun `repeated small gestures converge instead of drifting`() {
        // Ten consecutive 1 percent pinches should settle on a whole-sp value, not
        // wander off by an accumulated fractional remainder.
        var size = 12.sp
        repeat(10) {
            size = commitZoomFontSize(size, 1.01f, minFontSize, maxFontSize)
        }
        assertEquals(size.value, size.value.toInt().toFloat(), 0f)
    }

    @Test
    fun `never exceeds bounds across many consecutive gestures`() {
        var size = 12.sp
        repeat(50) {
            size = commitZoomFontSize(size, 1.5f, minFontSize, maxFontSize)
        }
        assertEquals(maxFontSize, size)

        repeat(50) {
            size = commitZoomFontSize(size, 0.5f, minFontSize, maxFontSize)
        }
        assertEquals(minFontSize, size)
    }
}
