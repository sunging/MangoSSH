package website.sung.mangossh.session

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/** A fresh picker-created Downloads document is the only real provider document this test touches. */
@SdkSuppress(minSdkVersion = 26)
@android.annotation.TargetApi(26)
class DownloadsUploadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemDownloadsSmallMultiChunkAndEmptyFilesUploadThroughSaf() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")?.toIntOrNull()
        if (InstrumentationRegistry.getArguments().getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val base = instrumentation.targetContext
        val selected = AtomicReference<Uri?>()
        val name = "mangossh-upload-test-${UUID.randomUUID()}.txt"
        val contract = object : ActivityResultContracts.CreateDocument("text/plain") {
            override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"))
            }
        }
        compose.setContent { MaterialTheme {
            val picker = rememberLauncherForActivityResult(contract) { selected.set(it) }
            Button(onClick = { picker.launch(name) }) { Text("Create isolated upload document") }
        } }
        compose.onNodeWithText("Create isolated upload document").performClick()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        var saved = false
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            while (!saved && System.nanoTime() < deadline) {
                val root = automation.rootInActiveWindow
                if (root != null) {
                    saved = clickSave(root)
                    root.recycle()
                }
                if (!saved) Thread.sleep(100)
            }
        } finally {
            if (!saved) recordPickerFailure(automation)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        assertTrue("The system picker must expose its save action", saved)
        compose.waitUntil(timeoutMillis = 15_000) { selected.get() != null }
        val source = requireNotNull(selected.get())
        val staging = File(base.cacheDir, "downloads-upload-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getCacheDir() = staging
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // A device reached over old wireless adb can use an explicitly scoped local relay.
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "127.0.0.1"
        val connection = SshConnection(host, port!!)
        try {
            assertEquals("com.android.providers.downloads.documents", source.authority)
            runBlocking {
                connection.connect(10_000) { _, _ -> true }
                assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
                val files = RemoteFileClient()
                val destination = RemoteFilePaths.join(files.resolveHome(connection), UUID.randomUUID().toString())
                files.mkdirIfMissing(connection, destination)
                val manager = FileTransferManager(context, scope, files, { connection }, {})
                withTimeout(30_000) {
                    for (length in listOf(17, 80_000, 0)) {
                        val bytes = ByteArray(length) { (it % 251).toByte() }
                        base.contentResolver.openOutputStream(source, "wt")!!.use { it.write(bytes) }
                        val target = "file-$length"
                        manager.uploadFile("fixture", source, target, destination)
                        val preview = manager.conflicts.first { it.isNotEmpty() }.single()
                        manager.resolveConflict(preview.id, TransferConflictDecision(TransferConflictAction.REPLACE, verifySha256 = true))
                        val state = manager.transfers.first { it.last().isFinished }.last()
                        assertEquals(ScpTransferPhase.COMPLETED, state.phase)
                        assertEquals(length.toLong(), state.transferredBytes)
                        BlockingOperation().use { control ->
                            val received = java.io.ByteArrayOutputStream()
                            files.download(connection, "$destination/$target", received, 0, null, control) { _, _ -> }
                            assertArrayEquals(bytes, received.toByteArray())
                        }
                    }
                    manager.uploadFile("fixture", source, "cancelled", destination)
                    val preview = manager.conflicts.first { it.isNotEmpty() }.single()
                    manager.resolveConflict(preview.id, TransferConflictDecision(TransferConflictAction.CANCEL))
                    assertEquals(ScpTransferPhase.CANCELLED, manager.transfers.first { it.last().isFinished }.last().phase)
                    assertEquals(setOf("file-17", "file-80000", "file-0"),
                        files.list(connection, "fixture", destination, 20).entries.map { it.name }.toSet())
                }
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            connection.close()
            assertTrue("Delete only the picker-created test document", DocumentsContract.deleteDocument(base.contentResolver, source))
            staging.deleteRecursively()
        }
    }

    /** Records structure, never text or document names; screenshots are CI-only DocumentsUI. */
    private fun recordPickerFailure(automation: android.app.UiAutomation) {
        val root = automation.rootInActiveWindow ?: return
        try {
            fun describe(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 20) return
                println("Picker node: class=${node.className} id=${node.viewIdResourceName} " +
                    "enabled=${node.isEnabled} clickable=${node.isClickable} package=${node.packageName}")
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { describe(child, depth + 1) } finally { child.recycle() }
                }
            }
            describe(root, 0)
            // This test has only created an empty, randomly named document in a disposable emulator.
            if (InstrumentationRegistry.getArguments().getString("ciDiagnostics") == "true" &&
                root.packageName?.toString()?.endsWith(".documentsui") == true) {
                runCatching {
                    automation.executeShellCommand("mkdir -p /sdcard/mangossh-ci-diagnostics").use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                    }
                    automation.executeShellCommand("screencap -p /sdcard/mangossh-ci-diagnostics/documents-picker.png").use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                    }
                }
            }
        } finally { root.recycle() }
    }

    private fun clickSave(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName.orEmpty()
        val documentsUi = node.packageName?.toString()?.endsWith(".documentsui") == true
        if (node.isEnabled && documentsUi && (id.endsWith(":id/action_menu_save") || id == "android:id/button1")) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val clicked = try { clickSave(child) } finally { child.recycle() }
            if (clicked) return true
        }
        return false
    }
}
