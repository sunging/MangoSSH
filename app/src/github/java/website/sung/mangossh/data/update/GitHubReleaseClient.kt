package website.sung.mangossh.data.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import website.sung.mangossh.core.AppUpdateLogEvent
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.domain.AppRelease
import website.sung.mangossh.domain.AppReleaseAsset
import website.sung.mangossh.domain.CHECKSUM_ASSET_NAME
import website.sung.mangossh.domain.parseSha256Sums

/** Result of a release metadata check. */
sealed interface AppUpdateCheckResult {
    data class Success(val release: AppRelease) : AppUpdateCheckResult
    data class Failure(val reason: AppUpdateFailureReason, val statusCode: Int? = null) : AppUpdateCheckResult
}

/** Result of downloading and digest-verifying the release APK. */
sealed interface AppUpdateDownloadResult {
    data class Success(val file: File) : AppUpdateDownloadResult
    data class Failure(val reason: AppUpdateFailureReason, val statusCode: Int? = null) : AppUpdateDownloadResult
}

/**
 * Minimal HTTPS client for the GitHub Releases API and the release assets it
 * points to, mirroring [website.sung.mangossh.data.sync.WebDavClient]'s
 * `HttpURLConnection`-only, bounded-transfer shape.
 *
 * No `Authorization` header is ever sent: this client is unauthenticated and
 * unauthenticated GitHub API traffic is rate-limited per IP, not per app
 * install. Embedding a token in a distributed client binary would be a
 * credential leak and is deliberately avoided.
 */
internal class GitHubReleaseClient(
    private val appVersionName: String = "unknown",
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    /** Fetches and decodes the latest published, non-draft, non-prerelease release. */
    suspend fun latestRelease(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        MangoLog.info(AppUpdateLogEvent.CHECK_STARTED)
        runCatching {
            val body = withConnection(URL(LATEST_RELEASE_URL), followRedirects = false) { connection ->
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                checkStatus(connection)
                val length = connection.contentLengthLong
                if (length > MAX_METADATA_BYTES) throw AppUpdateException(AppUpdateFailureReason.RESPONSE_TOO_LARGE)
                connection.inputStream.use { readLimited(it, MAX_METADATA_BYTES) }
            }
            GitHubReleaseCodec.decodeRelease(String(body, Charsets.UTF_8))
        }.fold(
            onSuccess = { release ->
                MangoLog.info(AppUpdateLogEvent.CHECK_SUCCEEDED)
                AppUpdateCheckResult.Success(release)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                MangoLog.warn(AppUpdateLogEvent.CHECK_FAILED, error)
                val (reason, statusCode) = error.failureParts()
                AppUpdateCheckResult.Failure(reason, statusCode)
            },
        )
    }

    /**
     * Downloads [release]'s APK to [destination], verifying it against the
     * release's published `SHA256SUMS` before returning success.
     *
     * A missing checksum asset is treated as [AppUpdateFailureReason.CHECKSUM_MISSING]
     * rather than an unverified download: this app holds SSH private keys and
     * a Keystore-bound vault, so installing an unverified binary into that
     * signing identity is not an acceptable fallback.
     */
    suspend fun downloadApk(
        release: AppRelease,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        MangoLog.info(AppUpdateLogEvent.DOWNLOAD_STARTED)
        runCatching {
            val checksumAsset = release.checksumAsset
                ?: throw AppUpdateException(AppUpdateFailureReason.CHECKSUM_MISSING)
            val expectedDigest = fetchExpectedDigest(checksumAsset, release.apkAsset.name)
            downloadAndVerify(release.apkAsset, expectedDigest, destination, onProgress)
        }.fold(
            onSuccess = {
                MangoLog.info(AppUpdateLogEvent.DOWNLOAD_SUCCEEDED)
                AppUpdateDownloadResult.Success(destination)
            },
            onFailure = { error ->
                destination.delete()
                // Cancellation is caller-driven (a user tap or a lifecycle
                // teardown), not a network failure, and must propagate through
                // structured concurrency rather than surface as a Failed phase.
                if (error is CancellationException) {
                    MangoLog.info(AppUpdateLogEvent.DOWNLOAD_CANCELLED)
                    throw error
                }
                MangoLog.warn(AppUpdateLogEvent.DOWNLOAD_FAILED, error)
                val (reason, statusCode) = error.failureParts()
                AppUpdateDownloadResult.Failure(reason, statusCode)
            },
        )
    }

    private fun fetchExpectedDigest(checksumAsset: AppReleaseAsset, apkName: String): String {
        // openFollowingRedirects already guarantees a 2xx response here.
        val text = withConnection(URL(checksumAsset.downloadUrl), followRedirects = true) { connection ->
            val length = connection.contentLengthLong
            if (length > MAX_CHECKSUM_BYTES) throw AppUpdateException(AppUpdateFailureReason.RESPONSE_TOO_LARGE)
            connection.inputStream.use { readLimited(it, MAX_CHECKSUM_BYTES) }
        }.let { String(it, Charsets.UTF_8) }
        return parseSha256Sums(text, apkName)
            ?: throw AppUpdateException(AppUpdateFailureReason.CHECKSUM_MISSING)
    }

    private suspend fun downloadAndVerify(
        asset: AppReleaseAsset,
        expectedDigestHex: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val declared = asset.sizeBytes
        if (declared !in 1..MAX_APK_BYTES) throw AppUpdateException(AppUpdateFailureReason.RESPONSE_TOO_LARGE)

        val digest = MessageDigest.getInstance("SHA-256")
        // openFollowingRedirects already guarantees a 2xx response here.
        val connection = openFollowingRedirects(URL(asset.downloadUrl))
        try {
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0 && contentLength != declared) {
                throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)
            }

            var written = 0L
            var lastPublishedPercent = -1
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > declared) throw AppUpdateException(AppUpdateFailureReason.RESPONSE_TOO_LARGE)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = ((written * 100) / declared).toInt()
                        if (percent != lastPublishedPercent) {
                            lastPublishedPercent = percent
                            onProgress(written, declared)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written != declared) throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)
        } finally {
            connection.disconnect()
        }

        val actual = digest.digest()
        val expected = expectedDigestHex.hexToByteArrayOrNull()
            ?: throw AppUpdateException(AppUpdateFailureReason.CHECKSUM_MISMATCH)
        if (!MessageDigest.isEqual(expected, actual)) {
            MangoLog.warn(AppUpdateLogEvent.VERIFY_FAILED)
            throw AppUpdateException(AppUpdateFailureReason.CHECKSUM_MISMATCH)
        }
    }

    /**
     * Follows GitHub's release-asset redirect chain while re-establishing the
     * transport and origin guarantees on every hop.
     *
     * A redirect target is attacker-influenceable in a way the initial
     * constant URL is not, so the scheme and host are re-checked before each
     * connection rather than only once. `browser_download_url` and
     * `SHA256SUMS` URLs from `api.github.com` legitimately redirect to
     * `objects.githubusercontent.com` with a short-lived signed query string.
     */
    private fun openFollowingRedirects(initialUrl: URL): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS) {
            if (!url.protocol.equals("https", ignoreCase = true) || !isAllowedDownloadHost(url.host)) {
                throw AppUpdateException(AppUpdateFailureReason.INSECURE_REDIRECT)
            }
            val connection = open(url, followRedirects = false)
            connection.setRequestProperty("Accept", "application/octet-stream")
            val code = connection.responseCode
            when {
                code in 200..299 -> return connection
                code in REDIRECT_CODES -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = location
                        ?.let { runCatching { URL(url, it) }.getOrNull() }
                        ?: throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)
                }
                else -> {
                    connection.disconnect()
                    throw AppUpdateException(AppUpdateFailureReason.HTTP_STATUS, code)
                }
            }
        }
        throw AppUpdateException(AppUpdateFailureReason.INVALID_RESPONSE)
    }

    private inline fun <T> withConnection(url: URL, followRedirects: Boolean, block: (HttpURLConnection) -> T): T {
        val connection = if (followRedirects) openFollowingRedirects(url) else open(url, followRedirects = false)
        return try {
            block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, followRedirects: Boolean): HttpURLConnection {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw AppUpdateException(AppUpdateFailureReason.INSECURE_REDIRECT)
        }
        return (openConnection(url)).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            instanceFollowRedirects = followRedirects
            // GitHub rejects requests with no User-Agent. Deliberately excludes
            // device model, OS version, and locale, which would be fingerprinting.
            setRequestProperty("User-Agent", "MangoSSH/$appVersionName (+https://github.com/$REPO_SLUG)")
        }
    }

    private fun checkStatus(connection: HttpURLConnection) {
        when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_NOT_FOUND -> throw AppUpdateException(AppUpdateFailureReason.NO_RELEASE)
            HttpURLConnection.HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS -> {
                val exhausted = connection.getHeaderField("x-ratelimit-remaining") == "0" ||
                    connection.getHeaderField("retry-after") != null
                throw AppUpdateException(
                    if (exhausted) AppUpdateFailureReason.RATE_LIMITED else AppUpdateFailureReason.HTTP_STATUS,
                    code,
                )
            }
            else -> throw AppUpdateException(AppUpdateFailureReason.HTTP_STATUS, code)
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw AppUpdateException(AppUpdateFailureReason.RESPONSE_TOO_LARGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isAllowedDownloadHost(host: String): Boolean =
        host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")

    companion object {
        /** Canonical public repository used by both update checks and the About page. */
        internal const val REPO_SLUG = "sunging/MangoSSH"

        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO_SLUG/releases/latest"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_METADATA_BYTES = 512 * 1024
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun String.hexToByteArrayOrNull(): ByteArray? {
    if (length % 2 != 0 || !all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}
