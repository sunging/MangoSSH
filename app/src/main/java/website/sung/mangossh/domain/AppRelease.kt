package website.sung.mangossh.domain

/** A single downloadable file attached to a GitHub release. */
data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * A published, non-draft, non-prerelease MangoSSH release fetched from GitHub.
 *
 * [title] and [notes] are server-owned text authored by the release process
 * (release-please) and must never be translated or passed to `stringResource`.
 */
data class AppRelease(
    val version: AppVersion,
    val tag: String,
    /** Server-owned release title. Never translated. */
    val title: String,
    /** Server-owned, length-clamped release body. Never translated. */
    val notes: String,
    /** Validated `https://github.com/...` release page, or `null` if absent or untrusted. */
    val htmlUrl: String?,
    val publishedAtEpochMillis: Long?,
    val apkAsset: AppReleaseAsset,
    /** `null` when the release has no published checksum file; blocks installation. */
    val checksumAsset: AppReleaseAsset?,
)

/** Exact asset name produced by `.github/workflows/android-release.yml` for a tag. */
fun apkAssetName(tag: String): String = "MangoSSH-$tag.apk"

/** Exact checksum asset name produced by `.github/workflows/android-release.yml`. */
const val CHECKSUM_ASSET_NAME: String = "SHA256SUMS"

private val CHECKSUM_LINE = Regex("""^([0-9a-fA-F]{64}) [ *](\S.*)$""")
private const val MAX_CHECKSUM_LINES = 64

/**
 * Extracts the expected SHA-256 digest for exactly one named file from a
 * `sha256sum`-formatted checksum listing.
 *
 * Both the GNU text (`"  "`) and binary (`" *"`) separators are accepted, and
 * hex is normalized to lowercase. Two lines naming the same file are rejected
 * rather than resolved, because a duplicate entry means the checksum file
 * cannot be trusted to pin a single artifact. Returns `null` for a missing
 * entry, a duplicate, or unparsable content.
 */
fun parseSha256Sums(text: String, fileName: String): String? {
    val matches = text.lineSequence()
        .take(MAX_CHECKSUM_LINES)
        .mapNotNull { CHECKSUM_LINE.matchEntire(it.trimEnd('\r', '\n')) }
        .filter { it.groupValues[2].trimEnd() == fileName }
        .map { it.groupValues[1].lowercase() }
        .toList()
    return matches.singleOrNull()
}
