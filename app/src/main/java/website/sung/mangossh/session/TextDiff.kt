package website.sung.mangossh.session

/** One line of a line-based diff. Line text is user data: shown verbatim, never logged. */
internal data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, ADDED, REMOVED }
}

/**
 * Line-based diff (Myers' O(ND) algorithm) used to show what a save would change.
 *
 * Editable files are capped at 128 KiB, but a pathological pair could still need a lot
 * of work, so both the line count and the number of edits are bounded; beyond them
 * [lines] returns null and the caller says the change is too large to show.
 */
internal object TextDiff {
    const val MAX_LINES = 8_000
    const val MAX_EDITS = 1_500

    fun lines(before: String, after: String): List<DiffLine>? {
        val a = split(before)
        val b = split(after)
        if (a.size > MAX_LINES || b.size > MAX_LINES) return null
        // Common head and tail cost nothing and keep the search small.
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val middle = myers(a.subList(head, a.size - tail), b.subList(head, b.size - tail)) ?: return null
        return a.subList(0, head).map { DiffLine(DiffLine.Kind.SAME, it) } + middle +
            a.subList(a.size - tail, a.size).map { DiffLine(DiffLine.Kind.SAME, it) }
    }

    /**
     * Keeps [context] unchanged lines around each change and replaces longer unchanged runs
     * with a single null marker, so the review shows the edits rather than the whole file.
     */
    fun condense(lines: List<DiffLine>, context: Int = 2): List<DiffLine?> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffLine.Kind.SAME) {
                for (near in maxOf(0, index - context)..minOf(lines.lastIndex, index + context)) keep[near] = true
            }
        }
        val result = ArrayList<DiffLine?>()
        var skipping = false
        lines.forEachIndexed { index, line ->
            if (keep[index]) { result += line; skipping = false }
            else if (!skipping) { result += null; skipping = true }
        }
        return result
    }

    private fun split(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split("\r\n", "\n")

    private fun myers(a: List<String>, b: List<String>): List<DiffLine>? {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val limit = minOf(max, MAX_EDITS)
        val offset = limit + 1
        var v = IntArray(2 * limit + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..limit) {
            trace += v.copyOf()
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = d; break@search }
            }
        }
        if (found < 0) return null
        // Walk the recorded frontiers back from the end to recover the edit script.
        val script = ArrayList<DiffLine>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            v = trace[d]
            val k = x - y
            val previousK = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) k + 1 else k - 1
            val previousX = v[offset + previousK]
            val previousY = previousX - previousK
            while (x > previousX && y > previousY) { script += DiffLine(DiffLine.Kind.SAME, a[x - 1]); x--; y-- }
            if (x == previousX) script += DiffLine(DiffLine.Kind.ADDED, b[y - 1]).also { y-- }
            else script += DiffLine(DiffLine.Kind.REMOVED, a[x - 1]).also { x-- }
        }
        while (x > 0 && y > 0) { script += DiffLine(DiffLine.Kind.SAME, a[x - 1]); x--; y-- }
        return script.asReversed()
    }
}
