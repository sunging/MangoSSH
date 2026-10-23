package website.sung.mangossh.presentation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind

/** Synthetic browser and picker results only; never opens saved profiles or writes documents. */
class DirectoryDownloadPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = RemoteFileEntry("folder", "/fixture/folder", RemoteFileKind.DIRECTORY)
    private val initial = RemoteBrowserUiState("original-session", "Synthetic", "/fixture",
        entries = listOf(folder), isLoading = false)
    private val selected = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/synthetic")

    private inner class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var launched: Intent? = null
        private var pendingCode: Int? = null

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            assertNull(pendingCode)
            launched = contract.createIntent(context, input)
            pendingCode = requestCode
        }

        fun reply(uri: Uri?) {
            val code = checkNotNull(pendingCode)
            pendingCode = null
            dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) })
        }
    }

    private fun download() {
        compose.onNodeWithTag("remote-file-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.common_download)).performClick()
    }

    @Test fun browserExposesInternalStorageAndUsesTheGrantedDestination() {
        val picker = PickerRegistry()
        val results = mutableListOf<Triple<String, String, Uri>>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                RemoteFileBrowserScreen(initial, {}, {}, {}, {}, {}, { _, _, _ -> },
                    { session, path, uri -> results += Triple(session, path, uri) }, { _, _ -> }, { _, _ -> }, {}, {})
            } }
        }
        download()
        compose.runOnIdle {
            val intent = checkNotNull(picker.launched)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
            assertTrue(intent.getBooleanExtra("android.content.extra.SHOW_ADVANCED", false))
            @Suppress("DEPRECATION")
            assertEquals(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:"),
                intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
            picker.reply(selected)
        }
        compose.runOnIdle { assertEquals(listOf(Triple(initial.sessionId, folder.path, selected)), results) }
    }

    @Test fun cancellingThenChoosingAgainUsesOnlyTheNewRequest() {
        val picker = PickerRegistry()
        val state = mutableStateOf(initial)
        val results = mutableListOf<Triple<String, String, Uri>>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                RemoteFileBrowserScreen(state.value, {}, {}, {}, {}, {}, { _, _, _ -> },
                    { session, path, uri -> results += Triple(session, path, uri) }, { _, _ -> }, { _, _ -> }, {}, {})
            } }
        }
        download()
        compose.runOnIdle {
            picker.reply(null)
            assertTrue(results.isEmpty())
            state.value = initial.copy(sessionId = "next-session", entries = listOf(folder.copy(path = "/next/folder")))
        }
        download()
        compose.runOnIdle { picker.reply(selected) }
        compose.runOnIdle { assertEquals(listOf(Triple("next-session", "/next/folder", selected)), results) }
    }

    /** A picker can outlive the browser's saved-state recreation and a switch to another session. */
    @Test fun restoredPickerResultRemainsBoundToTheOriginalSession() {
        val picker = PickerRegistry()
        val state = mutableStateOf(initial)
        val results = mutableListOf<Triple<String, String, Uri>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                RemoteFileBrowserScreen(state.value, {}, {}, {}, {}, {}, { _, _, _ -> },
                    { session, path, uri -> results += Triple(session, path, uri) }, { _, _ -> }, { _, _ -> }, {}, {})
            } }
        }
        download()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state.value = initial.copy(sessionId = "next-session", entries = emptyList()) }
        compose.waitForIdle()
        compose.runOnIdle { picker.reply(selected) }
        compose.runOnIdle { assertEquals(listOf(Triple(initial.sessionId, folder.path, selected)), results) }
    }
}
