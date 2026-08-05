package website.sung.mangossh.domain

/**
 * A stable MAJOR.MINOR.PATCH release identity for MangoSSH itself.
 *
 * The bounds and the packed [versionCode] deliberately mirror the parser in
 * `app/build.gradle.kts`. A pre-release, build-metadata, or leading-zero tag
 * is rejected rather than coerced, so an ambiguous GitHub tag can never be
 * presented as an installable upgrade.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    /**
     * The Android `versionCode` that `app/build.gradle.kts` derives from this
     * same triple. Kept in sync manually; see [AppVersion.parse] for the
     * matching bounds and `AppVersionTest` for the drift guard.
     */
    val versionCode: Long = major * 1_000_000L + minor * 1_000L + patch

    override fun compareTo(other: AppVersion): Int = versionCode.compareTo(other.versionCode)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        const val MAX_MAJOR = 2_100
        const val MAX_MINOR = 999
        const val MAX_PATCH = 999
        private const val MAX_VERSION_CODE = 2_100_000_000L

        private val PATTERN = Regex("""^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

        /**
         * Parses a stable SemVer triple, optionally prefixed with `v`.
         *
         * This accepts both the GitHub `tag_name` (`v0.1.0`) and the local
         * `versionName` (`0.1.0`) with one parser. Prereleases (`-rc1`), build
         * metadata (`+meta`), leading zeros, and non-numeric tags such as the
         * `manual-<sha>` workflow_dispatch naming all return `null`.
         */
        fun parse(raw: String?): AppVersion? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (major > MAX_MAJOR || minor > MAX_MINOR || patch > MAX_PATCH) return null
            val version = AppVersion(major, minor, patch)
            return version.takeIf { it.versionCode in 1L..MAX_VERSION_CODE }
        }
    }
}
