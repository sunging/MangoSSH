package website.sung.mangossh.data.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.sung.mangossh.data.vault.WebDavConfig

/** Minimal HTTPS WebDAV PUT/GET client for encrypted portable vault blobs. */
class WebDavClient {
    suspend fun upload(config: WebDavConfig, encryptedBlob: ByteArray): WebDavResult =
        withContext(Dispatchers.IO) {
            runCatching {
                require(encryptedBlob.size in 1..MAX_TRANSFER_BYTES) { "Encrypted backup size is invalid" }
                withConnection(config, "PUT") { connection ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(encryptedBlob.size)
                    connection.outputStream.use { it.write(encryptedBlob) }
                    val code = connection.responseCode
                    require(code in 200..299) { "WebDAV upload failed (HTTP $code)" }
                }
            }.fold(
                onSuccess = { WebDavResult.Success },
                onFailure = { WebDavResult.Failure(it.message ?: "Unable to upload encrypted backup") },
            )
        }

    suspend fun download(config: WebDavConfig): WebDavDownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            withConnection(config, "GET") { connection ->
                val code = connection.responseCode
                require(code == HttpURLConnection.HTTP_OK) { "WebDAV download failed (HTTP $code)" }
                val length = connection.contentLengthLong
                // Chunked WebDAV responses legitimately report an unknown (-1) length.
                // The stream is still bounded by readLimited below.
                require(length <= MAX_TRANSFER_BYTES.toLong()) { "Remote backup is too large" }
                connection.inputStream.use(::readLimited)
            }
        }.fold(
            onSuccess = { WebDavDownloadResult.Success(it) },
            onFailure = { WebDavDownloadResult.Failure(it.message ?: "Unable to download encrypted backup") },
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
        require(endpoint.startsWith("https://")) { "WebDAV endpoint must use HTTPS" }
        val remoteName = config.remoteFileName.trim().trimStart('/')
        require(
            remoteName.isNotEmpty() &&
                !remoteName.contains("..") &&
                remoteName.none { it == '?' || it == '#' || it == '\\' || it.isISOControl() },
        ) { "WebDAV remote file name is invalid" }
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
            require(output.size() + count <= MAX_TRANSFER_BYTES) { "Remote backup is too large" }
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

sealed interface WebDavResult {
    data object Success : WebDavResult

    data class Failure(val message: String) : WebDavResult
}

sealed interface WebDavDownloadResult {
    data class Success(val encryptedBlob: ByteArray) : WebDavDownloadResult

    data class Failure(val message: String) : WebDavDownloadResult
}
