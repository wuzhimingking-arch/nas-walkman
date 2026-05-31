package tech.peakedge.naswalkman.data.repository

import android.content.Context
import androidx.room.withTransaction
import tech.peakedge.naswalkman.data.db.AppDatabase
import tech.peakedge.naswalkman.data.db.CacheItemEntity
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import tech.peakedge.naswalkman.data.db.NasServerEntity
import tech.peakedge.naswalkman.data.db.PlayHistoryEntity
import tech.peakedge.naswalkman.data.db.PlaylistEntity
import tech.peakedge.naswalkman.data.db.PlaylistTrackEntity
import tech.peakedge.naswalkman.data.db.TrackEntity
import tech.peakedge.naswalkman.network.ConnectionResolver
import tech.peakedge.naswalkman.network.NasConnectionDraft
import tech.peakedge.naswalkman.network.NasCredentials
import tech.peakedge.naswalkman.network.NasDirectory
import tech.peakedge.naswalkman.network.NasFileClient
import tech.peakedge.naswalkman.network.RemoteItem
import tech.peakedge.naswalkman.network.WebDavHttpException
import tech.peakedge.naswalkman.network.WebDavResult
import tech.peakedge.naswalkman.security.CredentialCipher
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class NasForm(
    val name: String = "",
    val mode: NasConnectionMode = NasConnectionMode.FN_CONNECT,
    val inputAddress: String = "",
    val username: String = "",
    val password: String = "",
    val musicRootPath: String = "",
    val selectedMusicRemotePath: String = "",
    val selectedMusicDisplayPath: String = "",
    val selectedMusicFolderName: String = "",
    val autoScanWifiOnly: Boolean = true,
    val allowMobilePlayback: Boolean = true,
)

data class ScanProgress(
    val currentFolder: String = "",
    val discovered: Int = 0,
    val isRunning: Boolean = false,
)

data class DirectoryCrumb(
    val name: String,
    val remotePath: String,
    val displayPath: String,
)

data class DirectoryBrowserSnapshot(
    val current: DirectoryCrumb,
    val directories: List<NasDirectory>,
)

sealed class DirectoryBrowserResult {
    data class Success(val snapshot: DirectoryBrowserSnapshot) : DirectoryBrowserResult()
    data class Failure(val message: String) : DirectoryBrowserResult()
}

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
        val existing = database.nasDao().getActiveNas()
        validateSaveForm(form, allowStoredPassword = existing?.canUseStoredPasswordFor(form) == true)
            ?.let { return WebDavResult.Failure(it) }
        val normalized = form.normalized()
        val attempt = testResolvedConnection(normalized)
        if (attempt.result !is WebDavResult.Success) return attempt.result
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        val successfulBaseUrl = attempt.successfulBaseUrl ?: resolved.primaryBaseUrl
        val selectedPath = normalized.effectiveMusicPath()
        val selectedDisplayPath = normalized.selectedMusicDisplayPath
            .ifBlank { nasFileClient.getDisplayPath(selectedPath) }
        val selectedFolderName = normalized.selectedMusicFolderName
            .ifBlank { selectedDisplayPath.substringAfterLast(" / ", selectedDisplayPath) }
        val now = System.currentTimeMillis()
        val server = NasServerEntity(
            id = existing?.id ?: 1L,
            name = normalized.name.ifBlank { "家里的 NAS" },
            baseUrl = successfulBaseUrl,
            mode = normalized.mode,
            inputAddress = resolved.inputAddress,
            resolvedBaseUrl = successfulBaseUrl,
            username = normalized.username,
            encryptedPassword = if (normalized.password.isBlank() && existing != null) {
                existing.encryptedPassword
            } else {
                credentialCipher.encrypt(normalized.password)
            },
            musicRootPath = selectedPath,
            selectedMusicRemotePath = selectedPath,
            selectedMusicDisplayPath = selectedDisplayPath,
            selectedMusicFolderName = selectedFolderName,
            selectedMusicSelectedAt = now,
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
        val existing = database.nasDao().getActiveNas()
        validateConnectionForm(form, allowStoredPassword = existing?.canUseStoredPasswordFor(form) == true)
            ?.let { return WebDavResult.Failure(it) }
        return testResolvedConnection(form.normalized()).result
    }

    suspend fun testCurrentConnection(): WebDavResult {
        val credentials = currentCredentials() ?: return WebDavResult.Failure("先连接你的 NAS")
        val result = nasFileClient.testConnection(credentials)
        if (result is WebDavResult.Success) {
            database.nasDao().markConnected(credentials.serverId, System.currentTimeMillis())
        }
        return result
    }

    suspend fun listDirectoriesForForm(
        form: NasForm,
        remotePath: String,
        displayPath: String,
    ): DirectoryBrowserResult {
        val existing = database.nasDao().getActiveNas()
        validateConnectionForm(form, allowStoredPassword = existing?.canUseStoredPasswordFor(form) == true)
            ?.let { return DirectoryBrowserResult.Failure(it) }
        val normalized = form.normalized()
        val candidates = credentialsCandidatesForForm(normalized)
        var lastFailure: String = "已连接到 NAS，但暂时无法读取文件夹。请确认飞牛 NAS 已开启文件访问服务，或尝试使用完整远程访问地址。"
        for (credentials in candidates) {
            try {
                val directories = nasFileClient.listDirectories(credentials, remotePath)
                    .map { directory ->
                        directory.copy(displayPath = childDisplayPath(displayPath, directory.name))
                    }
                    .sortedWith(
                        compareByDescending<NasDirectory> { it.name.lowercase() in COMMON_DIRECTORY_NAMES }
                            .thenBy { it.name.lowercase() },
                    )
                return DirectoryBrowserResult.Success(
                    DirectoryBrowserSnapshot(
                        current = DirectoryCrumb(
                            name = folderNameForDisplay(displayPath),
                            remotePath = normalizeRemotePath(remotePath),
                            displayPath = displayPath,
                        ),
                        directories = directories,
                    ),
                )
            } catch (error: Exception) {
                lastFailure = browsingErrorMessage(error)
            }
        }
        return DirectoryBrowserResult.Failure(lastFailure)
    }

    suspend fun testDirectoryForForm(form: NasForm, remotePath: String): WebDavResult {
        val existing = database.nasDao().getActiveNas()
        validateConnectionForm(form, allowStoredPassword = existing?.canUseStoredPasswordFor(form) == true)
            ?.let { return WebDavResult.Failure(it) }
        for (credentials in credentialsCandidatesForForm(form.normalized())) {
            val result = nasFileClient.testDirectory(credentials, normalizeRemotePath(remotePath))
            if (result is WebDavResult.Success) return result
        }
        return WebDavResult.Failure("没有权限访问这个文件夹，请检查 NAS 用户权限。")
    }

    private suspend fun testResolvedConnection(normalized: NasForm): ConnectionAttempt {
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        var lastFailure: WebDavResult.Failure? = null
        var result: WebDavResult = WebDavResult.Failure("无法连接 NAS，请检查 FN Connect 是否开启或当前网络是否可用。")
        var successfulBaseUrl: String? = null
        for (credentials in credentialsCandidatesForForm(normalized).map { it.copy(musicRootPath = "/") }) {
            result = nasFileClient.testConnection(credentials)
            if (result is WebDavResult.Success) {
                successfulBaseUrl = credentials.baseUrl
                break
            }
            lastFailure = result as? WebDavResult.Failure
        }
        if (result !is WebDavResult.Success && normalized.mode == NasConnectionMode.FN_CONNECT && resolved.wasFnIdOnly) {
            result = WebDavResult.Failure("已识别 FN ID，但暂时无法读取文件夹。请确认飞牛 NAS 已开启文件访问服务，或复制完整远程访问地址后重试。")
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
            musicRootPath = server.selectedMusicRemotePath.ifBlank { server.musicRootPath },
        )
    }

    suspend fun listDirectory(remotePath: String? = null): List<RemoteItem> {
        val credentials = currentCredentials() ?: return emptyList()
        return nasFileClient.listDirectory(credentials, remotePath ?: credentials.musicRootPath)
    }

    suspend fun scanLibrary(onProgress: (ScanProgress) -> Unit): WebDavResult {
        val server = database.nasDao().getActiveNas() ?: return WebDavResult.Failure("先连接你的 NAS")
        val credentials = currentCredentials() ?: return WebDavResult.Failure("先连接你的 NAS")
        val discovered = mutableListOf<TrackEntity>()
        val scanId = System.currentTimeMillis()
        val rootDisplayPath = server.selectedMusicDisplayPath
            .ifBlank { nasFileClient.getDisplayPath(credentials.musicRootPath) }
        val stack = ArrayDeque<DirectoryCrumb>()
        stack += DirectoryCrumb(
            name = server.selectedMusicFolderName.ifBlank { folderNameForDisplay(rootDisplayPath) },
            remotePath = credentials.musicRootPath,
            displayPath = rootDisplayPath,
        )
        onProgress(ScanProgress(currentFolder = rootDisplayPath, isRunning = true))

        return try {
            while (stack.isNotEmpty()) {
                val folder = stack.removeLast()
                onProgress(ScanProgress(currentFolder = folder.displayPath, discovered = discovered.size, isRunning = true))
                val items = nasFileClient.listDirectory(credentials, folder.remotePath)
                for (item in items) {
                    if (item.isDirectory) {
                        stack += DirectoryCrumb(
                            name = item.displayName,
                            remotePath = item.remotePath,
                            displayPath = childDisplayPath(folder.displayPath, item.displayName),
                        )
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
            WebDavResult.Success(
                message = if (discovered.isEmpty()) {
                    "这个文件夹里没有发现支持的音乐文件，可以重新选择其他目录。"
                } else {
                    null
                },
            )
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
        val credentials = currentCredentials() ?: return@withContext WebDavResult.Failure("先连接你的 NAS")
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

    private fun validateConnectionForm(form: NasForm, allowStoredPassword: Boolean): String? = when {
        form.inputAddress.isBlank() -> when (form.mode) {
            NasConnectionMode.FN_CONNECT -> "请填写 FN ID 或 FN Connect 远程访问地址"
            NasConnectionMode.REMOTE_URL -> "请填写访问地址"
            NasConnectionMode.WEBDAV_ADVANCED -> "请填写连接地址"
        }
        form.username.isBlank() || (form.password.isBlank() && !allowStoredPassword) -> "请填写 NAS 用户名和密码"
        else -> null
    }

    private fun validateSaveForm(form: NasForm, allowStoredPassword: Boolean): String? =
        validateConnectionForm(form, allowStoredPassword) ?: if (form.effectiveMusicPath().isBlank()) {
            "请先选择音乐目录"
        } else {
            null
        }

    private fun NasForm.normalized(): NasForm {
        return copy(
            name = name.trim(),
            inputAddress = inputAddress.trim().trimEnd('/'),
            username = username.trim(),
            musicRootPath = normalizeOptionalPath(musicRootPath),
            selectedMusicRemotePath = normalizeOptionalPath(selectedMusicRemotePath),
            selectedMusicDisplayPath = selectedMusicDisplayPath.trim(),
            selectedMusicFolderName = selectedMusicFolderName.trim(),
        )
    }

    private fun NasForm.toConnectionDraft(): NasConnectionDraft =
        NasConnectionDraft(
            mode = mode,
            inputAddress = inputAddress,
            musicRootPath = effectiveMusicPath().ifBlank { "/" },
        )

    private suspend fun credentialsCandidatesForForm(form: NasForm): List<NasCredentials> {
        val activeServer = database.nasDao().getActiveNas()
        val current = currentCredentials()
        if (form.password.isBlank() && current != null && activeServer?.canUseStoredPasswordFor(form) == true) {
            return listOf(current)
        }
        val resolved = connectionResolver.resolve(form.toConnectionDraft())
        return resolved.candidateBaseUrls.map { candidate ->
            NasCredentials(
                serverId = current?.serverId ?: 1L,
                baseUrl = candidate,
                username = form.username,
                password = form.password,
                musicRootPath = form.effectiveMusicPath().ifBlank { "/" },
            )
        }
    }

    private data class ConnectionAttempt(
        val result: WebDavResult,
        val successfulBaseUrl: String?,
    )

    private fun NasForm.effectiveMusicPath(): String =
        selectedMusicRemotePath.ifBlank { musicRootPath }

    private fun normalizeOptionalPath(path: String): String =
        path.trim().takeIf { it.isNotBlank() }?.let(::normalizeRemotePath).orEmpty()

    private fun normalizeRemotePath(path: String): String = "/" + path.trim().replace('\\', '/').trim('/')

    private fun childDisplayPath(parent: String, child: String): String =
        if (parent == ROOT_DISPLAY_PATH) child else "$parent / $child"

    private fun folderNameForDisplay(displayPath: String): String =
        displayPath.substringAfterLast(" / ").ifBlank { ROOT_DISPLAY_PATH }

    private fun browsingErrorMessage(error: Exception): String = when (error) {
        is WebDavHttpException -> when (error.code) {
            401, 403 -> "没有权限访问这个文件夹，请检查飞牛 NAS 用户权限。"
            404 -> "路径解析失败，请重新进入目录选择器选择文件夹。"
            else -> "已连接到 NAS，但暂时无法读取文件夹。请确认飞牛 NAS 已开启文件访问服务，或尝试使用完整远程访问地址。"
        }
        is UnknownHostException -> "无法找到这个 FN Connect 地址，请检查 FN ID 或远程访问地址。"
        is SocketTimeoutException -> "连接超时，可能是 NAS 休眠、网络较慢或远程访问不稳定。"
        is SSLHandshakeException -> "安全证书校验失败，请检查访问地址是否正确。"
        is SSLException -> "安全连接失败，请检查访问地址或证书配置。"
        is ConnectException -> "NAS 拒绝连接，请检查远程访问服务是否开启。"
        else -> "已连接到 NAS，但暂时无法读取文件夹。请确认飞牛 NAS 已开启文件访问服务，或尝试使用完整远程访问地址。"
    }

    private fun NasServerEntity.canUseStoredPasswordFor(form: NasForm): Boolean {
        val input = form.inputAddress.trim().trimEnd('/')
        return username == form.username.trim() &&
            mode == form.mode &&
            (inputAddress == input || resolvedBaseUrl == input || baseUrl == input)
    }

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

    private companion object {
        const val ROOT_DISPLAY_PATH = "NAS 根目录"
        val COMMON_DIRECTORY_NAMES = setOf(
            "我的文件",
            "home",
            "music",
            "音乐",
            "下载",
            "download",
            "downloads",
        )
    }
}
