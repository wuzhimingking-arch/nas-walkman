package tech.peakedge.naswalkman.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import okhttp3.Credentials
import tech.peakedge.naswalkman.data.db.AppDatabase
import tech.peakedge.naswalkman.data.db.CacheItemEntity
import tech.peakedge.naswalkman.data.db.MusicFolderEntity
import tech.peakedge.naswalkman.data.db.MusicSourceType
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import tech.peakedge.naswalkman.data.db.NasServerEntity
import tech.peakedge.naswalkman.data.db.PlayHistoryEntity
import tech.peakedge.naswalkman.data.db.PlaylistEntity
import tech.peakedge.naswalkman.data.db.PlaylistTrackEntity
import tech.peakedge.naswalkman.data.db.TrackEntity
import tech.peakedge.naswalkman.network.AudioFormats
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
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

private data class SourceScanStats(
    val fileCount: Int = 0,
    val audioCount: Int = 0,
    val skippedCount: Int = 0,
    val folderCount: Int = 0,
)

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

class PlaybackSourceException(message: String) : Exception(message)

class MusicRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val credentialCipher: CredentialCipher,
    private val connectionResolver: ConnectionResolver,
    private val nasFileClient: NasFileClient,
    val settingsStore: SettingsStore,
) {
    val nasServer: Flow<NasServerEntity?> = database.nasDao().observeActiveNas()
    val musicFolders: Flow<List<MusicFolderEntity>> = database.musicFolderDao().observeFolders()
    val tracks: Flow<List<TrackEntity>> = database.trackDao().observeTracks()
    val favorites: Flow<List<TrackEntity>> = database.trackDao().observeFavorites()
    val recent: Flow<List<TrackEntity>> = database.trackDao().observeRecent()
    val mostPlayed = database.playHistoryDao().observeMostPlayed()
    val playlists = database.playlistDao().observePlaylists()
    val cacheBytes = database.cacheDao().observeCacheBytes()
    private val lyricsCache = object : LinkedHashMap<String, LyricsLoadResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsLoadResult>?): Boolean =
            size > MAX_LYRICS_CACHE_SIZE
    }
    private val failedCoverKeys = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_FAILED_COVER_KEYS
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

    suspend fun addNasMusicFolder(
        remotePath: String,
        displayName: String,
        includeSubfolders: Boolean,
    ): WebDavResult = withContext(Dispatchers.IO) {
        val server = database.nasDao().getActiveNas()
            ?: return@withContext WebDavResult.Failure("请先登录 NAS 后再添加 NAS 文件夹")
        val normalizedPath = normalizeRemotePath(remotePath)
        val sourceKey = nasSourceKey(server.id, normalizedPath)
        if (database.musicFolderDao().getBySourceKey(sourceKey) != null) {
            return@withContext WebDavResult.Failure("该音乐文件夹已存在")
        }
        val now = System.currentTimeMillis()
        database.musicFolderDao().upsert(
            MusicFolderEntity(
                sourceType = MusicSourceType.NAS,
                sourceKey = sourceKey,
                path = normalizedPath,
                displayName = displayName.ifBlank { nasFileClient.getDisplayPath(normalizedPath) },
                includeSubfolders = includeSubfolders,
                nasServerId = server.id,
                createdAt = now,
                updatedAt = now,
            ),
        )
        WebDavResult.Success("已添加 NAS 音乐文件夹")
    }

    suspend fun addLocalMusicFolder(
        uri: Uri,
        grantFlags: Int,
        includeSubfolders: Boolean,
    ): WebDavResult = withContext(Dispatchers.IO) {
        val normalizedUri = uri.normalizeScheme()
        val uriText = normalizedUri.toString()
        val sourceKey = localSourceKey(uriText)
        if (database.musicFolderDao().getBySourceKey(sourceKey) != null) {
            return@withContext WebDavResult.Failure("该文件夹已添加")
        }
        val document = DocumentFile.fromTreeUri(context, normalizedUri)
            ?: return@withContext WebDavResult.Failure("无法访问该文件夹，请重新选择")
        if (!safeCanRead(document)) {
            return@withContext WebDavResult.Failure("无法访问该文件夹，请重新选择")
        }

        val persistableFlags = grantFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (persistableFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return@withContext WebDavResult.Failure("文件夹授权失败，请重新选择")
        }
        val persisted = try {
            context.contentResolver.takePersistableUriPermission(normalizedUri, persistableFlags)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "persist local folder permission failed: uri=$normalizedUri", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "persist local folder permission failed: uri=$normalizedUri", error)
            false
        } catch (error: Exception) {
            Log.w(TAG, "persist local folder permission failed: uri=$normalizedUri", error)
            false
        }
        if (!persisted && !hasPersistedReadPermission(normalizedUri)) {
            return@withContext WebDavResult.Failure("文件夹授权失败，请重新选择")
        }
        val now = System.currentTimeMillis()
        database.musicFolderDao().upsert(
            MusicFolderEntity(
                sourceType = MusicSourceType.LOCAL,
                sourceKey = sourceKey,
                path = uriText,
                displayName = queryDisplayName(normalizedUri),
                includeSubfolders = includeSubfolders,
                createdAt = now,
                updatedAt = now,
            ),
        )
        WebDavResult.Success("已添加本地音乐文件夹")
    }

    suspend fun updateMusicFolderIncludeSubfolders(folderId: Long, includeSubfolders: Boolean): WebDavResult =
        withContext(Dispatchers.IO) {
            val folder = database.musicFolderDao().getById(folderId)
                ?: return@withContext WebDavResult.Failure("音乐文件夹不存在")
            database.musicFolderDao().updateIncludeSubfolders(
                id = folder.id,
                includeSubfolders = includeSubfolders,
                updatedAt = System.currentTimeMillis(),
            )
            WebDavResult.Success("已更新音乐文件夹设置")
        }

    suspend fun deleteMusicFolder(folderId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.trackDao().deleteAllForSourceFolder(folderId)
            database.musicFolderDao().deleteById(folderId)
        }
    }

    suspend fun scanMusicFolder(folderId: Long, onProgress: (ScanProgress) -> Unit): WebDavResult {
        val folder = database.musicFolderDao().getById(folderId)
            ?: return WebDavResult.Failure("音乐文件夹不存在")
        return scanMusicFolderInternal(folder, onProgress)
    }

    suspend fun loadLyrics(track: TrackEntity, forceRefresh: Boolean = false): LyricsLoadResult =
        withContext(Dispatchers.IO) {
            if (track.sourceType == MusicSourceType.LOCAL) return@withContext LyricsLoadResult.NotFound
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
        val folders = database.musicFolderDao().getAll()
        if (folders.isEmpty()) {
            onProgress(ScanProgress(isRunning = false))
            return WebDavResult.Success("还没有添加音乐文件夹")
        }

        var totalDiscovered = 0
        val failures = mutableListOf<String>()
        folders.forEach { folder ->
            val result = scanMusicFolderInternal(folder) { progress ->
                onProgress(
                    progress.copy(
                        discovered = totalDiscovered + progress.discovered,
                        isRunning = true,
                    ),
                )
            }
            val latestCount = database.trackDao().countForSourceFolder(folder.id)
            totalDiscovered += latestCount
            if (result is WebDavResult.Failure) failures += result.message
        }
        onProgress(ScanProgress(discovered = totalDiscovered, isRunning = false))

        return when {
            failures.size == folders.size -> WebDavResult.Failure(failures.firstOrNull() ?: "音乐库扫描失败")
            failures.isNotEmpty() -> WebDavResult.Success("部分音乐文件夹扫描失败：${failures.distinct().joinToString("；")}")
            totalDiscovered == 0 -> WebDavResult.Success("已扫描音乐文件夹，但没有发现支持的音乐文件")
            else -> WebDavResult.Success()
        }
    }

    private suspend fun scanMusicFolderInternal(
        folder: MusicFolderEntity,
        onProgress: (ScanProgress) -> Unit,
    ): WebDavResult = withContext(Dispatchers.IO) {
        when (folder.sourceType) {
            MusicSourceType.NAS -> scanNasMusicFolder(folder, onProgress)
            MusicSourceType.LOCAL -> scanLocalMusicFolder(folder, onProgress)
        }
    }

    private suspend fun scanNasMusicFolder(
        sourceFolder: MusicFolderEntity,
        onProgress: (ScanProgress) -> Unit,
    ): WebDavResult {
        val server = database.nasDao().getActiveNas()
        val credentials = currentCredentials()
        Log.i(
            TAG,
            "NAS scan start folderId=${sourceFolder.id} " +
                "folderPath=${sourceFolder.path} includeSubfolders=${sourceFolder.includeSubfolders} " +
                "loggedIn=${credentials != null} baseHost=${credentials?.baseUrl?.safeHostForLog().orEmpty()}",
        )
        if (credentials == null) {
            val stats = SourceScanStats()
            val message = "NAS 未登录"
            recordSourceScanFailure(sourceFolder.id, stats, message)
            Log.w(TAG, "NAS scan failed folderId=${sourceFolder.id} reason=$message serverExists=${server != null}")
            return WebDavResult.Failure("请先登录 NAS 后再扫描 NAS 文件夹")
        }
        val discovered = mutableListOf<TrackEntity>()
        val scanId = System.currentTimeMillis()
        var stats = SourceScanStats()
        val visitedRemotePaths = mutableSetOf<String>()
        val rootDisplayPath = sourceFolder.displayName.ifBlank { nasFileClient.getDisplayPath(sourceFolder.path) }
        val stack = ArrayDeque<DirectoryCrumb>()
        stack += DirectoryCrumb(
            name = folderNameForDisplay(rootDisplayPath),
            remotePath = normalizeRemotePath(sourceFolder.path),
            displayPath = rootDisplayPath,
        )
        onProgress(ScanProgress(currentFolder = rootDisplayPath, isRunning = true))

        return try {
            while (stack.isNotEmpty()) {
                val folder = stack.removeLast()
                val normalizedFolderPath = normalizeRemotePath(folder.remotePath)
                if (!visitedRemotePaths.add(normalizedFolderPath)) {
                    Log.d(TAG, "NAS scan skip folder path=$normalizedFolderPath reason=already_visited")
                    continue
                }
                onProgress(ScanProgress(currentFolder = folder.displayPath, discovered = discovered.size, isRunning = true))
                Log.i(
                    TAG,
                    "NAS scan request folderId=${sourceFolder.id} path=${folder.remotePath} " +
                        "depth=1 recursive=${sourceFolder.includeSubfolders}",
                )
                val items = nasFileClient.listDirectoryRaw(credentials, folder.remotePath)
                val folderCount = items.count { it.isDirectory }
                val fileCount = items.count { !it.isDirectory }
                val audioCount = items.count { it.isSupportedAudio }
                stats = stats.copy(
                    fileCount = stats.fileCount + fileCount,
                    audioCount = stats.audioCount + audioCount,
                    folderCount = stats.folderCount + folderCount,
                )
                Log.i(
                    TAG,
                    "NAS scan response folderId=${sourceFolder.id} path=${folder.remotePath} " +
                        "httpStatus=207 itemCount=${items.size} fileCount=$fileCount folderCount=$folderCount audioCount=$audioCount",
                )
                for (item in items) {
                    if (item.isDirectory) {
                        if (sourceFolder.includeSubfolders) {
                            Log.d(TAG, "NAS scan enter folder path=${item.remotePath} name=${item.displayName}")
                            stack += DirectoryCrumb(
                                name = item.displayName,
                                remotePath = item.remotePath,
                                displayPath = childDisplayPath(folder.displayPath, item.displayName),
                            )
                        } else {
                            Log.d(TAG, "NAS scan skip folder path=${item.remotePath} reason=includeSubfolders_disabled")
                        }
                    } else {
                        val extension = AudioFormats.extension(item.displayName)
                        val isAudio = item.isSupportedAudio
                        Log.d(
                            TAG,
                            "NAS scan file name=${item.displayName} path=${item.remotePath} extension=$extension " +
                                "mimeType=${item.mimeType.orEmpty()} isAudio=$isAudio size=${item.size} modifiedAt=${item.modifiedAt}",
                        )
                        if (!isAudio) {
                            stats = stats.copy(skippedCount = stats.skippedCount + 1)
                            Log.d(TAG, "NAS scan skip file path=${item.remotePath} reason=unsupported_extension extension=$extension")
                            continue
                        }
                        val existing = database.trackDao().getTrackByRemotePath(credentials.serverId, item.remotePath)
                        discovered += item.toTrack(
                            credentials = credentials,
                            sourceFolderId = sourceFolder.id,
                            scanId = scanId,
                            existing = existing,
                        )
                        if (discovered.size % 20 == 0) {
                            database.trackDao().upsertAll(discovered.toList())
                        }
                    }
                }
            }
            val status = if (discovered.isEmpty()) "未发现支持的音频文件" else "成功"
            val error = if (discovered.isEmpty()) "扫描完成，但没有发现支持的音频文件" else null
            finishSourceScan(sourceFolder.id, discovered, scanId, stats, status, error)
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            Log.i(
                TAG,
                "NAS scan complete folderId=${sourceFolder.id} totalFiles=${stats.fileCount} " +
                    "totalFolders=${stats.folderCount} audio=${stats.audioCount} added=${discovered.size} " +
                    "skipped=${stats.skippedCount} status=$status error=${error.orEmpty()}",
            )
            if (discovered.isEmpty()) {
                WebDavResult.Success("扫描完成，但没有发现支持的音频文件")
            } else {
                WebDavResult.Success()
            }
        } catch (error: WebDavHttpException) {
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            val message = when (error.code) {
                401, 403 -> "文件夹没有读取权限"
                404 -> "文件夹不存在"
                else -> "NAS 连接失败（HTTP ${error.code}）"
            }
            recordSourceScanFailure(sourceFolder.id, stats, message)
            Log.e(
                TAG,
                "NAS scan failed folderId=${sourceFolder.id} status=${error.code} " +
                    "totalFiles=${stats.fileCount} audio=${stats.audioCount} skipped=${stats.skippedCount} reason=$message",
                error,
            )
            WebDavResult.Failure(message)
        } catch (error: Exception) {
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            val message = "扫描失败，请查看日志"
            recordSourceScanFailure(sourceFolder.id, stats, message)
            Log.e(
                TAG,
                "NAS scan failed folderId=${sourceFolder.id} totalFiles=${stats.fileCount} " +
                    "audio=${stats.audioCount} skipped=${stats.skippedCount} reason=${error.message}",
                error,
            )
            WebDavResult.Failure("NAS 文件夹扫描失败，请检查 NAS 连接和目录权限")
        }
    }

    private suspend fun scanLocalMusicFolder(
        sourceFolder: MusicFolderEntity,
        onProgress: (ScanProgress) -> Unit,
    ): WebDavResult {
        val treeUri = runCatching { Uri.parse(sourceFolder.path) }.getOrNull()
            ?: return WebDavResult.Failure("无法访问该文件夹，请重新选择")
        if (!hasPersistedReadPermission(treeUri)) {
            return WebDavResult.Failure("文件夹授权失败，请重新选择")
        }
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return WebDavResult.Failure("无法访问该文件夹，请重新选择")
        if (!safeCanRead(root)) {
            return WebDavResult.Failure("无法访问该文件夹，请重新选择")
        }

        val discovered = mutableListOf<TrackEntity>()
        val scanId = System.currentTimeMillis()
        var stats = SourceScanStats()
        val stack = ArrayDeque<LocalDirectory>()
        stack += LocalDirectory(root, sourceFolder.displayName.ifBlank { "本地音乐文件夹" })
        onProgress(ScanProgress(currentFolder = sourceFolder.displayName, isRunning = true))

        return try {
            while (stack.isNotEmpty()) {
                val directory = stack.removeLast()
                onProgress(ScanProgress(currentFolder = directory.displayPath, discovered = discovered.size, isRunning = true))
                val children = try {
                    directory.documentFile.listFiles()
                } catch (error: UnsupportedOperationException) {
                    Log.w(TAG, "skip unsupported local directory: ${directory.documentFile.uri}", error)
                    emptyArray()
                } catch (error: SecurityException) {
                    Log.w(TAG, "skip unreadable local directory: ${directory.documentFile.uri}", error)
                    emptyArray()
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "skip invalid local directory: ${directory.documentFile.uri}", error)
                    emptyArray()
                } catch (error: Exception) {
                    Log.w(TAG, "skip failed local directory: ${directory.documentFile.uri}", error)
                    emptyArray()
                }
                for (child in children) {
                    val displayName = safeDocumentName(child)
                    if (displayName.isBlank()) continue
                    val isDirectory = safeIsDirectory(child)
                    if (isDirectory) {
                        stats = stats.copy(folderCount = stats.folderCount + 1)
                        if (sourceFolder.includeSubfolders && safeCanRead(child)) {
                            stack += LocalDirectory(
                                documentFile = child,
                                displayPath = childDisplayPath(directory.displayPath, displayName),
                            )
                        }
                        continue
                    }
                    stats = stats.copy(fileCount = stats.fileCount + 1)
                    if (!safeIsFile(child) || !AudioFormats.isSupported(displayName)) continue
                    stats = stats.copy(audioCount = stats.audioCount + 1)
                    val documentUri = child.uri
                    val documentUriText = documentUri.toString()
                    val existing = database.trackDao().getTrackByRemotePath(LOCAL_SOURCE_SERVER_ID, documentUriText)
                    val track = try {
                        localDocumentToTrack(
                            sourceFolderId = sourceFolder.id,
                            documentUri = documentUri,
                            displayName = displayName,
                            size = safeLength(child),
                            modifiedAt = safeLastModified(child),
                            scanId = scanId,
                            existing = existing,
                        )
                    } catch (error: SecurityException) {
                        Log.w(TAG, "skip unreadable local audio: $documentUriText", error)
                        null
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "skip invalid local audio: $documentUriText", error)
                        null
                    } catch (error: Exception) {
                        Log.w(TAG, "skip failed local audio: $documentUriText", error)
                        null
                    }
                    if (track != null) {
                        discovered += track
                        if (discovered.size % 20 == 0) {
                            database.trackDao().upsertAll(discovered.toList())
                        }
                    }
                }
            }
            val status = if (discovered.isEmpty()) "未发现支持的音频文件" else "成功"
            val error = if (discovered.isEmpty()) "扫描完成，但没有发现支持的音频文件" else null
            finishSourceScan(sourceFolder.id, discovered, scanId, stats, status, error)
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            WebDavResult.Success()
        } catch (_: SecurityException) {
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            recordSourceScanFailure(sourceFolder.id, stats, "扫描本地音乐文件夹失败，请检查权限")
            WebDavResult.Failure("扫描本地音乐文件夹失败，请检查权限")
        } catch (_: Exception) {
            onProgress(ScanProgress(discovered = discovered.size, isRunning = false))
            recordSourceScanFailure(sourceFolder.id, stats, "扫描本地音乐文件夹失败，请检查权限")
            WebDavResult.Failure("扫描本地音乐文件夹失败，请检查权限")
        }
    }

    private suspend fun finishSourceScan(
        sourceFolderId: Long,
        tracks: List<TrackEntity>,
        scanId: Long,
        stats: SourceScanStats,
        status: String,
        error: String? = null,
    ) {
        database.withTransaction {
            database.trackDao().upsertAll(tracks)
            if (tracks.isEmpty()) {
                database.trackDao().deleteAllForSourceFolder(sourceFolderId)
            } else {
                database.trackDao().deleteNotSeenInSourceScan(sourceFolderId, scanId)
            }
            database.musicFolderDao().updateScanStats(
                id = sourceFolderId,
                songCount = tracks.size,
                status = status,
                error = error,
                fileCount = stats.fileCount,
                audioCount = stats.audioCount,
                lastScannedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun recordSourceScanFailure(sourceFolderId: Long, stats: SourceScanStats, error: String) {
        database.musicFolderDao().updateScanStats(
            id = sourceFolderId,
            songCount = database.trackDao().countForSourceFolder(sourceFolderId),
            status = "失败",
            error = error,
            fileCount = stats.fileCount,
            audioCount = stats.audioCount,
            lastScannedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
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

    suspend fun removeFromPlaylist(playlistId: Long, trackId: String) {
        database.playlistDao().removeTrack(playlistId, trackId)
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>> =
        database.playlistDao().observePlaylistTracks(playlistId)

    suspend fun cacheTrack(track: TrackEntity): WebDavResult = withContext(Dispatchers.IO) {
        if (track.sourceType == MusicSourceType.LOCAL) {
            return@withContext WebDavResult.Success("本地歌曲已可直接播放，无需缓存")
        }
        val credentials = currentCredentials() ?: return@withContext WebDavResult.Failure("先连接你的 NAS")
        return@withContext try {
            val safeName = track.fileName.replace(Regex("""[^\w.\-]+"""), "_")
            val target = File(context.filesDir, "music-cache/${track.id}-$safeName")
            val size = nasFileClient.downloadToFile(credentials, track.remotePath, target)
            val now = System.currentTimeMillis()
            database.trackDao().updateCachePath(track.id, target.absolutePath, now)
            database.cacheDao().upsert(CacheItemEntity(trackId = track.id, localPath = target.absolutePath, size = size, cachedAt = now))
            ensureMetadata(track.copy(localCachePath = target.absolutePath), force = true)
            trimManualCache()
            WebDavResult.Success()
        } catch (_: Exception) {
            WebDavResult.Failure("缓存失败，请检查网络连接")
        }
    }

    suspend fun ensureMetadata(track: TrackEntity, force: Boolean = false): TrackEntity? = withContext(Dispatchers.IO) {
        val current = database.trackDao().getTrack(track.id) ?: track
        if (!force && current.hasUsefulMetadata()) return@withContext current
        val credentials = if (current.sourceType == MusicSourceType.NAS) {
            currentCredentials() ?: return@withContext current
        } else {
            null
        }
        val metadata = withTimeoutOrNull(METADATA_READ_TIMEOUT_MS) {
            runCatching { readMetadata(credentials, current) }.getOrNull()
        } ?: return@withContext current.withFileNameFallback()
            .also { database.trackDao().upsert(it.copy(updatedAt = System.currentTimeMillis())) }

        val updated = current.mergeMetadata(metadata)
        database.trackDao().upsert(updated)
        updated
    }

    suspend fun hasCachedSongInfo(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        val current = database.trackDao().getTrack(track.id) ?: track
        current.hasUsefulMetadata() && (current.hasUsableCover() || current.hasKnownFailedCover())
    }

    suspend fun currentTrack(trackId: String): TrackEntity? = withContext(Dispatchers.IO) {
        database.trackDao().getTrack(trackId)
    }

    suspend fun ensureCover(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        val coverKey = coverCacheKey(track)
        track.coverCachePath
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L && it.nameWithoutExtension == coverKey }
            ?.let { return@withContext it.absolutePath }

        synchronized(failedCoverKeys) {
            if (failedCoverKeys.containsKey(coverKey)) return@withContext null
        }

        val credentials = if (track.sourceType == MusicSourceType.NAS) {
            currentCredentials() ?: return@withContext null
        } else {
            null
        }
        val embeddedCover = runCatching {
            extractEmbeddedCover(credentials, track)
        }.getOrNull() ?: run {
            synchronized(failedCoverKeys) { failedCoverKeys[coverKey] = System.currentTimeMillis() }
            return@withContext null
        }

        val bitmap = BitmapFactory.decodeByteArray(embeddedCover, 0, embeddedCover.size)
            ?: run {
                synchronized(failedCoverKeys) { failedCoverKeys[coverKey] = System.currentTimeMillis() }
                return@withContext null
            }
        val thumbnail = bitmap.scaledToMaxEdge(COVER_MAX_EDGE_PX)
        val coverDir = File(context.cacheDir, "album-art").apply { mkdirs() }
        val target = File(coverDir, "$coverKey.jpg")

        return@withContext runCatching {
            target.outputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
            }
            val path = target.absolutePath
            database.trackDao().updateCoverCachePath(track.id, path, System.currentTimeMillis())
            trimCoverCache()
            path
        }.getOrElse {
            synchronized(failedCoverKeys) { failedCoverKeys[coverKey] = System.currentTimeMillis() }
            null
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        File(context.filesDir, "music-cache").deleteRecursively()
        File(context.filesDir, "music-cache-prefetch").deleteRecursively()
        File(context.cacheDir, "album-art").deleteRecursively()
        database.cacheDao().clear()
        database.trackDao().clearAllCachePaths(System.currentTimeMillis())
        database.trackDao().clearAllCoverCachePaths(System.currentTimeMillis())
        synchronized(failedCoverKeys) { failedCoverKeys.clear() }
    }

    suspend fun mediaUriFor(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        if (track.sourceType == MusicSourceType.LOCAL) {
            return@withContext validatedLocalPlaybackUri(track.remotePath)
        }
        track.localCachePath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { return@withContext android.net.Uri.fromFile(it).toString() }
        val credentials = currentCredentials() ?: return@withContext null
        return@withContext nasFileClient.urlFor(credentials, track.remotePath)
    }

    private fun validatedLocalPlaybackUri(uriText: String): String? {
        if (uriText.isBlank()) throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
        val uri = try {
            Uri.parse(uriText)
        } catch (_: Exception) {
            throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
        }
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> {
                if (!hasPersistedReadPermissionFor(uri)) {
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                }
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        if (descriptor.statSize == 0L) {
                            Log.w(TAG, "local playback uri reports zero length: $uri")
                        }
                    } ?: throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                    uri.toString()
                } catch (error: SecurityException) {
                    Log.w(TAG, "local playback permission denied: $uri", error)
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                } catch (error: FileNotFoundException) {
                    Log.w(TAG, "local playback file missing: $uri", error)
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "local playback uri invalid: $uri", error)
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                } catch (error: IOException) {
                    Log.w(TAG, "local playback uri open failed: $uri", error)
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                }
            }
            "file" -> {
                val file = runCatching { File(requireNotNull(uri.path)) }.getOrNull()
                if (file?.exists() == true && file.canRead()) uri.toString() else {
                    throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
                }
            }
            else -> throw PlaybackSourceException("无法播放本地音乐，请检查文件权限或重新添加文件夹")
        }
    }

    suspend fun deleteBinding() {
        withContext(Dispatchers.IO) {
            File(context.filesDir, "music-cache").deleteRecursively()
            synchronized(failedCoverKeys) { failedCoverKeys.clear() }
            database.withTransaction {
                database.nasDao().clear()
                database.musicFolderDao().deleteBySourceType(MusicSourceType.NAS)
                database.trackDao().deleteAllForSourceType(MusicSourceType.NAS)
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

    private data class TrackMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val embeddedCover: ByteArray?,
    )

    private data class FileNameFallback(
        val title: String,
        val artist: String?,
    )

    private fun readMetadata(credentials: NasCredentials?, track: TrackEntity): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, credentials, track)
            TrackMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).cleanMetadata(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).cleanMetadata(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).cleanMetadata(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L },
                embeddedCover = retriever.embeddedPicture,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractEmbeddedCover(credentials: NasCredentials?, track: TrackEntity): ByteArray? =
        readMetadata(credentials, track).embeddedCover

    private fun setRetrieverDataSource(
        retriever: MediaMetadataRetriever,
        credentials: NasCredentials?,
        track: TrackEntity,
    ) {
        val localFile = track.localCachePath
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
        if (localFile != null) {
            retriever.setDataSource(localFile.absolutePath)
        } else if (track.sourceType == MusicSourceType.LOCAL) {
            retriever.setDataSource(context, Uri.parse(track.remotePath))
        } else {
            val nasCredentials = credentials ?: error("missing NAS credentials")
            retriever.setDataSource(
                nasFileClient.urlFor(nasCredentials, track.remotePath),
                mapOf(
                    "Authorization" to Credentials.basic(
                        nasCredentials.username,
                        nasCredentials.password,
                        Charsets.UTF_8,
                    ),
                ),
            )
        }
    }

    private fun TrackEntity.mergeMetadata(metadata: TrackMetadata): TrackEntity {
        val fallback = fileNameFallback(fileName)
        val withFallback = withFileNameFallback()
        val coverPath = metadata.embeddedCover?.let { saveCoverBytes(this, it) } ?: coverCachePath
        return copy(
            title = metadata.title ?: title.ifBlank { fallback.title },
            artist = metadata.artist ?: withFallback.artist,
            album = metadata.album ?: album,
            durationMs = metadata.durationMs ?: durationMs,
            coverCachePath = coverPath,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun TrackEntity.withFileNameFallback(): TrackEntity {
        val fallback = fileNameFallback(fileName)
        val currentArtist = artist?.trim()
        return copy(
            title = title.ifBlank { fallback.title },
            artist = when {
                !currentArtist.isNullOrBlank() && currentArtist != UNKNOWN_ARTIST -> currentArtist
                !fallback.artist.isNullOrBlank() -> fallback.artist
                else -> UNKNOWN_ARTIST
            },
        )
    }

    private fun TrackEntity.hasUsefulMetadata(): Boolean =
        title.isNotBlank() &&
            !artist.isNullOrBlank() &&
            artist != UNKNOWN_ARTIST &&
            durationMs != null &&
            durationMs > 0L

    private fun TrackEntity.hasUsableCover(): Boolean =
        (coverCachePath
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }) != null

    private fun TrackEntity.hasKnownFailedCover(): Boolean =
        synchronized(failedCoverKeys) { failedCoverKeys.containsKey(coverCacheKey(this)) }

    private fun String?.cleanMetadata(): String? =
        this?.trim()?.takeIf { it.isNotBlank() && it != "\u0000" }

    private fun fileNameFallback(fileName: String): FileNameFallback {
        val baseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName).trim()
            .ifBlank { fileName }
        val separators = listOf(" - ", " – ", " — ", "_-_")
        separators.forEach { separator ->
            val index = baseName.indexOf(separator)
            if (index > 0 && index < baseName.lastIndex) {
                val artist = baseName.substring(0, index).trim()
                val title = baseName.substring(index + separator.length).trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return FileNameFallback(title = title, artist = artist)
                }
            }
        }
        return FileNameFallback(title = baseName, artist = null)
    }

    private fun saveCoverBytes(track: TrackEntity, bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val thumbnail = bitmap.scaledToMaxEdge(COVER_MAX_EDGE_PX)
        val coverDir = File(context.cacheDir, "album-art").apply { mkdirs() }
        val coverKey = coverCacheKey(track)
        val target = File(coverDir, "$coverKey.jpg")
        return runCatching {
            target.outputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
            }
            coverDir.listFiles()
                ?.filter { it.name.startsWith("${track.id}-") && it.name != target.name }
                ?.forEach { it.delete() }
            trimCoverCache()
            target.absolutePath
        }.getOrNull()
    }

    private suspend fun shouldUseBackgroundCache(): Boolean {
        val settings = settingsStore.settings.first()
        if (settings.allowMobileCache) return true
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private suspend fun trimManualCache() {
        val limit = settingsStore.settings.first().cacheLimitBytes
        if (limit <= 0L) return
        var usedBytes = database.cacheDao().getAll().sumOf { item ->
            File(item.localPath).takeIf { it.exists() }?.length() ?: 0L
        }
        val items = database.cacheDao().getAll()
        for (item in items) {
            if (usedBytes <= limit) break
            val file = File(item.localPath)
            val size = file.takeIf { it.exists() }?.length() ?: item.size
            file.delete()
            database.cacheDao().deleteForTrack(item.trackId)
            database.trackDao().updateCachePath(item.trackId, null, System.currentTimeMillis())
            usedBytes -= size
        }
    }

    private fun trimCoverCache() {
        val coverDir = File(context.cacheDir, "album-art")
        val files = coverDir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (totalBytes <= COVER_CACHE_LIMIT_BYTES) return
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private fun coverCacheKey(track: TrackEntity): String =
        "${track.id}-${shortHash("${track.fileSize ?: 0L}:${track.modifiedAt.orEmpty()}")}"

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(12)
    }

    private fun Bitmap.scaledToMaxEdge(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge || edge <= 0) return this
        val scale = maxEdge.toFloat() / edge.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
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

    private data class LocalDirectory(
        val documentFile: DocumentFile,
        val displayPath: String,
    )

    private fun hasPersistedReadPermission(uri: Uri): Boolean {
        val uriText = uri.toString()
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.toString() == uriText
        }
    }

    private fun hasPersistedReadPermissionFor(uri: Uri): Boolean {
        val uriText = uri.toString()
        return context.contentResolver.persistedUriPermissions.any { permission ->
            if (!permission.isReadPermission) return@any false
            val treeUriText = permission.uri.toString()
            uriText == treeUriText || uriText.startsWith("$treeUriText/")
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val documentName = try {
            DocumentFile.fromTreeUri(context, uri)?.name
        } catch (error: UnsupportedOperationException) {
            Log.w(TAG, "query tree uri display name unsupported: $uri", error)
            null
        } catch (error: SecurityException) {
            Log.w(TAG, "query tree uri display name denied: $uri", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "query tree uri display name invalid: $uri", error)
            null
        } catch (error: Exception) {
            Log.w(TAG, "query tree uri display name failed: $uri", error)
            null
        }
        return documentName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: displayNameFromTreeUri(uri)
            ?: LOCAL_FOLDER_FALLBACK_NAME
    }

    private fun displayNameFromTreeUri(uri: Uri): String? {
        val segment = uri.lastPathSegment.orEmpty()
        val decoded = runCatching {
            java.net.URLDecoder.decode(segment, Charsets.UTF_8.name())
        }.getOrDefault(segment)
        return decoded
            .substringAfterLast(':')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun safeDocumentName(document: DocumentFile): String =
        try {
            document.name.orEmpty()
        } catch (error: Exception) {
            Log.w(TAG, "read local document name failed: ${document.uri}", error)
            ""
        }

    private fun safeCanRead(document: DocumentFile): Boolean =
        try {
            document.canRead()
        } catch (error: Exception) {
            Log.w(TAG, "read local document permission failed: ${document.uri}", error)
            false
        }

    private fun safeIsDirectory(document: DocumentFile): Boolean =
        try {
            document.isDirectory
        } catch (error: Exception) {
            Log.w(TAG, "read local document directory flag failed: ${document.uri}", error)
            false
        }

    private fun safeIsFile(document: DocumentFile): Boolean =
        try {
            document.isFile
        } catch (error: Exception) {
            Log.w(TAG, "read local document file flag failed: ${document.uri}", error)
            false
        }

    private fun safeLength(document: DocumentFile): Long? =
        try {
            document.length().takeIf { it >= 0L }
        } catch (error: Exception) {
            Log.w(TAG, "read local document length failed: ${document.uri}", error)
            null
        }

    private fun safeLastModified(document: DocumentFile): Long? =
        try {
            document.lastModified().takeIf { it > 0L }
        } catch (error: Exception) {
            Log.w(TAG, "read local document modified time failed: ${document.uri}", error)
            null
        }

    private fun nasSourceKey(serverId: Long, remotePath: String): String =
        "NAS:$serverId:${normalizeRemotePath(remotePath)}"

    private fun localSourceKey(uriText: String): String = "LOCAL:$uriText"

    private fun String.safeHostForLog(): String =
        runCatching { Uri.parse(this).host.orEmpty() }.getOrDefault("")

    private suspend fun localDocumentToTrack(
        sourceFolderId: Long,
        documentUri: Uri,
        displayName: String,
        size: Long?,
        modifiedAt: Long?,
        scanId: Long,
        existing: TrackEntity?,
    ): TrackEntity {
        val now = System.currentTimeMillis()
        val documentUriText = documentUri.toString()
        val fallback = fileNameFallback(displayName)
        val modifiedAtText = modifiedAt?.takeIf { it > 0L }?.toString()
        val isSameSource = existing != null &&
            existing.fileSize == size &&
            existing.modifiedAt == modifiedAtText
        val base = TrackEntity(
            id = existing?.id ?: stableTrackId(LOCAL_SOURCE_SERVER_ID, documentUriText),
            nasServerId = LOCAL_SOURCE_SERVER_ID,
            sourceType = MusicSourceType.LOCAL,
            sourceFolderId = sourceFolderId,
            remotePath = documentUriText,
            fileName = displayName,
            title = existing?.takeIf { isSameSource }?.title ?: fallback.title,
            artist = existing?.takeIf { isSameSource }?.artist ?: (fallback.artist ?: UNKNOWN_ARTIST),
            album = existing?.takeIf { isSameSource }?.album,
            durationMs = existing?.takeIf { isSameSource }?.durationMs,
            fileSize = size,
            modifiedAt = modifiedAtText,
            coverCachePath = existing
                ?.takeIf { isSameSource }
                ?.coverCachePath
                ?.takeIf { File(it).exists() },
            isFavorite = existing?.isFavorite ?: false,
            localCachePath = existing
                ?.takeIf { isSameSource }
                ?.localCachePath
                ?.takeIf { File(it).exists() },
            lastSeenScanId = scanId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (base.hasUsefulMetadata()) return base
        val metadata = withTimeoutOrNull(METADATA_READ_TIMEOUT_MS) {
            runCatching { readMetadata(credentials = null, track = base) }.getOrNull()
        }
        return metadata?.let { base.mergeMetadata(it) } ?: base.withFileNameFallback()
    }

    private suspend fun RemoteItem.toTrack(
        credentials: NasCredentials,
        sourceFolderId: Long,
        scanId: Long,
        existing: TrackEntity?,
    ): TrackEntity {
        val now = System.currentTimeMillis()
        val fallback = fileNameFallback(displayName)
        val isSameSource = existing != null &&
            existing.fileSize == size &&
            existing.modifiedAt == modifiedAt
        val base = TrackEntity(
            id = existing?.id ?: stableTrackId(credentials.serverId, remotePath),
            nasServerId = credentials.serverId,
            sourceType = MusicSourceType.NAS,
            sourceFolderId = sourceFolderId,
            remotePath = remotePath,
            fileName = displayName,
            title = existing?.takeIf { isSameSource }?.title ?: fallback.title,
            artist = existing?.takeIf { isSameSource }?.artist ?: (fallback.artist ?: UNKNOWN_ARTIST),
            album = existing?.takeIf { isSameSource }?.album,
            durationMs = existing?.takeIf { isSameSource }?.durationMs,
            fileSize = size,
            modifiedAt = modifiedAt,
            coverCachePath = existing
                ?.takeIf { isSameSource }
                ?.coverCachePath
                ?.takeIf { File(it).exists() },
            isFavorite = existing?.isFavorite ?: false,
            localCachePath = existing
                ?.takeIf { isSameSource }
                ?.localCachePath
                ?.takeIf { File(it).exists() },
            lastSeenScanId = scanId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (base.hasUsefulMetadata()) return base
        val metadata = withTimeoutOrNull(METADATA_READ_TIMEOUT_MS) {
            runCatching { readMetadata(credentials, base) }.getOrNull()
        }
        return metadata?.let { base.mergeMetadata(it) } ?: base.withFileNameFallback()
    }

    private fun stableTrackId(nasServerId: Long, remotePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$nasServerId:$remotePath".toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(32)
    }

    private companion object {
        const val ROOT_DISPLAY_PATH = "NAS 根目录"
        const val MAX_LYRICS_CACHE_SIZE = 48
        const val MAX_FAILED_COVER_KEYS = 160
        const val COVER_MAX_EDGE_PX = 512
        const val COVER_JPEG_QUALITY = 86
        const val COVER_CACHE_LIMIT_BYTES = 64L * 1024L * 1024L
        const val METADATA_READ_TIMEOUT_MS = 2_500L
        const val LOCAL_SOURCE_SERVER_ID = -1L
        const val LOCAL_FOLDER_FALLBACK_NAME = "本地音乐文件夹"
        const val UNKNOWN_ARTIST = "未知歌手"
        const val TAG = "MusicRepository"
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
