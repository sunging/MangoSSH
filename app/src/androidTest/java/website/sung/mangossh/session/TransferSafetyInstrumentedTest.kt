package website.sung.mangossh.session

import android.content.*
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Deterministic SAF provider exposes only a UUID-named cache directory owned by this test. */
@SdkSuppress(minSdkVersion = 29)
@android.annotation.TargetApi(29)
class TransferSafetyInstrumentedTest {
    private class Documents(val root: File) : ContentProvider() {
        var reverse = false
        val modes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tree: Uri = Uri.parse("content://mangossh.test.documents/tree/root")
        fun uri(name: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "root/$name")
        private fun file(uri: Uri): File {
            val id = DocumentsContract.getDocumentId(uri)
            val result = if (id == "root") root else File(root, id.removePrefix("root/"))
            require(result.canonicalFile == root.canonicalFile || result.canonicalPath.startsWith(root.canonicalPath + File.separator))
            return result
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = if (file(uri).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
            val parent = file(uri)
            val entries = if (uri.lastPathSegment == "children") parent.listFiles()!!.sortedBy { it.name }.let { if (reverse) it.reversed() else it } else listOf(parent)
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                entries.forEach { entry -> addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (entry == root) "root" else "root/" + entry.relativeTo(root).invariantSeparatorsPath
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (entry.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> entry.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> entry.lastModified()
                    else -> null
                } }.toTypedArray<Any?>()) }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode
            // Deliberately emulate a provider whose plain "w" does not truncate.
            val flags = when (mode) {
                "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
                "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND
                else -> ParcelFileDescriptor.MODE_WRITE_ONLY
            }
            return ParcelFileDescriptor.open(file(uri), flags)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Unexpected delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected update")
    }

    private fun fixture(block: suspend (Connection, Documents, FileTransferManager) -> Unit) = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")?.toIntOrNull()
        assumeTrue(port != null)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "transfer-test-${UUID.randomUUID()}").apply { mkdirs() }
        val provider = Documents(root).apply { attachInfo(base, ProviderInfo().apply { authority = "mangossh.test.documents" }) }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
            override fun getCacheDir() = File(root, ".staging").apply { mkdirs() }
        }
        val connection = Connection("127.0.0.1", port!!)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connection.connect({ _, _, _, _ -> true }, 5_000, 5_000)
            assertTrue(connection.authenticateWithNone("fixture"))
            val manager = FileTransferManager(context, scope, RemoteFileClient(), { connection }, {})
            withTimeout(20_000) { block(connection, provider, manager) }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            connection.abort()
            root.deleteRecursively()
        }
    }

    @Test fun shortDownloadExplicitlyTruncatesProviderTarget() = fixture { connection, provider, manager ->
        val name = UUID.randomUUID().toString()
        BlockingOperation().use { control ->
            RemoteFileClient().upload(connection, "new".byteInputStream(), "/", name, 0, 3, 1024, control) { _, _ -> }
        }
        val target = File(provider.root, "target").apply { writeText("old trailing content") }
        manager.downloadFile("fixture", "/$name", provider.uri("target"))
        val conflict = manager.conflicts.first { it.isNotEmpty() }.single()
        manager.resolveConflict(conflict.id, TransferConflictDecision(TransferConflictAction.DIRECT_OVERWRITE, verifySha256 = true))
        val state = manager.transfers.first { it.single().isFinished }.single()
        assertEquals(ScpTransferPhase.COMPLETED, state.phase)
        assertEquals("new", target.readText())
        assertTrue("wt" in provider.modes)
        assertFalse("w" in provider.modes)
    }

    @Test fun resumedDirectoryUsesOriginalManifestDespiteOrderAndNewFiles() = fixture { connection, provider, manager ->
        File(provider.root, "a").writeText("first")
        File(provider.root, "b").writeText("second")
        val files = RemoteFileClient()
        val remote = "/" + provider.root.name
        files.mkdirIfMissing(connection, remote)
        BlockingOperation().use { control -> files.upload(connection, "old".byteInputStream(), remote, "b", 0, 3, 1024, control) { _, _ -> } }
        manager.uploadDirectory("fixture", provider.tree, "/", provider.root.name)
        val first = manager.conflicts.first { it.isNotEmpty() }.single()
        manager.resolveConflict(first.id, TransferConflictDecision(TransferConflictAction.REPLACE))
        val second = manager.conflicts.first { it.any { conflict -> conflict.id != first.id } }.single()
        val id = manager.transfers.value.single().id
        assertEquals(1, manager.transfers.value.single().completedItems)
        manager.pause(id)
        manager.transfers.first { it.single().phase == ScpTransferPhase.PAUSED }
        provider.reverse = true
        File(provider.root, "c").writeText("not in original manifest")
        manager.resume(id)
        val resumed = manager.conflicts.first { it.any { conflict -> conflict.id != second.id } }.single()
        manager.resolveConflict(resumed.id, TransferConflictDecision(TransferConflictAction.REPLACE))
        val final = manager.transfers.first { it.single().isFinished }.single()
        assertEquals(ScpTransferPhase.COMPLETED, final.phase)
        assertEquals(setOf("a", "b"), files.list(connection, "fixture", remote, 20).entries.map { it.name }.toSet())
        BlockingOperation().use { control ->
            assertEquals("first", files.readEditable(connection, "$remote/a", control).text)
            assertEquals("second", files.readEditable(connection, "$remote/b", control).text)
        }
    }
    @Test fun changedSourceStopsResumeAndExplicitRetryUsesFreshManifest() = fixture { connection, provider, manager ->
        File(provider.root, "a").writeText("first")
        val source = File(provider.root, "b").apply { writeText("second") }
        val files = RemoteFileClient()
        val remote = "/" + provider.root.name
        files.mkdirIfMissing(connection, remote)
        BlockingOperation().use { control -> files.upload(connection, "old".byteInputStream(), remote, "b", 0, 3, 1024, control) { _, _ -> } }
        manager.uploadDirectory("fixture", provider.tree, "/", provider.root.name)
        val first = manager.conflicts.first { it.isNotEmpty() }.single()
        manager.resolveConflict(first.id, TransferConflictDecision(TransferConflictAction.REPLACE))
        manager.conflicts.first { it.any { question -> question.id != first.id } }
        val id = manager.transfers.value.single().id
        manager.pause(id)
        manager.transfers.first { it.single().phase == ScpTransferPhase.PAUSED }
        source.writeText("modified source with different length")
        manager.resume(id)
        val failed = manager.transfers.first { it.single().isFinished }.single()
        assertEquals(ScpTransferPhase.FAILED, failed.phase)
        assertEquals(RemoteFileMessage.SourceChanged, failed.detail)
        BlockingOperation().use { assertEquals("old", files.readEditable(connection, "$remote/b", it).text) }
        manager.retry(id)
        val retry = manager.conflicts.first { it.isNotEmpty() }.single()
        manager.resolveConflict(retry.id, TransferConflictDecision(TransferConflictAction.REPLACE, applyToTask = true))
        assertEquals(ScpTransferPhase.COMPLETED, manager.transfers.first { it.single().isFinished }.single().phase)
        BlockingOperation().use { assertEquals(source.readText(), files.readEditable(connection, "$remote/b", it).text) }
    }

}
