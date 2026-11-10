package website.sung.mangossh.presentation.settings

import android.content.ClipData
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.domain.AppThemeMode
import website.sung.mangossh.domain.AppThemePreferences
import website.sung.mangossh.presentation.AppLockScreen
import website.sung.mangossh.presentation.PinVerificationState
import website.sung.mangossh.presentation.SensitivePinDialog
import website.sung.mangossh.security.AppLockConfiguration
import website.sung.mangossh.ui.theme.MangoSshTheme

/** Exercises each PIN entry with transient inputs and isolated callbacks. */
class AppPinUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val captureRequested get() = arguments.getString("capturePinPreview") == "true"
    private val previewLocale get() = if (arguments.getString("pinPreviewLocale") == "zh-CN") {
        Locale.SIMPLIFIED_CHINESE
    } else Locale.ENGLISH
    private val previewMode get() = if (arguments.getString("pinPreviewDark") == "true") {
        AppThemeMode.DARK
    } else AppThemeMode.LIGHT
    private val resources by lazy {
        if (captureRequested) context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(previewLocale)
        }).resources else context.resources
    }
    private val random = SecureRandom()
    private val configuration = AppLockConfiguration(false, false)
    private fun text(id: Int) = resources.getString(id)
    private fun pin(size: Int) = CharArray(size) { ('0'.code + random.nextInt(10)).toChar() }.concatToString()
    private fun different(pin: String) = pin.dropLast(1) + ((pin.last().digitToInt() + 1) % 10)
    private fun callbacks(save: (String) -> Unit = {}) = SecuritySettingsCallbacks(save, {}, {}, {}, {})
    private fun submit() = compose.onNodeWithTag("pin_submit")
    private fun secondary() = compose.onNodeWithTag("pin_secondary")
    private fun backspace() = compose.onNodeWithTag("pin_backspace")
    private fun progress() = compose.onNodeWithTag("pin_progress", useUnmergedTree = true)
    private fun digit(value: Char) = compose.onNodeWithTag("pin_digit_$value")
    private fun enter(pin: String) = pin.forEach { digit(it).performScrollTo().performClick() }
    private fun setup() = compose.onNodeWithText(text(R.string.ui_set_pin)).performClick()

    @Test fun setupRequiresFourDigitsAndMatchingConfirmation() {
        val valid = pin(4)
        var saved: String? = null
        setSettings { saved = it }
        setup()
        capturePreview("setup", compose.onNode(isDialog()))
        assertNoEditableField()
        assertEnteredDigits(0)
        enter(valid.take(3))
        submit().assertIsNotEnabled()
        digit(valid.last()).performScrollTo().performClick()
        assertEnteredDigits(4)
        submit().performClick()
        compose.onNodeWithText(text(R.string.pin_confirm_title)).assertIsDisplayed()
        assertEnteredDigits(0)
        capturePreview("confirm", compose.onNode(isDialog()))
        compose.runOnIdle { assertNull(saved) }

        enter(different(valid))
        submit().performClick()
        compose.onNodeWithText(text(R.string.pin_mismatch)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pin_confirm_title)).performScrollTo().assertIsDisplayed()
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }

        enter(valid)
        submit().performClick()
        compose.runOnIdle { assertTrue(saved == valid) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun setupCapsTheDraftAtTwelveDigits() {
        val valid = pin(12)
        var saved: String? = null
        setSettings { saved = it }
        setup()
        enter(valid)
        digit(valid.first()).assertIsNotEnabled().performScrollTo().performTouchInput { click() }
        assertEnteredDigits(12)
        submit().performClick()
        enter(valid)
        submit().performClick()
        compose.runOnIdle { assertTrue(saved == valid) }
    }

    @Test fun setupBackAndCancelDiscardBothDrafts() {
        val valid = pin(4)
        setSettings { fail("Cancelled setup called the save callback") }
        setup()
        enter(valid)
        submit().performClick()
        enter(valid.take(3))
        secondary().performClick()
        compose.onNodeWithText(text(R.string.pin_new_title)).assertIsDisplayed()
        assertEnteredDigits(0)
        enter(valid)
        secondary().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()

        setup()
        compose.onNodeWithText(text(R.string.pin_new_title)).assertIsDisplayed()
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
    }

    @Test fun setupRestoresTheDialogAtTheFirstStepWithEmptyDrafts() {
        val restoration = StateRestorationTester(compose)
        val beforeRestore = pin(4)
        val afterRestore = different(beforeRestore)
        var saved: String? = null
        restoration.setContent {
            PinTestTheme { SecuritySettingsPage(SecuritySettingsState(configuration), callbacks { saved = it }) }
        }
        setup()
        enter(beforeRestore)
        submit().performClick()
        enter(beforeRestore)
        submit().assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.pin_new_title)).assertIsDisplayed()
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
        enter(afterRestore)
        submit().performClick()
        assertEnteredDigits(0)
        enter(afterRestore)
        submit().performClick()
        compose.runOnIdle { assertTrue(saved == afterRestore) }
    }

    @Test fun lockScreenUsesOnlyTheKeypadAndClearsOnRestoreAndSubmit() {
        val restoration = StateRestorationTester(compose)
        val valid = pin(4)
        var supplied: String? = null
        lateinit var hostView: View
        restoration.setContent {
            PinTestTheme {
                val view = LocalView.current
                SideEffect { hostView = view }
                AppLockScreen(PinVerificationState(), configuration.copy(pinConfigured = true), null,
                    { supplied = it }, { fail("Biometric unlock is disabled") }, {})
            }
        }
        capturePreview("unlock", compose.onRoot())
        secondary().assertDoesNotExist()
        assertNoEditableField()
        enter(valid)
        backspace().performScrollTo().performClick()
        assertEnteredDigits(3)
        submit().assertIsNotEnabled()
        backspace().performScrollTo().performTouchInput { longClick() }
        assertEnteredDigits(0)
        backspace().assertIsNotEnabled()

        enter(valid)
        assertNoEditableField()
        compose.runOnIdle {
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(hostView))
            assertFalse(insets.isVisible(WindowInsetsCompat.Type.ime()))
        }
        restoration.emulateSavedInstanceStateRestore()
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
        enter(valid)
        submit().performClick()
        compose.runOnIdle { assertTrue(supplied == valid) }
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
    }

    @Test fun lockScreenAutomaticallySubmitsOnlyWhenTheKnownLengthIsReached() {
        val valid = pin(6)
        var state by mutableStateOf(PinVerificationState())
        val submitted = mutableListOf<String>()
        compose.setContent {
            PinTestTheme {
                AppLockScreen(
                    state,
                    configuration.copy(pinConfigured = true, pinLength = valid.length),
                    null,
                    { submitted.add(it); state = PinVerificationState(busy = true) },
                    null,
                    {},
                )
            }
        }

        submit().assertDoesNotExist()
        enter(valid.dropLast(1))
        assertEnteredDigits(5)
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        enter(valid.takeLast(1))
        compose.runOnIdle { assertTrue(submitted.single() == valid) }
        assertEnteredDigits(0)
        digit(valid.first()).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.app_lock_verifying)).assertIsDisplayed()
    }

    @Test fun sensitiveVerificationDisablesInputDuringBusyAndCooldownAndCanBeCancelled() {
        val valid = pin(4)
        var state by mutableStateOf(PinVerificationState())
        var show by mutableStateOf(false)
        var supplied: String? = null
        compose.setContent {
            PinTestTheme {
                Button(onClick = { show = true }, modifier = Modifier.testTag("open_pin_verification")) {
                    Text(text(R.string.pin_verify_identity))
                }
                if (show) SensitivePinDialog(state, {
                    supplied = it
                    state = PinVerificationState(busy = true)
                }, { show = false }, { state = PinVerificationState() })
            }
        }
        val open = compose.onNodeWithTag("open_pin_verification")
        open.performClick()
        capturePreview("verify", compose.onNode(isDialog()))
        assertNoEditableField()
        enter(valid)
        submit().performClick()
        compose.runOnIdle { assertTrue(supplied == valid) }
        assertEnteredDigits(0)
        digit(valid.first()).assertIsNotEnabled()
        submit().assertIsNotEnabled()
        submit().assert(hasText(text(R.string.app_lock_verifying)))
        secondary().assertIsEnabled()

        val seconds = 3
        compose.runOnIdle { state = PinVerificationState(incorrect = true, cooldownSeconds = seconds) }
        compose.onNodeWithText(resources.getQuantityString(R.plurals.message_pin_cooldown, seconds, seconds))
            .assertIsDisplayed()
        digit(valid.first()).assertIsNotEnabled()
        submit().assertIsNotEnabled()
        secondary().assertIsEnabled()

        compose.runOnIdle { state = PinVerificationState(incorrect = true) }
        compose.onNodeWithText(text(R.string.message_pin_incorrect)).assertIsDisplayed()
        digit(valid.first()).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.message_pin_incorrect)).assertDoesNotExist()
        assertEnteredDigits(1)
        secondary().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()

        open.assertIsDisplayed().performClick()
        assertEnteredDigits(0)
        submit().assertIsNotEnabled()
    }

    @Test fun explicitPasteFiltersAndCapsDigitsWithoutChangingTheirCharacters() {
        val digits = pin(14).mapIndexed { index, digit ->
            if (index % 2 == 0) (0x0660 + digit.digitToInt()).toChar() else digit
        }
        val clipboard = TestClipboard(ClipEntry(ClipData.newPlainText("PIN fixture", digits.joinToString("-", prefix = "x"))))
        var supplied: String? = null
        compose.setContent {
            PinTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    SensitivePinDialog(PinVerificationState(), { supplied = it }, {})
                }
            }
        }
        progress().performScrollTo().performTouchInput { longClick() }
        val paste = compose.onNodeWithText(text(R.string.pin_entry_paste))
        paste.assertIsDisplayed()
        assertEnteredDigits(0)
        compose.runOnIdle { assertEquals(0, clipboard.reads) }
        paste.performClick()
        assertEnteredDigits(12)
        compose.runOnIdle { assertEquals(1, clipboard.reads) }
        assertNoEditableField()
        submit().performClick()
        compose.runOnIdle { assertTrue(supplied == digits.take(12).joinToString("")) }
    }

    @Test fun hardwareDigitBackspaceAndEnterUseTheSameDraft() {
        val valid = pin(4)
        val keys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        var supplied: String? = null
        compose.setContent {
            PinTestTheme {
                AppLockScreen(PinVerificationState(), configuration.copy(pinConfigured = true, biometricEnabled = true),
                    null, { supplied = it }, {}, {})
            }
        }
        val root = compose.onRoot()
        root.performKeyInput { valid.forEach { pressKey(keys[it.digitToInt()]) } }
        assertEnteredDigits(4)
        submit().assertIsEnabled()
        secondary().assertIsEnabled()
        capturePreview("unlock-biometric", root)

        root.performKeyInput {
            pressKey(Key.Backspace)
            pressKey(Key.Enter)
        }
        assertEnteredDigits(3)
        compose.runOnIdle { assertNull(supplied) }
        root.performKeyInput {
            pressKey(keys[valid.last().digitToInt()])
            pressKey(Key.Enter)
        }
        compose.runOnIdle { assertTrue(supplied == valid) }
        assertEnteredDigits(0)
    }

    @Test fun shortWideWindowWithLargeTextKeepsKeypadAndFooterReachable() {
        val size = DpSize(640.dp, 320.dp)
        val valid = pin(4)
        var supplied: String? = null
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size) then DeviceConfigurationOverride.FontScale(2f)) {
                PinTestTheme {
                    AppLockScreen(PinVerificationState(), configuration.copy(pinConfigured = true, biometricEnabled = true),
                        null, { supplied = it }, {}, {})
                }
            }
        }
        val title = compose.onNodeWithText(text(R.string.pin_enter_title))
        title.performScrollTo().assertIsDisplayed()
        secondary().assertIsDisplayed()
        submit().assertIsDisplayed()
        digit('0').performScrollTo().assertIsDisplayed()
        enter(valid)
        backspace().performScrollTo().assertIsDisplayed()
        secondary().assertIsDisplayed()
        submit().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(supplied == valid) }
    }

    private fun setSettings(onSave: (String) -> Unit) {
        compose.setContent { PinTestTheme { SecuritySettingsPage(SecuritySettingsState(configuration), callbacks(onSave)) } }
    }

    @Composable
    private fun PinTestTheme(content: @Composable () -> Unit) {
        val preferences = if (captureRequested) AppThemePreferences(previewMode, dynamicColorEnabled = false)
        else AppThemePreferences()
        CompositionLocalProvider(LocalResources provides resources) {
            MangoSshTheme(preferences, content)
        }
    }

    private fun assertNoEditableField() {
        val editable = hasSetTextAction() or SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        compose.onAllNodes(editable, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun assertEnteredDigits(count: Int) {
        val description = resources.getQuantityString(R.plurals.pin_entry_count, count, count)
        progress().assert(hasContentDescription(description))
    }

    /** The paste action sees only this fixture and cannot access the system clipboard. */
    private class TestClipboard(private var entry: ClipEntry?) : Clipboard {
        var reads = 0
            private set

        override suspend fun getClipEntry(): ClipEntry? {
            reads++
            return entry
        }

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
    }

    private fun capturePreview(name: String, node: SemanticsNodeInteraction) {
        if (Build.VERSION.SDK_INT < 28 || !captureRequested) return
        val directory = File(context.cacheDir, "pin-keypad-preview/${previewLocale.toLanguageTag()}-${previewMode.preferenceValue}")
            .apply { mkdirs() }
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
