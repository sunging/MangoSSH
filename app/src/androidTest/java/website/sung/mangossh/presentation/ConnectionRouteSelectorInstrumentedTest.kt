package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionRoute

class ConnectionRouteSelectorInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allThreeRoutesRemainUsableAtNarrowWidth() {
        var selected = ConnectionRoute.DIRECT
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    ConnectionRouteSelector(selected = selected, onSelected = { selected = it })
                }
            }
        }

        ConnectionRoute.entries.forEach { route ->
            composeRule.onNodeWithTag("connection_route_${route.name}").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("connection_route_TSNET").performClick()
        composeRule.runOnIdle { assertEquals(ConnectionRoute.TSNET, selected) }
        assertEquals(
            AuthenticationMethod.TAILSCALE_SSH,
            authenticationAfterRouteSelection(
                ConnectionRoute.TSNET,
                AuthenticationMethod.PRIVATE_KEY,
            ),
        )
        assertEquals(
            AuthenticationMethod.PASSWORD,
            authenticationAfterRouteSelection(
                ConnectionRoute.DIRECT,
                AuthenticationMethod.PASSWORD,
            ),
        )
    }
}
