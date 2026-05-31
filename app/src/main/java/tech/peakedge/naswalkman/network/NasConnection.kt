package tech.peakedge.naswalkman.network

import tech.peakedge.naswalkman.data.db.NasConnectionMode
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
        when (draft.mode) {
            NasConnectionMode.FN_CONNECT -> FnConnectResolver.resolve(draft)
            NasConnectionMode.REMOTE_URL,
            NasConnectionMode.WEBDAV_ADVANCED,
            -> UrlResolver.resolve(draft)
        }
}

private object FnConnectResolver {
    fun resolve(draft: NasConnectionDraft): ResolvedNasEndpoint {
        val input = draft.inputAddress.trim()
        if (input.isFullUrl()) {
            val normalized = input.normalizeUrl()
            return ResolvedNasEndpoint(
                inputAddress = input,
                primaryBaseUrl = normalized,
                candidateBaseUrls = listOf(normalized),
                wasFnIdOnly = false,
            )
        }

        val fnId = input.trim().trim('/').removePrefix("@")
        val candidates = listOf(
            "https://$fnId",
            "https://$fnId.5ddd.com",
        ).distinct()
        return ResolvedNasEndpoint(
            inputAddress = input,
            primaryBaseUrl = candidates.first(),
            candidateBaseUrls = candidates,
            wasFnIdOnly = true,
        )
    }
}

private object UrlResolver {
    fun resolve(draft: NasConnectionDraft): ResolvedNasEndpoint {
        val input = draft.inputAddress.trim()
        val normalized = if (input.isFullUrl()) input.normalizeUrl() else "https://${input.trim('/')}"
        return ResolvedNasEndpoint(
            inputAddress = input,
            primaryBaseUrl = normalized,
            candidateBaseUrls = listOf(normalized),
            wasFnIdOnly = false,
        )
    }
}

interface NasFileClient {
    suspend fun testConnection(credentials: NasCredentials): WebDavResult
    suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem>
    suspend fun listDirectories(credentials: NasCredentials, remotePath: String): List<NasDirectory>
    suspend fun listAudioFiles(credentials: NasCredentials, remotePath: String): List<NasAudioFile>
    suspend fun testDirectory(credentials: NasCredentials, remotePath: String): WebDavResult
    fun getDisplayPath(remotePath: String): String
    suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long
    fun urlFor(credentials: NasCredentials, remotePath: String): String
    suspend fun supportsRange(credentials: NasCredentials, remotePath: String): Boolean = true
}

class WebDavNasFileClient(private val webDavClient: WebDavClient) : NasFileClient {
    override suspend fun testConnection(credentials: NasCredentials): WebDavResult =
        webDavClient.testConnection(credentials)

    override suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        webDavClient.listDirectory(credentials, remotePath)

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

    override fun getDisplayPath(remotePath: String): String =
        remotePath.trim('/').split('/')
            .filter { it.isNotBlank() }
            .joinToString(" / ")
            .ifBlank { "NAS 根目录" }

    override suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long =
        webDavClient.downloadToFile(credentials, remotePath, target)

    override fun urlFor(credentials: NasCredentials, remotePath: String): String =
        webDavClient.urlFor(credentials, remotePath)
}

private fun String.isFullUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun String.normalizeUrl(): String = trim().trimEnd('/')
