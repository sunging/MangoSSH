package website.sung.mangossh.presentation

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.BackupPhase
import website.sung.mangossh.data.vault.PortableVaultCodec
import website.sung.mangossh.session.LocalDocumentTree

/** Real DocumentsUI and Activity transitions, restricted to this fixture's generated directory. */
class AppLockPickerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<LockRegressionActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var fixture: LockRegressionModel
    private lateinit var host: LockRegressionActivity
    private val model get() = fixture
    private var flags = 0
    private var ownsDirectory = false
    private var directory = ""
    private fun text(id: Int) = instrumentation.targetContext.getString(id)

    @Before fun prepare() {
        host = compose.activity
        fixture = host.model
        compose.waitUntil(30_000) { model.ready.value }
        directory = model.directoryName
        require(directory.matches(Regex("lock-regression-[0-9a-f-]{36}")))
        flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        assertNotEquals("/sdcard/Download/$directory", shell("ls -d /sdcard/Download/$directory"))
        assertEquals("", shell("mkdir /sdcard/Download/$directory"))
        ownsDirectory = true
    }

    @After fun cleanup() {
        if (model.platformPickerRequestCode != null) {
            cancelPicker()
        }
        automation.serviceInfo = automation.serviceInfo.apply { flags = this@AppLockPickerInstrumentedTest.flags }
        if (ownsDirectory) {
            // Every path is a fixed basename under the UUID directory created in prepare().
            listOf("mangossh-vault.mssh", "second-vault.mssh", "ssh_key.pub", "ssh_key.pem").forEach {
                shell("rm -f /sdcard/Download/$directory/$it")
            }
            assertEquals("", shell("rmdir /sdcard/Download/$directory"))
        }
        compose.runOnUiThread { model.dispose() }
    }

    private fun export() {
        model.lastUri = null
        compose.onNodeWithText(text(R.string.backup_export_action)).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(model.password)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(model.password)
        compose.onNodeWithText(text(R.string.common_continue)).performClick()
        awaitPicker { root -> visit(root) { it.isEditable && it.text?.toString() == "mangossh-vault.mssh" } }
        assertTrue(model.lock.documents.active)
        assertFalse(model.lock.locked.value)
    }

    @Test fun immediatePinExportWritesTwoDecryptableFilesWithoutRelocking() {
        for (name in listOf("mangossh-vault.mssh", "second-vault.mssh")) {
            export()
            if (name != "mangossh-vault.mssh") awaitPicker { root -> visit(root) { node ->
                node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                })
            } }
            save()
            compose.waitUntil(30_000) { model.backup.state.value.completed }
            assertFalse(model.lock.locked.value)
            assertFalse(model.lock.documents.active)
            val uri = checkNotNull(model.lastUri)
            val bytes = model.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue(bytes.isNotEmpty())
            assertEquals(name, LocalDocumentTree.displayName(model.context.contentResolver, uri))
            val snapshot = PortableVaultCodec.decrypt(bytes, model.password.toCharArray())
            assertTrue(snapshot.profiles.isEmpty())
            assertTrue(snapshot.keys.isEmpty())
            compose.runOnUiThread { model.backup.cancel() }
            compose.waitForIdle()
        }
    }

    @Test fun cancellingPickerDiscardsExportAndOrdinaryBackgroundStillLocks() {
        export()
        cancelPicker()
        compose.waitUntil(15_000) { !model.backup.state.value.exportReady && !model.lock.documents.active }
        assertFalse(model.lock.locked.value)
        assertTrue(model.lastResultWasCancelled)
        compose.runOnUiThread { model.lock.noteBackgrounded() }
        compose.onNodeWithText(text(R.string.ui_mangossh_is_locked)).assertIsDisplayed()
    }

    @Test fun rotatingHostWhilePickerIsOpenDoesNotCancelOrDuplicateExport() {
        val host = compose.activity
        val landscape = host.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        export()
        // ActivityScenario.recreate() waits for a resumed Activity and deadlocks while
        // DocumentsUI owns the foreground. Rotate the actual display instead.
        instrumentation.runOnMainSync {
            host.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        awaitPicker { root -> visit(root) { it.isEditable && it.text?.toString() == "mangossh-vault.mssh" } }
        assertTrue(model.lock.documents.active)
        save()
        compose.waitUntil(30_000) { model.backup.state.value.completed }
        assertFalse(model.lock.locked.value)
        assertNotNull(model.lastUri)
    }

    @Test fun screenOffDuringPickerRevokesResultAndCannotWriteAfterUnlock() {
        export()
        compose.runOnUiThread { model.lock.screenOff() }
        assertTrue(model.lock.locked.value)
        save()
        compose.onNodeWithText(text(R.string.ui_mangossh_is_locked)).assertIsDisplayed()
        assertNull(model.lastUri)
        assertFalse(model.backup.state.value.completed)
        assertEquals(BackupPhase.IDLE, model.backup.state.value.phase)
        compose.runOnUiThread { model.lock.unlockWithPin(model.pin) }
        compose.waitUntil(15_000) { !model.lock.busy.value }
        assertFalse(model.lock.locked.value)
        compose.waitForIdle()
        assertNull(model.lastUri)
        assertFalse(model.backup.state.value.completed)
    }

    @Test fun publicAndPrivateKeyNamesSurviveTheSystemSaveDialog() {
        compose.onNodeWithText("Keys fixture").performClick()
        for ((button, name) in listOf("Export public fixture" to "ssh_key.pub", "Export private fixture" to "ssh_key.pem")) {
            model.lastUri = null
            compose.onNodeWithText(button).performClick()
            awaitPicker { root -> visit(root) { it.isEditable && it.text?.toString() == name } }
            save()
            compose.waitUntil(15_000) { model.lastUri != null }
            val uri = checkNotNull(model.lastUri)
            assertEquals(name, LocalDocumentTree.displayName(model.context.contentResolver, uri))
            compose.waitUntil(15_000) {
                model.context.contentResolver.openInputStream(uri)!!.use { it.read() >= 0 }
            }
            assertFalse(model.lock.locked.value)
        }
    }

    private fun save() {
        var clicked = false
        awaitPicker { root -> visit(root) { node ->
            val id = node.viewIdResourceName.orEmpty()
            if (!clicked && node.isEnabled && (id.endsWith(":id/action_menu_save") || id == "android:id/button1")) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clicked
            } else false
        } }
        assertTrue(clicked)
        awaitHost()
    }

    /** Results can arrive before the host window and its Compose root have resumed. */
    private fun awaitHost() {
        compose.waitUntil(15_000) {
            compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED &&
                compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.waitForIdle()
    }

    /** Finishes exactly this external request; queued Back keys can otherwise also close the host. */
    private fun cancelPicker() {
        val code = checkNotNull(model.platformPickerRequestCode)
        instrumentation.runOnMainSync { host.finishActivity(code) }
        compose.waitUntil(15_000) {
            model.platformPickerRequestCode == null && !model.lock.documents.active
        }
        awaitHost()
    }

    private fun awaitPicker(action: (AccessibilityNodeInfo) -> Boolean) {
        compose.waitUntil(15_000) {
            automation.rootInActiveWindow?.let { root ->
                try { root.packageName?.toString()?.endsWith(".documentsui") == true && action(root) }
                finally { root.recycle() }
            } ?: false
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

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }
}
