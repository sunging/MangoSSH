package website.sung.mangossh.data.sync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import website.sung.mangossh.data.vault.BackupException
import website.sung.mangossh.data.vault.BackupFailure
import website.sung.mangossh.data.vault.BackupHistoryEntry
import website.sung.mangossh.data.vault.PortableVaultCodec
import website.sung.mangossh.data.vault.WebDavConfig

/** Opaque remote ciphertext and the validator returned by the same GET response. */
internal class RemoteBackup(val bytes: ByteArray, val etag: String?)

/** Distinguishes transport I/O from local storage errors without retaining URLs or response text. */
internal class WebDavTransportException : java.io.IOException()

/** The coordinator can exercise network failure paths without contacting a user server. */
internal interface BackupRemoteTransport {
    fun download(config: WebDavConfig): RemoteBackup?
    fun history(config: WebDavConfig): List<BackupHistoryEntry>
    fun readHistory(config: WebDavConfig, id: String): RemoteBackup
    fun archive(config: WebDavConfig, backup: RemoteBackup)
    fun publish(config: WebDavConfig, bytes: ByteArray, etag: String?)
    fun verifyConditionalWrites(config: WebDavConfig)
    fun prune(config: WebDavConfig)
}

/** Bounded HTTPS WebDAV transport. Credentials and response bodies are never logged. */
internal class WebDavClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
    private val allowHttpForTests: Boolean = false,
) : BackupRemoteTransport {
    fun validate(config: WebDavConfig) { url(config) }

    override fun download(config: WebDavConfig): RemoteBackup? = get(config, url(config))

    override fun history(config: WebDavConfig): List<BackupHistoryEntry> {
        val directory = historyUrl(config)
        val body = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".encodeToByteArray()
        request(config, directory, "PROPFIND", body, mapOf("Depth" to "1")).use { response ->
            if (response.code == 404) return emptyList()
            if (response.code != 207) throw BackupException(BackupFailure.UNSAFE_SERVER)
            val bytes = bounded(response, 1024 * 1024)
            return parseHistory(bytes, directory)
        }
    }

    override fun readHistory(config: WebDavConfig, id: String): RemoteBackup {
        require(HISTORY_NAME.matches(id))
        return get(config, historyUrl(config).newBuilder().addPathSegment(id).build())
            ?: throw BackupException(BackupFailure.NETWORK)
    }

    /** Archive first; failed preservation must never lead to a head overwrite. */
    override fun archive(config: WebDavConfig, backup: RemoteBackup) {
        val directory = historyUrl(config)
        request(config, directory, "MKCOL").use {
            if (it.code !in listOf(201, 405)) throw BackupException(BackupFailure.UNSAFE_SERVER)
        }
        val name = "mssh-${System.currentTimeMillis()}-${UUID.randomUUID()}.mssh"
        put(config, directory.newBuilder().addPathSegment(name).build(), backup.bytes, null)
    }

    override fun publish(config: WebDavConfig, bytes: ByteArray, etag: String?) = put(config, url(config), bytes, etag)

    /** Probe only a disposable owned object; a server ignoring conditions must never receive a head PUT. */
    override fun verifyConditionalWrites(config: WebDavConfig) {
        val directory = historyUrl(config)
        request(config, directory, "MKCOL").use {
            if (it.code !in listOf(201, 405)) throw BackupException(BackupFailure.UNSAFE_SERVER)
        }
        val probe = directory.newBuilder().addPathSegment(".probe-${UUID.randomUUID()}").build()
        val bytes = UUID.randomUUID().toString().encodeToByteArray()
        put(config, probe, bytes, null)
        try {
            listOf(mapOf("If-None-Match" to "*"), mapOf("If-Match" to "\"unmatched-${UUID.randomUUID()}\"")).forEach { condition ->
                request(config, probe, "PUT", bytes, condition).use {
                    if (it.code != 412) throw BackupException(BackupFailure.UNSAFE_SERVER)
                }
            }
        } finally {
            try { request(config, probe, "DELETE").close() } catch (_: java.io.IOException) { /* Probe is not a backup. */ }
        }
    }

    /** Only recognized immutable history files in this exact target directory may be deleted. */
    override fun prune(config: WebDavConfig) {
        history(config).drop(10).forEach { entry ->
            request(config, historyUrl(config).newBuilder().addPathSegment(entry.id).build(), "DELETE").use {
                if (!it.isSuccessful && it.code != 404) throw BackupException(BackupFailure.PARTIAL)
            }
        }
    }

    private fun put(config: WebDavConfig, target: HttpUrl, bytes: ByteArray, etag: String?) {
        if (bytes.size !in 1..PortableVaultCodec.MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        val condition = if (etag == null) mapOf("If-None-Match" to "*") else {
            if (!strongEtag(etag)) throw BackupException(BackupFailure.UNSAFE_SERVER)
            mapOf("If-Match" to etag)
        }
        request(config, target, "PUT", bytes, condition).use {
            if (it.code == 412) throw BackupException(BackupFailure.CHANGED)
            if (!it.isSuccessful) throw BackupException(BackupFailure.NETWORK)
        }
    }

    private fun get(config: WebDavConfig, target: HttpUrl): RemoteBackup? = request(config, target, "GET").use {
        if (it.code == 404) return null
        if (it.code != 200) throw BackupException(BackupFailure.NETWORK)
        RemoteBackup(bounded(it, PortableVaultCodec.MAX_FILE_BYTES), it.header("ETag"))
    }

    private fun request(config: WebDavConfig, target: HttpUrl, method: String, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): Response {
        val contentType = if (method == "PROPFIND") "application/xml; charset=utf-8" else "application/octet-stream"
        val body = bytes?.toRequestBody(contentType.toMediaType())
            ?: if (method == "MKCOL") ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(target).method(method, body)
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            .header("Accept", "application/octet-stream, application/xml")
        headers.forEach { (name, value) -> request.header(name, value) }
        return try { client.newCall(request.build()).execute() } catch (_: java.io.IOException) { throw WebDavTransportException() }
    }

    private fun url(config: WebDavConfig): HttpUrl {
        val base = config.endpoint.trim().trimEnd('/').toHttpUrl()
        require((base.isHttps || allowHttpForTests) && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        val segments = config.remoteFileName.split('/')
        require(segments.isNotEmpty() && segments.all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c == '\\' || c == '?' || c == '#' || c == '%' || c.isISOControl() } })
        return base.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
    }

    private fun historyUrl(config: WebDavConfig): HttpUrl {
        val current = url(config)
        return current.newBuilder().removePathSegment(current.pathSegments.lastIndex)
            .addPathSegment(".${current.pathSegments.last()}.history").addPathSegment("").build()
    }

    /** Reject off-origin and nested hrefs before deriving handles; XML cannot load external entities. */
    internal fun parseHistory(bytes: ByteArray, directory: HttpUrl): List<BackupHistoryEntry> {
        require(bytes.size <= 1024 * 1024)
        val xml = bytes.toString(Charsets.UTF_8)
        require(!xml.contains('\u0000') && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are prohibited") }
            setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
        }.parse(bytes.inputStream())
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        require(responses.length <= 1000)
        return (0 until responses.length).mapNotNull { index ->
            val element = responses.item(index) as Element
            val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
            val target = directory.resolve(href) ?: return@mapNotNull null
            if (target.scheme != directory.scheme || target.host != directory.host || target.port != directory.port || target.query != null || target.fragment != null) return@mapNotNull null
            if (target.encodedPath.substringBeforeLast('/') + "/" != directory.encodedPath) return@mapNotNull null
            val id = target.pathSegments.last()
            if (!HISTORY_NAME.matches(id)) return@mapNotNull null
            BackupHistoryEntry(id, id.removePrefix("mssh-").substringBefore('-').toLong(), true)
        }.distinctBy { it.id }.sortedWith(compareByDescending<BackupHistoryEntry> { it.createdAt }.thenByDescending { it.id })
    }

    private fun bounded(response: Response, limit: Int): ByteArray {
        if (response.body.contentLength() > limit) throw BackupException(BackupFailure.TOO_LARGE)
        return try { response.body.byteStream().use { readLimited(it, limit) } }
        catch (_: java.io.IOException) { throw WebDavTransportException() }
    }

    companion object {
        private val HISTORY_NAME = Regex("mssh-[0-9]{1,17}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mssh")
        fun strongEtag(value: String?): Boolean = value != null && value.length >= 2 && value.startsWith('"') && value.endsWith('"') && value.none { it.isISOControl() }
        fun readLimited(input: java.io.InputStream, limit: Int = PortableVaultCodec.MAX_FILE_BYTES): ByteArray {
            return website.sung.mangossh.data.vault.BackupLimits.readLimited(input, limit)
        }
    }
}
