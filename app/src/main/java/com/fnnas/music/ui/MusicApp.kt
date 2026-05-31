package com.fnnas.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fnnas.music.data.db.NasConnectionMode
import com.fnnas.music.data.db.PlaylistSummary
import com.fnnas.music.data.db.TrackEntity
import com.fnnas.music.data.repository.NasForm
import com.fnnas.music.network.RemoteItem
import kotlin.math.roundToInt

@Composable
fun MusicApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.nasServer != null) {
                Column {
                    MiniPlayer(state, viewModel)
                    BottomNavigation(state.selectedTab, viewModel::selectTab)
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.nasServer == null) {
                NasBindingScreen(
                    title = "连接飞牛 NAS",
                    form = viewModel.defaultForm(),
                    isBusy = state.isBusy,
                    onTest = viewModel::testConnection,
                    onSave = viewModel::saveNas,
                )
            } else {
                when (state.selectedTab) {
                    MainTab.Library -> LibraryScreen(state, viewModel)
                    MainTab.Folders -> FolderScreen(state, viewModel)
                    MainTab.Player -> PlayerScreen(state, viewModel)
                    MainTab.Settings -> SettingsScreen(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: MainTab, onSelected: (MainTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.Library,
            onClick = { onSelected(MainTab.Library) },
            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = "音乐库") },
            label = { Text("音乐库") },
        )
        NavigationBarItem(
            selected = selected == MainTab.Folders,
            onClick = { onSelected(MainTab.Folders) },
            icon = { Icon(Icons.Rounded.Folder, contentDescription = "文件夹") },
            label = { Text("文件夹") },
        )
        NavigationBarItem(
            selected = selected == MainTab.Player,
            onClick = { onSelected(MainTab.Player) },
            icon = { Icon(Icons.Rounded.PlayCircle, contentDescription = "播放") },
            label = { Text("播放") },
        )
        NavigationBarItem(
            selected = selected == MainTab.Settings,
            onClick = { onSelected(MainTab.Settings) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = "设置") },
            label = { Text("设置") },
        )
    }
}

@Composable
private fun NasBindingScreen(
    title: String,
    form: NasForm,
    isBusy: Boolean,
    onTest: (NasForm) -> Unit,
    onSave: (NasForm) -> Unit,
) {
    var editing by remember(form.mode, form.inputAddress, form.username, form.musicRootPath) { mutableStateOf(form) }
    var showPassword by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        FnIdHelpDialog(onDismiss = { showHelp = false })
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "通过 FN Connect 远程播放家里 NAS 中的音乐",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("连接方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    ConnectionModeSelector(
                        selected = editing.mode,
                        onSelected = { editing = editing.copy(mode = it) },
                    )
                    Text(
                        connectionModeDescription(editing.mode),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("连接信息") {
                        if (editing.mode == NasConnectionMode.FN_CONNECT) {
                            TextButton(onClick = { showHelp = true }) {
                                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("哪里找 FN ID？")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editing.name,
                        onValueChange = { editing = editing.copy(name = it) },
                        label = { Text("NAS 名称") },
                        placeholder = { Text("家里的飞牛") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editing.inputAddress,
                        onValueChange = { editing = editing.copy(inputAddress = it) },
                        label = { Text(addressLabel(editing.mode)) },
                        placeholder = { Text(addressPlaceholder(editing.mode)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editing.mode == NasConnectionMode.FN_CONNECT &&
                        editing.inputAddress.isNotBlank() &&
                        !editing.inputAddress.startsWith("http", ignoreCase = true)
                    ) {
                        Text(
                            "如果你在飞牛 fnOS 中开启了 FN Connect，可以直接填写 FN ID；自动连接失败时，请改填完整远程访问地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (editing.inputAddress.startsWith("http://", ignoreCase = true)) {
                        Text(
                            "HTTP 连接未加密，建议使用 HTTPS。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    OutlinedTextField(
                        value = editing.username,
                        onValueChange = { editing = editing.copy(username = it) },
                        label = { Text("飞牛 NAS 用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editing.password,
                        onValueChange = { editing = editing.copy(password = it) },
                        label = { Text("飞牛 NAS 密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editing.musicRootPath,
                        onValueChange = { editing = editing.copy(musicRootPath = it) },
                        label = { Text("音乐目录") },
                        placeholder = { Text("/Music 或 /音乐") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingSwitch(
                        title = "仅 Wi-Fi 下自动扫描",
                        checked = editing.autoScanWifiOnly,
                        onCheckedChange = { editing = editing.copy(autoScanWifiOnly = it) },
                    )
                    SettingSwitch(
                        title = "允许移动网络播放",
                        checked = editing.allowMobilePlayback,
                        onCheckedChange = { editing = editing.copy(allowMobilePlayback = it) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onTest(editing) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("测试连接")
                }
                Button(
                    onClick = { onSave(editing) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存并进入")
                    }
                }
            }
        }
        item {
            Text(
                "App 只读取音乐文件，不会上传、删除或修改 NAS 文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ConnectionModeSelector(
    selected: NasConnectionMode,
    onSelected: (NasConnectionMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = selected == NasConnectionMode.FN_CONNECT,
            onClick = { onSelected(NasConnectionMode.FN_CONNECT) },
            label = { Text("FN Connect") },
        )
        FilterChip(
            selected = selected == NasConnectionMode.REMOTE_URL,
            onClick = { onSelected(NasConnectionMode.REMOTE_URL) },
            label = { Text("远程地址") },
        )
        FilterChip(
            selected = selected == NasConnectionMode.WEBDAV_ADVANCED,
            onClick = { onSelected(NasConnectionMode.WEBDAV_ADVANCED) },
            label = { Text("WebDAV 高级") },
        )
    }
}

@Composable
private fun FnIdHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("哪里找 FN ID？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 打开飞牛 fnOS 或飞牛 App。")
                Text("2. 确认已经开启 FN Connect 远程访问。")
                Text("3. 找到 FN ID 或远程访问地址。")
                Text("4. 回到本 App，输入 FN ID 或完整远程访问地址。")
                Text("5. 再填写飞牛 NAS 的用户名和密码。")
                Text("6. 音乐目录填写 NAS 里保存歌曲的文件夹路径，例如 /Music。")
                Text(
                    "不同系统版本的入口可能不同，本 App 不依赖飞牛内部私有接口。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun connectionModeDescription(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "推荐。输入 FN ID 或从飞牛系统复制的 FN Connect 远程访问地址。"
    NasConnectionMode.REMOTE_URL -> "适合已经配置公网 IP、DDNS、反向代理或 HTTPS 域名的用户。"
    NasConnectionMode.WEBDAV_ADVANCED -> "适合已经知道文件访问服务如何配置的高级用户。"
}

private fun addressLabel(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "FN ID 或飞牛远程访问地址"
    NasConnectionMode.REMOTE_URL -> "访问地址"
    NasConnectionMode.WEBDAV_ADVANCED -> "WebDAV 地址"
}

private fun addressPlaceholder(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "myfnid 或 https://myfnid.5ddd.com"
    NasConnectionMode.REMOTE_URL -> "https://nas.example.com"
    NasConnectionMode.WEBDAV_ADVANCED -> "https://nas.example.com/dav"
}

private fun connectionModeLabel(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "FN Connect"
    NasConnectionMode.REMOTE_URL -> "远程地址"
    NasConnectionMode.WEBDAV_ADVANCED -> "WebDAV 高级"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(state: AppUiState, viewModel: AppViewModel) {
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(playlistName)
                    playlistName = ""
                    showPlaylistDialog = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showPlaylistDialog = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐库") },
                actions = {
                    IconButton(onClick = { viewModel.scanLibrary() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "扫描音乐库")
                    }
                    IconButton(onClick = { viewModel.selectTab(MainTab.Settings) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NasStatus(state)
                if (state.scanProgress.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在扫描 ${state.scanProgress.currentFolder}，已发现 ${state.scanProgress.discovered} 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("搜索歌曲、歌手、专辑或路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.searchQuery.isNotBlank()) {
                trackSection(
                    title = "搜索结果",
                    tracks = state.searchResults,
                    emptyText = "没有找到匹配的歌曲",
                    state = state,
                    viewModel = viewModel,
                )
            } else {
                item {
                    SectionHeader("我的歌单") {
                        TextButton(onClick = { showPlaylistDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建")
                        }
                    }
                    if (state.playlists.isEmpty()) {
                        EmptyCard("还没有歌单，可先创建一个本地歌单。")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.playlists.take(3).forEach { playlist ->
                                AssistChip(
                                    onClick = { },
                                    label = { Text("${playlist.name} · ${playlist.trackCount}") },
                                )
                            }
                        }
                    }
                }
                trackSection("最近播放", state.recent, "还没有播放记录", state, viewModel)
                trackSection("收藏歌曲", state.favorites, "还没有收藏歌曲", state, viewModel)
                trackSection("全部歌曲", state.tracks, "请先扫描音乐库", state, viewModel)
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackSection(
    title: String,
    tracks: List<TrackEntity>,
    emptyText: String,
    state: AppUiState,
    viewModel: AppViewModel,
) {
    item { SectionHeader(title) }
    if (tracks.isEmpty()) {
        item { EmptyCard(emptyText) }
    } else {
        items(tracks.take(30), key = { it.id }) { track ->
            TrackRow(
                track = track,
                playlists = state.playlists,
                onPlay = { viewModel.playTrack(track, tracks) },
                onFavorite = { viewModel.toggleFavorite(track) },
                onCache = { viewModel.cacheTrack(track) },
                onAddToPlaylist = { playlist -> viewModel.addToPlaylist(playlist.id, track) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderScreen(state: AppUiState, viewModel: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.folderPath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goUpFolder() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上一级")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openFolder(state.folderPath) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新目录")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.folderItems.isEmpty() && !state.isBusy) {
                item { EmptyCard("当前目录没有发现音乐文件") }
            }
            items(state.folderItems, key = { it.remotePath }) { item ->
                FolderItemRow(item, onClick = { viewModel.playFolderItem(item) })
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun PlayerScreen(state: AppUiState, viewModel: AppViewModel) {
    val track = state.currentTrack
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
        }
        Text(
            track?.title ?: "还没有播放歌曲",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track?.artist ?: "从音乐库或文件夹选择一首歌",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PlaybackProgress(state, onSeek = viewModel::seekTo)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::previous, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(34.dp))
            }
            IconButton(
                onClick = viewModel::togglePlayback,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(42.dp),
                )
            }
            IconButton(onClick = viewModel::next, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", modifier = Modifier.size(34.dp))
            }
        }
        if (track != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.toggleFavorite(track) }) {
                    Icon(
                        if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (track.isFavorite) "已收藏" else "收藏")
                }
                OutlinedButton(onClick = { viewModel.cacheTrack(track) }) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (track.localCachePath != null) "已缓存" else "缓存")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, viewModel: AppViewModel) {
    var showConnectionEditor by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            SettingsCard("连接设置") {
                val server = state.nasServer
                SettingInfoRow("当前连接方式", server?.let { connectionModeLabel(it.mode) } ?: "未连接")
                SettingInfoRow("NAS 名称", server?.name ?: "未连接 NAS")
                SettingInfoRow("FN ID / 远程访问地址", server?.inputAddress?.ifBlank { server.resolvedBaseUrl } ?: "先连接你的飞牛 NAS")
                SettingInfoRow("音乐目录", server?.musicRootPath ?: "/Music")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = viewModel::testCurrentConnection,
                        enabled = !state.isBusy && server != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接") }
                    Button(
                        onClick = { showConnectionEditor = !showConnectionEditor },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (showConnectionEditor) "收起" else "修改连接") }
                }
                OutlinedButton(
                    onClick = viewModel::deleteBinding,
                    enabled = !state.isBusy && server != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除绑定") }
                if (showConnectionEditor) {
                    HorizontalDivider()
                    NasInlineEditor(
                        form = viewModel.defaultForm(),
                        isBusy = state.isBusy,
                        onTest = viewModel::testConnection,
                        onSave = viewModel::saveNas,
                    )
                }
            }
        }
        item {
            SettingsCard("播放设置") {
                SettingSwitch(
                    title = "启动时自动连接 NAS",
                    checked = state.settings.autoConnectOnStart,
                    onCheckedChange = viewModel::setAutoConnect,
                )
                SettingSwitch(
                    title = "启动时自动扫描",
                    checked = state.settings.autoScanOnStart,
                    onCheckedChange = viewModel::setAutoScan,
                )
                SettingSwitch(
                    title = "移动网络允许缓存",
                    checked = state.settings.allowMobileCache,
                    onCheckedChange = viewModel::setAllowMobileCache,
                )
            }
        }
        item {
            SettingsCard("缓存设置") {
                Text("当前缓存 ${formatBytes(state.cacheBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CacheLimitChip("500MB", 500L * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                    CacheLimitChip("1GB", 1024L * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                    CacheLimitChip("2GB", 2L * 1024 * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                }
                OutlinedButton(onClick = viewModel::clearCache) {
                    Text("清理全部缓存")
                }
            }
        }
        item {
            SettingsCard("界面设置") {
                Text("主题")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("跟随系统", "system", state.settings.themeMode, viewModel::setThemeMode)
                    ModeChip("浅色", "light", state.settings.themeMode, viewModel::setThemeMode)
                    ModeChip("深色", "dark", state.settings.themeMode, viewModel::setThemeMode)
                }
                Spacer(Modifier.height(10.dp))
                Text("语言")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("跟随系统", "system", state.settings.language, viewModel::setLanguage)
                    ModeChip("中文", "zh", state.settings.language, viewModel::setLanguage)
                    ModeChip("English", "en", state.settings.language, viewModel::setLanguage)
                }
            }
        }
        item {
            SettingsCard("关于") {
                Text("飞牛音乐", style = MaterialTheme.typography.titleMedium)
                Text("0.2.0 · 私有 NAS 音乐播放器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun NasInlineEditor(
    form: NasForm,
    isBusy: Boolean,
    onTest: (NasForm) -> Unit,
    onSave: (NasForm) -> Unit,
) {
    var editing by remember(form.mode, form.inputAddress, form.username, form.musicRootPath) { mutableStateOf(form) }
    var showPassword by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        FnIdHelpDialog(onDismiss = { showHelp = false })
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("修改连接时需要重新输入密码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("连接方式", fontWeight = FontWeight.SemiBold)
        ConnectionModeSelector(
            selected = editing.mode,
            onSelected = { editing = editing.copy(mode = it) },
        )
        OutlinedTextField(
            value = editing.name,
            onValueChange = { editing = editing.copy(name = it) },
            label = { Text("NAS 名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editing.inputAddress,
            onValueChange = { editing = editing.copy(inputAddress = it) },
            label = { Text(addressLabel(editing.mode)) },
            placeholder = { Text(addressPlaceholder(editing.mode)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editing.mode == NasConnectionMode.FN_CONNECT) {
            TextButton(onClick = { showHelp = true }) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("哪里找 FN ID？")
            }
        }
        if (editing.inputAddress.startsWith("http://", ignoreCase = true)) {
            Text(
                "HTTP 连接未加密，建议使用 HTTPS。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        OutlinedTextField(
            value = editing.username,
            onValueChange = { editing = editing.copy(username = it) },
            label = { Text("飞牛 NAS 用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editing.password,
            onValueChange = { editing = editing.copy(password = it) },
            label = { Text("飞牛 NAS 密码") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editing.musicRootPath,
            onValueChange = { editing = editing.copy(musicRootPath = it) },
            label = { Text("音乐目录") },
            placeholder = { Text("/Music 或 /音乐") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onTest(editing) },
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
            ) { Text("测试") }
            Button(
                onClick = { onSave(editing) },
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    playlists: List<PlaylistSummary>,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onCache: () -> Unit,
    onAddToPlaylist: (PlaylistSummary) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" · ").ifBlank { track.fileName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (track.isFavorite) "取消收藏" else "收藏",
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("播放") }, onClick = { menuOpen = false; onPlay() })
                    DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { menuOpen = false; onCache() })
                    playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text("添加到 ${playlist.name}") },
                            onClick = { menuOpen = false; onAddToPlaylist(playlist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderItemRow(item: RemoteItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (item.isDirectory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (item.isDirectory) "文件夹" else formatBytes(item.size ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: AppUiState, viewModel: AppViewModel) {
    val track = state.currentTrack ?: return
    SurfaceLike {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.selectTab(MainTab.Player) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = viewModel::togglePlayback) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "播放或暂停")
            }
        }
    }
}

@Composable
private fun PlaybackProgress(state: AppUiState, onSeek: (Long) -> Unit) {
    val duration = state.playbackDurationMs.coerceAtLeast(0L)
    val position = state.playbackPositionMs.coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0) position.toFloat() else 0f,
            onValueChange = { onSeek(it.roundToInt().toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NasStatus(state: AppUiState) {
    val server = state.nasServer
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (server != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
            )
            Text(server?.name ?: "未连接 NAS", fontWeight = FontWeight.SemiBold)
            if (state.isBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.width(118.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CacheLimitChip(label: String, value: Long, selected: Long, onSelected: (Long) -> Unit) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelected(value) },
        label = { Text(label) },
    )
}

@Composable
private fun ModeChip(label: String, value: String, selected: String, onSelected: (String) -> Unit) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelected(value) },
        label = { Text(label) },
    )
}

@Composable
private fun SurfaceLike(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 1.dp),
    ) {
        content()
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1fGB".format(gb)
        mb >= 1 -> "%.1fMB".format(mb)
        kb >= 1 -> "%.0fKB".format(kb)
        else -> "${bytes}B"
    }
}
