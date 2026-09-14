package website.sung.mangossh.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** A dedicated preference namespace and runtime-generated inputs never touch the user's app lock. */
class AppLockPersistenceInstrumentedTest {
    @Test fun recreatedStoreRetainsCooldownAndSuccessfulVerificationResetsIt() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "app-lock-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        var now = 1_000_000L
        val random = SecureRandom()
        val pin = CharArray(6) { ('0'.code + random.nextInt(10)).toChar() }
        val wrong = pin.copyOf().apply { this[0] = if (this[0] == '9') '0' else this[0] + 1 }
        try {
            val original = AppLockStore(context) { now }
            assertEquals(ReauthenticationMode.DISABLED, original.configuration().reauthentication)
            original.setPin(pin)
            repeat(5) { assertFalse(original.verifyPin(wrong)) }
            val recreated = AppLockStore(context) { now }
            assertEquals(30_000L, recreated.cooldownRemainingMillis())
            assertFalse(recreated.verifyPin(pin))
            now += 30_000L
            assertFalse(recreated.verifyPin(wrong))
            assertEquals(60_000L, AppLockStore(context) { now }.cooldownRemainingMillis())
            now += 60_000L
            assertTrue(recreated.verifyPin(pin))
            assertEquals(0L, AppLockStore(context) { now }.cooldownRemainingMillis())
        } finally {
            pin.fill('\u0000'); wrong.fill('\u0000')
            base.deleteSharedPreferences(name)
        }
    }
}
