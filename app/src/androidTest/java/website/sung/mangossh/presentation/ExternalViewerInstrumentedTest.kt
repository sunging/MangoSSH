package website.sung.mangossh.presentation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.ScpTransferKind
import website.sung.mangossh.session.ScpTransferPhase
import website.sung.mangossh.session.ScpTransferState

/** Synthetic provider and intercepted launches only; no files, saved state, or external apps are touched. */
@SdkSuppress(minSdkVersion = 29)
class ExternalViewerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri("mangossh.viewer.test", "parent-id"), "opaque-folder-id",
    )
    private val canonical = DocumentsContract.buildDocumentUri("mangossh.viewer.test", "opaque-folder-id")
    private val launched = AtomicReference<Intent?>()
    private val picker = PickerRegistry()
    private lateinit var context: Context
    private lateinit var provider: DirectoryProvider

    private inner class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var intent: Intent? = null
        private var pendingCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            assertNull(pendingCode)
            intent = contract.createIntent(context, input)
            pendingCode = requestCode
        }
        fun reply(uri: Uri?) {
            val code = checkNotNull(pendingCode)
            pendingCode = null
            dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) })
        }
    }

    private inner class DirectoryProvider : ContentProvider() {
        var exists = true
        var denied = false
        var unsupported = false
        var canonicalResult: Uri? = canonical
        var mimeType = DocumentsContract.Document.MIME_TYPE_DIR
        var queryThread: Thread? = null
        var canonicalThread: Thread? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = mimeType
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            queryThread = Thread.currentThread()
            if (denied) throw SecurityException("Synthetic permission loss")
            assertEquals(directory, uri)
            return MatrixCursor(requireNotNull(projection)).apply {
                if (exists) addRow(arrayOf(mimeType))
            }
        }
        override fun canonicalize(uri: Uri): Uri? {
            canonicalThread = Thread.currentThread()
            assertEquals(directory, uri)
            if (unsupported) throw UnsupportedOperationException()
            return canonicalResult
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected write")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected write")
    }

    @Before fun isolateProvider() {
        provider = DirectoryProvider().apply {
            attachInfo(base, ProviderInfo().apply { authority = directory.authority })
        }
        val resolver = ContentResolver.wrap(provider)
        context = object : ContextWrapper(base) {
            override fun getContentResolver() = resolver
            override fun startActivity(intent: Intent) { launched.set(intent) }
        }
    }

    @Test fun canonicalizationPreservesTheDocumentAndRunsOffMain() = runBlocking {
        assertEquals(canonical, prepareDirectoryViewUri(context, directory))
        assertNotEquals(Looper.getMainLooper().thread, provider.queryThread)
        assertNotEquals(Looper.getMainLooper().thread, provider.canonicalThread)
    }

    @Test fun unsupportedCanonicalizationRetainsTheActualDirectoryDocument() = runBlocking {
        provider.canonicalResult = null
        assertEquals(directory, prepareDirectoryViewUri(context, directory))
        provider.unsupported = true
        assertEquals(directory, prepareDirectoryViewUri(context, directory))
    }

    @Test fun aCanonicalParentOrDifferentProviderCannotReplaceTheActualDirectory() = runBlocking {
        provider.canonicalResult = DocumentsContract.buildDocumentUri(directory.authority!!, "parent-id")
        assertEquals(directory, prepareDirectoryViewUri(context, directory))
        provider.canonicalResult = DocumentsContract.buildDocumentUri("another.provider", "opaque-folder-id")
        assertEquals(directory, prepareDirectoryViewUri(context, directory))
    }

    @Test fun missingDirectoryDoesNotLaunchTheBrowser() = runBlocking {
        provider.exists = false
        withContext(Dispatchers.Main) { launchDirectoryViewIntent(context, directory) { fail("Unexpected launch") } }
        assertNull(launched.get())
    }

    @Test fun revokedPermissionDoesNotLaunchTheBrowser() = runBlocking {
        provider.denied = true
        withContext(Dispatchers.Main) { launchDirectoryViewIntent(context, directory) { fail("Unexpected launch") } }
        assertNull(launched.get())
    }

    @Test fun aFileCannotBeOpenedAsADirectory() = runBlocking {
        provider.mimeType = "text/plain"
        withContext(Dispatchers.Main) { launchDirectoryViewIntent(context, directory) { fail("Unexpected launch") } }
        assertNull(launched.get())
    }

    @Test fun missingViewerAndLaunchPermissionFailureAreHandled() = runBlocking {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            withContext(Dispatchers.Main) { launchDirectoryViewIntent(context, directory) { throw failure } }
        }
        assertNull(launched.get())
    }

    @Test fun completedFolderBrowsesTheActualDirectoryWithoutAnAppChooserOrWriteGrant() {
        showTransfer(transfer())
        open()
        compose.waitUntil { picker.intent != null }
        assertBrowser(canonical)
        assertNull(launched.get())
    }

    @Test fun unsupportedCanonicalizationStillBrowsesTheActualDirectory() {
        provider.unsupported = true
        showTransfer(transfer())
        open()
        compose.waitUntil { picker.intent != null }
        assertBrowser(directory)
    }

    @Test fun cancellingTheBrowserAllowsReopeningTheSameDownload() {
        showTransfer(transfer())
        open()
        compose.waitUntil { picker.intent != null }
        compose.runOnIdle { picker.reply(null); picker.intent = null }
        assertNull(launched.get())
        open()
        compose.waitUntil { picker.intent != null }
        assertBrowser(canonical)
    }

    @Test fun selectingAFileFromTheBrowserOpensItReadOnly() {
        showTransfer(transfer())
        open()
        compose.waitUntil { picker.intent != null }
        provider.mimeType = "text/plain"
        val file = DocumentsContract.buildDocumentUri(directory.authority!!, "opaque-child-id")
        compose.runOnIdle { picker.reply(file) }
        compose.waitUntil { launched.get() != null }
        val view = assertChooser()
        assertEquals(file, view.data)
        assertEquals("text/plain", view.type)
    }

    @Test fun completedFileKeepsItsDocumentAndMimeType() {
        provider.mimeType = "text/plain"
        showTransfer(transfer().copy(kind = ScpTransferKind.FILE))
        open()
        compose.waitUntil { launched.get() != null }
        val view = assertChooser()
        assertEquals(directory, view.data)
        assertEquals("text/plain", view.type)
    }

    @Test fun completedUploadStillOpensTheRemoteDirectory() {
        val upload = transfer().copy(direction = ScpTransferDirection.UPLOAD)
        val remote = AtomicReference<ScpTransferState?>()
        showTransfer(upload) { remote.set(it) }
        open()
        compose.runOnIdle {
            assertEquals(upload, remote.get())
            assertNull(launched.get())
        }
    }

    private fun showTransfer(transfer: ScpTransferState, onRemote: (ScpTransferState) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides picker) {
                MaterialTheme {
                    FileTransfersSheet(listOf(transfer), {}, {}, {}, {}, onRemote, {}, {})
                }
            }
        }
    }

    private fun open() = compose.onNodeWithContentDescription(base.getString(R.string.common_open)).performClick()

    private fun assertBrowser(initial: Uri) {
        val intent = checkNotNull(picker.intent)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(setOf(Intent.CATEGORY_OPENABLE), intent.categories)
        assertEquals("*/*", intent.type)
        assertEquals(initial, IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        assertTrue(intent.getBooleanExtra("android.content.extra.SHOW_ADVANCED", false))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertNull(intent.data)
        assertNull(intent.clipData)
    }

    private fun assertChooser(): Intent {
        val chooser = checkNotNull(launched.get())
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val view = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_VIEW, view.action)
        val unexpected = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(0, chooser.flags and unexpected)
        assertEquals(0, view.flags and unexpected)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        return view
    }

    private fun transfer() = ScpTransferState("fixture", "fixture-session", ScpTransferDirection.DOWNLOAD,
        "Synthetic folder", "/fixture/folder", ScpTransferPhase.COMPLETED, kind = ScpTransferKind.DIRECTORY,
        localUri = directory.toString())
}
