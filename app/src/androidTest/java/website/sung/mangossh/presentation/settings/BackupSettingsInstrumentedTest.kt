package website.sung.mangossh.presentation.settings

import android.graphics.Bitmap
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.*
import java.util.UUID
import java.util.Locale

/** Verifies user confirmation boundaries without touching the application vault or WebDAV. */
class BackupSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun callbacks(
        export: (String?, Boolean, Boolean) -> Unit = { _, _, _ -> },
        commit: (ImportDecision) -> Unit = {},
    ) = BackupSettingsCallbacks(
        onSaveWebDav = { _, _, _, _ -> }, onClearWebDav = {}, onPrepareExport = export,
        onWriteExport = {}, onImport = { _, _, _ -> }, onUpload = { _, _ -> }, onDownloadAndImport = { _, _ -> },
        onCommit = commit, onCancel = {}, onConfirmUpload = {}, onHistory = {}, onRestore = { _, _ -> }, onForget = {}, onRefresh = {},
    )

    @Test fun newExportRequiresMatchingPasswordAndConfigIsExcludedByDefault() {
        var result: Triple<String?, Boolean, Boolean>? = null
        compose.setContent { MaterialTheme { BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null), callbacks(export = { a, b, c -> result = Triple(a, b, c) })) } }
        compose.onNodeWithText(text(R.string.backup_export_action)).performClick()
        compose.onNodeWithText(text(R.string.common_continue)).assertIsNotEnabled()
        val password = UUID.randomUUID().toString()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(password)
        compose.onNodeWithText(text(R.string.common_continue)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(password)
        compose.onNodeWithText(text(R.string.common_continue)).performClick()
        compose.runOnIdle { assertEquals(Triple(password, false, false), result) }
    }

    @Test fun bulkIncomingDoesNotSelectTrustConflicts() {
        var decision: ImportDecision? = null
        val preview = ImportPreview(UUID.randomUUID().toString(), 1, 1, 0, 2,
            listOf(ImportConflict("profile:0", "profile", 1), ImportConflict("trust:0", "trust", 1, true)), false, null)
        compose.setContent { MaterialTheme { BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(preview = preview)), callbacks(commit = { decision = it })) } }
        compose.onNodeWithText(text(R.string.backup_all_incoming)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_apply)).performClick()
        compose.runOnIdle {
            assertEquals(setOf("profile:0"), decision!!.useIncoming)
            assertFalse(decision!!.replace)
            assertFalse(decision!!.restoreWebDav)
        }
    }

    @Test fun savedPassphraseCanBeUsedWithoutExposingIt() {
        var used = false
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(rememberedManual = true)), callbacks(export = { password, remember, include ->
                used = password == null && !remember && !include
            }))
        } }
        compose.onNodeWithText(text(R.string.backup_export_action)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.common_continue)).performClick()
        compose.runOnIdle { assertTrue(used) }
    }

    @Test fun busyOperationDisablesRepeatedActions() {
        compose.setContent { MaterialTheme { BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(phase = BackupPhase.SAVING)), callbacks()) } }
        compose.onNodeWithText(text(R.string.backup_export_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.backup_import_action)).assertIsNotEnabled()
    }

    @Test fun passwordInputIsNotRestoredAsSavedUiState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null), callbacks()) } }
        compose.onNodeWithText(text(R.string.backup_export_action)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(UUID.randomUUID().toString())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.backup_export_action)).performClick()
        compose.onNodeWithText(text(R.string.common_continue)).assertIsNotEnabled()
    }

    @Test fun unconfiguredWebDavOnlyOffersSetup() {
        compose.setContent { MaterialTheme { BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null), callbacks()) } }
        compose.onNodeWithText(text(R.string.ui_configure_webdav)).performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(4)
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        listOf(R.string.backup_upload_action, R.string.ui_download_and_import, R.string.backup_remote_history,
            R.string.backup_remove_webdav, R.string.backup_forget_manual, R.string.backup_forget_remote).forEach {
            compose.onNodeWithText(text(it)).assertDoesNotExist()
        }
    }

    @Test fun configuredManagementRowsRouteToTheirOwnSource() {
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", ""),
                BackupOperationState(rememberedManual = true, rememberedRemote = true)), callbacks().copy(
                onHistory = { events += "history:$it" }, onForget = { events += "forget:$it" }, onClearWebDav = { events += "remove" },
            ))
        } }
        listOf(R.string.backup_local_history, R.string.backup_forget_manual, R.string.backup_remote_history,
            R.string.backup_forget_remote, R.string.backup_remove_webdav).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().performClick()
        }
        compose.runOnIdle { assertFalse(events.contains("remove")) }
        compose.onNodeWithText(text(R.string.common_remove)).performClick()
        compose.onNodeWithText(text(R.string.backup_edit_webdav)).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(4)
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf("history:false", "forget:false", "history:true", "forget:true", "remove"), events) }
    }

    @Test fun removingWebDavRequiresConfirmationAndCancelPreservesConfiguration() {
        var removals = 0
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", "")),
                callbacks().copy(onClearWebDav = { removals++ }))
        } }
        compose.onNodeWithText(text(R.string.backup_remove_webdav)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_remove_webdav_detail)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(text(R.string.backup_remove_webdav)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.common_remove)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test fun removalConfirmationIsDisabledIfAnOperationStarts() {
        val operation = mutableStateOf(BackupOperationState())
        var removals = 0
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", ""), operation.value),
                callbacks().copy(onClearWebDav = { removals++ }))
        } }
        compose.onNodeWithText(text(R.string.backup_remove_webdav)).performScrollTo().performClick()
        compose.runOnIdle { operation.value = BackupOperationState(phase = BackupPhase.UPLOADING) }
        compose.onNodeWithText(text(R.string.common_remove)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
    }

    @Test fun uploadAndDownloadUseSavedRemotePassphrase() {
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", ""),
                BackupOperationState(rememberedRemote = true)), callbacks().copy(
                onUpload = { password, remember -> assertNull(password); assertFalse(remember); events += "upload" },
                onDownloadAndImport = { password, remember -> assertNull(password); assertFalse(remember); events += "download" },
            ))
        } }
        listOf(R.string.backup_upload_action, R.string.ui_download_and_import).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText(text(R.string.common_continue)).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("upload", "download"), events) }
    }

    @Test fun emptyHistoryKeepsTheSelectedSourceTitle() {
        val operation = mutableStateOf(BackupOperationState())
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", ""), operation.value), callbacks().copy(
                onHistory = { operation.value = BackupOperationState(history = emptyList()) },
                onCancel = { operation.value = BackupOperationState() },
            ))
        } }
        listOf(R.string.backup_local_history, R.string.backup_remote_history).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().performClick()
            compose.onNode(hasText(text(it)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_history_empty)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        }
    }

    @Test fun localHistoryRowRestoresWithoutRequestingAPassword() {
        val entry = BackupHistoryEntry(UUID.randomUUID().toString(), 0L, false)
        var restored = false
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(history = listOf(entry))),
                callbacks().copy(onRestore = { selected, password -> restored = selected == entry && password == null }))
        } }
        compose.onNodeWithText(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(0L))).performClick()
        compose.runOnIdle { assertTrue(restored) }
    }

    @Test fun filePickerResultIsPassedToImportAfterPasswordConfirmation() {
        val uri = Uri.parse("content://backup-test/document")
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocument)
                dispatchResult(requestCode, uri)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var imported = false
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MaterialTheme {
                BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(rememberedManual = true)),
                    callbacks().copy(onImport = { selected, password, remember -> imported = selected == uri && password == null && !remember }))
            } }
        }
        compose.onNodeWithText(text(R.string.backup_import_action)).performClick()
        compose.onNodeWithText(text(R.string.common_continue)).performClick()
        compose.runOnIdle { assertTrue(imported) }
    }

    @Test fun remoteHistoryAsksForOriginalPasswordEvenWhenOneIsSaved() {
        val entry = BackupHistoryEntry(UUID.randomUUID().toString(), 0L, true)
        val operation = mutableStateOf(BackupOperationState(history = listOf(entry), rememberedRemote = true))
        val password = UUID.randomUUID().toString()
        var restored = false
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, operation.value), callbacks().copy(
                onCancel = { operation.value = BackupOperationState(rememberedRemote = true) },
                onRestore = { selected, supplied -> restored = selected == entry && supplied == password },
            ))
        } }
        compose.onNodeWithText(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(0L))).performClick()
        compose.onNodeWithText(text(R.string.backup_restore_history)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_use_saved)).assertIsOff()
        compose.onNodeWithText(text(R.string.common_continue)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)[0].performTextInput(password)
        compose.onNodeWithText(text(R.string.common_continue)).performClick()
        compose.runOnIdle { assertTrue(restored) }
    }

    @Test fun passwordAndWebDavDialogsRemainUsableWithLargeFonts() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { MaterialTheme {
                BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null), callbacks())
            } }
        }
        compose.onNodeWithText(text(R.string.backup_export_action)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_include_config)).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(text(R.string.backup_remember)).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(text(R.string.common_continue)).assertIsNotEnabled()
        captureDialog("backup-password-large")
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.onNodeWithText(text(R.string.ui_configure_webdav)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_remote_file_name)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_save)).assertIsDisplayed()
        captureDialog("backup-webdav-large")
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
    }

    @Test fun importPreviewGroupsStayReachableWithLargeFonts() {
        val preview = ImportPreview(UUID.randomUUID().toString(), 1, 1, 0, 2,
            listOf(ImportConflict("profile:0", "profile", 1), ImportConflict("trust:0", "trust", 1, true)), true, null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { MaterialTheme {
                BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(preview = preview)), callbacks())
            } }
        }
        listOf(R.string.backup_information, R.string.backup_import_mode, R.string.backup_data_conflicts, R.string.backup_trust_conflicts).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.backup_apply)).assertIsDisplayed()
        captureDialog("backup-import-large")
    }

    private fun captureDialog(name: String) {
        java.io.File(context.externalCacheDir, "$name.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun busyStateDisablesManagementAndShowsStatusAboveActions() {
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", ""),
                BackupOperationState(phase = BackupPhase.UPLOADING, rememberedManual = true, rememberedRemote = true)), callbacks())
        } }
        compose.onNodeWithTag("backup_status").assertIsDisplayed()
        val status = compose.onNodeWithTag("backup_status").getUnclippedBoundsInRoot()
        assertTrue(status.bottom <= compose.onNodeWithText(text(R.string.backup_export_action)).getUnclippedBoundsInRoot().top)
        listOf(R.string.backup_export_action, R.string.backup_import_action, R.string.backup_local_history,
            R.string.backup_upload_action, R.string.ui_download_and_import, R.string.backup_remote_history,
            R.string.backup_edit_webdav, R.string.backup_forget_manual, R.string.backup_forget_remote, R.string.backup_remove_webdav).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun replacementStillRequiresIndividualTrustSelection() {
        var decision: ImportDecision? = null
        val preview = ImportPreview(UUID.randomUUID().toString(), 1, 1, 0, 2,
            listOf(ImportConflict("profile:0", "profile", 1), ImportConflict("trust:0", "trust", 1, true)), false, null)
        compose.setContent { MaterialTheme {
            BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null, BackupOperationState(preview = preview)), callbacks(commit = { decision = it }))
        } }
        compose.onNodeWithText(text(R.string.backup_replace)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_replace)).assertIsOn()
        compose.onNodeWithText(text(R.string.backup_data_conflicts)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_trust_conflicts)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_apply)).performClick()
        compose.runOnIdle { assertTrue(decision!!.replace); assertTrue(decision!!.useIncoming.isEmpty()) }
    }

    @Test fun narrowChineseLayoutStacksActions() = verifyLayout(320, 1f, Locale.SIMPLIFIED_CHINESE, false, true)
    @Test fun chineseLayoutPairsActions() = verifyLayout(440, 1f, Locale.SIMPLIFIED_CHINESE, false, false)
    @Test fun narrowTabletDetailStacksActions() = verifyLayout(280, 1f, Locale.ENGLISH, false, true)
    @Test fun wideEnglishLayoutPairsActions() = verifyLayout(440, 1f, Locale.ENGLISH, false, false)
    @Test fun largeEnglishDarkLayoutStacksActions() = verifyLayout(440, 2f, Locale.ENGLISH, true, true)
    @Test fun shortChineseDarkLayoutKeepsManagementReachable() = verifyLayout(440, 1f, Locale.SIMPLIFIED_CHINESE, true, false, 320)

    @Test fun wideTabletDetailCapsAndCentersContent() {
        val density = Density(1.5f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) { MaterialTheme {
                Surface(Modifier.width(800.dp).height(700.dp).testTag("tablet_detail")) {
                    BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, null), callbacks(), Modifier.fillMaxSize())
                }
            } }
        }
        val host = compose.onNodeWithTag("tablet_detail").fetchSemanticsNode().boundsInRoot
        val primary = compose.onNodeWithText(text(R.string.backup_export_action)).fetchSemanticsNode().boundsInRoot
        val secondary = compose.onNodeWithText(text(R.string.backup_import_action)).fetchSemanticsNode().boundsInRoot
        assertEquals(host.center.x, (primary.left + secondary.right) / 2f, 1f)
        with(density) { assertTrue(secondary.right - primary.left <= 640.dp.toPx()) }
        assertTrue(primary.left > host.left && secondary.right < host.right)
    }

    /** Exercises actual semantics and bounds using empty, synthetic state; captures contain no user data. */
    private fun verifyLayout(width: Int, fontScale: Float, locale: Locale, dark: Boolean, stacked: Boolean, height: Int = 760) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.width(width.dp).height(height.dp)) {
                        BackupSettingsPage(BackupSettingsState(VaultStatus.Ready, WebDavConfig("", "", "")), callbacks())
                    }
                }
            }
        }
        val first = compose.onNodeWithText(localized.getString(R.string.backup_export_action)).getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText(localized.getString(R.string.backup_import_action)).getUnclippedBoundsInRoot()
        if (stacked) assertTrue(first.bottom <= second.top) else {
            assertEquals(first.top.value, second.top.value, 1f)
            assertTrue(first.right <= second.left)
            assertEquals((first.right - first.left).value, (second.right - second.left).value, 1f)
        }
        listOf(R.string.backup_local_history, R.string.backup_upload_action, R.string.ui_download_and_import,
            R.string.backup_remote_history, R.string.backup_edit_webdav, R.string.backup_remove_webdav).forEach {
            compose.onNodeWithText(localized.getString(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(localized.getString(R.string.backup_file_section)).performScrollTo()
        java.io.File(context.externalCacheDir, "backup-layout-$width-$fontScale-${locale.language}-$dark-$height.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun remoteConflictActionsAreCompactAndFullyVisible() {
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            RemoteBackupConflictDialog(false, { calls += "download" }, { calls += "overwrite" }, { calls += "cancel" })
        } }
        val actions = listOf(R.string.backup_download_merge, R.string.backup_overwrite, R.string.common_cancel)
            .map { compose.onNodeWithText(text(it)) }
        actions.forEach { action ->
            action.assertIsDisplayed()
            val visible = action.fetchSemanticsNode().boundsInRoot
            val full = action.getUnclippedBoundsInRoot()
            with(compose.density) { assertEquals((full.bottom - full.top).toPx(), visible.height, 1f) }
        }
        actions.zipWithNext().forEach { (first, second) ->
            val gap = second.getUnclippedBoundsInRoot().top - first.getUnclippedBoundsInRoot().bottom
            assertTrue("Actions must not overlap or leave large gaps", gap.value in 0f..16f)
        }
        // This synthetic dialog contains no vault data; keep an artifact for visual regression review.
        java.io.File(context.externalCacheDir, "remote-conflict-dialog.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        actions.forEach { it.performClick() }
        compose.runOnIdle { assertEquals(listOf("download", "overwrite", "cancel"), calls) }
    }

    @Test fun remoteConflictActionsRemainReachableWithLargeFonts() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { RemoteBackupConflictDialog(false, {}, {}, {}) }
            }
        }
        listOf(R.string.backup_download_merge, R.string.backup_overwrite, R.string.common_cancel).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed().performClick()
        }
    }

    @Test fun remoteConflictDisablesAllChoicesWhileUploading() {
        compose.setContent { MaterialTheme { RemoteBackupConflictDialog(true, {}, {}, {}) } }
        listOf(R.string.backup_download_merge, R.string.backup_overwrite, R.string.common_cancel).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsNotEnabled()
        }
    }
}
