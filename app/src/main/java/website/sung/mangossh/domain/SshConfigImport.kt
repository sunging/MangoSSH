package website.sung.mangossh.domain

/** Preview-only declarative import. Locations are retained solely for explicit key mapping. */
data class SshConfigCandidate(
    val alias: String,
    val hostname: String,
    val username: String?,
    val port: Int,
    val identityFiles: List<String>,
    val identitiesOnly: Boolean,
    val forwardAgent: Boolean,
    val proxyJumps: List<String>,
    val connectTimeoutSeconds: Int?,
    val keepaliveSeconds: Int?,
)

/** A line-numbered unsupported or malformed directive, never an executable command. */
data class SshConfigIssue(val line: Int, val directive: String)
data class SshConfigPreview(val candidates: List<SshConfigCandidate>, val issues: List<SshConfigIssue>)

/** Bounded OpenSSH-style Host matching with first-obtained scalar values; no files or commands execute. */
object SshConfigImport {
    fun parse(text: String): SshConfigPreview {
        require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val sections = mutableListOf(Section(listOf("*")))
        val issues = mutableListOf<SshConfigIssue>()
        var unsupportedBlock = false
        val lines = text.lineSequence().take(2001).toList()
        require(lines.size <= 2000)
        lines.forEachIndexed { index, raw ->
            if (raw.length > 4096) { issues += SshConfigIssue(index + 1, "line-too-long"); return@forEachIndexed }
            val tokens = tokenize(raw) ?: run { issues += SshConfigIssue(index + 1, "syntax"); return@forEachIndexed }
            if (tokens.isEmpty()) return@forEachIndexed
            val name = tokens.first().lowercase()
            val values = tokens.drop(1)
            if (name == "host") {
                unsupportedBlock = false
                if (values.isEmpty()) issues += SshConfigIssue(index + 1, name)
                else sections += Section(values)
            } else if (name == "match") {
                unsupportedBlock = true
                issues += SshConfigIssue(index + 1, name)
            } else if (unsupportedBlock || name !in supported || values.size != 1) {
                issues += SshConfigIssue(index + 1, name.take(64))
            } else sections.last().values += Directive(index + 1, name, values.single())
        }
        val aliases = sections.flatMap { it.patterns }.filter { !it.startsWith('!') && '*' !in it && '?' !in it }.distinct()
        require(aliases.size <= 500)
        val candidates = aliases.map { alias ->
            val values = linkedMapOf<String, Directive>()
            val identities = mutableListOf<String>()
            sections.filter { matches(it.patterns, alias) }.forEach { section ->
                section.values.forEach { directive ->
                    if (directive.name == "identityfile") identities += directive.value
                    else values.putIfAbsent(directive.name, directive)
                }
            }
            fun integer(name: String, range: IntRange, fallback: Int?): Int? {
                val directive = values[name] ?: return fallback
                return directive.value.toIntOrNull()?.takeIf { it in range } ?: run {
                    issues += SshConfigIssue(directive.line, name); fallback
                }
            }
            fun flag(name: String): Boolean {
                val directive = values[name] ?: return false
                if (directive.value.lowercase() !in listOf("yes", "no")) issues += SshConfigIssue(directive.line, name)
                return directive.value.equals("yes", true)
            }
            val hostname = (values["hostname"]?.value ?: alias).replace("%h", alias)
            if ('%' in hostname) issues += SshConfigIssue(values["hostname"]?.line ?: 0, "hostname-expansion")
            val jumps = values["proxyjump"]?.value?.takeUnless { it.equals("none", true) }?.split(',').orEmpty()
            if (jumps.size > 4 || jumps.any { it.isBlank() }) issues += SshConfigIssue(values["proxyjump"]?.line ?: 0, "proxyjump")
            val keepalive = integer("serveraliveinterval", 0..300, null)
            if (keepalive != null && keepalive in 1..9) issues += SshConfigIssue(values.getValue("serveraliveinterval").line, "serveraliveinterval")
            SshConfigCandidate(alias, hostname, values["user"]?.value, integer("port", 1..65535, 22)!!,
                identities.distinct(), flag("identitiesonly"), flag("forwardagent"), jumps,
                integer("connecttimeout", 5..120, null), keepalive?.takeIf { it == 0 || it >= 10 })
        }
        return SshConfigPreview(candidates, issues.distinct())
    }

    private fun matches(patterns: List<String>, alias: String): Boolean {
        fun match(pattern: String): Boolean = Regex(buildString {
            append('^')
            pattern.forEach { append(when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) }) }
            append('$')
        }, RegexOption.IGNORE_CASE).matches(alias)
        return patterns.any { !it.startsWith('!') && match(it) } && patterns.none { it.startsWith('!') && match(it.drop(1)) }
    }

    private fun tokenize(line: String): List<String>? {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        fun flush() { if (started) { tokens += token.toString(); token.clear(); started = false } }
        for (char in line) {
            if (escaped) { token.append(char); started = true; escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (quote != null) { if (char == quote) quote = null else token.append(char); continue }
            when {
                char == '#' -> break
                char == '\'' || char == '"' -> { quote = char; started = true }
                char.isWhitespace() || (char == '=' && tokens.size <= 1 && token.isEmpty()) -> flush()
                char == '=' && tokens.isEmpty() -> flush()
                else -> { token.append(char); started = true }
            }
        }
        if (escaped || quote != null) return null
        flush()
        return tokens
    }

    private data class Directive(val line: Int, val name: String, val value: String)
    private data class Section(val patterns: List<String>, val values: MutableList<Directive> = mutableListOf())
    private val supported = setOf("hostname", "user", "port", "identityfile", "identitiesonly", "forwardagent", "proxyjump", "connecttimeout", "serveraliveinterval")
}
