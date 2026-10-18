package website.sung.mangossh.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.session.*

/** Synthetic UI data only. Tests do not open the vault, connect hosts, or change preferences. */
class ServerResourceDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val snapshot = ServerResourceSnapshot(ServerResourcePlatform.WINDOWS, "Example OS", 12345, 8,
        42.0, false, null, ResourceCapacity(4096, 3072, 1024), null,
        listOf(ResourceDisk("Example drive with a long name", ResourceCapacity(8192, 4096, 4096))),
        listOf(ResourceNetwork("Example network interface with a long name", 1024.0, 2048.0, false)), 1000)

    @Test fun narrowLargeFontsKeepActionsVisibleAndCardsScrollable() {
        mount(widthDp = 360f, fontScale = 2f)
        compose.onNodeWithTag("resource_columns_1").assertExists()
        compose.onNodeWithTag("resource_refresh").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("resource_close").assertIsDisplayed()
        capture("narrow-large-font")
        compose.onNodeWithText("Example network interface with a long name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("resource_close").assertIsDisplayed()
        capture("narrow-network")
    }

    @Test fun wideScreenUsesTwoColumns() {
        mount(widthDp = 960f)
        compose.onNodeWithTag("resource_columns_2").assertExists()
        compose.onNodeWithTag("resource_close").assertIsDisplayed()
        val system = compose.onNodeWithTag("resource_system").fetchSemanticsNode().boundsInRoot
        val memory = compose.onNodeWithTag("resource_memory").fetchSemanticsNode().boundsInRoot
        val disks = compose.onNodeWithTag("resource_disks").fetchSemanticsNode().boundsInRoot
        val network = compose.onNodeWithTag("resource_network").fetchSemanticsNode().boundsInRoot
        assertEquals(system.top, disks.top, 1f)
        assertTrue(memory.top - system.bottom in 1f..20f)
        assertEquals(memory.top - system.bottom, network.top - disks.bottom, 1f)
        capture("wide")
    }

    @Test fun narrowRefreshKeepsScrolledContentAndActionsInPlace() = assertRefreshDoesNotMoveContent(360f)

    @Test fun wideRefreshKeepsContentAndActionsInPlace() = assertRefreshDoesNotMoveContent(960f)

    @Test fun virtualFilterPreservesUnknownInterfacesAndChoiceAcrossRefresh() {
        val networks = listOf(ResourceNetwork("Physical example", 1.0, 2.0, false),
            ResourceNetwork("Virtual example", 1.0, 2.0, true), ResourceNetwork("Unknown example", 1.0, 2.0))
        var state by mutableStateOf(ServerResourceState(snapshot.copy(networks = networks)))
        mount(stateProvider = { state })
        compose.onNodeWithText("Physical example").assertExists()
        compose.onNodeWithText("Unknown example").assertExists()
        compose.onNodeWithText("Virtual example").assertDoesNotExist()
        compose.onNodeWithTag("resource_virtual_toggle").performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithText("Virtual example").assertExists()
        compose.runOnIdle { state = state.copy(isRefreshing = true) }
        compose.onNodeWithTag("resource_virtual_toggle").assertIsSelected()
        compose.runOnIdle { state = state.copy(isRefreshing = false, snapshot = state.snapshot!!.copy(collectedAtEpochMillis = 6000)) }
        compose.onNodeWithText("Virtual example").assertExists()
        compose.onNodeWithTag("resource_virtual_toggle").performScrollTo().performClick()
        compose.onNodeWithText("Virtual example").assertDoesNotExist()
        compose.onNodeWithText("Unknown example").assertExists()
    }

    @Test fun allVirtualInterfacesHaveAnExplicitHiddenState() {
        mount(state = ServerResourceState(snapshot.copy(networks = listOf(ResourceNetwork("Virtual example", null, null, true)))))
        compose.onNodeWithTag("resource_virtual_hidden").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("resource_virtual_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("resource_virtual_hidden").assertDoesNotExist()
        compose.onNodeWithText("Virtual example").assertExists()
    }

    @Test fun resourceTextSupportsLongPressSelectionAndCopyAcrossRefresh() {
        val clipboard = TestClipboard()
        var state by mutableStateOf(ServerResourceState(snapshot))
        mount(clipboard = clipboard, stateProvider = { state })
        val system = compose.onNodeWithText("Example OS")
        system.performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(width / 4f, 0f)) }
        compose.runOnIdle { state = state.copy(isRefreshing = true) }
        compose.runOnIdle { state = state.copy(isRefreshing = false, snapshot = snapshot.copy(cpuPercent = 43.0, collectedAtEpochMillis = 6000)) }
        system.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals("Example", clipboard.entry?.clipData?.getItemAt(0)?.text?.toString()) }
        compose.runOnIdle { clipboard.entry = null }
        val disk = compose.onNodeWithText("Example drive with a long name")
        disk.performScrollTo().performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f)) }
        disk.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals("Example", clipboard.entry?.clipData?.getItemAt(0)?.text?.toString()) }
    }

    @Test fun refreshingKeepsSnapshotAndDisablesDuplicateRequest() {
        mount(state = ServerResourceState(snapshot, isRefreshing = true))
        compose.onNodeWithTag("resource_loading").assertExists()
        compose.onNodeWithText("Example OS").assertExists()
        compose.onNodeWithTag("resource_refresh").assertIsNotEnabled()
    }

    @Test fun initialLoadAndFailureDoNotCreateFakeMetrics() {
        var state by mutableStateOf(ServerResourceState(isRefreshing = true))
        compose.setContent { MaterialTheme {
            ServerResourceDialog(state, "example", true, {}, {}, {})
        } }
        compose.onNodeWithTag("resource_loading").assertExists()
        compose.onNodeWithTag("resource_cpu").assertDoesNotExist()
        compose.runOnIdle { state = ServerResourceState(failure = ServerResourceFailure.TIMEOUT) }
        compose.onNodeWithTag("resource_error").assertExists()
        compose.onNodeWithTag("resource_cpu").assertDoesNotExist()
        compose.onNodeWithTag("resource_refresh").assertIsEnabled()
    }

    @Test fun partialFailureKeepsOtherSectionsAndFailedRefreshKeepsTimestamp() {
        mount(state = ServerResourceState(snapshot, failure = ServerResourceFailure.QUERY))
        compose.onNodeWithTag("resource_error").assertExists()
        compose.onNodeWithText("Example OS").assertExists()
        compose.onNodeWithTag("resource_swap").performScrollTo().assertExists()
        compose.onNodeWithTag("resource_disks").assertExists()
    }

    @Test fun backgroundSessionSwitchAndDisposalStopOnlyTheirOwnWatch() {
        val events = mutableListOf<Pair<String, Boolean>>()
        var session by mutableStateOf("first")
        var mounted by mutableStateOf(true)
        lateinit var owner: TestLifecycleOwner
        compose.runOnUiThread { owner = TestLifecycleOwner(); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (mounted) {
                    val id = session
                    ResourceMonitoringEffect(id, true) { events += id to it }
                }
            }
        }
        compose.runOnIdle { assertEquals(listOf("first" to true), events); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals("first" to false, events.last()); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { session = "second" }
        compose.runOnIdle {
            assertEquals(listOf("first" to false, "second" to true), events.takeLast(2))
            mounted = false
        }
        compose.runOnIdle { assertEquals("second" to false, events.last()) }
    }

    private fun assertRefreshDoesNotMoveContent(widthDp: Float) {
        var state by mutableStateOf(ServerResourceState(snapshot))
        mount(widthDp = widthDp, stateProvider = { state })
        compose.onNodeWithText("Example network interface with a long name").performScrollTo()
        val tags = listOf("server_resources_dialog", "resource_network", "resource_refresh", "resource_close")
        fun bounds() = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val before = bounds()
        repeat(2) {
            compose.runOnIdle { state = state.copy(isRefreshing = true) }
            compose.onNodeWithTag("resource_loading").assertExists()
            assertEquals(before, bounds())
            compose.runOnIdle { state = state.copy(isRefreshing = false, snapshot = snapshot.copy(cpuPercent = 43.0, collectedAtEpochMillis = 6000)) }
            compose.onNodeWithTag("resource_loading").assertDoesNotExist()
            assertEquals(before, bounds())
        }
    }

    private fun mount(widthDp: Float = 360f, fontScale: Float = 1f, state: ServerResourceState = ServerResourceState(snapshot),
        stateProvider: () -> ServerResourceState = { state }, clipboard: Clipboard? = null) {
        compose.setContent {
            val windowWidth = LocalWindowInfo.current.containerDpSize.width
            val pixels = with(LocalDensity.current) { windowWidth.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(pixels / widthDp, fontScale),
                LocalClipboard provides (clipboard ?: LocalClipboard.current)) {
                MaterialTheme { ServerResourceDialog(stateProvider(), "example", true, {}, {}, {}) }
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** No reads or writes reach the device's personal clipboard. */
    private class TestClipboard : Clipboard {
        var entry: ClipEntry? = null
        override suspend fun getClipEntry() = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }

    private fun capture(name: String) {
        // Draw only this synthetic dialog, including on API 26/27 where PixelCopy is unavailable.
        val view = (compose.onNode(isDialog()).fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.externalCacheDir, "server-resource-test-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
