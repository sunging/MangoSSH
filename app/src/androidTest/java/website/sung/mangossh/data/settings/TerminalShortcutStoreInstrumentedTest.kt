package website.sung.mangossh.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey

@RunWith(AndroidJUnit4::class)
class TerminalShortcutStoreInstrumentedTest {
    @Test
    fun persistsStructuredConfigurationAndValidEmptyToolbarAcrossRecreation() {
        withTestPreferences { context, _ ->
            val custom = TerminalShortcutConfig(
                listOf(
                    TerminalShortcutItem(
                        "ctrl-alt-t",
                        TerminalShortcutAction.Chord(
                            setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
                            TerminalShortcutKey.Character('T'),
                        ),
                    ),
                    TerminalShortcutItem("text", TerminalShortcutAction.Text("status\n"), "Status", visible = false),
                ),
            )
            TerminalShortcutStore(context).save(custom)
            assertEquals(custom, TerminalShortcutStore(context).config.value)

            TerminalShortcutStore(context).save(TerminalShortcutConfig(emptyList()))
            assertEquals(TerminalShortcutConfig(emptyList()), TerminalShortcutStore(context).config.value)
        }
    }

    @Test
    fun corruptPayloadFallsBackToDefaultsAndResetRemovesCustomization() {
        withTestPreferences { context, preferences ->
            preferences.edit().putString("config", "not-json").commit()
            assertEquals(TerminalShortcutConfig.defaults(), TerminalShortcutStore(context).config.value)

            val store = TerminalShortcutStore(context)
            store.save(TerminalShortcutConfig(emptyList()))
            store.reset()
            assertEquals(TerminalShortcutConfig.defaults(), TerminalShortcutStore(context).config.value)
        }
    }

    private fun withTestPreferences(block: (Context, SharedPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = base.getSharedPreferences(
            "terminal-shortcuts-test-${System.nanoTime()}",
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
