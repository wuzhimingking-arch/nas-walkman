package com.fnnas.music.network

import com.fnnas.music.data.db.NasConnectionMode
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
    suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long
    fun urlFor(credentials: NasCredentials, remotePath: String): String
    suspend fun supportsRange(credentials: NasCredentials, remotePath: String): Boolean = true
}

class WebDavNasFileClient(private val webDavClient: WebDavClient) : NasFileClient {
    override suspend fun testConnection(credentials: NasCredentials): WebDavResult =
        webDavClient.testConnection(credentials)

    override suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        webDavClient.listDirectory(credentials, remotePath)

    override suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long =
        webDavClient.downloadToFile(credentials, remotePath, target)

    override fun urlFor(credentials: NasCredentials, remotePath: String): String =
        webDavClient.urlFor(credentials, remotePath)
}

private fun String.isFullUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun String.normalizeUrl(): String = trim().trimEnd('/')
