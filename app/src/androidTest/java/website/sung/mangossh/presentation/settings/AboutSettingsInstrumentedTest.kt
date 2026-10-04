package website.sung.mangossh.presentation.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import java.io.File
import java.util.UUID

/** Uses only a unique test directory; never reads or clears the developer's crash report. */
class AboutSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var isolatedContext: Context
    private var sharedIntent: Intent? = null

    @Before
    fun isolateCrashStorage() {
        directory = File(base.cacheDir, "about-test-${UUID.randomUUID()}").apply { mkdirs() }
        isolatedContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun startActivity(intent: Intent) { sharedIntent = intent }
        }
    }

    @After
    fun removeTestStorage() {
        directory.deleteRecursively()
    }

    private fun about(callbacks: AboutSettingsCallbacks = AboutSettingsCallbacks({}, {})) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides isolatedContext) {
                MaterialTheme {
                    AboutSettingsPage(AboutSettingsState("1.2.3", 123), callbacks)
                }
            }
        }
    }

    @Test
    fun landingPageHasActionsWithoutExpandedNotices() {
        var repositoryOpened = false
        var licensesOpened = false
        about(AboutSettingsCallbacks({ repositoryOpened = true }, { licensesOpened = true }))
        compose.onNodeWithText(base.getString(R.string.settings_about_version, "1.2.3", 123L)).assertIsDisplayed()
        compose.onNodeWithTag("settings_about_release_page").performClick()
        compose.onNodeWithTag("settings_about_licenses").performClick()
        compose.runOnIdle {
            assertTrue(repositoryOpened)
            assertTrue(licensesOpened)
        }
        compose.onNodeWithTag("about_crash_report_card").assertDoesNotExist()
        compose.onNodeWithTag("about_mosh_gpl_card").assertDoesNotExist()
        compose.onNodeWithTag("about_license_OkHttp").assertDoesNotExist()
    }

    @Test
    fun storedReportCanBeViewedSharedAndDiscardedInIsolation() {
        val report = "MangoSSH crash report\nthrown: java.lang.IllegalStateException"
        val reportFile = File(directory, "crash/last-crash.txt").apply {
            parentFile!!.mkdirs()
            writeText(report)
        }
        about()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("settings_about_crash_report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings_about_crash_report").performScrollTo().performClick()
        compose.onNodeWithText(report).assertExists()
        compose.onNodeWithText(base.getString(R.string.settings_about_crash_report_share)).performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CHOOSER, sharedIntent?.action)
            @Suppress("DEPRECATION")
            val send = sharedIntent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertEquals(report, send?.getStringExtra(Intent.EXTRA_TEXT))
        }
        compose.onNodeWithText(base.getString(R.string.settings_about_crash_report_clear)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("about_crash_report_card").fetchSemanticsNodes().isEmpty()
        }
        assertFalse(reportFile.exists())
        compose.onNodeWithTag("about_crash_report_dialog").assertDoesNotExist()
        compose.onNodeWithTag("about_crash_report_card").assertDoesNotExist()
    }

    @Test
    fun everyNoticeLoadsVerbatimAndClosingPreservesListPosition() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(300.dp).height(400.dp)) { LicensesSettingsPage() } }
        }
        thirdPartyNotices.forEach { notice ->
            val tag = "about_license_${notice.name}"
            compose.onNodeWithTag("about_licenses_list").performScrollToNode(hasTestTag(tag))
            val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag(tag).performClick()
            val expected = base.assets.open(requireNotNull(notice.licenseAsset)).bufferedReader().use { it.readText() }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(notice.url).assertExists()
            notice.descriptionResource?.let { compose.onNodeWithText(base.getString(it)).assertExists() }
            compose.onNodeWithText(base.getString(R.string.common_close)).performClick()
            assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    fun loadingNoticeRemainsReadableUntilTextArrives() {
        val loaded = CompletableDeferred<String?>()
        val notice = thirdPartyNotices.first()
        compose.setContent {
            MaterialTheme { LicenseTextDialog(notice, {}, loadText = { _, _ -> loaded.await() }) }
        }
        compose.onNodeWithText(base.getString(R.string.settings_about_license_loading)).assertIsDisplayed()
        compose.onNodeWithText(notice.url).assertIsDisplayed()
        loaded.complete("License text supplied by the test")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("License text supplied by the test").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(base.getString(R.string.settings_about_license_loading)).assertDoesNotExist()
    }

    @Test
    fun missingLicenseShowsFailureAndKeepsAttribution() {
        val notice = thirdPartyNotices.first().copy(licenseAsset = "licenses/missing-test-license.txt")
        compose.setContent { MaterialTheme { LicenseTextDialog(notice, {}) } }
        val message = base.getString(R.string.settings_about_license_unavailable)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(notice.url).assertExists()
        compose.onNodeWithText(message).assertIsDisplayed()
    }
}
