package website.sung.mangossh.presentation

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.TransferConflict
import website.sung.mangossh.session.TransferConflictAction
import website.sung.mangossh.session.TransferConflictDecision
import website.sung.mangossh.data.vault.AuthenticationSummary
import website.sung.mangossh.data.vault.SshKeySummary
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.security.AppAccessState
import website.sung.mangossh.security.DocumentPickerRequests

/** Synthetic names and picker grants only; never touches saved connections or real documents. */
class DownloadDocumentContractInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val selected = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "synthetic")

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

    private fun assertIntent(intent: Intent, name: String, type: String) {
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(type, intent.type)
        assertEquals(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
            IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
    }

    @Test fun authenticationExportPreservesThePublicSuffixAndRequestAcrossRestoration() {
        val picker = PickerRegistry()
        val key = SshKeySummary("synthetic-key", "Disposable key", "Synthetic", "")
        val authentication = AuthenticationSummary("synthetic-auth", "Disposable credential", key.id)
        val results = mutableListOf<Pair<String, KeyExportPart>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                AuthenticationScreen(VaultStatus.Ready, listOf(authentication), listOf(key), emptyList(),
                    AuthenticationEditorActions(), {}, { id, part, _ -> results += id to part }, false)
            } }
        }
        compose.onNodeWithText(context.getString(R.string.ui_export)).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_export_to_file)).performClick()
        compose.runOnIdle {
            assertEquals("*/*", picker.launched?.type)
            assertEquals("ssh_key.pub", picker.launched?.getStringExtra(Intent.EXTRA_TITLE))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { picker.reply(selected) }
        compose.runOnIdle { assertEquals(listOf(key.id to KeyExportPart.PUBLIC), results) }
        compose.onNodeWithText(context.getString(R.string.ui_export)).performClick()
        compose.onNode(hasText(context.getString(R.string.ui_private_key)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_export_to_file)).performClick()
        compose.runOnIdle {
            assertEquals("application/x-pem-file", picker.launched?.type)
            assertEquals("ssh_key.pem", picker.launched?.getStringExtra(Intent.EXTRA_TITLE))
            picker.reply(selected)
            assertEquals(key.id to KeyExportPart.PRIVATE, results.last())
        }
    }

    @Test fun launchFailureAndRemovalOfThePickerOwnerReleaseTheLockExemption() {
        val requests = DocumentPickerRequests(AppAccessState(false))
        val shown = mutableStateOf(true)
        var fail = true
        var failed = false
        val registry = object : ActivityResultRegistry(), ActivityResultRegistryOwner {
            override val activityResultRegistry get() = this
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (fail) throw android.content.ActivityNotFoundException()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry, LocalDocumentPickerRequests provides requests) {
                if (shown.value) {
                    val picker = rememberAppDocumentLauncher(SystemDocumentContract("*/*")) {}
                    androidx.compose.material3.Button(onClick = {
                        try { picker.launch("synthetic") } catch (_: android.content.ActivityNotFoundException) { failed = true }
                    }) { androidx.compose.material3.Text("Launch isolated picker") }
                }
            }
        }
        compose.onNodeWithText("Launch isolated picker").performClick()
        compose.runOnIdle { assertTrue(failed); assertFalse(requests.active); fail = false }
        compose.onNodeWithText("Launch isolated picker").performClick()
        compose.runOnIdle { assertTrue(requests.active); shown.value = false }
        compose.runOnIdle { assertFalse(requests.active) }
    }

    @Test fun oneContractResolvesEachFilenameWithoutChangingItsSpelling() {
        val contract = DownloadDocumentContract()
        for ((name, type) in listOf(
            "notes.txt" to "text/plain", "report.PDF" to "application/pdf", "photo.jpg" to "image/jpeg",
            "archive.zip" to "application/zip", "two words.txt" to "text/plain",
            "payload.bin" to "*/*", "unknown.mangossh-unknown" to "*/*", "README" to "*/*", ".profile" to "*/*",
            ".z" to "*/*", ".Z" to "*/*",
        )) assertIntent(contract.createIntent(context, name), name, type)
    }

    @Test fun downloadsAndExportsPreferAnAccessiblePlatformPicker() {
        val manager = context.packageManager
        val platformPackages = setOf("com.android.documentsui", "com.google.android.documentsui")
        for ((contract, name, type) in listOf<Triple<ActivityResultContracts.CreateDocument, String, String>>(
            Triple(DownloadDocumentContract(), ".z", "*/*"),
            Triple(DownloadDocumentContract(), "report.pdf", "application/pdf"),
            Triple(SystemDocumentContract("application/octet-stream"), "synthetic.backup", "application/octet-stream"),
            Triple(SystemDocumentContract("text/plain"), "synthetic.txt", "text/plain"),
            Triple(SystemDocumentContract("application/x-pem-file"), "synthetic.pem", "application/x-pem-file"),
        )) {
            val intent = contract.createIntent(context, name)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(type, intent.type)
            val original = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type)
            val defaultPackage = manager.resolveActivity(original, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            val accessiblePlatforms = platformPackages.filterTo(mutableSetOf()) { candidate ->
                val info = manager.resolveActivity(Intent(original).setPackage(candidate),
                    PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
                val permission = info?.permission
                info != null && info.enabled && info.exported &&
                    (info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 &&
                    (permission.isNullOrEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
            if (accessiblePlatforms.isNotEmpty()) {
                val component = checkNotNull(intent.component)
                assertTrue(component.packageName in accessiblePlatforms)
                if (defaultPackage in accessiblePlatforms) assertEquals(defaultPackage, component.packageName)
                val info = manager.getActivityInfo(component, 0)
                assertTrue(info.exported && info.enabled)
                assertTrue((info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
            } else {
                assertNull(intent.component)
            }
        }
    }

    @Test fun browserCancellationAndRestorationKeepTheCorrectNameAndSession() {
        val picker = PickerRegistry()
        val first = RemoteFileEntry("report.PDF", "/fixture/report.PDF", RemoteFileKind.FILE)
        val state = mutableStateOf(RemoteBrowserUiState("first", "Synthetic", "/fixture",
            entries = listOf(first), isLoading = false))
        val results = mutableListOf<Triple<String, String, Uri>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                RemoteFileBrowserScreen(state.value, {}, {}, {}, {}, {},
                    { session, path, uri -> results += Triple(session, path, uri) }, { _, _, _ -> },
                    { _, _ -> }, { _, _ -> }, {}, {})
            } }
        }
        fun download() {
            compose.onNodeWithTag("remote-file-actions").performClick()
            compose.onNodeWithText(context.getString(R.string.common_download)).performClick()
        }
        download()
        compose.runOnIdle {
            assertIntent(checkNotNull(picker.launched), first.name, "application/pdf")
            picker.reply(null)
            assertTrue(results.isEmpty())
            state.value = state.value.copy(sessionId = "second",
                entries = listOf(first.copy(name = "notes.txt", path = "/second/notes.txt")))
        }
        download()
        compose.runOnIdle { assertIntent(checkNotNull(picker.launched), "notes.txt", "text/plain") }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state.value = state.value.copy(sessionId = "third", entries = emptyList()) }
        compose.waitForIdle()
        compose.runOnIdle { picker.reply(selected) }
        compose.runOnIdle { assertEquals(listOf(Triple("second", "/second/notes.txt", selected)), results) }
    }

    @Test fun saveAsRetainsTheNameAndCreationProvenanceAfterCancellation() {
        val picker = PickerRegistry()
        var result: TransferConflictDecision? = null
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { MaterialTheme {
                TransferConflictDialog(TransferConflict("synthetic", "photo.jpg", ScpTransferDirection.DOWNLOAD,
                    targetExists = true, atomicReplace = false)) { result = it }
            } }
        }
        compose.onNodeWithText(context.getString(R.string.editor_save_as)).performClick()
        compose.runOnIdle {
            assertIntent(checkNotNull(picker.launched), "photo.jpg", "image/jpeg")
            picker.reply(null)
            assertNull(result)
        }
        compose.onNodeWithText(context.getString(R.string.editor_save_as)).performClick()
        compose.runOnIdle { picker.reply(selected) }
        compose.runOnIdle {
            assertEquals(TransferConflictAction.SAVE_AS, result?.action)
            assertEquals(selected.toString(), result?.localUri)
            assertTrue(checkNotNull(result).localDocumentCreated)
        }
    }
}
