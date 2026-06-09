package tech.peakedge.naswalkman.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import tech.peakedge.naswalkman.NasMusicApplication
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import tech.peakedge.naswalkman.data.db.NasServerEntity
import tech.peakedge.naswalkman.data.db.MusicFolderEntity
import tech.peakedge.naswalkman.data.db.PlaylistSummary
import tech.peakedge.naswalkman.data.db.TrackEntity
import tech.peakedge.naswalkman.data.db.TrackWithPlayCount
import tech.peakedge.naswalkman.data.repository.AppSettings
import tech.peakedge.naswalkman.data.repository.DirectoryCrumb
import tech.peakedge.naswalkman.data.repository.DirectoryBrowserResult
import tech.peakedge.naswalkman.data.repository.LyricsContent
import tech.peakedge.naswalkman.data.repository.LyricsLoadResult
import tech.peakedge.naswalkman.data.repository.NasForm
import tech.peakedge.naswalkman.data.repository.MusicRepository
import tech.peakedge.naswalkman.data.repository.ScanProgress
import tech.peakedge.naswalkman.network.RemoteItem
import tech.peakedge.naswalkman.network.WebDavResult
import tech.peakedge.naswalkman.playback.MusicPlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class MainTab { Library, Folders, Player, Settings }

enum class PlaybackUiStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Error,
}

enum class DirectoryPickerMode {
    ConnectionRoot,
    AddMusicSource,
}

sealed class LibraryPage {
    data object Home : LibraryPage()
    data object AllSongs : LibraryPage()
    data object Favorites : LibraryPage()
    data object MostPlayed : LibraryPage()
    data object Artists : LibraryPage()
    data object Singles : LibraryPage()
    data class ArtistSongs(val artistName: String) : LibraryPage()
    data class PlaylistDetail(val id: Long, val name: String) : LibraryPage()
}

data class SongInfoFetchProgress(
    val isRunning: Boolean = false,
    val totalCount: Int = 0,
    val currentIndex: Int = 0,
    val currentTrackName: String = "",
    val skippedCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val statusText: String = "",
    val isComplete: Boolean = false,
) {
    val progress: Float
        get() = if (totalCount <= 0) 0f else currentIndex.toFloat() / totalCount.toFloat()
}

data class DirectoryPickerState(
    val isOpen: Boolean = false,
    val mode: DirectoryPickerMode = DirectoryPickerMode.ConnectionRoot,
    val includeSubfolders: Boolean = true,
    val current: DirectoryCrumb = DirectoryCrumb(
        name = "NAS 根目录",
        remotePath = "/",
        displayPath = "NAS 根目录",
    ),
    val breadcrumbs: List<DirectoryCrumb> = listOf(
        DirectoryCrumb(
            name = "NAS 根目录",
            remotePath = "/",
            displayPath = "NAS 根目录",
        ),
    ),
    val directories: List<tech.peakedge.naswalkman.network.NasDirectory> = emptyList(),
    val isLoading: Boolean = false,
)

sealed class LyricsUiState(open val trackId: String?) {
    data object Idle : LyricsUiState(null)
    data class Loading(override val trackId: String) : LyricsUiState(trackId)
    data class Ready(override val trackId: String, val lyrics: LyricsContent) : LyricsUiState(trackId)
    data class Empty(override val trackId: String, val message: String) : LyricsUiState(trackId)
    data class Error(override val trackId: String, val message: String) : LyricsUiState(trackId)
}

data class AppUiState(
    val nasServer: NasServerEntity? = null,
    val connectionForm: NasForm = NasForm(),
    val isConnectionTested: Boolean = false,
    val directoryPicker: DirectoryPickerState = DirectoryPickerState(),
    val musicFolders: List<MusicFolderEntity> = emptyList(),
    val showMusicFolderManager: Boolean = false,
    val showScanPrompt: Boolean = false,
    val tracks: List<TrackEntity> = emptyList(),
    val favorites: List<TrackEntity> = emptyList(),
    val recent: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackWithPlayCount> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val selectedPlaylistTracks: List<TrackEntity> = emptyList(),
    val libraryPage: LibraryPage = LibraryPage.Home,
    val folderPath: String = "/",
    val folderItems: List<RemoteItem> = emptyList(),
    val selectedTab: MainTab = MainTab.Library,
    val searchQuery: String = "",
    val isBusy: Boolean = false,
    val scanProgress: ScanProgress = ScanProgress(),
    val message: String? = null,
    val currentTrackId: String? = null,
    val preparingTrackId: String? = null,
    val playbackStatus: PlaybackUiStatus = PlaybackUiStatus.Idle,
    val isPlaying: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val showLyrics: Boolean = false,
    val lyricsState: LyricsUiState = LyricsUiState.Idle,
    val cacheBytes: Long = 0L,
    val settings: AppSettings = AppSettings(),
    val songInfoFetchProgress: SongInfoFetchProgress = SongInfoFetchProgress(),
) {
    val searchResults: List<TrackEntity>
        get() {
            val query = searchQuery.trim()
            if (query.isBlank()) return emptyList()
            return tracks.filter { track ->
                listOf(track.title, track.artist, track.album, track.fileName, track.remotePath)
                    .filterNotNull()
                    .any { it.contains(query, ignoreCase = true) }
            }
        }

    val currentTrack: TrackEntity?
        get() = tracks.firstOrNull { it.id == currentTrackId }
            ?: favorites.firstOrNull { it.id == currentTrackId }
            ?: recent.firstOrNull { it.id == currentTrackId }

    val preparingTrack: TrackEntity?
        get() = tracks.firstOrNull { it.id == preparingTrackId }
            ?: favorites.firstOrNull { it.id == preparingTrackId }
            ?: recent.firstOrNull { it.id == preparingTrackId }
            ?: selectedPlaylistTracks.firstOrNull { it.id == preparingTrackId }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: MusicRepository =
        (application as NasMusicApplication).container.repository
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playbackTicker: Job? = null
    private var lyricLoadJob: Job? = null
    private var playTrackJob: Job? = null
    private var playlistTracksJob: Job? = null
    private var playbackTimeoutJob: Job? = null
    private var songInfoFetchJob: Job? = null
    private var playRequestId: Long = 0L
    private val coverLoadJobs = mutableMapOf<String, Job>()
    private val metadataLoadJobs = mutableMapOf<String, Job>()
    private val metadataRequestedIds = mutableSetOf<String>()
    private val playbackCommandMutex = Mutex()
    private var lastPlaybackCommandAtMs = 0L
    private var lastCountedPlayingTrackId: String? = null

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@AppViewModel.controller === controller) {
                this@AppViewModel.controller = null
            }
            playbackTicker?.cancel()
            _uiState.update {
                it.copy(isPlaying = false, message = "播放器连接已断开")
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == MusicPlaybackService.ACTION_PLAYBACK_RECOVERY_FAILED) {
                _uiState.update {
                    it.copy(message = "播放失败，请检查文件格式或网络连接")
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    init {
        bindRepositoryFlows()
        observeLyricRequests()
        observeCoverRequests()
        connectPlaybackController(application)
    }

    fun defaultForm(): NasForm {
        val server = _uiState.value.nasServer
        return if (server == null) {
            _uiState.value.connectionForm
        } else {
            server.toForm()
        }
    }

    fun updateConnectionForm(form: NasForm) {
        _uiState.update { state ->
            state.copy(
                connectionForm = form,
                isConnectionTested = false,
            )
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == MainTab.Folders && _uiState.value.folderItems.isEmpty()) {
            openFolder(_uiState.value.folderPath)
        }
    }

    fun openLibraryHome() {
        playlistTracksJob?.cancel()
        _uiState.update {
            it.copy(
                selectedTab = MainTab.Library,
                libraryPage = LibraryPage.Home,
                selectedPlaylistTracks = emptyList(),
            )
        }
    }

    fun openAllSongs() {
        openLibraryPage(LibraryPage.AllSongs)
    }

    fun openFavorites() {
        openLibraryPage(LibraryPage.Favorites)
    }

    fun openMostPlayed() {
        openLibraryPage(LibraryPage.MostPlayed)
    }

    fun openArtists() {
        openLibraryPage(LibraryPage.Artists)
    }

    fun openSingles() {
        openLibraryPage(LibraryPage.Singles)
    }

    fun openArtistSongs(artistName: String) {
        openLibraryPage(LibraryPage.ArtistSongs(artistName))
    }

    fun openPlaylist(playlist: PlaylistSummary) {
        playlistTracksJob?.cancel()
        _uiState.update {
            it.copy(
                selectedTab = MainTab.Library,
                libraryPage = LibraryPage.PlaylistDetail(playlist.id, playlist.name),
                selectedPlaylistTracks = emptyList(),
            )
        }
        playlistTracksJob = viewModelScope.launch {
            repository.observePlaylistTracks(playlist.id).collect { tracks ->
                _uiState.update { it.copy(selectedPlaylistTracks = tracks) }
                tracks.take(COVER_WARMUP_LIMIT).forEach { requestCover(it) }
            }
        }
    }

    private fun openLibraryPage(page: LibraryPage) {
        playlistTracksJob?.cancel()
        _uiState.update {
            it.copy(
                selectedTab = MainTab.Library,
                libraryPage = page,
                selectedPlaylistTracks = emptyList(),
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun testConnection(form: NasForm = _uiState.value.connectionForm) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, connectionForm = form) }
            val result = repository.testConnection(form)
            _uiState.update { state ->
                state.copy(
                    isBusy = false,
                    isConnectionTested = result is WebDavResult.Success,
                    message = result.messageOrSuccess("连接成功"),
                )
            }
        }
    }

    fun testCurrentConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.testCurrentConnection()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("连接成功"),
                )
            }
        }
    }

    fun saveNas(form: NasForm = _uiState.value.connectionForm) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, connectionForm = form) }
            val result = repository.saveNas(form)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    selectedTab = if (result is WebDavResult.Success) MainTab.Settings else it.selectedTab,
                    showScanPrompt = false,
                    message = result.messageOrSuccess("连接成功，已保存 NAS 配置"),
                )
            }
            if (result is WebDavResult.Success) {
                _uiState.update {
                    it.copy(folderPath = normalizePath(form.selectedMusicRemotePath.ifBlank { form.musicRootPath }))
                }
            }
        }
    }

    fun dismissScanPrompt() {
        _uiState.update { it.copy(showScanPrompt = false) }
    }

    fun confirmScanPrompt() {
        _uiState.update { it.copy(showScanPrompt = false) }
        scanLibrary()
    }

    fun deleteBinding() {
        viewModelScope.launch {
            repository.deleteBinding()
            _uiState.update {
                it.copy(
                    nasServer = null,
                    connectionForm = NasForm(),
                    isConnectionTested = false,
                    directoryPicker = DirectoryPickerState(),
                    folderItems = emptyList(),
                    selectedTab = MainTab.Settings,
                    message = "已删除 NAS 绑定，本地音乐文件夹不受影响",
                )
            }
        }
    }

    fun openDirectoryPicker() {
        val form = _uiState.value.connectionForm
        if (!_uiState.value.isConnectionTested && _uiState.value.nasServer == null) {
            _uiState.update { it.copy(message = "请先测试连接") }
            return
        }
        val root = DirectoryCrumb("NAS 根目录", "/", "NAS 根目录")
        loadDirectoryPicker(
            form = form,
            current = root,
            breadcrumbs = listOf(root),
            open = true,
            mode = DirectoryPickerMode.ConnectionRoot,
            includeSubfolders = true,
        )
    }

    fun openNasFolderPickerForSource(includeSubfolders: Boolean = true) {
        val server = _uiState.value.nasServer
        if (server == null) {
            _uiState.update { it.copy(message = "请先登录 NAS 后再添加 NAS 文件夹") }
            return
        }
        val root = DirectoryCrumb("NAS 根目录", "/", "NAS 根目录")
        loadDirectoryPicker(
            form = server.toForm(),
            current = root,
            breadcrumbs = listOf(root),
            open = true,
            mode = DirectoryPickerMode.AddMusicSource,
            includeSubfolders = includeSubfolders,
        )
    }

    fun closeDirectoryPicker() {
        _uiState.update { it.copy(directoryPicker = DirectoryPickerState()) }
    }

    fun refreshDirectoryPicker() {
        val picker = _uiState.value.directoryPicker
        loadDirectoryPicker(
            form = _uiState.value.connectionForm,
            current = picker.current,
            breadcrumbs = picker.breadcrumbs,
            open = true,
            mode = picker.mode,
            includeSubfolders = picker.includeSubfolders,
        )
    }

    fun enterPickerDirectory(directory: tech.peakedge.naswalkman.network.NasDirectory) {
        val crumb = DirectoryCrumb(
            name = directory.name,
            remotePath = directory.remotePath,
            displayPath = directory.displayPath,
        )
        val nextBreadcrumbs = _uiState.value.directoryPicker.breadcrumbs + crumb
        val picker = _uiState.value.directoryPicker
        loadDirectoryPicker(
            form = _uiState.value.connectionForm,
            current = crumb,
            breadcrumbs = nextBreadcrumbs,
            open = true,
            mode = picker.mode,
            includeSubfolders = picker.includeSubfolders,
        )
    }

    fun pickerGoUp() {
        val picker = _uiState.value.directoryPicker
        if (picker.breadcrumbs.size <= 1) return
        val nextBreadcrumbs = picker.breadcrumbs.dropLast(1)
        val parent = nextBreadcrumbs.last()
        val pickerState = _uiState.value.directoryPicker
        loadDirectoryPicker(
            form = _uiState.value.connectionForm,
            current = parent,
            breadcrumbs = nextBreadcrumbs,
            open = true,
            mode = pickerState.mode,
            includeSubfolders = pickerState.includeSubfolders,
        )
    }

    fun chooseCurrentPickerDirectory() {
        val picker = _uiState.value.directoryPicker
        val current = picker.current
        if (picker.mode == DirectoryPickerMode.AddMusicSource) {
            viewModelScope.launch {
                _uiState.update { it.copy(isBusy = true) }
                val result = repository.addNasMusicFolder(
                    remotePath = current.remotePath,
                    displayName = current.displayPath,
                    includeSubfolders = picker.includeSubfolders,
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        directoryPicker = DirectoryPickerState(),
                        message = result.messageOrSuccess("已添加 NAS 音乐文件夹"),
                    )
                }
                if (result is WebDavResult.Success) {
                    scanLibrary()
                }
            }
            return
        }
        val updatedForm = _uiState.value.connectionForm.copy(
            musicRootPath = current.remotePath,
            selectedMusicRemotePath = current.remotePath,
            selectedMusicDisplayPath = current.displayPath,
            selectedMusicFolderName = current.name,
        )
        _uiState.update {
            it.copy(
                connectionForm = updatedForm,
                directoryPicker = DirectoryPickerState(),
                message = "已选择音乐目录：${current.displayPath}",
            )
        }
    }

    fun setDirectoryPickerIncludeSubfolders(includeSubfolders: Boolean) {
        _uiState.update {
            it.copy(directoryPicker = it.directoryPicker.copy(includeSubfolders = includeSubfolders))
        }
    }

    fun setManualMusicPath(path: String) {
        val normalized = "/" + path.trim().trim('/').replace('\\', '/')
        val updated = _uiState.value.connectionForm.copy(
            musicRootPath = normalized,
            selectedMusicRemotePath = normalized,
            selectedMusicDisplayPath = "已手动填写路径：$normalized",
            selectedMusicFolderName = normalized.substringAfterLast('/').ifBlank { normalized },
        )
        _uiState.update { it.copy(connectionForm = updated, message = "已填写音乐目录路径") }
    }

    fun testManualMusicPath(path: String) {
        viewModelScope.launch {
            val normalized = "/" + path.trim().trim('/').replace('\\', '/')
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.testDirectoryForForm(_uiState.value.connectionForm, normalized)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("路径可以访问"),
                )
            }
        }
    }

    fun openMusicFolderManager() {
        _uiState.update {
            it.copy(selectedTab = MainTab.Settings, showMusicFolderManager = true)
        }
    }

    fun closeMusicFolderManager() {
        _uiState.update { it.copy(showMusicFolderManager = false) }
    }

    fun addLocalMusicFolder(uri: Uri, grantFlags: Int, includeSubfolders: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.addLocalMusicFolder(uri, grantFlags, includeSubfolders)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("已添加本地音乐文件夹"),
                )
            }
            if (result is WebDavResult.Success) {
                scanLibrary()
            }
        }
    }

    fun rescanMusicFolder(folder: MusicFolderEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.scanMusicFolder(folder.id) { progress ->
                _uiState.update { it.copy(scanProgress = progress) }
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("已重新扫描音乐文件夹"),
                )
            }
        }
    }

    fun setMusicFolderIncludeSubfolders(folder: MusicFolderEntity, includeSubfolders: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.updateMusicFolderIncludeSubfolders(folder.id, includeSubfolders)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("已更新音乐文件夹设置"),
                )
            }
            if (result is WebDavResult.Success) {
                rescanMusicFolder(folder.copy(includeSubfolders = includeSubfolders))
            }
        }
    }

    fun deleteMusicFolder(folder: MusicFolderEntity) {
        viewModelScope.launch {
            repository.deleteMusicFolder(folder.id)
            _uiState.update { it.copy(message = "已删除音乐文件夹，真实文件不会被删除") }
        }
    }

    fun openFolder(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, folderPath = normalizePath(path)) }
            runCatching { repository.listDirectory(path) }
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(isBusy = false, folderItems = items, selectedTab = MainTab.Folders)
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            folderItems = emptyList(),
                            message = "目录读取失败，请检查远程访问设置",
                        )
                    }
                }
        }
    }

    fun openFolderBrowser() {
        val state = _uiState.value
        val server = state.nasServer
        if (server == null) {
            _uiState.update { it.copy(message = "请先连接你的 NAS") }
            return
        }
        val path = state.folderPath.ifBlank {
            server.selectedMusicRemotePath.ifBlank { server.musicRootPath }
        }
        openFolder(path)
    }

    fun goUpFolder() {
        val current = _uiState.value.folderPath.trim('/').replace('\\', '/')
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        openFolder(if (parent.isBlank()) "/" else "/$parent")
    }

    fun scanLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.scanLibrary { progress ->
                _uiState.update { it.copy(scanProgress = progress) }
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.messageOrSuccess("扫描完成，已更新音乐库"),
                )
            }
        }
    }

    fun playTrack(track: TrackEntity, queue: List<TrackEntity> = _uiState.value.tracks) {
        val state = _uiState.value
        if (track.id == state.currentTrackId && state.preparingTrackId == null && playTrackJob?.isActive == true) return

        val shouldOpenPlayer = state.currentTrackId == null &&
            state.preparingTrackId == null &&
            state.playbackStatus == PlaybackUiStatus.Idle
        val requestId = ++playRequestId
        cancelPendingPlaybackWork()
        lastCountedPlayingTrackId = null
        requestCover(track, forceCheck = true)
        cancelMetadataRequests()

        _uiState.update {
            it.copy(
                selectedTab = if (shouldOpenPlayer) MainTab.Player else it.selectedTab,
                currentTrackId = track.id,
                preparingTrackId = track.id,
                playbackStatus = PlaybackUiStatus.Loading,
                playbackPositionMs = 0L,
                playbackDurationMs = 0L,
                isPlaying = false,
            )
        }

        playTrackJob = viewModelScope.launch {
            runCatching {
                val player = controller ?: error("播放器尚未准备好")
                val sourceQueue = queue.takeIf { items -> items.any { it.id == track.id } } ?: listOf(track)
                val mediaItems = buildMediaItems(sourceQueue, requestId)
                if (requestId != playRequestId) return@launch

                val startIndex = mediaItems.indexOfFirst { it.first.id == track.id }.coerceAtLeast(0)
                val items = mediaItems.map { it.second }
                if (items.isEmpty()) error("无法播放，文件不可访问或 NAS 未登录")

                player.setMediaItems(items, startIndex, 0L)
                player.prepare()
                player.play()
                startPlaybackTimeout(requestId, track.id)
                if (requestId != playRequestId) return@launch
                _uiState.update {
                    if (requestId == playRequestId) {
                        it.copy(
                            selectedTab = if (shouldOpenPlayer) MainTab.Player else it.selectedTab,
                            currentTrackId = track.id,
                            playbackStatus = PlaybackUiStatus.Loading,
                        )
                    } else {
                        it
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException && requestId != playRequestId) throw error
                if (requestId == playRequestId) {
                    controller?.let(::resetPlayerAfterFailure)
                    _uiState.update {
                        it.copy(
                            preparingTrackId = null,
                            playbackStatus = PlaybackUiStatus.Error,
                            isPlaying = false,
                            message = error.message ?: "播放失败，请检查文件格式或网络连接",
                        )
                    }
                }
            }
        }
    }

    private fun cancelPendingPlaybackWork() {
        playTrackJob?.cancel()
        playbackTimeoutJob?.cancel()
    }

    private suspend fun buildMediaItems(
        tracks: List<TrackEntity>,
        requestId: Long,
    ): List<Pair<TrackEntity, MediaItem>> = withContext(Dispatchers.IO) {
        val items = mutableListOf<Pair<TrackEntity, MediaItem>>()
        for (item in tracks) {
            if (!isActive || requestId != playRequestId) break
            val uri = repository.mediaUriFor(item) ?: continue
            items += item to MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist.orEmpty())
                        .setAlbumTitle(item.album.orEmpty())
                        .build(),
                )
                .build()
        }
        items
    }

    private fun startPlaybackTimeout(requestId: Long, trackId: String) {
        playbackTimeoutJob?.cancel()
        playbackTimeoutJob = viewModelScope.launch {
            delay(PLAYBACK_READY_TIMEOUT_MS)
            val state = _uiState.value
            if (requestId != playRequestId || state.preparingTrackId != trackId) return@launch
            controller?.let(::resetPlayerAfterFailure)
            _uiState.update {
                if (requestId == playRequestId && it.preparingTrackId == trackId) {
                    it.copy(
                        preparingTrackId = null,
                        playbackStatus = PlaybackUiStatus.Error,
                        isPlaying = false,
                        message = "播放加载超时，请检查网络连接后重试",
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun resetPlayerAfterFailure(player: MediaController) {
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun playFolderItem(item: RemoteItem) {
        if (item.isDirectory) {
            openFolder(item.remotePath)
            return
        }
        viewModelScope.launch {
            val knownTrack = _uiState.value.tracks.firstOrNull { it.remotePath == item.remotePath }
            if (knownTrack != null) {
                playTrack(knownTrack)
            } else {
                scanLibrary()
                _uiState.update { it.copy(message = "已开始扫描，扫描完成后可从全部歌曲播放") }
            }
        }
    }

    fun togglePlayback() {
        runPlaybackCommand {
            val player = controller ?: return@runPlaybackCommand
            if (player.isPlaying) player.pause() else player.play()
            refreshPlaybackState()
        }
    }

    fun next() {
        runPlaybackCommand {
            lastCountedPlayingTrackId = null
            playbackTimeoutJob?.cancel()
            _uiState.update { it.copy(preparingTrackId = null, playbackStatus = PlaybackUiStatus.Loading) }
            controller?.seekToNextMediaItem()
            refreshPlaybackState()
        }
    }

    fun previous() {
        runPlaybackCommand {
            lastCountedPlayingTrackId = null
            playbackTimeoutJob?.cancel()
            _uiState.update { it.copy(preparingTrackId = null, playbackStatus = PlaybackUiStatus.Loading) }
            controller?.seekToPreviousMediaItem()
            refreshPlaybackState()
        }
    }

    fun toggleShuffle() {
        runPlaybackCommand {
            val player = controller ?: return@runPlaybackCommand
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            refreshPlaybackState()
        }
    }

    fun cycleRepeatMode() {
        runPlaybackCommand {
            val player = controller ?: return@runPlaybackCommand
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            refreshPlaybackState()
        }
    }

    fun seekTo(positionMs: Long) {
        runPlaybackCommand(debounce = false) {
            controller?.seekTo(positionMs)
            refreshPlaybackState()
        }
    }

    fun toggleLyricsMode() {
        _uiState.update { it.copy(showLyrics = !it.showLyrics) }
    }

    fun retryLyrics() {
        loadLyricsForTrack(_uiState.value.currentTrackId, forceRefresh = true)
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(track.id)
        }
    }

    fun cacheTrack(track: TrackEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.cacheTrack(track)
            _uiState.update {
                it.copy(isBusy = false, message = result.messageOrSuccess("已缓存到本地"))
            }
        }
    }

    fun prepareVisibleTrack(track: TrackEntity) {
        requestCover(track)
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            _uiState.update { it.copy(message = "已清理缓存") }
        }
    }

    fun fetchSongInfo(forceRefresh: Boolean = false) {
        if (songInfoFetchJob?.isActive == true) {
            _uiState.update { it.copy(message = "正在获取歌曲信息") }
            return
        }
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) {
            _uiState.update {
                it.copy(
                    songInfoFetchProgress = SongInfoFetchProgress(
                        totalCount = 0,
                        statusText = "当前没有可获取信息的歌曲",
                        isComplete = true,
                    ),
                    message = "当前没有可获取信息的歌曲",
                )
            }
            return
        }

        cancelMetadataRequests()
        songInfoFetchJob = viewModelScope.launch {
            var skippedCount = 0
            var successCount = 0
            var failureCount = 0
            _uiState.update {
                it.copy(
                    songInfoFetchProgress = SongInfoFetchProgress(
                        isRunning = true,
                        totalCount = tracks.size,
                        statusText = "正在读取：封面 / 歌手 / 时长 / 歌词 / 专辑",
                    ),
                )
            }
            tracks.forEachIndexed { index, track ->
                if (!isActive) return@forEachIndexed
                _uiState.update {
                    it.copy(
                        songInfoFetchProgress = it.songInfoFetchProgress.copy(
                            isRunning = true,
                            currentIndex = index + 1,
                            currentTrackName = track.title.ifBlank { track.fileName },
                            skippedCount = skippedCount,
                            successCount = successCount,
                            failureCount = failureCount,
                            statusText = "正在读取：封面 / 歌手 / 时长 / 歌词 / 专辑",
                        ),
                    )
                }
                val shouldSkip = !forceRefresh && repository.hasCachedSongInfo(track)
                if (shouldSkip) {
                    skippedCount += 1
                    _uiState.update {
                        it.copy(
                            songInfoFetchProgress = it.songInfoFetchProgress.copy(
                                currentIndex = index + 1,
                                skippedCount = skippedCount,
                                successCount = successCount,
                                failureCount = failureCount,
                                statusText = "已跳过缓存完整的歌曲",
                            ),
                        )
                    }
                } else {
                    val success = withTimeoutOrNull(TRACK_INFO_FETCH_TIMEOUT_MS) {
                        runCatching {
                            val updatedTrack = repository.ensureMetadata(track, force = forceRefresh) ?: track
                            val latestTrack = repository.currentTrack(updatedTrack.id) ?: updatedTrack
                            if (forceRefresh || !repository.hasCachedSongInfo(latestTrack)) {
                                repository.ensureCover(latestTrack)
                            }
                            repository.loadLyrics(latestTrack, forceRefresh = forceRefresh)
                            true
                        }.getOrDefault(false)
                    } ?: false
                    if (success) {
                        successCount += 1
                    } else {
                        failureCount += 1
                    }
                    _uiState.update {
                        it.copy(
                            songInfoFetchProgress = it.songInfoFetchProgress.copy(
                                currentIndex = index + 1,
                                skippedCount = skippedCount,
                                successCount = successCount,
                                failureCount = failureCount,
                            ),
                        )
                    }
                }
            }
            val completedText = when {
                !isActive -> "已取消获取歌曲信息"
                failureCount > 0 -> "获取完成，部分歌曲信息读取失败"
                else -> "获取完成"
            }
            _uiState.update {
                it.copy(
                    songInfoFetchProgress = it.songInfoFetchProgress.copy(
                        isRunning = false,
                        isComplete = true,
                        skippedCount = skippedCount,
                        successCount = successCount,
                        failureCount = failureCount,
                        statusText = "$completedText，跳过 $skippedCount 首，更新 $successCount 首，失败 $failureCount 首",
                    ),
                    message = completedText,
                )
            }
        }
    }

    fun cancelSongInfoFetch() {
        if (songInfoFetchJob?.isActive != true) return
        songInfoFetchJob?.cancel()
        _uiState.update {
            it.copy(
                songInfoFetchProgress = it.songInfoFetchProgress.copy(
                    isRunning = false,
                    isComplete = true,
                    statusText = "已取消获取歌曲信息",
                ),
                message = "已取消获取歌曲信息",
            )
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun addToPlaylist(playlistId: Long, track: TrackEntity) {
        viewModelScope.launch {
            repository.addToPlaylist(playlistId, track.id)
            _uiState.update { it.copy(message = "已添加到歌单") }
        }
    }

    fun addToPlaylists(playlistIds: Set<Long>, track: TrackEntity) {
        if (playlistIds.isEmpty()) return
        viewModelScope.launch {
            playlistIds.forEach { playlistId ->
                repository.addToPlaylist(playlistId, track.id)
            }
            _uiState.update { it.copy(message = "已添加到歌单") }
        }
    }

    fun removeFromPlaylist(playlistId: Long, track: TrackEntity) {
        viewModelScope.launch {
            repository.removeFromPlaylist(playlistId, track.id)
            _uiState.update { it.copy(message = "已从歌单移除") }
        }
    }

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch { repository.settingsStore.setCacheLimit(bytes) }
    }

    fun setAllowMobileCache(value: Boolean) {
        viewModelScope.launch { repository.settingsStore.setAllowMobileCache(value) }
    }

    fun setAutoConnect(value: Boolean) {
        viewModelScope.launch { repository.settingsStore.setAutoConnect(value) }
    }

    fun setAutoScan(value: Boolean) {
        viewModelScope.launch { repository.settingsStore.setAutoScan(value) }
    }

    fun setThemeMode(value: String) {
        viewModelScope.launch { repository.settingsStore.setThemeMode(value) }
    }

    fun setLanguage(value: String) {
        viewModelScope.launch { repository.settingsStore.setLanguage(value) }
    }

    private fun bindRepositoryFlows() {
        viewModelScope.launch {
            repository.nasServer.collect { server ->
                _uiState.update {
                    it.copy(
                        nasServer = server,
                        connectionForm = server?.toForm() ?: it.connectionForm,
                        folderPath = server?.selectedMusicRemotePath?.ifBlank { server.musicRootPath } ?: it.folderPath,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.musicFolders.collect { folders ->
                _uiState.update { it.copy(musicFolders = folders) }
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { tracks ->
                _uiState.update { it.copy(tracks = tracks) }
                tracks.take(COVER_WARMUP_LIMIT).forEach { requestCover(it) }
                tracks.firstOrNull { it.id == _uiState.value.currentTrackId }
                    ?.let { requestCover(it, forceCheck = true) }
            }
        }
        viewModelScope.launch {
            repository.favorites.collect { favorites -> _uiState.update { it.copy(favorites = favorites) } }
        }
        viewModelScope.launch {
            repository.recent.collect { recent -> _uiState.update { it.copy(recent = recent) } }
        }
        viewModelScope.launch {
            repository.mostPlayed.collect { mostPlayed -> _uiState.update { it.copy(mostPlayed = mostPlayed) } }
        }
        viewModelScope.launch {
            repository.playlists.collect { playlists -> _uiState.update { it.copy(playlists = playlists) } }
        }
        viewModelScope.launch {
            repository.cacheBytes.collect { bytes -> _uiState.update { it.copy(cacheBytes = bytes) } }
        }
        viewModelScope.launch {
            repository.settingsStore.settings.collect { settings -> _uiState.update { it.copy(settings = settings) } }
        }
    }

    private fun observeLyricRequests() {
        viewModelScope.launch {
            uiState
                .map { it.currentTrackId }
                .distinctUntilChanged()
                .collect { trackId -> loadLyricsForTrack(trackId, forceRefresh = false) }
        }
    }

    private fun observeCoverRequests() {
        viewModelScope.launch {
            uiState
                .map { it.currentTrackId }
                .distinctUntilChanged()
                .collect { trackId ->
                    _uiState.value.currentTrack
                        ?.takeIf { it.id == trackId }
                        ?.let { requestCover(it, forceCheck = true) }
                }
        }
    }

    private fun connectPlaybackController(application: Application) {
        val token = SessionToken(
            application,
            ComponentName(application, MusicPlaybackService::class.java),
        )
        val future = runCatching {
            MediaController.Builder(application, token)
                .setListener(controllerListener)
                .buildAsync()
        }.onFailure { error ->
            Log.e(TAG, "Failed to start MediaController connection", error)
            controller = null
            playbackTicker?.cancel()
            _uiState.update { it.copy(message = "播放器暂时不可用") }
        }.getOrNull() ?: return

        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    future.get()
                }.onSuccess { connectedController ->
                    controller = connectedController
                    connectedController.addListener(playerListener)
                    refreshPlaybackState()
                    startPlaybackTicker()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to connect MediaController", error)
                    controller = null
                    playbackTicker?.cancel()
                    _uiState.update { it.copy(message = "播放器暂时不可用") }
                }
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            markPlaybackStartedIfNeeded()
            refreshPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                lastCountedPlayingTrackId = null
            }
            _uiState.update {
                val isPreparingTransition = it.preparingTrackId != null && it.preparingTrackId == mediaId
                it.copy(
                    currentTrackId = if (isPreparingTransition) it.currentTrackId else mediaId,
                    preparingTrackId = if (!isPreparingTransition && it.preparingTrackId == mediaId) null else it.preparingTrackId,
                )
            }
            (_uiState.value.currentTrack ?: _uiState.value.preparingTrack)
                ?.takeIf { it.id == mediaId }
                ?.let { requestCover(it, forceCheck = true) }
            refreshPlaybackState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                playbackTimeoutJob?.cancel()
            }
            refreshPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackTimeoutJob?.cancel()
            val failedTrackId = controller?.currentMediaItem?.mediaId ?: _uiState.value.preparingTrackId
            controller?.let(::resetPlayerAfterFailure)
            _uiState.update {
                it.copy(
                    preparingTrackId = null,
                    currentTrackId = if (it.currentTrackId == failedTrackId) null else it.currentTrackId,
                    playbackStatus = PlaybackUiStatus.Error,
                    isPlaying = false,
                    message = "播放失败，请检查文件格式或网络连接",
                )
            }
        }
    }

    private fun startPlaybackTicker() {
        playbackTicker?.cancel()
        playbackTicker = viewModelScope.launch {
            while (true) {
                refreshPlaybackState()
                delay(700)
            }
        }
    }

    private fun markPlaybackStartedIfNeeded() {
        val player = controller ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (!player.isPlaying || mediaId == lastCountedPlayingTrackId) return
        lastCountedPlayingTrackId = mediaId
        viewModelScope.launch {
            repository.markPlayed(mediaId)
        }
    }

    private fun refreshPlaybackState() {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val currentMediaId = player.currentMediaItem?.mediaId
        _uiState.update {
            val isPreparingCurrent = it.preparingTrackId != null && it.preparingTrackId == currentMediaId
            val canAdoptPreparing = player.isPlaying || player.playbackState == Player.STATE_READY
            val nextCurrentTrackId = if (isPreparingCurrent && !canAdoptPreparing) {
                it.currentTrackId
            } else {
                currentMediaId ?: it.currentTrackId
            }
            val nextPreparingTrackId = if (
                it.preparingTrackId == currentMediaId &&
                (player.isPlaying || player.playbackState == Player.STATE_READY)
            ) {
                null
            } else {
                it.preparingTrackId
            }
            it.copy(
                isPlaying = player.isPlaying,
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                currentTrackId = nextCurrentTrackId,
                preparingTrackId = nextPreparingTrackId,
                playbackStatus = when {
                    nextPreparingTrackId != null -> PlaybackUiStatus.Loading
                    player.isPlaying -> PlaybackUiStatus.Playing
                    nextCurrentTrackId != null && player.playbackState == Player.STATE_READY -> PlaybackUiStatus.Paused
                    player.playbackState == Player.STATE_BUFFERING -> PlaybackUiStatus.Loading
                    nextCurrentTrackId == null -> PlaybackUiStatus.Idle
                    else -> it.playbackStatus
                },
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L),
                playbackDurationMs = duration,
            )
        }
    }

    private fun loadLyricsForTrack(trackId: String?, forceRefresh: Boolean) {
        lyricLoadJob?.cancel()
        if (trackId == null) {
            _uiState.update { it.copy(lyricsState = LyricsUiState.Idle) }
            return
        }
        val existingState = _uiState.value.lyricsState
        if (!forceRefresh &&
            existingState.trackId == trackId &&
            existingState !is LyricsUiState.Loading &&
            existingState !is LyricsUiState.Error
        ) {
            return
        }
        val track = _uiState.value.currentTrack
        if (track == null) {
            _uiState.update {
                it.copy(
                    lyricsState = LyricsUiState.Empty(
                        trackId = trackId,
                        message = "暂无歌词\n请将同名 .lrc 歌词文件放在音乐文件同目录下",
                    ),
                )
            }
            return
        }
        lyricLoadJob = viewModelScope.launch {
            _uiState.update {
                if (it.currentTrackId == trackId) {
                    it.copy(lyricsState = LyricsUiState.Loading(trackId))
                } else {
                    it
                }
            }
            val result = repository.loadLyrics(track, forceRefresh = forceRefresh)
            _uiState.update {
                if (it.currentTrackId != trackId) {
                    it
                } else {
                    it.copy(
                        lyricsState = when (result) {
                            is LyricsLoadResult.Found -> LyricsUiState.Ready(trackId, result.lyrics)
                            LyricsLoadResult.NotFound -> LyricsUiState.Empty(
                                trackId = trackId,
                                message = "暂无歌词\n请将同名 .lrc 歌词文件放在音乐文件同目录下",
                            )
                            is LyricsLoadResult.Failure -> LyricsUiState.Error(trackId, result.message)
                        },
                    )
                }
            }
        }
    }

    private fun runPlaybackCommand(
        debounce: Boolean = true,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            playbackCommandMutex.withLock {
                if (debounce) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPlaybackCommandAtMs < PLAYBACK_COMMAND_DEBOUNCE_MS) {
                        return@withLock
                    }
                    lastPlaybackCommandAtMs = now
                }
                block()
            }
        }
    }

    private fun requestCover(track: TrackEntity, forceCheck: Boolean = false) {
        if (!forceCheck && track.coverCachePath?.let { java.io.File(it).exists() } == true) return
        if (coverLoadJobs[track.id]?.isActive == true) return
        val job = viewModelScope.launch {
            repository.ensureCover(track)
        }
        coverLoadJobs[track.id] = job
        job.invokeOnCompletion {
            if (coverLoadJobs[track.id] === job) {
                coverLoadJobs.remove(track.id)
            }
        }
    }

    private fun requestMetadata(track: TrackEntity) {
        if (metadataRequestedIds.contains(track.id)) return
        if (track.durationMs != null && track.durationMs > 0L && !track.artist.isNullOrBlank() && track.artist != "未知歌手") {
            return
        }
        if (metadataLoadJobs[track.id]?.isActive == true) return
        metadataRequestedIds += track.id
        val job = viewModelScope.launch {
            repository.ensureMetadata(track)
        }
        metadataLoadJobs[track.id] = job
        job.invokeOnCompletion {
            if (metadataLoadJobs[track.id] === job) {
                metadataLoadJobs.remove(track.id)
            }
        }
    }

    private fun cancelMetadataRequests(exceptTrackId: String? = null) {
        metadataLoadJobs
            .filterKeys { it != exceptTrackId }
            .forEach { (trackId, job) ->
                job.cancel()
                metadataLoadJobs.remove(trackId)
                metadataRequestedIds.remove(trackId)
            }
    }

    override fun onCleared() {
        playbackTicker?.cancel()
        lyricLoadJob?.cancel()
        playTrackJob?.cancel()
        playlistTracksJob?.cancel()
        playbackTimeoutJob?.cancel()
        songInfoFetchJob?.cancel()
        coverLoadJobs.values.forEach { it.cancel() }
        coverLoadJobs.clear()
        metadataLoadJobs.values.forEach { it.cancel() }
        metadataLoadJobs.clear()
        controller?.removeListener(playerListener)
        controller?.release()
        controllerFuture?.cancel(true)
        super.onCleared()
    }

    private fun WebDavResult.messageOrSuccess(success: String): String = when (this) {
        is WebDavResult.Success -> message ?: success
        is WebDavResult.Failure -> message
    }

    private fun normalizePath(path: String): String = "/" + path.trim().trim('/').replace('\\', '/')

    private fun loadDirectoryPicker(
        form: NasForm,
        current: DirectoryCrumb,
        breadcrumbs: List<DirectoryCrumb>,
        open: Boolean,
        mode: DirectoryPickerMode,
        includeSubfolders: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionForm = form,
                    directoryPicker = it.directoryPicker.copy(
                        isOpen = open,
                        mode = mode,
                        includeSubfolders = includeSubfolders,
                        current = current,
                        breadcrumbs = breadcrumbs,
                        isLoading = true,
                    ),
                )
            }
            when (val result = repository.listDirectoriesForForm(form, current.remotePath, current.displayPath)) {
                is DirectoryBrowserResult.Success -> {
                    _uiState.update {
                        it.copy(
                            directoryPicker = it.directoryPicker.copy(
                                isOpen = open,
                                mode = mode,
                                includeSubfolders = includeSubfolders,
                                current = result.snapshot.current,
                                breadcrumbs = breadcrumbs,
                                directories = result.snapshot.directories,
                                isLoading = false,
                            ),
                        )
                    }
                }
                is DirectoryBrowserResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            directoryPicker = it.directoryPicker.copy(
                                isOpen = open,
                                mode = mode,
                                includeSubfolders = includeSubfolders,
                                current = current,
                                breadcrumbs = breadcrumbs,
                                directories = emptyList(),
                                isLoading = false,
                            ),
                            message = result.message,
                        )
                    }
                }
            }
        }
    }

    private fun NasServerEntity.toForm(): NasForm =
        NasForm(
            name = name,
            mode = mode,
            inputAddress = inputAddress.ifBlank { resolvedBaseUrl.ifBlank { baseUrl } },
            username = username,
            password = "",
            musicRootPath = selectedMusicRemotePath.ifBlank { musicRootPath },
            selectedMusicRemotePath = selectedMusicRemotePath.ifBlank { musicRootPath },
            selectedMusicDisplayPath = selectedMusicDisplayPath,
            selectedMusicFolderName = selectedMusicFolderName,
            autoScanWifiOnly = autoScanWifiOnly,
            allowMobilePlayback = allowMobilePlayback,
        )

    companion object {
        private const val TAG = "AppViewModel"
        private const val PLAYBACK_COMMAND_DEBOUNCE_MS = 180L
        private const val PLAYBACK_READY_TIMEOUT_MS = 15_000L
        private const val TRACK_INFO_FETCH_TIMEOUT_MS = 8_000L
        private const val COVER_WARMUP_LIMIT = 8
    }
}
