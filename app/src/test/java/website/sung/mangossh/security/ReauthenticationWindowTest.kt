package website.sung.mangossh.security

import org.junit.Assert.*
import org.junit.Test

class ReauthenticationWindowTest {
    @Test fun lockAndExpiryInvalidateGrant() {
        val access = AppAccessState(false)
        var clock = 1L
        val window = ReauthenticationWindow(access) { clock }
        assertFalse(window.isValid())
        window.grant()
        assertTrue(window.isValid())
        clock += 300_000_000_000L
        assertFalse(window.isValid())
        window.grant()
        access.setLocked(true)
        access.setLocked(false)
        assertFalse(window.isValid())
    }
}
