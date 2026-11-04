package website.sung.mangossh

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.presentation.UserMessageDialog
import website.sung.mangossh.presentation.UiText
import website.sung.mangossh.ui.theme.MangoSshTheme
import java.io.File
import java.util.UUID

/** Uses isolated preferences and the production message dialog without touching the vault or permissions. */
class NotificationPermissionGateInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "notification-permission-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val gate = NotificationPermissionGate(preferences)

    @After fun cleanup() {
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun denialIsExplainedOnceAndLaterConnectionsDoNotRequestAgain() {
        assertTrue(gate.shouldRequest(granted = false))
        assertTrue(gate.onResult(granted = false))
        repeat(3) {
            assertFalse(gate.shouldRequest(granted = false))
            assertFalse(gate.onResult(granted = false))
        }
    }

    @Test fun denialSurvivesGateRecreation() {
        gate.onResult(granted = false)
        val recreated = NotificationPermissionGate(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        assertFalse(recreated.shouldRequest(granted = false))
        assertFalse(recreated.onResult(granted = false))
    }

    @Test fun grantDoesNotExplainAndResetsEarlierDenial() {
        gate.onResult(granted = false)
        assertFalse(gate.shouldRequest(granted = true))
        assertTrue(gate.shouldRequest(granted = false))
        assertFalse(gate.onResult(granted = true))
    }

    @Test fun deniedDialogEnglish() = checkDialog("en", "OK")

    @Test fun deniedDialogChinese() = checkDialog("zh-CN", context.createConfigurationContext(
        android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("zh-CN"))
        },
    ).getString(R.string.common_ok))

    private fun checkDialog(locale: String, dismissLabel: String) {
        var showExplanation by mutableStateOf(gate.onResult(granted = false))
        val localizedContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(locale))
            },
        )
        // The test host is a ComponentActivity, so its Dialog window keeps the host
        // context locale. Resolve this fixture from real localized resources first.
        val explanation = localizedContext.getString(R.string.notification_permission_denied)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext,
                LocalResources provides localizedContext.resources) {
                MangoSshTheme {
                    if (showExplanation) UserMessageDialog(UiText.Verbatim(explanation)) {
                        showExplanation = false
                    }
                }
            }
        }
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(explanation).assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("captureNotificationPreview") == "true") {
            val directory = File(context.cacheDir, "notification-permission-preview").apply { mkdirs() }
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(directory, "$locale.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithText(dismissLabel).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            repeat(3) { assertFalse(gate.shouldRequest(granted = false)) }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
