package com.fnnas.music.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.fnnas.music.data.db.AppDatabase
import com.fnnas.music.data.db.CacheItemEntity
import com.fnnas.music.data.db.NasConnectionMode
import com.fnnas.music.data.db.NasServerEntity
import com.fnnas.music.data.db.PlayHistoryEntity
import com.fnnas.music.data.db.PlaylistEntity
import com.fnnas.music.data.db.PlaylistTrackEntity
import com.fnnas.music.data.db.TrackEntity
import com.fnnas.music.network.ConnectionResolver
import com.fnnas.music.network.NasConnectionDraft
import com.fnnas.music.network.NasCredentials
import com.fnnas.music.network.NasFileClient
import com.fnnas.music.network.RemoteItem
import com.fnnas.music.network.WebDavResult
import com.fnnas.music.security.CredentialCipher
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class NasForm(
    val name: String = "",
    val mode: NasConnectionMode = NasConnectionMode.FN_CONNECT,
    val inputAddress: String = "",
    val username: String = "",
    val password: String = "",
    val musicRootPath: String = "/Music",
    val autoScanWifiOnly: Boolean = true,
    val allowMobilePlayback: Boolean = true,
)

data class ScanProgress(
    val currentFolder: String = "",
    val discovered: Int = 0,
    val isRunning: Boolean = false,
)

class MusicRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val credentialCipher: CredentialCipher,
    private val connectionResolver: ConnectionResolver,
    private val nasFileClient: NasFileClient,
    val settingsStore: SettingsStore,
) {
    val nasServer: Flow<NasServerEntity?> = database.nasDao().observeActiveNas()
    val tracks: Flow<List<TrackEntity>> = database.trackDao().observeTracks()
    val favorites: Flow<List<TrackEntity>> = database.trackDao().observeFavorites()
    val recent: Flow<List<TrackEntity>> = database.trackDao().observeRecent()
    val playlists = database.playlistDao().observePlaylists()
    val cacheBytes = database.cacheDao().observeCacheBytes()

    fun search(query: String): Flow<List<TrackEntity>> = database.trackDao().search(query)

    suspend fun saveNas(form: NasForm): WebDavResult {
        validateForm(form)?.let { return WebDavResult.Failure(it) }
        val normalized = form.normalized()
        val attempt = testResolvedConnection(normalized)
        if (attempt.result !is WebDavResult.Success) return attempt.result
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        val successfulBaseUrl = attempt.successfulBaseUrl ?: resolved.primaryBaseUrl
        val existing = database.nasDao().getActiveNas()
        val now = System.currentTimeMillis()
        val server = NasServerEntity(
            id = existing?.id ?: 1L,
            name = normalized.name.ifBlank { "家里的飞牛 NAS" },
            baseUrl = successfulBaseUrl,
            mode = normalized.mode,
            inputAddress = resolved.inputAddress,
            resolvedBaseUrl = successfulBaseUrl,
            username = normalized.username,
            encryptedPassword = credentialCipher.encrypt(normalized.password),
            musicRootPath = normalized.musicRootPath,
            allowMobilePlayback = normalized.allowMobilePlayback,
            autoScanWifiOnly = normalized.autoScanWifiOnly,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastConnectedAt = now,
        )
        database.nasDao().upsert(server)
        return WebDavResult.Success()
    }

    suspend fun testConnection(form: NasForm): WebDavResult {
        validateForm(form)?.let { return WebDavResult.Failure(it) }
        return testResolvedConnection(form.normalized()).result
    }

    suspend fun testCurrentConnection(): WebDavResult {
        val credentials = currentCredentials() ?: return WebDavResult.Failure("先连接你的飞牛 NAS")
        val result = nasFileClient.testConnection(credentials)
        if (result is WebDavResult.Success) {
            database.nasDao().markConnected(credentials.serverId, System.currentTimeMillis())
        }
        return result
    }

    private suspend fun testResolvedConnection(normalized: NasForm): ConnectionAttempt {
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        var lastFailure: WebDavResult.Failure? = null
        var result: WebDavResult = WebDavResult.Failure("无法连接到飞牛 NAS，请检查 FN Connect 是否开启或当前网络是否可用。")
        var successfulBaseUrl: String? = null
        for (candidate in resolved.candidateBaseUrls) {
            val credentials = NasCredentials(
                serverId = 1L,
                baseUrl = candidate,
                username = normalized.username,
                password = normalized.password,
                musicRootPath = normalized.musicRootPath,
            )
            result = nasFileClient.testConnection(credentials)
            if (result is WebDavResult.Success) {
                successfulBaseUrl = candidate
                break
            }
            lastFailure = result as? WebDavResult.Failure
        }
        if (result !is WebDavResult.Success && normalized.mode == NasConnectionMode.FN_CONNECT && resolved.wasFnIdOnly) {
            result = WebDavResult.Failure("已识别 FN ID，但还需要完整的飞牛远程访问地址或开启文件访问服务。请复制飞牛系统中的完整远程访问地址后重试。")
        } else if (result !is WebDavResult.Success) {
            lastFailure?.let { result = it }
        }
        if (result is WebDavResult.Success) {
            database.nasDao().getActiveNas()?.let { database.nasDao().markConnected(it.id, System.currentTimeMillis()) }
        }
        return ConnectionAttempt(result = result, successfulBaseUrl = successfulBaseUrl)
    }

    suspend fun currentCredentials(): NasCredentials? {
        val server = database.nasDao().getActiveNas() ?: return null
        return NasCredentials(
            serverId = server.id,
            baseUrl = server.resolvedBaseUrl.ifBlank { server.baseUrl },
            username = server.username,
            password = credentialCipher.decrypt(server.encryptedPassword),
            musicRootPath = server.musicRootPath,
        )
    }

    suspend fun listDirectory(remotePath: String? = null): List<RemoteItem> {
        val credentials = currentCredentials() ?: return emptyList()
        return nasFileClient.listDirectory(credentials, remotePath ?: credentials.musicRootPath)
    }

    suspend fun scanLibrary(onProgress: (ScanProgress) -> Unit): WebDavResult {
        val credentials = currentCredentials() ?: return WebDavResult.Failure("先连接你的飞牛 NAS")
        val discovered = mutableListOf<TrackEntity>()
        val scanId = System.currentTimeMillis()
        val stack = ArrayDeque<String>()
        stack += credentials.musicRootPath
        onProgress(ScanProgress(currentFolder = credentials.musicRootPath, isRunning = true))

        return try {
            while (stack.isNotEmpty()) {
                val folder = stack.removeLast()
                onProgress(ScanProgress(currentFolder = folder, discovered = discovered.size, isRunning = true))
                val items = nasFileClient.listDirectory(credentials, folder)
                for (item in items) {
                    if (item.isDirectory) {
                        stack += item.remotePath
                    } else if (item.isSupportedAudio) {
                        val existing = database.trackDao().getTrackByRemotePath(credentials.serverId, item.remotePath)
                        discovered += item.toTrack(credentials.serverId, scanId, existing)
                        if (discovered.size % 20 == 0) {
                            database.trackDao().upsertAll(discovered.toList())
                        }
                    }
                }
            }
            database.withTransaction {
                database.trackDao().upsertAll(discovered)
                if (discovered.isEmpty()) {
                    database.trackDao().deleteAllForNas(credentials.serverId)
                } else {
                    database.trackDao().deleteNotSeenInScan(credentials.serverId, scanId)
                }
            }
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            WebDavResult.Success()
        } catch (error: Exception) {
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            WebDavResult.Failure("扫描中断，请检查网络连接和飞牛远程访问设置。")
        }
    }

    suspend fun toggleFavorite(trackId: String) {
        database.trackDao().toggleFavorite(trackId, System.currentTimeMillis())
    }

    suspend fun markPlayed(trackId: String, progressMs: Long = 0L) {
        database.playHistoryDao().insert(
            PlayHistoryEntity(trackId = trackId, playedAt = System.currentTimeMillis(), progressMs = progressMs),
        )
    }

    suspend fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        database.playlistDao().insertPlaylist(PlaylistEntity(name = trimmed, createdAt = now, updatedAt = now))
    }

    suspend fun addToPlaylist(playlistId: Long, trackId: String) {
        val now = System.currentTimeMillis()
        val sortOrder = database.playlistDao().nextSortOrder(playlistId)
        database.playlistDao().addTrack(PlaylistTrackEntity(playlistId, trackId, sortOrder, now))
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>> =
        database.playlistDao().observePlaylistTracks(playlistId)

    suspend fun cacheTrack(track: TrackEntity): WebDavResult = withContext(Dispatchers.IO) {
        val credentials = currentCredentials() ?: return@withContext WebDavResult.Failure("先连接你的飞牛 NAS")
        return@withContext try {
            val safeName = track.fileName.replace(Regex("""[^\w.\-]+"""), "_")
            val target = File(context.filesDir, "music-cache/${track.id}-$safeName")
            val size = nasFileClient.downloadToFile(credentials, track.remotePath, target)
            val now = System.currentTimeMillis()
            database.trackDao().updateCachePath(track.id, target.absolutePath, now)
            database.cacheDao().upsert(CacheItemEntity(trackId = track.id, localPath = target.absolutePath, size = size, cachedAt = now))
            WebDavResult.Success()
        } catch (_: Exception) {
            WebDavResult.Failure("缓存失败，请检查网络连接")
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        File(context.filesDir, "music-cache").deleteRecursively()
        database.cacheDao().clear()
        database.trackDao().clearAllCachePaths(System.currentTimeMillis())
    }

    suspend fun mediaUriFor(track: TrackEntity): String? {
        track.localCachePath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { return android.net.Uri.fromFile(it).toString() }
        val credentials = currentCredentials() ?: return null
        return nasFileClient.urlFor(credentials, track.remotePath)
    }

    suspend fun deleteBinding() {
        withContext(Dispatchers.IO) {
            File(context.filesDir, "music-cache").deleteRecursively()
            database.withTransaction {
                database.nasDao().clear()
                database.playHistoryDao().clear()
                database.cacheDao().clear()
                database.trackDao().deleteAll()
            }
        }
    }

    private fun validateForm(form: NasForm): String? = when {
        form.inputAddress.isBlank() -> when (form.mode) {
            NasConnectionMode.FN_CONNECT -> "请填写 FN ID 或飞牛远程访问地址"
            NasConnectionMode.REMOTE_URL -> "请填写访问地址"
            NasConnectionMode.WEBDAV_ADVANCED -> "请填写连接地址"
        }
        form.username.isBlank() || form.password.isBlank() -> "请填写飞牛 NAS 用户名和密码"
        form.musicRootPath.isBlank() -> "请填写音乐根目录"
        else -> null
    }

    private fun NasForm.normalized(): NasForm {
        return copy(
            name = name.trim(),
            inputAddress = inputAddress.trim().trimEnd('/'),
            username = username.trim(),
            musicRootPath = normalizePath(musicRootPath),
        )
    }

    private fun NasForm.toConnectionDraft(): NasConnectionDraft =
        NasConnectionDraft(
            mode = mode,
            inputAddress = inputAddress,
            musicRootPath = musicRootPath,
        )

    private data class ConnectionAttempt(
        val result: WebDavResult,
        val successfulBaseUrl: String?,
    )

    private fun normalizePath(path: String): String = "/" + path.trim().replace('\\', '/').trim('/')

    private fun RemoteItem.toTrack(nasServerId: Long, scanId: Long, existing: TrackEntity?): TrackEntity {
        val now = System.currentTimeMillis()
        val fileTitle = displayName.substringBeforeLast('.').ifBlank { displayName }
        return TrackEntity(
            id = existing?.id ?: stableTrackId(nasServerId, remotePath),
            nasServerId = nasServerId,
            remotePath = remotePath,
            fileName = displayName,
            title = existing?.title ?: fileTitle,
            artist = existing?.artist ?: "未知歌手",
            album = existing?.album,
            durationMs = existing?.durationMs,
            fileSize = size,
            modifiedAt = modifiedAt,
            coverCachePath = existing?.coverCachePath,
            isFavorite = existing?.isFavorite ?: false,
            localCachePath = existing?.localCachePath?.takeIf { File(it).exists() },
            lastSeenScanId = scanId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun stableTrackId(nasServerId: Long, remotePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$nasServerId:$remotePath".toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(32)
    }
}
