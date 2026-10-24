package website.sung.mangossh.session

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.IntentCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.presentation.DownloadDirectoryContract
import website.sung.mangossh.presentation.FileTransfersSheet
import website.sung.mangossh.R
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/**
 * Real API 26+ picker and SAF regression. Shell setup creates only a fresh UUID
 * folder; downloads use only the tree URI granted by the picker. No saved
 * profiles, preferences, credentials or pre-existing documents are modified.
 * Completed tasks launch the real product browser, verify the actual directory,
 * then cancel and reopen it before selecting a synthetic file. Only the final
 * file viewer is intercepted; the folder browser is never replaced by the test.
 */
@SdkSuppress(minSdkVersion = 26)
class DirectoryDownloadInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun internalStorageDestinationSupportsPreviewCancellationAndNestedDownloads() = download(false)

    @Test fun documentsDestinationSupportsPreviewCancellationAndNestedDownloads() = download(true)

    @Test fun downloadDestinationOpensTheDownloadedSubfolder() = download(false, inDownloads = true)

    private fun download(inDocuments: Boolean, inDownloads: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull("Required isolated fixture", port)
        assumeTrue(port != null)
        val name = "mangossh-directory-test-${UUID.randomUUID()}"
        val parent = when { inDocuments -> "Documents"; inDownloads -> "Download"; else -> null }
        val location = when { inDocuments -> "documents"; inDownloads -> "download"; else -> "internal-storage" }
        val relative = parent?.let { "$it/$name" } ?: name
        val shellPath = "/sdcard/$relative"
        var createdParent = false
        var ownsDirectory = false
        var verifiedDestination = false
        var destination: Uri? = null
        val selected = AtomicReference<Uri?>()
        val launched = AtomicReference<Intent?>()
        val completed = mutableStateOf<ScpTransferState?>(null)
        val pickerInitial = AtomicReference(parent?.let { DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:$it") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connection = SshConnection(args.getString("fixtureHost") ?: "127.0.0.1", port!!)
        val originalFlags = automation.serviceInfo.flags
        try {
            if (parent != null) {
                if (shell("ls -d /sdcard/$parent") != "/sdcard/$parent") {
                    shell("mkdir /sdcard/$parent")
                    assertTrue("Create the missing destination parent", shell("ls -d /sdcard/$parent") == "/sdcard/$parent")
                    createdParent = true
                }
            }
            assertFalse("The test destination must not already exist", shell("ls -d $shellPath") == shellPath)
            shell("mkdir $shellPath")
            assertTrue("Create the isolated test destination", shell("ls -d $shellPath") == shellPath)
            ownsDirectory = true
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            compose.setContent { MaterialTheme {
                val picker = rememberLauncherForActivityResult(DownloadDirectoryContract()) { selected.set(it) }
                Button(onClick = { picker.launch(pickerInitial.get()) }) {
                    Text("Choose isolated destination")
                }
                completed.value?.let { transfer ->
                    val activity = LocalContext.current
                    val viewerContext = remember(activity) { object : ContextWrapper(activity) {
                        override fun startActivity(intent: Intent) {
                            launched.set(intent)
                        }
                    } }
                    CompositionLocalProvider(LocalContext provides viewerContext) {
                        FileTransfersSheet(listOf(transfer), {}, {}, {}, {}, {}, {}, {})
                    }
                }
            } }
            compose.onNodeWithText("Choose isolated destination").performClick()
            clickPickerEntry(name)
            // Touch injection can return before an older DocumentsUI publishes
            // its new current directory. Do not confirm the preceding parent.
            awaitPickerAction(scroll = false) { node -> node.text?.toString() == name && isInToolbar(node) }
            // Only this empty test-owned folder is visible in optional screenshots.
            if (args.getString("captureDirectoryPicker") == "true") {
                capturePicker("$location.png")
            }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (selected.get() == null && SystemClock.uptimeMillis() < deadline) {
                val root = automation.rootInActiveWindow
                if (root != null) try {
                    if (root.packageName?.toString()?.endsWith(".documentsui") == true) {
                        visit(root) { node -> node.viewIdResourceName == "android:id/button1" && click(node) }
                    }
                } finally { root.recycle() }
                SystemClock.sleep(150)
            }
            val granted = checkNotNull(selected.get()) { "System picker did not return a tree grant" }
            destination = granted
            assertEquals("com.android.externalstorage.documents", granted.authority)
            // Document IDs are opaque, including on OEM providers. The unique
            // fresh folder name establishes ownership without assuming ID syntax.
            val grantedName = LocalDocumentTree.queryDocument(context.contentResolver, LocalDocumentTree.rootOf(granted),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertTrue("The grant must identify only the test-owned UUID folder", grantedName == name)
            verifiedDestination = true
            runBlocking {
                connection.connect(10_000) { _, _ -> true }
                assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
                val files = RemoteFileClient()
                val remote = RemoteFilePaths.join(files.resolveHome(connection), UUID.randomUUID().toString())
                files.mkdirIfMissing(connection, remote)
                files.mkdirIfMissing(connection, "$remote/nested")
                files.mkdirIfMissing(connection, "$remote/empty-folder")
                val bytes = ByteArray(80_000) { (it % 251).toByte() }
                BlockingOperation().use { control ->
                    files.upload(connection, bytes.inputStream(), "$remote/nested", "data.PDF", 0, bytes.size.toLong(),
                        bytes.size.toLong(), control) { _, _ -> }
                    files.upload(connection, "small".byteInputStream(), remote, "small", 0, 5, 1024, control) { _, _ -> }
                }
                val tree = checkNotNull(destination)
                val root = LocalDocumentTree.rootOf(tree)
                val manager = FileTransferManager(context, scope, files, { connection }, {})
                withTimeout(60_000) {
                    manager.downloadDirectory("fixture", remote, tree)
                    val cancelledPreview = manager.conflicts.first { it.isNotEmpty() }.single()
                    assertEquals(TransferPromptKind.DIRECTORY_PREVIEW, cancelledPreview.promptKind)
                    assertTrue(LocalDocumentTree.listChildren(context.contentResolver, tree, root).isEmpty())
                    manager.resolveConflict(cancelledPreview.id, TransferConflictDecision(TransferConflictAction.CANCEL))
                    val cancelled = manager.transfers.first { it.single().isFinished }.single()
                    assertEquals(ScpTransferPhase.CANCELLED, cancelled.phase)
                    assertTrue(LocalDocumentTree.listChildren(context.contentResolver, tree, root).isEmpty())
                    manager.retry(cancelled.id)
                    val preview = manager.conflicts.first { it.isNotEmpty() }.single()
                    assertEquals(2, preview.totalFiles)
                    assertEquals(2, preview.totalDirectories)
                    assertEquals(80_005L, preview.totalBytes)
                    assertTrue(LocalDocumentTree.listChildren(context.contentResolver, tree, root).isEmpty())
                    manager.resolveConflict(preview.id, TransferConflictDecision(TransferConflictAction.PROCEED, verifySha256 = true))
                    assertEquals(ScpTransferPhase.COMPLETED, manager.transfers.first { it.single().isFinished }.single().phase)
                }
                val downloaded = LocalDocumentTree.listChildren(context.contentResolver, tree, root).single()
                val finished = manager.transfers.value.single()
                assertEquals(downloaded.documentUri.toString(), finished.localUri)
                val children = LocalDocumentTree.listChildren(context.contentResolver, tree, downloaded.documentUri)
                assertTrue("Downloaded folder contains exactly the fixture entries",
                    children.map { it.name }.toSet() == setOf("small", "nested", "empty-folder"))
                assertEquals("small", context.contentResolver.openInputStream(children.single { it.name == "small" }.documentUri)!!.use { it.reader().readText() })
                val nested = children.single { it.name == "nested" }.documentUri
                val data = LocalDocumentTree.listChildren(context.contentResolver, tree, nested).single()
                assertEquals("data.PDF", data.name)
                assertEquals("application/pdf", context.contentResolver.getType(data.documentUri))
                assertArrayEquals(bytes, context.contentResolver.openInputStream(data.documentUri)!!.use { it.readBytes() })
                assertTrue(LocalDocumentTree.listChildren(context.contentResolver, tree,
                    children.single { it.name == "empty-folder" }.documentUri).isEmpty())
                withContext(Dispatchers.Main) { completed.value = finished }
                compose.onNodeWithContentDescription(context.getString(R.string.common_open)).performClick()
                awaitOpenedDirectory(downloaded.name)
                if (args.getString("captureDirectoryDownloadResult") == "true") capturePicker("$location-result.png")
                returnToTest()
                assertNull("Cancelling browsing must not launch a file viewer", launched.get())
                compose.onNodeWithContentDescription(context.getString(R.string.common_open)).performClick()
                awaitOpenedDirectory(downloaded.name)
                clickPickerEntry("small")
                compose.waitUntil(15_000) { launched.get() != null }
                val chooser = checkNotNull(launched.get())
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val view = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals(DocumentsContract.getDocumentId(children.single { it.name == "small" }.documentUri),
                    DocumentsContract.getDocumentId(checkNotNull(view.data)))
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags)
                assertEquals(downloaded.documentUri.toString(), manager.transfers.value.single().localUri)
                assertEquals(ScpTransferPhase.COMPLETED, manager.transfers.value.single().phase)
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            connection.close()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            val tree = destination ?: selected.get()
            if (ownsDirectory) {
                if (verifiedDestination && tree != null) {
                    assertTrue("Remove only the test-owned UUID folder", DocumentsContract.deleteDocument(context.contentResolver, LocalDocumentTree.rootOf(tree)))
                } else {
                    // rmdir cannot remove contents; an unexpected non-empty folder is preserved.
                    shell("rmdir $shellPath")
                }
            }
            if (createdParent) shell("rmdir /sdcard/$parent")
        }
    }

    /** Verifies the location opened by the product itself, without relaunching or replacing its intent. */
    private fun awaitOpenedDirectory(name: String) {
        // Pump Compose's test clock while the provider lookup returns to the
        // sheet coroutine. Accessibility polling alone would leave it suspended.
        compose.waitUntil(15_000) {
            val root = automation.rootInActiveWindow
            root?.let { try { it.packageName?.toString()?.endsWith(".documentsui") == true } finally { it.recycle() } } ?: false
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        var lastPackage: String? = null
        var titleVisible = false
        var childrenVisible = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                lastPackage = root.packageName?.toString()
                titleVisible = visit(root) { it.text?.toString() == name && isInToolbar(it) }
                childrenVisible = listOf("small", "nested", "empty-folder").all { child ->
                    visit(root) { it.text?.toString() == child }
                }
                if (lastPackage?.endsWith(".documentsui") == true && titleVisible && childrenVisible) return
            } finally { root.recycle() }
            SystemClock.sleep(150)
        }
        fail("Product directory browser location not confirmed: package=$lastPackage, title=$titleVisible, children=$childrenVisible")
    }

    /** Back only navigates the system viewer; it never selects another tree or edits documents. */
    private fun returnToTest() {
        repeat(12) {
            val root = automation.rootInActiveWindow
            val inTest = root?.let { try { it.packageName == context.packageName } finally { it.recycle() } } ?: false
            if (inTest) { compose.waitForIdle(); return }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(200)
        }
        fail("The system directory viewer did not return to the test")
    }

    /** Callers first establish that the current directory contains only this test's synthetic data. */
    private fun capturePicker(name: String) {
        SystemClock.sleep(500)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val directory = File(context.cacheDir, "directory-picker-test").apply { mkdirs() }
            File(directory, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    /** Commands use only fixed paths and UUID names, with no shell operators or user data. */
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }

    private fun clickPickerEntry(name: String) = awaitPickerAction { node ->
        if (node.text?.toString() == name) click(node) else false
    }

    /** Traverses in memory without recording any other document names or content. */
    private fun awaitPickerAction(scroll: Boolean = true, action: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                if (root.packageName?.toString()?.endsWith(".documentsui") == true) {
                    if (visit(root, action)) return
                    if (scroll) visit(root) { node ->
                        node.isScrollable && (node.className?.toString()?.contains("RecyclerView") == true ||
                            node.className?.toString()?.contains("ListView") == true) &&
                            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                }
            } finally { root.recycle() }
            SystemClock.sleep(150)
        }
        fail("System directory picker did not expose the required action")
    }

    private fun visit(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (action(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, action)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun isInToolbar(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        while (parent != null) {
            val value = parent
            try {
                if (value.viewIdResourceName?.endsWith(":id/toolbar") == true ||
                    value.className?.toString()?.endsWith(".Toolbar") == true) return true
                parent = value.parent
            } finally { value.recycle() }
        }
        return false
    }

    /** Old DocumentsUI rows handle touch gestures without exposing an accessibility click action. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        fun clickAction(value: AccessibilityNodeInfo, parents: Int): Boolean {
            if (value.isClickable && value.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (parents == 0) return false
            val parent = value.parent ?: return false
            return try { clickAction(parent, parents - 1) } finally { parent.recycle() }
        }
        if (clickAction(node, 5)) return true
        // Only an exact test-owned name or the picker confirmation control reaches
        // this helper. Use that node's bounds, never an unrelated ancestor's bounds.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        val down = inject(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(50)
        return inject(MotionEvent.ACTION_UP) && down
    }
}
