package tech.peakedge.naswalkman.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import tech.peakedge.naswalkman.NasMusicApplication
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import tech.peakedge.naswalkman.data.db.NasServerEntity
import tech.peakedge.naswalkman.data.db.PlaylistSummary
import tech.peakedge.naswalkman.data.db.TrackEntity
import tech.peakedge.naswalkman.data.repository.AppSettings
import tech.peakedge.naswalkman.data.repository.DirectoryCrumb
import tech.peakedge.naswalkman.data.repository.DirectoryBrowserResult
import tech.peakedge.naswalkman.data.repository.NasForm
import tech.peakedge.naswalkman.data.repository.MusicRepository
import tech.peakedge.naswalkman.data.repository.ScanProgress
import tech.peakedge.naswalkman.network.RemoteItem
import tech.peakedge.naswalkman.network.WebDavResult
import tech.peakedge.naswalkman.playback.MusicPlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MainTab { Library, Folders, Player, Settings }

data class DirectoryPickerState(
    val isOpen: Boolean = false,
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

data class AppUiState(
    val nasServer: NasServerEntity? = null,
    val connectionForm: NasForm = NasForm(),
    val isConnectionTested: Boolean = false,
    val directoryPicker: DirectoryPickerState = DirectoryPickerState(),
    val showScanPrompt: Boolean = false,
    val tracks: List<TrackEntity> = emptyList(),
    val favorites: List<TrackEntity> = emptyList(),
    val recent: List<TrackEntity> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val folderPath: String = "/",
    val folderItems: List<RemoteItem> = emptyList(),
    val selectedTab: MainTab = MainTab.Library,
    val searchQuery: String = "",
    val isBusy: Boolean = false,
    val scanProgress: ScanProgress = ScanProgress(),
    val message: String? = null,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val cacheBytes: Long = 0L,
    val settings: AppSettings = AppSettings(),
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
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: MusicRepository =
        (application as NasMusicApplication).container.repository
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playbackTicker: Job? = null
    private val playbackCommandMutex = Mutex()
    private var lastPlaybackCommandAtMs = 0L

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
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    init {
        bindRepositoryFlows()
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
                    selectedTab = if (result is WebDavResult.Success) MainTab.Library else it.selectedTab,
                    showScanPrompt = result is WebDavResult.Success,
                    message = result.messageOrSuccess("连接成功，已保存 NAS 配置"),
                )
            }
            if (result is WebDavResult.Success) {
                openFolder(form.selectedMusicRemotePath.ifBlank { form.musicRootPath })
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
                    tracks = emptyList(),
                    favorites = emptyList(),
                    recent = emptyList(),
                    selectedTab = MainTab.Library,
                    message = "已删除 NAS 绑定",
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
        loadDirectoryPicker(form, root, listOf(root), open = true)
    }

    fun closeDirectoryPicker() {
        _uiState.update { it.copy(directoryPicker = DirectoryPickerState()) }
    }

    fun refreshDirectoryPicker() {
        val picker = _uiState.value.directoryPicker
        loadDirectoryPicker(_uiState.value.connectionForm, picker.current, picker.breadcrumbs, open = true)
    }

    fun enterPickerDirectory(directory: tech.peakedge.naswalkman.network.NasDirectory) {
        val crumb = DirectoryCrumb(
            name = directory.name,
            remotePath = directory.remotePath,
            displayPath = directory.displayPath,
        )
        val nextBreadcrumbs = _uiState.value.directoryPicker.breadcrumbs + crumb
        loadDirectoryPicker(_uiState.value.connectionForm, crumb, nextBreadcrumbs, open = true)
    }

    fun pickerGoUp() {
        val picker = _uiState.value.directoryPicker
        if (picker.breadcrumbs.size <= 1) return
        val nextBreadcrumbs = picker.breadcrumbs.dropLast(1)
        val parent = nextBreadcrumbs.last()
        loadDirectoryPicker(_uiState.value.connectionForm, parent, nextBreadcrumbs, open = true)
    }

    fun chooseCurrentPickerDirectory() {
        val picker = _uiState.value.directoryPicker
        val current = picker.current
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
        runPlaybackCommand playback@{
            val sourceQueue = queue.takeIf { items -> items.any { it.id == track.id } } ?: listOf(track)
            val mediaItems = sourceQueue.mapNotNull { item ->
                val uri = repository.mediaUriFor(item) ?: return@mapNotNull null
                item to MediaItem.Builder()
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
            val startIndex = mediaItems.indexOfFirst { it.first.id == track.id }.coerceAtLeast(0)
            val items = mediaItems.map { it.second }
            if (items.isEmpty()) {
                _uiState.update { it.copy(message = "无法播放，请先连接 NAS") }
                return@playback
            }
            val player = controller
            if (player == null) {
                _uiState.update { it.copy(message = "播放器尚未准备好") }
                return@playback
            }
            player.setMediaItems(items, startIndex, 0L)
            player.prepare()
            player.play()
            repository.markPlayed(track.id)
            _uiState.update { it.copy(selectedTab = MainTab.Player, currentTrackId = track.id) }
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
            controller?.seekToNextMediaItem()
            refreshPlaybackState()
        }
    }

    fun previous() {
        runPlaybackCommand {
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

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            _uiState.update { it.copy(message = "已清理缓存") }
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
                if (server != null && _uiState.value.folderItems.isEmpty()) {
                    openFolder(server.selectedMusicRemotePath.ifBlank { server.musicRootPath })
                }
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { tracks -> _uiState.update { it.copy(tracks = tracks) } }
        }
        viewModelScope.launch {
            repository.favorites.collect { favorites -> _uiState.update { it.copy(favorites = favorites) } }
        }
        viewModelScope.launch {
            repository.recent.collect { recent -> _uiState.update { it.copy(recent = recent) } }
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
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlaybackState()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _uiState.update { it.copy(currentTrackId = mediaItem?.mediaId) }
            refreshPlaybackState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = refreshPlaybackState()
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

    private fun refreshPlaybackState() {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        _uiState.update {
            it.copy(
                isPlaying = player.isPlaying,
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                currentTrackId = player.currentMediaItem?.mediaId ?: it.currentTrackId,
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L),
                playbackDurationMs = duration,
            )
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

    override fun onCleared() {
        playbackTicker?.cancel()
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
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    directoryPicker = it.directoryPicker.copy(
                        isOpen = open,
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
    }
}
