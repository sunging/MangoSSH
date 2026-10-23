package website.sung.mangossh.presentation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind
import website.sung.mangossh.ui.theme.MangoSshTheme
import java.io.File
import java.util.Locale

/** Synthetic chrome and gestures only; no vault, preferences, Keystore, pickers or network operations. */
class RemoteBrowserChromeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = RemoteBrowserUiState(
        "synthetic-session", "Synthetic server with a deliberately long title", "/fixture",
        homePath = "/home/synthetic", isLoading = false,
        entries = listOf(RemoteFileEntry("synthetic.txt", "/fixture/synthetic.txt", RemoteFileKind.FILE)),
    )
    private val actions = listOf(R.string.ui_home_directory, R.string.ui_upload_to_this_directory, R.string.fm_tasks)
    private val captureRequested get() = InstrumentationRegistry.getArguments().getString("captureBrowserChrome") == "true"
    private val screenshotResources by lazy {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }).resources
    }
    private fun label(id: Int) = (if (captureRequested) screenshotResources else context.resources).getString(id)
    private fun headerButton(id: Int) = compose.onNode(
        hasContentDescription(label(id)) and hasAnyAncestor(hasTestTag("remote-browser-toolbar")),
    )
    private fun more() = headerButton(R.string.fm_more).performClick()

    @Composable
    private fun Toolbar(value: RemoteBrowserUiState = state, busy: Boolean = false, onEvent: (String) -> Unit = {}) {
        RemoteBrowserToolbar(value, busy,
            onClose = { onEvent("close") }, onHome = { onEvent("home") },
            onUploadFile = { onEvent("file") }, onUploadFolder = { onEvent("folder") }, onTasks = { onEvent("tasks") },
            onAction = { onEvent(it.kind?.name ?: "platform") }, onSelectAll = { onEvent("all") })
    }

    @Composable
    private fun Browser(value: RemoteBrowserUiState, onRefresh: () -> Unit = {}) {
        RemoteFileBrowserScreen(value, {}, {}, {}, onRefresh, {}, { _, _, _ -> }, { _, _, _ -> },
            { _, _ -> }, { _, _ -> }, {}, {})
    }

    private fun pull() {
        compose.onNodeWithTag("remote-file-list").performTouchInput {
            swipeDown(startY = height * .12f, endY = height * .88f, durationMillis = 500)
        }
        compose.waitForIdle()
    }

    /** Optional screenshots contain only this synthetic browser and use an isolated cache directory. */
    private fun capture(name: String) {
        if (!captureRequested) return
        compose.waitForIdle()
        compose.onNodeWithTag("remote-file-browser").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // SurfaceFlinger also needs the real Activity window animation to finish;
        // settling Compose's virtual clock alone can still capture the launch frame.
        SystemClock.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "remote-browser-chrome")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun actionsOverflowFromRightToLeftAndReturnWhenWidthRecovers() {
        var width by mutableStateOf(352.dp)
        compose.setContent {
            // Fixed density makes every breakpoint fit the physical phone, including at double font.
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme { Box(Modifier.width(width)) { Toolbar() } }
            }
        }
        for ((available, count) in listOf(352 to 3, 304 to 2, 256 to 1, 208 to 0, 352 to 3)) {
            compose.runOnIdle { width = available.dp }
            actions.forEachIndexed { index, id ->
                if (index < count) headerButton(id).assertIsDisplayed() else headerButton(id).assertDoesNotExist()
            }
            val title = compose.onNodeWithTag("remote-browser-title").fetchSemanticsNode().boundsInRoot
            val back = headerButton(R.string.ui_close_the_file_browser).fetchSemanticsNode().boundsInRoot
            val moreBounds = headerButton(R.string.fm_more).fetchSemanticsNode().boundsInRoot
            assertTrue(title.width >= 96f)
            assertEquals(back.center.y, moreBounds.center.y, .5f)
            assertTrue(moreBounds.right <= available)
            more()
            headerButton(R.string.ui_parent_directory).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.ui_parent_directory)).assertDoesNotExist()
            actions.forEachIndexed { index, id ->
                if (id == R.string.ui_upload_to_this_directory) {
                    if (index >= count) {
                        compose.onNodeWithText(label(R.string.ui_upload_file)).assertIsDisplayed()
                        compose.onNodeWithText(label(R.string.ui_upload_folder)).assertIsDisplayed()
                    } else compose.onNodeWithText(label(R.string.ui_upload_file)).assertDoesNotExist()
                } else {
                    if (index >= count) compose.onNodeWithText(label(id)).assertIsDisplayed()
                    else compose.onNodeWithText(label(id)).assertDoesNotExist()
                }
            }
            compose.onNodeWithText(label(R.string.fm_select_all)).performClick()
        }
    }

    @Test fun visibleAndOverflowActionsDispatchTheSameCallbacks() {
        var width by mutableStateOf(416.dp)
        val events = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme { Box(Modifier.width(width)) { Toolbar(onEvent = { events += it }) } }
            }
        }
        headerButton(R.string.ui_home_directory).performClick()
        headerButton(R.string.fm_tasks).performClick()
        headerButton(R.string.ui_upload_to_this_directory).performClick()
        compose.onNodeWithText(label(R.string.ui_upload_file)).performClick()
        headerButton(R.string.ui_upload_to_this_directory).performClick()
        compose.onNodeWithText(label(R.string.ui_upload_folder)).performClick()
        compose.runOnIdle { width = 208.dp }
        for (id in listOf(R.string.ui_home_directory, R.string.fm_tasks, R.string.ui_upload_file, R.string.ui_upload_folder)) {
            more()
            compose.onNodeWithText(label(id)).performClick()
        }
        more()
        compose.onNodeWithText(label(R.string.fm_new_file)).performClick()
        more()
        compose.onNodeWithText(label(R.string.fm_new_directory)).performClick()
        more()
        compose.onNodeWithText(label(R.string.fm_select_all)).performClick()
        more()
        compose.onNodeWithText(label(R.string.fm_platform)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("home", "tasks", "file", "folder", "home", "tasks", "file", "folder",
                "CREATE_FILE", "CREATE_DIRECTORY", "all", "platform"), events)
        }
    }

    @Test fun disabledActionsStayDisabledAfterOverflowAndWhileConnecting() {
        var width by mutableStateOf(416.dp)
        var current by mutableStateOf(state.copy(path = "/", homePath = "/"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme { Box(Modifier.width(width)) { Toolbar(current, busy = true) } }
            }
        }
        headerButton(R.string.ui_home_directory).assertIsNotEnabled()
        headerButton(R.string.ui_parent_directory).assertDoesNotExist()
        compose.runOnIdle { width = 208.dp }
        more()
        compose.onNodeWithText(label(R.string.ui_home_directory)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.ui_parent_directory)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.fm_platform)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.fm_select_all)).performClick()
        compose.runOnIdle { current = current.copy(isConnecting = true) }
        headerButton(R.string.fm_more).assertIsNotEnabled()
        headerButton(R.string.ui_close_the_file_browser).assertIsEnabled()
    }

    @Test fun headerHasOneTitleAndNoPathOrRefreshButton() {
        val initial = if (captureRequested) state.copy(title = "Demo server", entries = listOf(
            RemoteFileEntry("documents", "/fixture/documents", RemoteFileKind.DIRECTORY),
            RemoteFileEntry("downloads", "/fixture/downloads", RemoteFileKind.DIRECTORY),
            RemoteFileEntry("notes.txt", "/fixture/notes.txt", RemoteFileKind.FILE, sizeBytes = 2048),
            RemoteFileEntry("example.png", "/fixture/example.png", RemoteFileKind.FILE, sizeBytes = 65536),
        )) else state
        var current by mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalResources provides (if (captureRequested) screenshotResources else LocalResources.current)) {
                MangoSshTheme { Browser(current) { current = current.copy(isLoading = true) } }
            }
        }
        compose.onNodeWithTag("remote-browser-title").assertTextEquals(initial.title)
        compose.onNodeWithText(state.path).assertDoesNotExist()
        compose.onNodeWithText("fixture").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.common_refresh)).assertDoesNotExist()
        capture("remote-browser-toolbar.png")
        more()
        compose.onNodeWithText(label(R.string.fm_new_file)).assertIsDisplayed()
        capture("remote-browser-more.png")
        compose.onNodeWithText(label(R.string.fm_select_all)).performClick()
        pull()
        compose.runOnIdle { assertTrue(current.isLoading) }
        capture("remote-browser-refreshing.png")
    }

    @Test fun shortEmptyAndFailedDirectoriesCanRefreshWithoutDuplicateRequests() {
        var current by mutableStateOf(state)
        var refreshes = 0
        compose.setContent { MaterialTheme { Browser(current) { refreshes++; current = current.copy(isLoading = true) } } }
        pull()
        compose.runOnIdle { assertEquals(1, refreshes) }
        pull()
        compose.runOnIdle { assertEquals(1, refreshes); current = state.copy(entries = emptyList()) }
        compose.onNodeWithText(label(R.string.ui_this_directory_is_empty)).assertIsDisplayed()
        pull()
        compose.runOnIdle { assertEquals(2, refreshes); current = state.copy(errorMessage = uiText(R.string.common_retry)) }
        pull()
        compose.runOnIdle { assertEquals(3, refreshes); current = state }
        pull()
        compose.runOnIdle { assertEquals(4, refreshes) }
    }

    @Test fun scrollingInTheMiddleDoesNotRefreshUntilTheListReachesTheTop() {
        var refreshes = 0
        val entries = List(120) { RemoteFileEntry("synthetic-$it.txt", "/fixture/$it", RemoteFileKind.FILE) }
        compose.setContent { MaterialTheme { Browser(state.copy(entries = entries)) { refreshes++ } } }
        compose.onNodeWithTag("remote-file-list").performScrollToIndex(60)
        pull()
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("remote-file-list").performScrollToIndex(0)
        pull()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun accessibilityRefreshAndRetryUseTheExistingRefreshCallback() {
        var refreshes = 0
        var current by mutableStateOf(state)
        compose.setContent { MaterialTheme { Browser(current) { refreshes++ } } }
        val refreshAction = compose.onNodeWithTag("remote-file-list").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals(label(R.string.common_refresh), refreshAction.label)
        compose.runOnIdle { assertTrue(refreshAction.action()) }
        compose.runOnIdle { assertEquals(1, refreshes); current = state.copy(errorMessage = uiText(R.string.fm_failed)) }
        compose.onNodeWithText(label(R.string.common_retry)).performClick()
        compose.runOnIdle { assertEquals(2, refreshes); current = state.copy(isLoading = true) }
        assertTrue(compose.onNodeWithTag("remote-file-list").fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        pull()
        compose.runOnIdle { assertEquals(2, refreshes); current = state.copy(isConnecting = true) }
        compose.onNodeWithTag("remote-file-refresh").assertDoesNotExist()
        compose.onNodeWithTag("remote-file-browser").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(2, refreshes) }
    }
}
