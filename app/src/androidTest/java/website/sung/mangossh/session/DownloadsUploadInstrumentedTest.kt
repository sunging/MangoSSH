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
import java.util.concurrent.atomic.AtomicInteger
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
import website.sung.mangossh.diagnostics.CiRegressionDiagnostics
import org.json.JSONObject
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.presentation.DownloadDocumentContract

/** A fresh picker-created Downloads document is the only real provider document this test touches. */
@SdkSuppress(minSdkVersion = 26)
@android.annotation.TargetApi(26)
class DownloadsUploadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    /** Exercises the real API 26+ provider; the synthetic SAF suite requires API 29. */
    @Test fun systemDownloadsSmallMultiChunkAndEmptyFilesDownloadThroughSaf() = downloadThroughSaf(false)

    @Test fun externalStorageDownloadsSmallMultiChunkAndEmptyFilesDownloadThroughSaf() = downloadThroughSaf(true)

    private fun downloadThroughSaf(externalStorage: Boolean) {
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")?.toIntOrNull()
        if (InstrumentationRegistry.getArguments().getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = createDocument("application/octet-stream", externalStorage)
        val staging = File(base.cacheDir, "downloads-download-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getCacheDir() = staging }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "127.0.0.1"
        val connection = SshConnection(host, port!!)
        val failure = AtomicReference<Throwable?>()
        try {
            assertEquals(if (externalStorage) "com.android.externalstorage.documents" else
                "com.android.providers.downloads.documents", destination.authority)
            runBlocking {
                connection.connect(10_000) { _, _ -> true }
                assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
                val files = RemoteFileClient()
                val remote = RemoteFilePaths.join(files.resolveHome(connection), UUID.randomUUID().toString())
                files.mkdirIfMissing(connection, remote)
                val manager = FileTransferManager(context, scope, files, { connection }, {},
                    onTaskFailure = { failure.set(it) })
                withTimeout(60_000) {
                    for ((index, length) in listOf(2424, 80_000, 17, 0).withIndex()) {
                        val bytes = ByteArray(length) { (it % 251).toByte() }
                        val name = "file-$length"
                        BlockingOperation().use { control ->
                            files.upload(connection, bytes.inputStream(), remote, name, 0, length.toLong(), 1_048_576, control) { _, _ -> }
                        }
                        if (index == 0) manager.downloadCreatedDocument("fixture", "$remote/$name", destination)
                        else manager.downloadFile("fixture", "$remote/$name", destination)
                        val preview = manager.conflicts.first { it.isNotEmpty() }.single()
                        assertEquals(index != 0, preview.targetExists)
                        manager.resolveConflict(preview.id, TransferConflictDecision(
                            if (index == 0) TransferConflictAction.REPLACE else TransferConflictAction.DIRECT_OVERWRITE,
                            verifySha256 = true))
                        val state = manager.transfers.first { it.last().isFinished }.last()
                        val error = failure.get()
                        // An explicitly identified OEM regression is a failure-path test,
                        // not a skip or permission to accept failures on other providers.
                        if (!externalStorage && InstrumentationRegistry.getArguments()
                                .getString("expectDownloadsWriteRejected") == "true") {
                            assertEquals(ScpTransferPhase.FAILED, state.phase)
                            assertEquals(RemoteFileMessage.LocalDocumentWriteFailure, state.detail)
                            assertTrue(error is LocalDocumentWriteException)
                            assertTrue(state.canRetry)
                            assertFalse(state.canResume)
                            assertEquals(0, base.contentResolver.openInputStream(destination)!!.use { it.readBytes().size })
                            break
                        }
                        val diagnostic = error?.let {
                            MangoLog.describe(it) + " at " + it.stackTrace.filter { frame ->
                                frame.className.startsWith("website.sung.mangossh.")
                            }.take(6).joinToString { frame -> "${frame.fileName}:${frame.lineNumber}" }
                        }.orEmpty()
                        assertEquals("Download $length bytes: $diagnostic", ScpTransferPhase.COMPLETED, state.phase)
                        assertEquals(length.toLong(), state.transferredBytes)
                        assertArrayEquals(bytes, base.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
                        // The next overwrite captures a new baseline only after this
                        // test-owned write has been published by an asynchronous provider.
                        awaitDocumentWritePublication(length.toLong(), readMetadata = {
                            LocalDocumentTree.queryDocument(base.contentResolver, destination, arrayOf(
                                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            )) { cursor ->
                                if (!cursor.moveToFirst()) DocumentWriteMetadata(null, null)
                                else DocumentWriteMetadata(
                                    if (cursor.isNull(0)) null else cursor.getLong(0),
                                    if (cursor.isNull(1)) null else cursor.getLong(1),
                                )
                            }
                        }, waitUntil = { ready -> compose.waitUntil(timeoutMillis = 5_000, condition = ready) })
                    }
                }
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            connection.close()
            assertTrue("Delete only the picker-created test document", DocumentsContract.deleteDocument(base.contentResolver, destination))
            staging.deleteRecursively()
        }
    }

    private fun createDocument(mimeType: String, externalStorage: Boolean = false): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selected = AtomicReference<Uri?>()
        val name = "mangossh-transfer-test-${UUID.randomUUID()}.txt"
        val contract = if (externalStorage) DownloadDocumentContract(mimeType) else object : ActivityResultContracts.CreateDocument(mimeType) {
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
        return requireNotNull(selected.get())
    }

    @Test fun systemDownloadsSmallMultiChunkAndEmptyFilesUploadThroughSaf() {
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")?.toIntOrNull()
        if (InstrumentationRegistry.getArguments().getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val source = createDocument("text/plain")
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
                val uploadIndex = AtomicInteger(-1)
                val uploadLength = AtomicInteger(-1)
                val manager = FileTransferManager(context, scope, files, { connection }, {}, onTaskFailure = { error ->
                    CiRegressionDiagnostics.phase("downloads-upload-error", index = uploadIndex.get(), count = uploadLength.get())
                    CiRegressionDiagnostics.failure(error, "downloads-upload")
                })
                withTimeout(30_000) {
                    for ((index, length) in listOf(17, 80_000, 0).withIndex()) {
                        uploadIndex.set(index); uploadLength.set(length)
                        CiRegressionDiagnostics.phase("downloads-write", index = index, count = length)
                        val bytes = ByteArray(length) { (it % 251).toByte() }
                        base.contentResolver.openOutputStream(source, "wt")!!.use { it.write(bytes) }
                        CiRegressionDiagnostics.phase("downloads-write-closed", index = index, count = length)
                        val published = awaitDocumentWritePublication(length.toLong(), readMetadata = {
                            LocalDocumentTree.queryDocument(base.contentResolver, source, arrayOf(
                                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            )) { cursor ->
                                if (!cursor.moveToFirst()) DocumentWriteMetadata(null, null)
                                else DocumentWriteMetadata(
                                    if (cursor.isNull(0)) null else cursor.getLong(0).takeIf { it >= 0 },
                                    if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it > 0 },
                                )
                            }
                        }, waitUntil = { ready -> compose.waitUntil(timeoutMillis = 5_000, condition = ready) })
                        CiRegressionDiagnostics.record("downloads-write-published", JSONObject().put("index", index)
                            .put("expectedBytes", length).put("size", published.size).put("modifiedMillis", published.modifiedMillis))
                        val target = "file-$length"
                        manager.uploadFile("fixture", source, target, destination)
                        val preview = manager.conflicts.first { it.isNotEmpty() }.single()
                        CiRegressionDiagnostics.phase("downloads-preview", index = index, count = length)
                        manager.resolveConflict(preview.id, TransferConflictDecision(TransferConflictAction.REPLACE, verifySha256 = true))
                        val state = manager.transfers.first { it.last().isFinished }.last()
                        CiRegressionDiagnostics.record("downloads-upload-result", JSONObject().put("index", index)
                            .put("length", length).put("phase", state.phase.name).put("bytes", state.transferredBytes)
                            .put("detailType", state.detail?.javaClass?.simpleName ?: "none"))
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
