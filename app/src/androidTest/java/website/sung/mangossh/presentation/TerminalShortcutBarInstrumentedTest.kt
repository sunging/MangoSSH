package website.sung.mangossh.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutItem

/** Real pointer tests protect against accidental terminal input while scrolling or leaving a hold. */
class TerminalShortcutBarInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private var config by mutableStateOf(TerminalShortcutConfig.defaults())
    private var enabled by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var session by mutableStateOf(0)
    private var dark by mutableStateOf(false)
    private var size by mutableStateOf(DpSize(320.dp, 480.dp))
    private var fontScale by mutableStateOf(1f)
    private var locale by mutableStateOf("en")
    private val modifiers = TerminalModifierState()
    private val output = mutableListOf<Pair<Int, Int>>()
    private val actions = mutableListOf<String>()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var longPressTimeout = 0L

    @Test
    fun textMacroAccessibilityUsesOnlyTheUserLabel() {
        config = TerminalShortcutConfig(listOf(
            TerminalShortcutItem("default-macro", TerminalShortcutAction.Text("example text\n"), "My macro"),
        ))
        mount()
        assertEquals(listOf("My macro"), button("macro").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun defaultKeyboardFitsSixColumnsWithoutHolesAndPasteHasNoText() {
        mount()
        bar().assertHeightIsEqualTo(102.dp)
        val top = listOf("paste", "modifier-ctrl", "modifier-alt", "up", "modifier-shift", "page-up")
        val bottom = listOf("escape", "tab", "left", "down", "right", "page-down")
        for (width in listOf(320.dp, 360.dp, 412.dp, 448.dp)) {
            composeRule.runOnIdle { size = size.copy(width = width) }
            val viewport = bar().getUnclippedBoundsInRoot()
            val bounds = top.zip(bottom).map { (above, below) ->
                val a = button(above).assertIsDisplayed().assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
                val b = button(below).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(a.center.x, b.center.x, 1f)
                assertTrue(a.bottom < b.top)
                assertTrue(button(above).getUnclippedBoundsInRoot().right <= viewport.right)
                a to b
            }
            bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.first.right < b.first.left) }
            assertEquals(bounds[0].second.top, bounds[5].second.top, 1f)
            val fullColumns = (top + listOf("ctrl-c", "ctrl-d", "ctrl-l", "ctrl-z")).count {
                val keyBounds = button(it).getUnclippedBoundsInRoot()
                keyBounds.left >= viewport.left && keyBounds.right <= viewport.right
            }
            assertTrue("Expected six or seven complete columns at $width, got $fullColumns", fullColumns in 6..7)
        }
        assertTrue(button("paste").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty())
        assertEquals(listOf("Paste"), button("paste").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        saveBarImage("terminal-shortcuts-default.png")
        button("ctrl-z").performScrollTo().assertIsDisplayed()
        button("slash").assertIsDisplayed()
    }

    @Test
    fun modifierColorsMatchOrdinaryKeysUntilSelectedAndAllDisableTogether() {
        mount()
        for (darkMode in listOf(false, true)) {
            composeRule.runOnIdle { dark = darkMode }
            for (id in listOf("modifier-ctrl", "modifier-alt", "modifier-shift")) {
                button(id).assertIsNotSelected()
                assertSameBackground("tab", id)
                val normal = backgroundOf("tab")
                button(id).performClick().assertIsSelected()
                assertNotEquals(normal, backgroundOf(id))
                button(id).performClick().assertIsNotSelected()
            }
        }
        composeRule.runOnIdle { enabled = false }
        for (id in listOf("modifier-ctrl", "modifier-alt", "modifier-shift", "tab", "up", "paste")) {
            button(id).assertIsNotEnabled()
            assertSameBackground("tab", id)
        }
        button("up").performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(output.isEmpty()) }
    }

    @Test
    fun labelsLocalesFontScaleAndScreenSizesPreserveAlignmentAndReachability() {
        mount()
        composeRule.runOnIdle {
            config = config.copy(items = config.items.map { if (it.id == "default-up") it.copy(labelOverride = "Move up") else it })
        }
        assertEquals(button("up").fetchSemanticsNode().boundsInRoot.center.x, button("down").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        for (dimensions in listOf(DpSize(640.dp, 320.dp), DpSize(800.dp, 600.dp), DpSize(320.dp, 240.dp))) {
            composeRule.runOnIdle { size = dimensions; fontScale = 2f; locale = "zh-CN" }
            button("page-down").performScrollTo().assertIsDisplayed()
            val up = button("up").fetchSemanticsNode().boundsInRoot
            val down = button("down").fetchSemanticsNode().boundsInRoot
            assertEquals(up.center.x, down.center.x, 1f)
            assertTrue(up.bottom < down.top)
            button("slash").performScrollTo().assertIsDisplayed()
        }
        composeRule.runOnIdle { config = config.copy(rowCount = 1) }
        button("slash").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { config = config.copy(items = emptyList()) }
        bar().assertDoesNotExist()
    }

    @Test
    fun shortTapAndSemanticClickSendOnceAndPageKeysDoNotRepeat() {
        mount()
        button("up").performTouchInput { click() }
        button("down").performClick()
        composeRule.runOnIdle { assertEquals(2, output.size) }
        freezeClock()
        button("page-up").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 500)
        composeRule.runOnIdle { assertEquals(2, output.size) }
        button("page-up").performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(3, output.size) }
    }

    @Test
    fun hardwareActivationSendsOnceAndDoesNotStartTouchRepeat() {
        mount()
        button("up").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        button("up").performKeyInput {
            keyDown(Key.Enter)
            advanceEventTime(longPressTimeout + 240)
            keyUp(Key.Enter)
        }
        composeRule.runOnIdle { assertEquals(1, output.size) }
    }

    @Test
    fun longPressRepeatsAtIntervalKeepsModifiersAndDoesNotSendOnRelease() {
        mount()
        button("modifier-ctrl").performClick()
        freezeClock()
        button("up").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(longPressTimeout - 32)
        composeRule.runOnIdle { assertTrue(output.isEmpty()) }
        composeRule.mainClock.advanceTimeBy(32 + 240)
        composeRule.runOnIdle {
            assertEquals(4, output.size)
            assertTrue(output.all { it.first == 4 })
            assertTrue(modifiers.activeModifiers.isEmpty())
        }
        button("up").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.runOnIdle { assertEquals(4, output.size) }
        button("up").performClick()
        composeRule.runOnIdle { assertEquals(0, output.last().first) }
    }

    @Test
    fun horizontalSwipeAndMovingOutCancelWithoutSendingInput() {
        mount()
        freezeClock()
        button("up").performTouchInput {
            down(center)
            moveBy(Offset(-100f, 0f), delayMillis = 48)
            up()
        }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 240)
        composeRule.runOnIdle { assertTrue(output.isEmpty()) }
        button("up").performTouchInput {
            down(center)
            moveTo(Offset(center.x, -1f), delayMillis = 48)
        }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 240)
        button("up").performTouchInput { up() }
        composeRule.runOnIdle { assertTrue(output.isEmpty()) }
    }

    @Test
    fun draggingAfterRepeatStartsStopsFurtherKeys() {
        mount()
        freezeClock()
        button("up").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 160)
        val before = composeRule.runOnIdle { output.size.also { assertTrue(it > 0) } }
        button("up").performTouchInput { moveBy(Offset(-100f, 0f), delayMillis = 16) }
        composeRule.mainClock.advanceTimeBy(320)
        button("up").performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(before, output.size) }
    }

    @Test
    fun anotherPointerCancelsHoldEvenWhenItTouchesADifferentKey() {
        mount()
        freezeClock()
        val up = button("up").fetchSemanticsNode().boundsInRoot.center
        val ctrl = button("modifier-ctrl").fetchSemanticsNode().boundsInRoot.center
        val origin = bar().fetchSemanticsNode().boundsInRoot.topLeft
        bar().performTouchInput { down(0, up - origin) }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 160)
        val before = composeRule.runOnIdle { output.size.also { assertTrue(it > 0) } }
        bar().performTouchInput { down(1, ctrl - origin) }
        composeRule.mainClock.advanceTimeBy(320)
        bar().performTouchInput { up(1); up(0) }
        composeRule.runOnIdle { assertEquals(before, output.size) }
    }

    @Test fun disablingSessionCancelsActiveHold() = assertHoldCancelled { enabled = false }
    @Test fun switchingSessionCancelsActiveHold() = assertHoldCancelled { session++ }
    @Test fun removingBarCancelsActiveHold() = assertHoldCancelled { mounted = false }
    @Test fun backgroundingCancelsActiveHold() = assertHoldCancelled { owner.registry.currentState = Lifecycle.State.STARTED }

    private fun assertHoldCancelled(change: () -> Unit) {
        mount()
        freezeClock()
        button("up").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(longPressTimeout + 160)
        val before = composeRule.runOnIdle { output.size.also { assertTrue(it > 0) }; change(); output.size }
        composeRule.mainClock.advanceTimeBy(320)
        composeRule.runOnIdle { assertEquals(before, output.size) }
    }

    private fun mount() {
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.Locales(LocaleList(locale)),
            ) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.fillMaxSize()) {
                            if (mounted) key(session) {
                                TerminalShortcutBar(
                                    config = config,
                                    enabled = enabled,
                                    activeModifiers = modifiers.activeModifiers,
                                    onAction = { actions += it.id; dispatch(it) },
                                    onStartRepeat = { item ->
                                        val captured = item.copy(action = captureTerminalShortcutRepeat(item.action, modifiers))
                                        val repeat: () -> Unit = { dispatch(captured) }
                                        repeat
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dispatch(item: TerminalShortcutItem) = dispatchTerminalShortcut(
        item.action, modifiers,
        dispatchKey = { mask, key -> output += mask to key },
        dispatchCharacter = { mask, character -> output += mask to character },
        onPaste = {},
    )

    private fun freezeClock() { composeRule.mainClock.autoAdvance = false }
    private fun bar() = composeRule.onNodeWithTag("terminal_shortcut_bar")
    private fun button(id: String) = composeRule.onNodeWithTag("terminal_shortcut_button_default-$id")
    private fun backgroundOf(id: String) = button(id).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 5] }

    private fun assertSameBackground(first: String, second: String) {
        // Pixel captures include platform click feedback, which may outlive Compose clock advances.
        // Require exact resting colors, while allowing the feedback to finish in real time.
        composeRule.waitUntil(timeoutMillis = 2_000) { backgroundOf(first) == backgroundOf(second) }
    }

    private fun saveBarImage(name: String) {
        val bitmap = bar().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
