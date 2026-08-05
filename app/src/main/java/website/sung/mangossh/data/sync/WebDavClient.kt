package website.sung.mangossh.data.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/** Minimal HTTPS WebDAV PUT/GET client for encrypted portable vault blobs. */
class WebDavClient {
    /** Uploads an already-encrypted portable vault; endpoint and credentials are never logged. */
    suspend fun upload(config: WebDavConfig, encryptedBlob: ByteArray): WebDavResult =
        withContext(Dispatchers.IO) {
            runCatching {
                if (encryptedBlob.size !in 1..MAX_TRANSFER_BYTES) {
                    throw WebDavClientException(WebDavFailureReason.INVALID_BACKUP_SIZE)
                }
                withConnection(config, "PUT") { connection ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(encryptedBlob.size)
                    connection.outputStream.use { it.write(encryptedBlob) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw WebDavClientException(WebDavFailureReason.HTTP_STATUS, code)
                    }
                }
            }.fold(
                onSuccess = {
                    MangoLog.info(MangoLogEvent.WEBDAV_UPLOAD_SUCCEEDED)
                    WebDavResult.Success
                },
                onFailure = { error ->
                    MangoLog.warn(MangoLogEvent.WEBDAV_UPLOAD_FAILED, error)
                    error.toWebDavFailure()
                },
            )
        }

    /** Downloads a bounded encrypted vault blob; its contents stay opaque to this HTTP layer. */
    suspend fun download(config: WebDavConfig): WebDavDownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            withConnection(config, "GET") { connection ->
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw WebDavClientException(WebDavFailureReason.HTTP_STATUS, code)
                }
                val length = connection.contentLengthLong
                // Chunked WebDAV responses legitimately report an unknown (-1) length.
                // The stream is still bounded by readLimited below.
                if (length > MAX_TRANSFER_BYTES.toLong()) {
                    throw WebDavClientException(WebDavFailureReason.RESPONSE_TOO_LARGE)
                }
                connection.inputStream.use(::readLimited)
            }
        }.fold(
            onSuccess = {
                MangoLog.info(MangoLogEvent.WEBDAV_DOWNLOAD_SUCCEEDED)
                WebDavDownloadResult.Success(it)
            },
            onFailure = { error ->
                MangoLog.warn(MangoLogEvent.WEBDAV_DOWNLOAD_FAILED, error)
                error.toWebDavDownloadFailure()
            },
        )
    }

    private inline fun <T> withConnection(
        config: WebDavConfig,
        method: String,
        block: (HttpURLConnection) -> T,
    ): T {
        val connection = open(config, method)
        return try {
            block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(config: WebDavConfig, method: String): HttpURLConnection {
        val remoteUrl = remoteUrl(config)
        return (remoteUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Authorization", basicAuthorization(config.username, config.password))
        }
    }

    private fun remoteUrl(config: WebDavConfig): URL {
        val endpoint = config.endpoint.trim().trimEnd('/')
        if (!endpoint.startsWith("https://")) {
            throw WebDavClientException(WebDavFailureReason.INVALID_CONFIGURATION)
        }
        val remoteName = config.remoteFileName.trim().trimStart('/')
        if (
            remoteName.isEmpty() ||
            remoteName.contains("..") ||
            remoteName.any { it == '?' || it == '#' || it == '\\' || it.isISOControl() }
        ) {
            throw WebDavClientException(WebDavFailureReason.INVALID_CONFIGURATION)
        }
        return URL("$endpoint/$remoteName")
    }

    private fun basicAuthorization(username: String, password: String): String {
        val value = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
        return "Basic $value"
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_TRANSFER_BYTES) {
                throw WebDavClientException(WebDavFailureReason.RESPONSE_TOO_LARGE)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_TRANSFER_BYTES = 16 * 1024 * 1024
    }
}

/** Sanitized WebDAV failure category safe to expose without credentials or remote response bodies. */
enum class WebDavFailureReason {
    INVALID_CONFIGURATION,
    INVALID_BACKUP_SIZE,
    HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    NETWORK,
}

private class WebDavClientException(
    val reason: WebDavFailureReason,
    val statusCode: Int? = null,
) : Exception()

private fun Throwable.failureParts(): Pair<WebDavFailureReason, Int?> =
    (this as? WebDavClientException)?.let { it.reason to it.statusCode }
        ?: (WebDavFailureReason.NETWORK to null)

private fun Throwable.toWebDavFailure(): WebDavResult.Failure {
    val (reason, statusCode) = failureParts()
    return WebDavResult.Failure(reason, statusCode)
}

private fun Throwable.toWebDavDownloadFailure(): WebDavDownloadResult.Failure {
    val (reason, statusCode) = failureParts()
    return WebDavDownloadResult.Failure(reason, statusCode)
}

sealed interface WebDavResult {
    data object Success : WebDavResult

    data class Failure(
        val reason: WebDavFailureReason,
        val statusCode: Int? = null,
    ) : WebDavResult
}

sealed interface WebDavDownloadResult {
    data class Success(val encryptedBlob: ByteArray) : WebDavDownloadResult

    data class Failure(
        val reason: WebDavFailureReason,
        val statusCode: Int? = null,
    ) : WebDavDownloadResult
}
