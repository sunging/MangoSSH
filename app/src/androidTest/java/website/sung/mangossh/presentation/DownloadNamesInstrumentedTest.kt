package website.sung.mangossh.presentation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.diagnostics.CiRegressionDiagnostics
import website.sung.mangossh.session.LocalDocumentTree

/**
 * Real picker regression using only synthetic documents inside a new UUID directory.
 * No saved profiles, preferences, keys or existing documents are read or changed.
 */
@SdkSuppress(minSdkVersion = 26)
class DownloadNamesInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun systemPickerPreservesNamesAndSavesTheGrantedDocuments() {
        val directoryName = "mangossh-download-names-${UUID.randomUUID()}"
        val shellPath = "/sdcard/Download/$directoryName"
        val selected = AtomicReference<Uri?>()
        val requested = AtomicReference("")
        val originalFlags = automation.serviceInfo.flags
        var ownsDirectory = false
        Closeable {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            selected.getAndSet(null)?.let { deletePickerDocument(it, directoryName) }
            // rmdir only removes the empty test-owned directory; unexpected contents are preserved.
            if (ownsDirectory) assertEquals("", shell("rmdir $shellPath"))
        }.use {
            assertNotEquals(shellPath, shell("ls -d $shellPath"))
            shell("mkdir $shellPath")
            assertEquals(shellPath, shell("ls -d $shellPath"))
            ownsDirectory = true
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val delegate = DownloadDocumentContract()
            // Override only the location so screenshots and Save never expose other documents.
            val contract = object : ActivityResultContract<String, Uri?>() {
                override fun createIntent(context: Context, input: String): Intent =
                    delegate.createIntent(context, input).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download/$directoryName"))
                    }
                override fun parseResult(resultCode: Int, intent: Intent?): Uri? = delegate.parseResult(resultCode, intent)
            }
            compose.setContent { MaterialTheme {
                val picker = rememberLauncherForActivityResult(contract) { selected.set(it) }
                Button(onClick = { picker.launch(requested.get()) }) { Text("Save synthetic download") }
            } }
            val names = listOf("notes.txt", "report.PDF", "photo.jpg", "archive.zip", "archive.tar.gz",
                "payload.bin", "unknown.mangossh-unknown", "README", ".profile", ".z", ".Z", ".z.z", "two words.txt")
            for ((index, name) in names.withIndex()) {
                requested.set(name)
                selected.set(null)
                compose.onNodeWithText("Save synthetic download").performClick()
                awaitPicker { root ->
                    visit(root) { it.text?.toString() == directoryName } &&
                        visit(root) { it.isEditable && it.text?.toString() == name }
                }
                if (InstrumentationRegistry.getArguments().getString("captureDownloadNames") == "true" &&
                    name in listOf("report.PDF", "unknown.mangossh-unknown", "README", ".z", ".profile")) {
                    capturePicker("picker-$index.png")
                }
                savePickerDocument()
                compose.waitUntil(15_000) { selected.get() != null }
                val document = checkNotNull(selected.get())
                // Consume cleanup ownership first; use preserves a failed assertion if deletion also fails.
                Closeable {
                    selected.compareAndSet(document, null)
                    deletePickerDocument(document, directoryName)
                }.use {
                    assertEquals("com.android.externalstorage.documents", document.authority)
                    assertEquals(name, LocalDocumentTree.displayName(context.contentResolver, document))
                    val bytes = ByteArray(257 + index) { (it % 251).toByte() }
                    context.contentResolver.openOutputStream(document, "wt")!!.use { it.write(bytes) }
                    assertArrayEquals(bytes, context.contentResolver.openInputStream(document)!!.use { it.readBytes() })
                }
            }
        }
    }

    /**
     * Removes only a document inside this test's UUID directory. Some provider
     * versions report deletion failure after removing a just-written dotfile;
     * verify actual absence instead, while the final rmdir still rejects leaks.
     */
    private fun deletePickerDocument(document: Uri, directoryName: String) {
        val prefix = "primary:Download/$directoryName/"
        val id = DocumentsContract.getDocumentId(document)
        require(document.authority == "com.android.externalstorage.documents" && id.startsWith(prefix))
        val name = id.removePrefix(prefix)
        require(name.matches(Regex("[A-Za-z0-9._ -]+")) && name != "." && name != "..")
        fun removed(): Boolean {
            val remaining = shell("ls -1a /sdcard/Download/$directoryName").lineSequence().toSet()
            return "." in remaining && ".." in remaining && name !in remaining
        }
        var failure: Exception? = null
        repeat(10) {
            try {
                if (DocumentsContract.deleteDocument(context.contentResolver, document)) return
            } catch (denied: SecurityException) {
                if (removed()) return
                throw denied
            } catch (error: Exception) {
                failure = error
            }
            // UiAutomation executes argv directly, not shell syntax. Listing
            // this UUID directory also handles fixed fixture names with spaces.
            if (removed()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Remove only the picker-created document", failure)
    }

    /** Polls Save readiness without clicking; a delayed result must never cause another Save request. */
    @Suppress("DEPRECATION")
    private fun savePickerDocument() {
        var button: AccessibilityNodeInfo? = null
        try {
            awaitPicker { root -> visit(root) { node ->
                val id = node.viewIdResourceName.orEmpty()
                if (node.isEnabled && (id.endsWith(":id/action_menu_save") || id == "android:id/button1")) {
                    button = AccessibilityNodeInfo.obtain(node)
                    true
                } else false
            } }
            assertTrue("Dispatch Save exactly once", checkNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { button?.recycle() }
    }

    /** Polls only the picker and never records the text of unrelated nodes. */
    private fun awaitPicker(action: (AccessibilityNodeInfo) -> Boolean) {
        try {
            compose.waitUntil(15_000) {
                val root = automation.rootInActiveWindow
                root?.let { try {
                    it.packageName?.toString()?.endsWith(".documentsui") == true && action(it)
                } finally { it.recycle() } } ?: false
            }
        } catch (failure: Throwable) {
            CiRegressionDiagnostics.pickerState("download-names")
            throw failure
        }
    }

    private fun visit(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (action(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, action)) return true } finally { child.recycle() }
        }
        return false
    }

    /** Called only after confirming the picker shows the empty synthetic destination and filename. */
    private fun capturePicker(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val directory = File(context.cacheDir, "download-name-test").apply { mkdirs() }
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** Shell paths contain only test-generated UUIDs and validated fixed fixture basenames. */
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }
}
