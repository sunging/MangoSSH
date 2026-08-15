package website.sung.mangossh.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalThemeId

@RunWith(AndroidJUnit4::class)
class SessionTerminalStoreInstrumentedTest {
    @Test
    fun applyingThemeKeepsTheActiveEmulatorInstance() {
        val store = SessionTerminalStore(
            onKeyboardInput = { _, _ -> },
            onResize = { _, _, _ -> },
            appearanceProvider = { TerminalAppearance() },
            behaviorProvider = { TerminalBehavior() },
        )
        val sessionId = "theme-test"

        store.create(sessionId)
        val active = checkNotNull(store.terminalFor(sessionId))
        store.applyAppearance(TerminalAppearance(theme = TerminalThemeId.DRACULA))

        assertSame(active, store.terminalFor(sessionId))
        store.remove(sessionId)
    }
}
