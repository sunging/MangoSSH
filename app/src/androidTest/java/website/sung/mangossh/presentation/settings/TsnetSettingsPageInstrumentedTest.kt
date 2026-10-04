package website.sung.mangossh.presentation.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.session.tsnet.EmbeddedTsnetBuildInfo
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus
import website.sung.mangossh.session.tsnet.TsnetDevice
import website.sung.mangossh.session.tsnet.TsnetNetworkSnapshot
import website.sung.mangossh.session.tsnet.TsnetSelf

class TsnetSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lab = TsnetDevice(
        id = "n1",
        hostName = "lab",
        dnsName = "lab.example.ts.net",
        os = "linux",
        addresses = listOf("100.64.0.2"),
        online = true,
        lastSeenEpochMillis = 0,
        tailscaleSshEnabled = true,
    )
    private val laptop = TsnetDevice(
        id = "n2",
        hostName = "laptop",
        dnsName = "laptop.example.ts.net",
        os = "windows",
        addresses = listOf("100.64.0.3"),
        online = false,
        lastSeenEpochMillis = 0,
        tailscaleSshEnabled = false,
    )
    private val network = TsnetNetworkSnapshot(
        self = TsnetSelf("mangossh-android-abc", "phone.example.ts.net", listOf("100.64.0.1")),
        devices = listOf(lab, laptop),
    )

    @Test
    fun pageShowsBothTheEnrollmentCardAndTheVendoredVersionLine() {
        setPage(TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED)))

        composeRule.onNodeWithTag("embedded_tsnet_status").assertIsDisplayed()
        composeRule.onNodeWithTag("embedded_tsnet_version")
            .assertIsDisplayed()
            .assertTextEquals("Embedded node version ${EmbeddedTsnetBuildInfo.TAILSCALE_VERSION}")
    }

    @Test
    fun unresolvedIdentityOffersNoSignIn() {
        setPage(
            TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED, identityResolved = false)),
        )

        composeRule.onNodeWithTag("embedded_tsnet_status").assertTextEquals("Checking sign-in…")
        composeRule.onNodeWithTag("embedded_tsnet_browser_login").assertDoesNotExist()
        composeRule.onNodeWithTag("embedded_tsnet_auth_key_login").assertDoesNotExist()
    }

    @Test
    fun visiblePageHoldsTheNodeForBrowsing() {
        val browsing = mutableListOf<Boolean>()
        setPage(
            TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.READY_IDLE), nodeName = "mangossh-android-abc"),
            callbacks(onBrowsingChanged = { browsing += it }),
        )

        composeRule.waitForIdle()
        assertEquals(listOf(true), browsing)
        composeRule.onNodeWithTag("embedded_tsnet_self_name").assertTextEquals("mangossh-android-abc")
        composeRule.onNodeWithTag("embedded_tsnet_devices_placeholder").assertIsDisplayed()
    }

    @Test
    fun runningNodeListsDevicesWithTailscaleSshState() {
        setPage(TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.ACTIVE), network = network))

        composeRule.onNodeWithTag("embedded_tsnet_status").assertTextEquals("Signed in · connected")
        composeRule.onNodeWithTag("embedded_tsnet_self_name").assertTextEquals("phone")
        composeRule.onNodeWithText("Tailnet devices · 1 online").assertIsDisplayed()
        // The clickable row merges its children for accessibility.
        composeRule.onNodeWithTag("embedded_tsnet_device_ssh_n1", useUnmergedTree = true)
            .assertTextEquals("Tailscale SSH on")
        composeRule.onNodeWithTag("embedded_tsnet_device_ssh_n2", useUnmergedTree = true)
            .assertTextEquals("Tailscale SSH off")
        composeRule.onNodeWithTag("embedded_tsnet_device_connect_n2").assertIsNotEnabled()
    }

    @Test
    fun quickConnectDefaultsToTailscaleSshForAnSshDevice() {
        var connected: ConnectionProfile? = null
        setPage(
            TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.ACTIVE), network = network),
            callbacks(onQuickConnect = { connected = it }),
        )

        composeRule.onNodeWithTag("embedded_tsnet_device_connect_n1").performClick()
        composeRule.onNodeWithTag("embedded_tsnet_quick_connect_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("embedded_tsnet_quick_connect_username").performTextInput("root")
        composeRule.onNodeWithTag("embedded_tsnet_quick_connect_confirm").performClick()

        val profile = requireNotNull(connected)
        assertEquals("lab.example.ts.net", profile.hostname)
        assertEquals("root", profile.username)
        assertEquals(ConnectionRoute.TSNET, profile.route)
        assertEquals(AuthenticationMethod.TAILSCALE_SSH, profile.authentication)
    }

    @Test
    fun connectReusesTheOnlySavedHostForTheDevice() {
        val saved = ConnectionProfile(
            id = "saved",
            label = "Lab",
            hostname = "lab",
            username = "me",
            route = ConnectionRoute.TSNET,
        )
        var connectedSaved: ConnectionProfile? = null
        var quick: ConnectionProfile? = null
        setPage(
            TsnetSettingsState(EmbeddedTsnetStatus(EmbeddedTsnetPhase.ACTIVE), network = network, hosts = listOf(saved)),
            callbacks(onConnectSavedHost = { connectedSaved = it }, onQuickConnect = { quick = it }),
        )

        composeRule.onNodeWithTag("embedded_tsnet_device_connect_n1").performClick()

        assertEquals(saved, connectedSaved)
        assertNull(quick)
    }

    private fun setPage(state: TsnetSettingsState, callbacks: TsnetSettingsCallbacks = callbacks()) {
        composeRule.setContent {
            MaterialTheme {
                TsnetSettingsPage(state = state, callbacks = callbacks)
            }
        }
    }

    private fun callbacks(
        onBrowsingChanged: (Boolean) -> Unit = {},
        onConnectSavedHost: (ConnectionProfile) -> Unit = {},
        onQuickConnect: (ConnectionProfile) -> Unit = {},
    ) = TsnetSettingsCallbacks(
        onBeginBrowserEnrollment = {},
        onBeginAuthKeyEnrollment = { _, _ -> },
        onLogout = {},
        onBrowsingChanged = onBrowsingChanged,
        onConnectSavedHost = onConnectSavedHost,
        onQuickConnect = onQuickConnect,
    )
}
