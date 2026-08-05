package website.sung.mangossh.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.domain.UpdatePreferences

@RunWith(AndroidJUnit4::class)
class UpdatePreferencesStoreInstrumentedTest {
    @Test
    fun defaultsEnableAutomaticChecksWithNoPriorCheckOrDismissal() {
        withTestPreferences { context, _ ->
            assertEquals(UpdatePreferences(), UpdatePreferencesStore(context).current())
        }
    }

    @Test
    fun persistsAutomaticCheckToggleAndLastCheckedTimeAcrossStoreRecreation() {
        withTestPreferences { context, _ ->
            val store = UpdatePreferencesStore(context)
            store.setAutomaticCheckEnabled(false)
            store.recordCheck(1_700_000_000_000L)

            val restored = UpdatePreferencesStore(context).current()
            assertFalse(restored.automaticCheckEnabled)
            assertEquals(1_700_000_000_000L, restored.lastCheckedAtEpochMillis)
        }
    }

    @Test
    fun dismissVersionTakesTheMaxAndClearDismissalResets() {
        withTestPreferences { context, _ ->
            val store = UpdatePreferencesStore(context)
            store.dismissVersion(1_002_000L)
            store.dismissVersion(1_001_000L)
            assertEquals(1_002_000L, store.current().dismissedVersionCode)

            store.clearDismissal()
            assertEquals(0L, store.current().dismissedVersionCode)
        }
    }

    @Test
    fun recordCheckIsObservableThroughThePreferencesFlow() {
        withTestPreferences { context, _ ->
            val store = UpdatePreferencesStore(context)
            store.recordCheck(42L)
            assertTrue(store.preferences.value.lastCheckedAtEpochMillis == 42L)
        }
    }

    private fun withTestPreferences(
        block: (Context, SharedPreferences) -> Unit,
    ) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = base.getSharedPreferences(
            "app-updates-test-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        try {
            block(context, preferences)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
