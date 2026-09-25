package website.sung.mangossh.session

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.session.DiffLine.Kind.ADDED
import website.sung.mangossh.session.DiffLine.Kind.REMOVED
import website.sung.mangossh.session.DiffLine.Kind.SAME

class TextDiffTest {
    @Test fun identicalTextHasNoEdits() {
        assertEquals(listOf(DiffLine(SAME, "a"), DiffLine(SAME, "b")), TextDiff.lines("a\nb", "a\nb"))
    }

    @Test fun aChangedLineIsARemovalAndAnAddition() {
        assertEquals(
            listOf(DiffLine(SAME, "a"), DiffLine(REMOVED, "b"), DiffLine(ADDED, "B"), DiffLine(SAME, "c")),
            TextDiff.lines("a\nb\nc", "a\nB\nc"),
        )
    }

    @Test fun crlfAndLfCompareByLineContent() {
        assertEquals(listOf(DiffLine(SAME, "a"), DiffLine(ADDED, "b")), TextDiff.lines("a", "a\r\nb"))
    }

    @Test fun randomEditsReproduceBothSides() {
        val random = Random(3)
        repeat(50) {
            val before = List(random.nextInt(0, 40)) { "l" + random.nextInt(8) }
            val after = before.toMutableList().apply {
                repeat(random.nextInt(0, 10)) {
                    if (isNotEmpty() && random.nextBoolean()) removeAt(random.nextInt(size)) else add(random.nextInt(size + 1), "n" + random.nextInt(8))
                }
            }
            val diff = TextDiff.lines(before.joinToString("\n"), after.joinToString("\n"))!!
            assertEquals(before, diff.filter { it.kind != ADDED }.map { it.text })
            assertEquals(after, diff.filter { it.kind != REMOVED }.map { it.text })
        }
    }

    @Test fun tooManyEditsAreReportedInsteadOfComputed() {
        val before = List(4_000) { "a$it" }.joinToString("\n")
        val after = List(4_000) { "b$it" }.joinToString("\n")
        assertNull(TextDiff.lines(before, after))
    }

    @Test fun condenseKeepsContextAroundChanges() {
        val lines = TextDiff.lines((1..20).joinToString("\n"), (1..20).joinToString("\n").replace("\n10\n", "\nten\n"))!!
        val condensed = TextDiff.condense(lines, context = 1)
        assertEquals(listOf(null, DiffLine(SAME, "9"), DiffLine(REMOVED, "10"), DiffLine(ADDED, "ten"), DiffLine(SAME, "11"), null), condensed)
    }
}
