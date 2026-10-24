package website.sung.mangossh.presentation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
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
        try {
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
                awaitPicker { root -> visit(root) { node ->
                    val id = node.viewIdResourceName.orEmpty()
                    node.isEnabled && (id.endsWith(":id/action_menu_save") || id == "android:id/button1") &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } }
                compose.waitUntil(15_000) { selected.get() != null }
                val document = checkNotNull(selected.get())
                try {
                    assertEquals("com.android.externalstorage.documents", document.authority)
                    assertEquals(name, LocalDocumentTree.displayName(context.contentResolver, document))
                    val bytes = ByteArray(257 + index) { (it % 251).toByte() }
                    context.contentResolver.openOutputStream(document, "wt")!!.use { it.write(bytes) }
                    assertArrayEquals(bytes, context.contentResolver.openInputStream(document)!!.use { it.readBytes() })
                } finally {
                    assertTrue("Delete only this picker-created document",
                        DocumentsContract.deleteDocument(context.contentResolver, document))
                    selected.set(null)
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            selected.get()?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            // rmdir only removes the empty test-owned directory; unexpected contents are preserved.
            if (ownsDirectory) assertEquals("", shell("rmdir $shellPath"))
        }
    }

    /** Polls only the picker and never records the text of unrelated nodes. */
    private fun awaitPicker(action: (AccessibilityNodeInfo) -> Boolean) {
        compose.waitUntil(15_000) {
            val root = automation.rootInActiveWindow
            root?.let { try {
                it.packageName?.toString()?.endsWith(".documentsui") == true && action(it)
            } finally { it.recycle() } } ?: false
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

    /** The only interpolated shell value is a test-generated UUID below a fixed directory. */
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }
}
