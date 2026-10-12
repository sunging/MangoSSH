package website.sung.mangossh.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.TransferConflict
import website.sung.mangossh.session.TransferConflictAction
import website.sung.mangossh.session.TransferConflictDecision

/** Synthetic previews use memory callbacks only; no production ViewModel, preferences, vault, or transfers. */
class TransferConflictDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val conflict = TransferConflict("preview-layout-test", "synthetic-upload.png", ScpTransferDirection.UPLOAD, false, true)
    private fun text(id: Int) = context.getString(id)
    private fun option(id: Int) = compose.onNode(hasText(text(id)) and isToggleable())

    @Test fun optionsAreCenteredAndActionsAreEvenlyDistributed() {
        compose.setContent { MaterialTheme { TransferConflictDialog(conflict) {} } }
        capture("regular")
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        listOf(R.string.transfer_verify_sha256, R.string.transfer_apply_task).forEach { id ->
            val row = option(id).assertIsOff().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(text(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Label must be vertically centered in its checkbox row", row.center.y, label.center.y, 1f)
        }
        val actions = listOf(R.string.transfer_skip, R.string.editor_save_as, R.string.editor_save)
            .map { compose.onNodeWithText(text(it)).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        actions.zipWithNext().forEach { (first, second) ->
            assertEquals("Normal-size actions must share one row", first.fetchSemanticsNode().boundsInRoot.center.y,
                second.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
        assertEvenlyDistributed(actions.map { it.getUnclippedBoundsInRoot() },
            option(R.string.transfer_verify_sha256).getUnclippedBoundsInRoot())
    }

    @Test fun clickingLabelsTogglesOnceAndSkipCarriesBothChoices() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme { TransferConflictDialog(conflict) { decision = it } } }
        // Hit the text rather than the indicator to exercise the entire row's touch target.
        listOf(R.string.transfer_verify_sha256, R.string.transfer_apply_task).forEach { id ->
            compose.onNodeWithText(text(id), useUnmergedTree = true).performTouchInput { click() }
            option(id).assertIsOn()
        }
        compose.onNodeWithText(text(R.string.transfer_skip)).performClick()
        compose.runOnIdle { assertEquals(TransferConflictDecision(TransferConflictAction.SKIP, applyToTask = true, verifySha256 = true), decision) }
    }

    @Test fun saveUsesUnselectedDefaults() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme { TransferConflictDialog(conflict) { decision = it } } }
        compose.onNodeWithText(text(R.string.editor_save)).performClick()
        compose.runOnIdle { assertEquals(TransferConflictDecision(TransferConflictAction.REPLACE), decision) }
    }

    @Test fun atomicReplaceCarriesCurrentChoices() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme { TransferConflictDialog(conflict.copy(targetExists = true)) { decision = it } } }
        compose.onNodeWithText(text(R.string.transfer_target_exists)).assertIsDisplayed()
        option(R.string.transfer_verify_sha256).performClick().assertIsOn()
        option(R.string.transfer_apply_task).performClick().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(text(R.string.editor_save)).performClick()
        compose.runOnIdle { assertEquals(TransferConflictDecision(TransferConflictAction.REPLACE, verifySha256 = true), decision) }
    }

    @Test fun uploadSaveAsNeedsAValidNameAndCarriesChoices() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme { TransferConflictDialog(conflict) { decision = it } } }
        option(R.string.transfer_verify_sha256).performClick()
        option(R.string.transfer_apply_task).performClick()
        compose.onNodeWithText(text(R.string.editor_save_as)).performClick()
        compose.runOnIdle { assertNull(decision) }
        val save = compose.onAllNodesWithText(text(R.string.editor_save)).onLast()
        save.assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("../invalid")
        save.assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("synthetic-renamed.png")
        save.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(TransferConflictDecision(TransferConflictAction.SAVE_AS, alternateName = "synthetic-renamed.png",
                applyToTask = true, verifySha256 = true), decision)
        }
    }

    @Test fun directOverwriteCarriesChoicesOnlyAfterConfirmation() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme {
            TransferConflictDialog(conflict.copy(targetExists = true, atomicReplace = false)) { decision = it }
        } }
        option(R.string.transfer_verify_sha256).performClick()
        option(R.string.transfer_apply_task).performClick()
        compose.onNodeWithText(text(R.string.editor_direct)).performClick()
        compose.onNodeWithText(text(R.string.editor_direct_risk)).assertIsDisplayed()
        compose.runOnIdle { assertNull(decision) }
        compose.onAllNodesWithText(text(R.string.editor_direct)).onLast().performClick()
        compose.runOnIdle {
            assertEquals(TransferConflictDecision(TransferConflictAction.DIRECT_OVERWRITE, applyToTask = true, verifySha256 = true), decision)
        }
    }

    @Test fun longNameAndLargeFontRemainReachableOnNarrowShortDialog() {
        val filename = "synthetic-long-upload-filename-0123456789-0123456789.png"
        var decision: TransferConflictDecision? = null
        compose.setContent {
            // Composition-local scaling never changes the device's font settings.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    TransferConflictDialog(conflict.copy(name = filename, direction = ScpTransferDirection.DOWNLOAD),
                        Modifier.width(280.dp).heightIn(max = 360.dp)) { decision = it }
                }
            }
        }
        compose.onNodeWithText(filename).performScrollTo().assertIsDisplayed()
        capture("large-narrow-short-top")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(filename).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The full long filename must wrap", layouts.single().lineCount > 1)
        assertEquals("Dialog must actually use the requested font scale", 2f, layouts.single().layoutInput.density.fontScale, 0.01f)
        listOf(R.string.transfer_verify_sha256, R.string.transfer_apply_task).forEach { id ->
            option(id).performScrollTo().assertIsDisplayed().performClick().assertIsOn()
            val row = option(id).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(text(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(row.center.y, label.center.y, 1f)
        }
        listOf(R.string.transfer_skip, R.string.editor_save_as, R.string.editor_save).forEach { id ->
            val action = compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
            val visible = action.fetchSemanticsNode().boundsInRoot
            val full = action.getUnclippedBoundsInRoot()
            with(compose.density) { assertEquals((full.bottom - full.top).toPx(), visible.height, 1f) }
        }
        val actions = listOf(R.string.transfer_skip, R.string.editor_save_as, R.string.editor_save)
            .map { compose.onNodeWithText(text(it)).getUnclippedBoundsInRoot() }
        assertTrue("Large-font actions must wrap to another row", actions.last().top > actions.first().top)
        actions.zipWithNext().forEach { (first, second) ->
            if (first.top != second.top) {
                assertTrue("Wrapped actions must not overlap", second.top >= first.bottom)
                assertEquals("Wrapped rows must stay compact", 8f, (second.top - first.bottom).value, 1f)
            }
        }
        val contentBounds = option(R.string.transfer_verify_sha256).getUnclippedBoundsInRoot()
        actions.groupBy { it.top }.values.forEach { assertEvenlyDistributed(it, contentBounds) }
        capture("large-narrow-short")
        compose.onNodeWithText(text(R.string.editor_save)).performClick()
        compose.runOnIdle { assertEquals(TransferConflictDecision(TransferConflictAction.REPLACE, applyToTask = true, verifySha256 = true), decision) }
    }

    private fun assertEvenlyDistributed(actions: List<DpRect>, content: DpRect) {
        val gaps = listOf((actions.first().left - content.left).value) +
            actions.zipWithNext().map { (first, second) -> (second.left - first.right).value } +
            (content.right - actions.last().right).value
        gaps.forEach {
            assertTrue("Actions must stay inside the content without overlapping", it >= 0f)
            assertEquals("Outer margins and gaps must be equal to center and distribute the actions", gaps.first(), it, 1f)
        }
    }

    private fun capture(name: String) {
        val dialog = compose.onNode(isDialog())
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            dialog.captureToImage().asAndroidBitmap()
        } else {
            // Compose's dialog capture requires API 28. Draw only the synthetic dialog's
            // view on older supported devices, excluding the status bar and other apps.
            val view = (dialog.fetchSemanticsNode().root as ViewRootForTest).view
            compose.runOnIdle {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
        }
        File(context.externalCacheDir, "transfer-preview-test-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
