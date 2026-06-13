package tech.peakedge.naswalkman.network

import android.util.Log
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import java.net.URI
import java.io.File

data class NasConnectionDraft(
    val mode: NasConnectionMode,
    val inputAddress: String,
    val musicRootPath: String,
)

data class ResolvedNasEndpoint(
    val inputAddress: String,
    val primaryBaseUrl: String,
    val candidateBaseUrls: List<String>,
    val wasFnIdOnly: Boolean = false,
)

data class NasDirectory(
    val name: String,
    val remotePath: String,
    val displayPath: String,
    val modifiedAt: String?,
    val hasPermission: Boolean = true,
    val canEnter: Boolean = true,
    val itemCount: Int? = null,
)

data class NasAudioFile(
    val name: String,
    val remotePath: String,
    val displayPath: String,
    val size: Long?,
    val modifiedAt: String?,
)

interface ConnectionResolver {
    fun resolve(draft: NasConnectionDraft): ResolvedNasEndpoint
}

class DefaultConnectionResolver : ConnectionResolver {
    override fun resolve(draft: NasConnectionDraft): ResolvedNasEndpoint =
        WebDavEndpointResolver.resolve(draft)
}

object WebDavEndpointResolver {
    fun resolve(draft: NasConnectionDraft): ResolvedNasEndpoint {
        val input = draft.inputAddress.trim()
        val endpoint = resolveWebDavEndpoint(input, draft.mode)
        val wasFnIdOnly = draft.mode == NasConnectionMode.FN_CONNECT && input.isPlainFnId()
        logResolved(draft.mode, if (wasFnIdOnly) "FNID" else "URL", endpoint)
        return ResolvedNasEndpoint(
            inputAddress = input,
            primaryBaseUrl = endpoint,
            candidateBaseUrls = listOf(endpoint),
            wasFnIdOnly = wasFnIdOnly,
        )
    }

    fun resolveWebDavEndpoint(input: String, mode: NasConnectionMode): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "empty endpoint" }
        if (trimmed.isFullUrl()) return normalizeWebDavUrl(trimmed)

        return when (mode) {
            NasConnectionMode.FN_CONNECT -> resolveFnIdInput(trimmed)
            NasConnectionMode.REMOTE_URL,
            NasConnectionMode.WEBDAV_ADVANCED,
            -> normalizeWebDavUrl(trimmed)
        }
    }

    private fun resolveFnIdInput(input: String): String {
        val value = input.trim().trim('/').removePrefix("@")
        require(value.isNotBlank()) { "empty fnid" }
        if (value.startsWith("dav.", ignoreCase = true)) {
            return normalizeWebDavUrl(value, addDefaultHttpsPort = true)
        }
        require(!value.contains("/")) { "invalid fnid" }
        if (value.contains(".") || value.contains(":")) {
            return normalizeWebDavUrl(value)
        }
        require(FN_ID_REGEX.matches(value)) { "invalid fnid" }
        return "https://dav.$value.${fnWebDavDomain()}:443"
    }

    private fun normalizeWebDavUrl(input: String, addDefaultHttpsPort: Boolean = false): String {
        val withScheme = if (input.isFullUrl()) {
            input.trim()
        } else {
            "https://${input.trim().trim('/')}"
        }.trimEnd('/')
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: throw IllegalArgumentException("invalid endpoint")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "invalid scheme" }
        require(!uri.host.isNullOrBlank()) { "invalid host" }
        if (addDefaultHttpsPort && scheme == "https" && uri.port == -1) {
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("invalid host")
            return withScheme.replaceFirst("://${authority}", "://${authority}:443")
        }
        return withScheme
    }

    private fun String.isPlainFnId(): Boolean {
        val value = trim().trim('/').removePrefix("@")
        return !value.isFullUrl() && FN_ID_REGEX.matches(value)
    }

    private fun logResolved(mode: NasConnectionMode, inputKind: String, resolved: String) {
        runCatching {
            Log.d(
                "WebDavResolver",
                "mode=${mode.logName()} inputKind=$inputKind resolved=$resolved",
            )
        }
    }

    private fun NasConnectionMode.logName(): String = when (this) {
        NasConnectionMode.FN_CONNECT -> "FN_ID"
        NasConnectionMode.REMOTE_URL -> "WEBDAV"
        NasConnectionMode.WEBDAV_ADVANCED -> "WEBDAV"
    }

    private fun fnWebDavDomain(): String =
        listOf("5", "ddd", ".", "com").joinToString("")

    private val FN_ID_REGEX = Regex("""^[A-Za-z0-9_-]+$""")
}

interface NasFileClient {
    suspend fun testConnection(credentials: NasCredentials): WebDavResult
    suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem>
    suspend fun listDirectoryRaw(credentials: NasCredentials, remotePath: String): List<RemoteItem>
    suspend fun listDirectories(credentials: NasCredentials, remotePath: String): List<NasDirectory>
    suspend fun listAudioFiles(credentials: NasCredentials, remotePath: String): List<NasAudioFile>
    suspend fun listLyricFiles(credentials: NasCredentials, remotePath: String): List<RemoteItem>
    suspend fun testDirectory(credentials: NasCredentials, remotePath: String): WebDavResult
    fun getDisplayPath(remotePath: String): String
    suspend fun readTextFile(credentials: NasCredentials, remotePath: String, maxBytes: Long = 512 * 1024): String
    suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long
    fun urlFor(credentials: NasCredentials, remotePath: String): String
    suspend fun supportsRange(credentials: NasCredentials, remotePath: String): Boolean = true
}

class WebDavNasFileClient(private val webDavClient: WebDavClient) : NasFileClient {
    override suspend fun testConnection(credentials: NasCredentials): WebDavResult =
        webDavClient.testConnection(credentials)

    override suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        webDavClient.listDirectory(credentials, remotePath)

    override suspend fun listDirectoryRaw(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        webDavClient.listDirectoryRaw(credentials, remotePath)

    override suspend fun listDirectories(credentials: NasCredentials, remotePath: String): List<NasDirectory> =
        webDavClient.listDirectory(credentials, remotePath)
            .filter { it.isDirectory }
            .map {
                NasDirectory(
                    name = it.displayName,
                    remotePath = it.remotePath,
                    displayPath = getDisplayPath(it.remotePath),
                    modifiedAt = it.modifiedAt,
                )
            }

    override suspend fun listAudioFiles(credentials: NasCredentials, remotePath: String): List<NasAudioFile> =
        webDavClient.listDirectory(credentials, remotePath)
            .filter { it.isSupportedAudio }
            .map {
                NasAudioFile(
                    name = it.displayName,
                    remotePath = it.remotePath,
                    displayPath = getDisplayPath(it.remotePath),
                    size = it.size,
                    modifiedAt = it.modifiedAt,
                )
            }

    override suspend fun testDirectory(credentials: NasCredentials, remotePath: String): WebDavResult =
        webDavClient.testDirectory(credentials, remotePath)

    override suspend fun listLyricFiles(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        webDavClient.listLyricFiles(credentials, remotePath)

    override fun getDisplayPath(remotePath: String): String =
        remotePath.trim('/').split('/')
            .filter { it.isNotBlank() }
            .joinToString(" / ")
            .ifBlank { "NAS 根目录" }

    override suspend fun readTextFile(credentials: NasCredentials, remotePath: String, maxBytes: Long): String =
        webDavClient.readTextFile(credentials, remotePath, maxBytes)

    override suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long =
        webDavClient.downloadToFile(credentials, remotePath, target)

    override fun urlFor(credentials: NasCredentials, remotePath: String): String =
        webDavClient.urlFor(credentials, remotePath)
}

private fun String.isFullUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)
