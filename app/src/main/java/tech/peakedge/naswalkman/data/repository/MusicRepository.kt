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
import tech.peakedge.naswalkman.network.HttpBodyClassifier
import tech.peakedge.naswalkman.network.NasConnectionDraft
import tech.peakedge.naswalkman.network.NasCredentials
import tech.peakedge.naswalkman.network.NasDirectory
import tech.peakedge.naswalkman.network.NasFileClient
import tech.peakedge.naswalkman.network.RemoteItem
import tech.peakedge.naswalkman.network.WebDavHttpException
import tech.peakedge.naswalkman.network.WebDavResult
import tech.peakedge.naswalkman.network.WebDavUnexpectedResponseException
import tech.peakedge.naswalkman.security.CredentialCipher
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
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

data class LyricLine(
    val timeMs: Long?,
    val text: String,
)

data class LyricsContent(
    val sourceFileName: String,
    val lines: List<LyricLine>,
) {
    val hasTimeline: Boolean
        get() = lines.any { it.timeMs != null }
}

sealed class LyricsLoadResult {
    data class Found(val lyrics: LyricsContent) : LyricsLoadResult()
    data object NotFound : LyricsLoadResult()
    data class Failure(val message: String) : LyricsLoadResult()
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
    private val lyricsCache = object : LinkedHashMap<String, LyricsLoadResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsLoadResult>?): Boolean =
            size > MAX_LYRICS_CACHE_SIZE
    }

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
        val directoryAttempt = testResolvedDirectory(
            normalized = normalized,
            remotePath = selectedPath,
            preferredBaseUrl = successfulBaseUrl,
        )
        if (directoryAttempt.result !is WebDavResult.Success) return directoryAttempt.result
        val successfulPath = directoryAttempt.successfulRemotePath ?: selectedPath
        val selectedDisplayPath = if (normalizeRemotePath(successfulPath) == normalizeRemotePath(selectedPath)) {
            normalized.selectedMusicDisplayPath.ifBlank { nasFileClient.getDisplayPath(successfulPath) }
        } else {
            nasFileClient.getDisplayPath(successfulPath)
        }
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
            musicRootPath = successfulPath,
            selectedMusicRemotePath = successfulPath,
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
        val candidates = try {
            credentialsCandidatesForForm(normalized)
        } catch (_: IllegalArgumentException) {
            return DirectoryBrowserResult.Failure("连接地址解析失败，请检查 FNID 或 WebDAV 地址。")
        }
        var lastFailure: String = "已连接到 NAS，但暂时无法读取文件夹。请检查 WebDAV 地址和音乐目录权限。"
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
        return testResolvedDirectory(
            normalized = form.normalized(),
            remotePath = remotePath,
            preferredBaseUrl = null,
        ).result
    }

    private suspend fun testResolvedConnection(normalized: NasForm): ConnectionAttempt {
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        var lastFailure: WebDavResult.Failure? = null
        var result: WebDavResult = WebDavResult.Failure("无法连接 NAS，请检查 FNID、WebDAV 地址或当前网络是否可用。")
        var successfulBaseUrl: String? = null
        val candidates = try {
            credentialsCandidatesForForm(normalized).map { it.copy(musicRootPath = "/") }
        } catch (_: IllegalArgumentException) {
            return ConnectionAttempt(
                result = WebDavResult.Failure("连接地址解析失败，请检查 FNID 或 WebDAV 地址。"),
                successfulBaseUrl = null,
            )
        }
        for (credentials in candidates) {
            result = nasFileClient.testConnection(credentials)
            if (result is WebDavResult.Success) {
                successfulBaseUrl = credentials.baseUrl
                break
            }
            lastFailure = chooseBetterFailure(lastFailure, result as? WebDavResult.Failure)
        }
        if (result !is WebDavResult.Success && normalized.mode == NasConnectionMode.FN_CONNECT && resolved.wasFnIdOnly) {
            result = lastFailure ?: WebDavResult.Failure("FNID 已解析为 WebDAV 地址，但暂时无法读取文件目录。")
        } else if (result !is WebDavResult.Success) {
            lastFailure?.let { result = it }
        }
        if (result is WebDavResult.Success) {
            database.nasDao().getActiveNas()?.let { database.nasDao().markConnected(it.id, System.currentTimeMillis()) }
        }
        return ConnectionAttempt(result = result, successfulBaseUrl = successfulBaseUrl)
    }

    private suspend fun testResolvedDirectory(
        normalized: NasForm,
        remotePath: String,
        preferredBaseUrl: String?,
    ): ConnectionAttempt {
        val resolved = connectionResolver.resolve(normalized.toConnectionDraft())
        val normalizedPath = normalizeRemotePath(remotePath)
        val pathCandidates = directoryPathCandidates(
            path = normalizedPath,
            username = normalized.username,
            includeFnVariants = normalized.mode == NasConnectionMode.FN_CONNECT && resolved.wasFnIdOnly,
        )
        var result: WebDavResult = WebDavResult.Failure("音乐目录不存在，请重新选择目录或确认路径是否正确。")
        var lastFailure: WebDavResult.Failure? = null
        var successfulBaseUrl: String? = null
        var successfulRemotePath: String? = null
        val candidates = try {
            credentialsCandidatesForForm(normalized)
        } catch (_: IllegalArgumentException) {
            return ConnectionAttempt(
                result = WebDavResult.Failure("连接地址解析失败，请检查 FNID 或 WebDAV 地址。"),
                successfulBaseUrl = null,
            )
        }
            .let { credentials ->
                if (preferredBaseUrl == null) {
                    credentials
                } else {
                    credentials.sortedBy { it.baseUrl != preferredBaseUrl }
                }
            }
        for (credentials in candidates) {
            for (candidatePath in pathCandidates) {
                result = nasFileClient.testDirectory(credentials, candidatePath)
                if (result is WebDavResult.Success) {
                    successfulBaseUrl = credentials.baseUrl
                    successfulRemotePath = candidatePath
                    break
                }
                lastFailure = chooseBetterFailure(lastFailure, result as? WebDavResult.Failure)
            }
            if (result is WebDavResult.Success) break
        }
        if (result !is WebDavResult.Success && normalized.mode == NasConnectionMode.FN_CONNECT && resolved.wasFnIdOnly) {
            result = lastFailure ?: WebDavResult.Failure("FNID 已解析为 WebDAV 地址，但无法访问音乐目录。")
        } else if (result !is WebDavResult.Success) {
            lastFailure?.let { result = it }
        }
        return ConnectionAttempt(
            result = result,
            successfulBaseUrl = successfulBaseUrl,
            successfulRemotePath = successfulRemotePath,
        )
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

    suspend fun loadLyrics(track: TrackEntity, forceRefresh: Boolean = false): LyricsLoadResult =
        withContext(Dispatchers.IO) {
            val credentials = currentCredentials()
                ?: return@withContext LyricsLoadResult.Failure("先连接你的 NAS")
            val cacheKey = "${credentials.serverId}:${track.remotePath}:${track.modifiedAt.orEmpty()}"
            if (!forceRefresh) {
                synchronized(lyricsCache) {
                    lyricsCache[cacheKey]?.let { return@withContext it }
                }
            }

            val result = runCatching {
                findLyrics(credentials, track)
            }.getOrElse { error ->
                LyricsLoadResult.Failure(lyricErrorMessage(error))
            }

            if (result !is LyricsLoadResult.Failure) {
                synchronized(lyricsCache) {
                    lyricsCache[cacheKey] = result
                }
            }
            result
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
            NasConnectionMode.FN_CONNECT -> "请填写 FN ID 或 WebDAV 地址"
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
        val candidateBaseUrls = resolved.candidateBaseUrls
        return candidateBaseUrls.distinct().map { candidate ->
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
        val successfulRemotePath: String? = null,
    )

    private fun NasForm.effectiveMusicPath(): String =
        selectedMusicRemotePath.ifBlank { musicRootPath }

    private fun normalizeOptionalPath(path: String): String =
        path.trim().takeIf { it.isNotBlank() }?.let(::normalizeRemotePath).orEmpty()

    private fun normalizeRemotePath(path: String): String = "/" + path.trim().replace('\\', '/').trim('/')

    private fun directoryPathCandidates(
        path: String,
        username: String,
        includeFnVariants: Boolean,
    ): List<String> {
        val normalized = normalizeRemotePath(path)
        if (!includeFnVariants) return listOf(normalized)

        val trimmed = normalized.trim('/')
        val folderName = trimmed.substringAfterLast('/').ifBlank { trimmed.ifBlank { "music" } }
        return buildList {
            add(normalized)
            add("/$folderName")
            add("/dav/$folderName")
            add("/webdav/$folderName")
            if (username.isNotBlank()) {
                add("/home/$username/$folderName")
                add("/homes/$username/$folderName")
                add("/files/$username/$folderName")
                add("/$username/$folderName")
            }
            if (trimmed.isNotBlank() && !trimmed.startsWith("dav/")) add("/dav/$trimmed")
            if (trimmed.isNotBlank() && !trimmed.startsWith("webdav/")) add("/webdav/$trimmed")
        }
            .map(::normalizeRemotePath)
            .distinct()
    }

    private fun childDisplayPath(parent: String, child: String): String =
        if (parent == ROOT_DISPLAY_PATH) child else "$parent / $child"

    private fun folderNameForDisplay(displayPath: String): String =
        displayPath.substringAfterLast(" / ").ifBlank { ROOT_DISPLAY_PATH }

    private fun browsingErrorMessage(error: Exception): String = when (error) {
        is WebDavHttpException -> when (error.code) {
            401, 403 -> "账号或密码错误，或该账号没有 WebDAV 访问权限。"
            404 -> "WebDAV 路径不存在，请检查音乐目录。"
            405, 501 -> "当前地址不是 WebDAV 服务地址，请检查 FNID 解析结果或 WebDAV 地址。"
            else -> "已连接到 NAS，但暂时无法读取文件夹，WebDAV 服务响应异常（HTTP ${error.code}）。"
        }
        is WebDavUnexpectedResponseException -> unexpectedDirectoryMessage(error)
        is UnknownHostException -> "无法访问服务器，请检查网络或 FNID 是否正确。"
        is SocketTimeoutException -> "连接超时，请稍后重试或检查 NAS 远程访问状态。"
        is SSLHandshakeException -> "无法访问服务器，请检查网络或 FNID 是否正确。"
        is SSLException -> "无法访问服务器，请检查网络或 FNID 是否正确。"
        is ConnectException -> "无法访问服务器，请检查网络或 FNID 是否正确。"
        else -> "已连接到 NAS，但暂时无法读取文件夹。请检查 WebDAV 地址和音乐目录权限。"
    }

    private fun unexpectedDirectoryMessage(error: WebDavUnexpectedResponseException): String {
        val reason = error.diagnosis.reason
        return when {
            reason.startsWith("fn-connect-page") ->
                "返回的是 FN Connect 远程访问引导页，不是 WebDAV 目录。请检查 FNID 解析结果或 WebDAV 地址。"
            reason.startsWith("login-page") ->
                "返回的是登录页，登录态失效或文件服务需要网页登录，请重新登录。"
            reason.startsWith("captcha-page") ->
                "返回的是验证码页面，App 无法通过该页面读取文件目录。"
            reason.startsWith("permission-page") ->
                "返回的是权限页面，当前账号没有文件访问权限。"
            reason.startsWith("not-found-page") ->
                "返回的是 404/不存在页面，未找到 music 目录，请检查音乐目录路径。"
            reason.startsWith("method-not-allowed-page") ->
                "当前地址不支持目录读取，可能不是 WebDAV 文件服务地址。"
            reason == "webdav-xml-parse-failed" ->
                "文件服务可访问，但目录解析失败。"
            reason == "empty-body" ->
                "文件服务返回空内容，无法确认目录列表。"
            error.diagnosis.kind == HttpBodyClassifier.JSON ->
                "返回的是 JSON 响应（${reason}），不是文件目录列表。"
            else ->
                "文件服务返回了无法识别的内容，已在调试日志输出脱敏摘要。"
        }
    }

    private suspend fun findLyrics(credentials: NasCredentials, track: TrackEntity): LyricsLoadResult {
        val directory = parentRemotePath(track.remotePath)
        val baseName = track.fileName.substringBeforeLast('.', missingDelimiterValue = track.fileName)
        val exactFileName = "$baseName.lrc"
        readLyricsFileOrNull(
            credentials = credentials,
            remotePath = joinRemotePath(directory, exactFileName),
            sourceFileName = exactFileName,
        )?.let { return it }

        val lyricFiles = nasFileClient.listLyricFiles(credentials, directory)
        val matched = lyricFiles.firstOrNull { it.displayName.equals(exactFileName, ignoreCase = true) }
            ?: lyricFiles.firstOrNull { simplifyLyricName(it.displayName) == simplifyLyricName(baseName) }
            ?: return LyricsLoadResult.NotFound
        return readLyricsFileOrNull(credentials, matched.remotePath, matched.displayName)
            ?: LyricsLoadResult.NotFound
    }

    private suspend fun readLyricsFileOrNull(
        credentials: NasCredentials,
        remotePath: String,
        sourceFileName: String,
    ): LyricsLoadResult? {
        val text = try {
            nasFileClient.readTextFile(credentials, remotePath)
        } catch (error: WebDavHttpException) {
            if (error.code == 404) return null
            return LyricsLoadResult.Failure(lyricErrorMessage(error))
        }
        val lyrics = parseLrc(text, sourceFileName)
            ?: return LyricsLoadResult.Failure("歌词文件为空或格式异常，请检查 .lrc 内容。")
        return LyricsLoadResult.Found(lyrics)
    }

    private fun parseLrc(raw: String, sourceFileName: String): LyricsContent? {
        val timedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<LyricLine>()
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@forEach
                val timeTags = LRC_TIME_REGEX.findAll(line).toList()
                if (timeTags.isNotEmpty()) {
                    val lyricText = line.replace(LRC_TIME_REGEX, "").trim()
                    if (lyricText.isBlank()) return@forEach
                    timeTags.forEach { match ->
                        timedLines += LyricLine(
                            timeMs = parseLrcTimeMs(match),
                            text = lyricText,
                        )
                    }
                } else if (!LRC_METADATA_REGEX.matches(line)) {
                    plainLines += LyricLine(timeMs = null, text = line)
                }
            }

        val lines = if (timedLines.isNotEmpty()) {
            timedLines.sortedBy { it.timeMs ?: 0L }
        } else {
            plainLines
        }
        return lines.takeIf { it.isNotEmpty() }
            ?.let { LyricsContent(sourceFileName = sourceFileName, lines = it) }
    }

    private fun parseLrcTimeMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun lyricErrorMessage(error: Throwable): String = when (error) {
        is WebDavHttpException -> when (error.code) {
            401 -> "登录失败，无法读取歌词文件。请检查 NAS 用户名或密码。"
            403 -> "当前账号没有该目录权限，无法读取歌词文件。"
            404 -> "暂无歌词"
            405, 501 -> "文件服务未开启，无法读取歌词。请到飞牛 OS 的系统设置 > 文件共享协议 > WebDAV 开启服务。"
            else -> "歌词加载失败，文件服务返回 HTTP ${error.code}。"
        }
        is UnknownHostException, is SocketTimeoutException, is ConnectException -> "歌词加载失败，网络不可达，请稍后重试。"
        is SSLHandshakeException, is SSLException -> "歌词加载失败，安全连接异常，请检查访问地址或证书。"
        else -> "歌词加载失败，请稍后重试。"
    }

    private fun chooseBetterFailure(
        current: WebDavResult.Failure?,
        candidate: WebDavResult.Failure?,
    ): WebDavResult.Failure? = when {
        candidate == null -> current
        current == null -> candidate
        candidate.failurePriority() >= current.failurePriority() -> candidate
        else -> current
    }

    private fun WebDavResult.Failure.failurePriority(): Int = when (code) {
        401 -> 100
        403 -> 90
        404, 405, 501 -> 80
        in 500..599 -> 70
        null -> 10
        else -> 50
    }

    private fun parentRemotePath(remotePath: String): String {
        val normalized = normalizeRemotePath(remotePath)
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        return parent.ifBlank { "/" }
    }

    private fun joinRemotePath(parent: String, child: String): String =
        normalizeRemotePath("${parent.trimEnd('/')}/$child")

    private fun simplifyLyricName(fileName: String): String =
        fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

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
        const val MAX_LYRICS_CACHE_SIZE = 48
        val LRC_TIME_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val LRC_METADATA_REGEX = Regex("""^\[[a-zA-Z]+:.*]$""")
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
