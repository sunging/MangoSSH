package website.sung.mangossh.presentation

/** An immutable clipboard snapshot. Confirmation and delivery use precisely this text. */
internal class TerminalPasteSnapshot private constructor(
    val text: String,
    val utf8Bytes: Int,
    val needsConfirmation: Boolean,
) {
    val previewWasShortened: Boolean get() = text.length > PREVIEW_LIMIT
    fun preview(): String = plainTerminalPreview(text)

    companion object {
        /**
         * Blocks control characters that can act as keystrokes or disrupt bracketed paste.
         * Tab and line endings are retained. Invalid UTF-16 cannot be sent losslessly.
         */
        fun fromClipboard(text: String): TerminalPasteSnapshot? {
            var utf8Bytes = 0
            var hasLineEnding = false
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                when (codePoint) {
                    '\r'.code, '\n'.code -> hasLineEnding = true
                    '\t'.code -> Unit
                    in 0..0x1f, in 0x7f..0x9f, in 0xd800..0xdfff -> return null
                }
                utf8Bytes += when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                offset += Character.charCount(codePoint)
            }
            return TerminalPasteSnapshot(text, utf8Bytes, hasLineEnding || utf8Bytes > 4_096)
        }
    }
}

/** A screen owns one target token per session, emulator, and connected lifetime. */
internal class TerminalPasteTarget(private val open: Boolean, private val generation: () -> Long?) {
    @Volatile private var active = true

    fun currentGeneration(): Long? = if (open && active) generation() else null
    fun isCurrent(expected: Long): Boolean = currentGeneration() == expected
    fun invalidate() { active = false }
}

/** Reveals invisible formatting while keeping the preview bounded and preserving Unicode pairs. */
internal fun plainTerminalPreview(text: String, singleLine: Boolean = false): String = buildString {
    var offset = 0
    while (offset < minOf(text.length, PREVIEW_LIMIT)) {
        val codePoint = text.codePointAt(offset)
        when {
            codePoint == '\n'.code && !singleLine -> append('\n')
            codePoint == '\r'.code -> {
                append("\\r")
                if (!singleLine && text.getOrNull(offset + 1) != '\n') append('\n')
            }
            codePoint == '\t'.code -> append("\\t")
            Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt() ||
                codePoint == 0x2028 || codePoint == 0x2029 -> append("\\u").append(codePoint.toString(16).padStart(4, '0'))
            else -> appendCodePoint(codePoint)
        }
        offset += Character.charCount(codePoint)
    }
}

private const val PREVIEW_LIMIT = 4_096
