package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.AppRelease
import website.sung.mangossh.domain.AppReleaseAsset
import website.sung.mangossh.domain.AppVersion

class UpdateSettingsCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val release = AppRelease(
        version = AppVersion(0, 2, 0),
        tag = "v0.2.0",
        title = "MangoSSH 0.2.0",
        notes = "- A verbatim server-owned bullet point",
        htmlUrl = "https://github.com/sunging/MangoSSH/releases/tag/v0.2.0",
        publishedAtEpochMillis = null,
        apkAsset = AppReleaseAsset("MangoSSH-v0.2.0.apk", "https://example/apk", 100L),
        checksumAsset = AppReleaseAsset("SHA256SUMS", "https://example/sums", 10L),
    )

    private fun noopCallbacks() = UpdateCardCallbacks(
        onCheckNow = {},
        onDownload = {},
        onCancelDownload = {},
        onInstall = {},
        onDismissNotice = {},
        onSetAutomaticCheck = {},
        onOpenReleasePage = {},
    )

    @Test
    fun checkNowClickInvokesTheCallback() {
        var invoked = false
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(supported = true, phase = UpdatePhase.Idle),
                    callbacks = noopCallbacks().copy(onCheckNow = { invoked = true }),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_check_now").performClick()
        composeRule.runOnIdle { assertTrue(invoked) }
    }

    @Test
    fun availablePhaseShowsDownloadAndDismissNotInstall() {
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(supported = true, phase = UpdatePhase.Available(release)),
                    callbacks = noopCallbacks(),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_download").assertExists()
        composeRule.onNodeWithTag("app_update_dismiss").assertExists()
        composeRule.onNodeWithTag("app_update_install").assertDoesNotExist()
    }

    @Test
    fun downloadingPhaseShowsProgressAndCancelNotInstall() {
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(
                        supported = true,
                        phase = UpdatePhase.Downloading(release, downloadedBytes = 50L, totalBytes = 100L),
                    ),
                    callbacks = noopCallbacks(),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_progress").assertExists()
        composeRule.onNodeWithTag("app_update_cancel_download").assertExists()
        composeRule.onNodeWithTag("app_update_install").assertDoesNotExist()
        composeRule.onNodeWithTag("app_update_download").assertDoesNotExist()
    }

    @Test
    fun readyToInstallPhaseShowsInstall() {
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(supported = true, phase = UpdatePhase.ReadyToInstall(release)),
                    callbacks = noopCallbacks(),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_install").assertExists()
    }

    @Test
    fun togglingAutomaticCheckSwitchInvokesCallbackWithFalse() {
        var lastValue: Boolean? = null
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(supported = true, automaticCheckEnabled = true, phase = UpdatePhase.Idle),
                    callbacks = noopCallbacks().copy(onSetAutomaticCheck = { lastValue = it }),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_auto_check_switch").assertIsOn()
        composeRule.onNodeWithTag("app_update_auto_check_switch").performClick()
        composeRule.runOnIdle { assertEquals(false, lastValue) }
    }

    @Test
    fun releaseNotesDialogShowsTheServerOwnedBodyVerbatim() {
        composeRule.setContent {
            MaterialTheme {
                UpdateSettingsCard(
                    state = UpdateUiState(supported = true, phase = UpdatePhase.Available(release)),
                    callbacks = noopCallbacks(),
                )
            }
        }

        composeRule.onNodeWithTag("app_update_release_notes").performClick()
        composeRule.onNodeWithTag("app_update_release_notes_dialog").assertExists()
        composeRule.onNodeWithText(release.notes).assertExists()
    }
}
