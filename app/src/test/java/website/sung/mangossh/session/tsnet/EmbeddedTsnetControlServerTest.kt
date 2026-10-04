package website.sung.mangossh.session.tsnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTsnetControlServerTest {
    @Test
    fun blankAndTailscaleServersMeanTheDefault() {
        assertEquals("", EmbeddedTsnetControlServer.normalize(""))
        assertEquals("", EmbeddedTsnetControlServer.normalize("   "))
        assertEquals("", EmbeddedTsnetControlServer.normalize("https://controlplane.tailscale.com/"))
        assertEquals("", EmbeddedTsnetControlServer.normalize("https://LOGIN.tailscale.com:443"))
    }

    @Test
    fun selfHostedServersAreCanonicalized() {
        assertEquals(
            "https://headscale.example.com",
            EmbeddedTsnetControlServer.normalize("  https://Headscale.Example.com/ "),
        )
        assertEquals(
            "https://headscale.example.com:8443/hs",
            EmbeddedTsnetControlServer.normalize("HTTPS://headscale.example.com:8443/hs/"),
        )
        assertEquals("https://headscale.example.com", EmbeddedTsnetControlServer.normalize("https://headscale.example.com:443"))
        assertEquals("https://[fd7a::1]:8080", EmbeddedTsnetControlServer.normalize("https://[fd7a::1]:8080"))
    }

    @Test
    fun unsafeOrIncompleteServersAreRejected() {
        listOf(
            "http://headscale.example.com",
            "headscale.example.com",
            "https://",
            "https:headscale",
            "https://user:pass@headscale.example.com",
            "https://headscale.example.com/?key=value",
            "https://headscale.example.com/#fragment",
            "https://bad host",
            "https://" + "a".repeat(EmbeddedTsnetControlServer.MAX_LENGTH),
        ).forEach { assertNull(it, EmbeddedTsnetControlServer.normalize(it)) }
    }

    @Test
    fun defaultServerOnlyOpensTailscaleSignIn() {
        assertTrue(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://login.tailscale.com/a/abc", ""))
        assertFalse(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("http://login.tailscale.com/a/abc", ""))
        assertFalse(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://login.tailscale.com:8443/a", ""))
        assertFalse(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://evil.example/a", ""))
        assertFalse(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://x@login.tailscale.com/a", ""))
    }

    @Test
    fun selfHostedServerOnlyOpensItsOwnSignIn() {
        val server = "https://headscale.example.com:8443/hs"
        assertTrue(
            EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://headscale.example.com:8443/register/k", server),
        )
        assertFalse(
            EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://headscale.example.com/register/k", server),
        )
        assertFalse(EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("https://login.tailscale.com/a/abc", server))
        assertFalse(
            EmbeddedTsnetControlServer.isAllowedAuthorizationUrl("http://headscale.example.com:8443/register/k", server),
        )
        assertTrue(
            EmbeddedTsnetControlServer.isAllowedAuthorizationUrl(
                "https://headscale.example.com/register/k",
                "https://headscale.example.com",
            ),
        )
    }
}
