package website.sung.mangossh.data.settings

import org.json.JSONArray
import org.json.JSONObject
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

/** Versioned JSON codec for non-secret, device-local terminal shortcut settings. */
internal object TerminalShortcutConfigCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(config: TerminalShortcutConfig): String {
        require(config.isValid()) { "Terminal shortcut configuration is invalid." }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "items",
                JSONArray().apply {
                    config.items.forEach { put(encodeItem(it)) }
                },
            )
            .toString()
    }

    fun decode(encoded: String): TerminalShortcutConfig? = runCatching {
        val root = JSONObject(encoded)
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
        val itemsJson = root.getJSONArray("items")
        val items = buildList {
            repeat(minOf(itemsJson.length(), TerminalShortcutConfig.MAX_ITEMS)) { index ->
                decodeItem(itemsJson.optJSONObject(index))?.let(::add)
            }
        }
        TerminalShortcutConfig(items).normalized()
    }.getOrNull()

    private fun encodeItem(item: TerminalShortcutItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("visible", item.visible)
        .put("label", item.labelOverride ?: JSONObject.NULL)
        .put("action", encodeAction(item.action))

    private fun decodeItem(value: JSONObject?): TerminalShortcutItem? = runCatching {
        requireNotNull(value)
        TerminalShortcutItem(
            id = value.getString("id"),
            visible = value.optBoolean("visible", true),
            labelOverride = value.optString("label", "").takeIf { !value.isNull("label") },
            action = decodeAction(value.getJSONObject("action")) ?: return null,
        )
    }.getOrNull()

    private fun encodeAction(action: TerminalShortcutAction): JSONObject = when (action) {
        TerminalShortcutAction.Paste -> JSONObject().put("type", "paste")
        is TerminalShortcutAction.Modifier -> JSONObject()
            .put("type", "modifier")
            .put("modifier", action.modifier.preferenceValue)

        is TerminalShortcutAction.Text -> JSONObject()
            .put("type", "text")
            .put("value", action.value)

        is TerminalShortcutAction.SpecialKey -> JSONObject()
            .put("type", "special")
            .put("key", action.key.preferenceValue)

        is TerminalShortcutAction.Chord -> JSONObject()
            .put("type", "chord")
            .put(
                "modifiers",
                JSONArray().apply {
                    TerminalModifier.entries
                        .filter(action.modifiers::contains)
                        .forEach { put(it.preferenceValue) }
                },
            )
            .put("key", encodeKey(action.key))
    }

    private fun decodeAction(value: JSONObject): TerminalShortcutAction? = when (value.optString("type")) {
        "paste" -> TerminalShortcutAction.Paste
        "modifier" -> TerminalModifier.fromPreference(value.optString("modifier"))
            ?.let(TerminalShortcutAction::Modifier)

        "text" -> TerminalShortcutAction.Text(value.getString("value"))
        "special" -> TerminalSpecialKey.fromPreference(value.optString("key"))
            ?.let(TerminalShortcutAction::SpecialKey)

        "chord" -> {
            val modifiersJson = value.getJSONArray("modifiers")
            val modifiers = buildSet {
                repeat(modifiersJson.length()) { index ->
                    TerminalModifier.fromPreference(modifiersJson.optString(index))?.let(::add)
                }
            }
            decodeKey(value.getJSONObject("key"))?.let { key ->
                TerminalShortcutAction.Chord(modifiers = modifiers, key = key)
            }
        }

        else -> null
    }

    private fun encodeKey(key: TerminalShortcutKey): JSONObject = when (key) {
        is TerminalShortcutKey.Character -> JSONObject()
            .put("type", "character")
            .put("value", key.value.toString())

        is TerminalShortcutKey.Special -> JSONObject()
            .put("type", "special")
            .put("value", key.value.preferenceValue)
    }

    private fun decodeKey(value: JSONObject): TerminalShortcutKey? = when (value.optString("type")) {
        "character" -> value.optString("value")
            .takeIf { it.length == 1 }
            ?.single()
            ?.let(TerminalShortcutKey::Character)

        "special" -> TerminalSpecialKey.fromPreference(value.optString("value"))
            ?.let(TerminalShortcutKey::Special)

        else -> null
    }
}
