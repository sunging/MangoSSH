package website.sung.mangossh.presentation.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
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
import java.util.Locale

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

    private fun about(
        callbacks: AboutSettingsCallbacks = AboutSettingsCallbacks({}, {}),
        state: AboutSettingsState = AboutSettingsState("1.2.3", 123, "339054b293f5", "github"),
        context: Context = isolatedContext,
        width: Int = 360,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    AboutSettingsPage(state, callbacks, Modifier.width(width.dp))
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
        compose.onNodeWithText(base.getString(R.string.settings_about_build, "339054b293f5", "github")).assertIsDisplayed()
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

    @Test fun englishGithubBuildUsesHyphenatedIdentity() = verifyBuildIdentity("en", "github")

    @Test fun englishFdroidBuildUsesHyphenatedIdentity() = verifyBuildIdentity("en", "fdroid")

    @Test fun chineseGithubBuildUsesHyphenatedIdentity() = verifyBuildIdentity("zh-Hans-CN", "github")

    @Test fun chineseFdroidBuildUsesHyphenatedIdentity() = verifyBuildIdentity("zh-Hans-CN", "fdroid")

    @Test fun missingRevisionUsesLocalizedEnglishFallback() = verifyBuildIdentity("en", "github", "")

    @Test fun missingRevisionUsesLocalizedChineseFallback() = verifyBuildIdentity("zh-Hans-CN", "fdroid", "")

    @Test fun longBuildIdentityWrapsOnNarrowScreenWithLargeEnglishText() =
        verifyBuildIdentity("en", "github", width = 240, fontScale = 2f)

    @Test fun longBuildIdentityWrapsOnNarrowScreenWithLargeChineseText() =
        verifyBuildIdentity("zh-Hans-CN", "fdroid", width = 240, fontScale = 2f)

    /** Locale overrides and synthetic state stay inside this composition and the isolated crash directory. */
    private fun verifyBuildIdentity(languageTag: String, distribution: String, revision: String = "339054b293f5",
        width: Int = 360, fontScale: Float = 1f) {
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        val localized = base.createConfigurationContext(configuration)
        val context = object : ContextWrapper(isolatedContext) {
            override fun getResources(): Resources = localized.resources
        }
        about(state = AboutSettingsState("1.2.3", 123, revision, distribution), context = context,
            width = width, fontScale = fontScale)
        val displayedRevision = revision.ifEmpty { localized.getString(R.string.settings_about_build_unknown) }
        val text = localized.getString(R.string.settings_about_build, displayedRevision, distribution)
        val identity = compose.onNodeWithTag("about_build_identity").performScrollTo().assertIsDisplayed()
        identity.assertTextEquals(text)
        identity.assertTextContains("$displayedRevision-$distribution", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        identity.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Build identity must remain readable without clipping: size=${layout.size}, " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        if (fontScale > 1f) assertTrue("Long build identity must wrap", layouts.single().lineCount > 1)
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
