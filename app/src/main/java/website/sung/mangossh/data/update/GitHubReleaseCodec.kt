package website.sung.mangossh.data.update

import java.time.Instant
import org.json.JSONObject
import website.sung.mangossh.domain.AppRelease
import website.sung.mangossh.domain.AppReleaseAsset
import website.sung.mangossh.domain.AppVersion
import website.sung.mangossh.domain.CHECKSUM_ASSET_NAME
import website.sung.mangossh.domain.apkAssetName

/** Sanitized update failure category, safe to surface without URLs, paths, or response bodies. */
enum class AppUpdateFailureReason {
    INVALID_RESPONSE,
    NO_RELEASE,
    ASSET_MISSING,
    CHECKSUM_MISSING,
    CHECKSUM_MISMATCH,
    SIGNATURE_MISMATCH,
    RATE_LIMITED,
    HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    INSECURE_REDIRECT,
    STORAGE,
    NETWORK,
}

internal class AppUpdateException(
    val reason: AppUpdateFailureReason,
    val statusCode: Int? = null,
) : Exception()

internal fun Throwable.failureParts(): Pair<AppUpdateFailureReason, Int?> =
    (this as? AppUpdateException)?.let { it.reason to it.statusCode }
        ?: (AppUpdateFailureReason.NETWORK to null)

/**
 * Parses a GitHub `GET /repos/{owner}/{repo}/releases/latest` response body.
 *
 * All parsing logic lives here, isolated from [GitHubReleaseClient]'s network
 * transport, so it is unit-testable with plain `org.json` fixtures.
 */
internal object GitHubReleaseCodec {
    private const val MAX_NOTES_LENGTH = 8_000

    /**
     * Decodes one release. Throws [AppUpdateException] for a structurally
     * unusable response; a release with no published checksum still decodes
     * successfully with [AppRelease.checksumAsset] set to `null` — blocking
     * installation on a missing checksum is a policy decision made by the
     * caller, not this codec.
     */
    fun decodeRelease(body: String): AppRelease {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE) }

        // /releases/latest already excludes drafts and prereleases; this is a
        // defensive check against a future API change or endpoint mix-up.
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            throw AppUpdateException(AppUpdateFailureReason.NO_RELEASE)
        }

        val tag = root.optString("tag_name").takeIf(String::isNotBlank)
            ?: throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)
        val version = AppVersion.parse(tag)
            ?: throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)

        val assets = decodeAssets(root)
        val apkAsset = assets.firstOrNull { it.name == apkAssetName(tag) }
            ?: throw AppUpdateException(AppUpdateFailureReason.ASSET_MISSING)
        val checksumAsset = assets.firstOrNull { it.name == CHECKSUM_ASSET_NAME }

        val title = root.optString("name").takeIf(String::isNotBlank) ?: tag
        val notes = clampNotes(root.optString("body"))
        val htmlUrl = validatedHtmlUrl(root.optString("html_url"))
        val publishedAt = runCatching { Instant.parse(root.optString("published_at")).toEpochMilli() }
            .getOrNull()

        return AppRelease(
            version = version,
            tag = tag,
            title = title,
            notes = notes,
            htmlUrl = htmlUrl,
            publishedAtEpochMillis = publishedAt,
            apkAsset = apkAsset,
            checksumAsset = checksumAsset,
        )
    }

    private fun decodeAssets(root: JSONObject): List<AppReleaseAsset> {
        val assetsJson = root.optJSONArray("assets") ?: return emptyList()
        return buildList {
            for (index in 0 until assetsJson.length()) {
                val assetJson = assetsJson.optJSONObject(index) ?: continue
                val name = assetJson.optString("name").takeIf(String::isNotBlank) ?: continue
                val downloadUrl = assetJson.optString("browser_download_url").takeIf(String::isNotBlank)
                    ?: continue
                val size = assetJson.optLong("size", -1L)
                if (size < 0) continue
                add(AppReleaseAsset(name = name, downloadUrl = downloadUrl, sizeBytes = size))
            }
        }
    }

    /** Clamps server-owned release notes to a bounded length and strips control characters. */
    private fun clampNotes(body: String): String {
        val clamped = if (body.length > MAX_NOTES_LENGTH) body.substring(0, MAX_NOTES_LENGTH) else body
        return clamped.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    }

    /** Accepts only an `https://github.com/...` release page; anything else is dropped. */
    private fun validatedHtmlUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val url = runCatching { java.net.URL(raw) }.getOrNull() ?: return null
        return raw.takeIf { url.protocol.equals("https", ignoreCase = true) && url.host == "github.com" }
    }
}
