package com.fnnas.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.fnnas.music.data.db.NasConnectionMode
import com.fnnas.music.data.db.PlaylistSummary
import com.fnnas.music.data.db.TrackEntity
import com.fnnas.music.data.repository.NasForm
import com.fnnas.music.network.RemoteItem
import com.fnnas.music.ui.components.AlbumCover
import com.fnnas.music.ui.components.AppCard
import com.fnnas.music.ui.components.AppPasswordField
import com.fnnas.music.ui.components.AppPrimaryButton
import com.fnnas.music.ui.components.AppSecondaryButton
import com.fnnas.music.ui.components.AppSectionTitle
import com.fnnas.music.ui.components.AppTextField
import com.fnnas.music.ui.components.ConnectionModeSegmentedControl
import com.fnnas.music.ui.components.EmptyStatePanel
import com.fnnas.music.ui.components.FavoriteIcon
import com.fnnas.music.ui.components.MiniPlayerBar
import com.fnnas.music.ui.components.MusicDirectoryField
import com.fnnas.music.ui.components.NasStatusPill
import com.fnnas.music.ui.components.PlayerControlButton
import com.fnnas.music.ui.components.SecurityHint
import com.fnnas.music.ui.components.SettingGroupCard
import com.fnnas.music.ui.components.SettingRow
import com.fnnas.music.ui.components.SettingSwitchRow
import com.fnnas.music.ui.components.SoftIconBadge
import com.fnnas.music.ui.components.TrackListItem
import com.fnnas.music.ui.theme.AppFavorite
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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.nasServer != null && state.selectedTab != MainTab.Player && state.selectedTab != MainTab.Settings) {
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
            if (state.showScanPrompt) {
                ScanPromptDialog(
                    directory = state.connectionForm.selectedMusicDisplayPath.ifBlank { state.connectionForm.selectedMusicRemotePath },
                    onDismiss = viewModel::dismissScanPrompt,
                    onConfirm = viewModel::confirmScanPrompt,
                )
            }

            if (state.directoryPicker.isOpen) {
                DirectoryPickerScreen(state, viewModel)
            } else if (state.nasServer == null) {
                NasBindingScreen(
                    title = "连接飞牛 NAS",
                    form = state.connectionForm,
                    isBusy = state.isBusy,
                    canChooseDirectory = state.isConnectionTested,
                    onFormChange = viewModel::updateConnectionForm,
                    onTest = { viewModel.testConnection() },
                    onOpenDirectoryPicker = viewModel::openDirectoryPicker,
                    onManualPathSave = viewModel::setManualMusicPath,
                    onManualPathTest = viewModel::testManualMusicPath,
                    onSave = { viewModel.saveNas() },
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
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
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
    canChooseDirectory: Boolean,
    onFormChange: (NasForm) -> Unit,
    onTest: () -> Unit,
    onOpenDirectoryPicker: () -> Unit,
    onManualPathSave: (String) -> Unit,
    onManualPathTest: (String) -> Unit,
    onSave: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    var showManualPath by remember { mutableStateOf(false) }
    var attemptedSubmit by remember { mutableStateOf(false) }

    if (showHelp) {
        FnIdHelpDialog(onDismiss = { showHelp = false })
    }
    if (showManualPath) {
        ManualPathDialog(
            initialPath = form.selectedMusicRemotePath.ifBlank { form.musicRootPath },
            isBusy = isBusy,
            onDismiss = { showManualPath = false },
            onSave = {
                onManualPathSave(it)
                showManualPath = false
            },
            onTest = onManualPathTest,
        )
    }

    val directoryDisplay = form.selectedMusicDisplayPath
        .ifBlank { form.selectedMusicRemotePath }
        .ifBlank { form.musicRootPath }
    val nameError = if (attemptedSubmit && form.name.isBlank()) "请填写 NAS 名称" else null
    val addressError = if (attemptedSubmit && form.inputAddress.isBlank()) {
        when (form.mode) {
            NasConnectionMode.FN_CONNECT -> "请填写 FN ID 或远程访问地址"
            NasConnectionMode.REMOTE_URL -> "请填写远程访问地址"
            NasConnectionMode.WEBDAV_ADVANCED -> "请填写 WebDAV 地址"
        }
    } else {
        null
    }
    val userError = if (attemptedSubmit && form.username.isBlank()) "请填写用户名" else null
    val passwordError = if (attemptedSubmit && form.password.isBlank()) "请填写密码" else null
    val directoryError = if (attemptedSubmit && directoryDisplay.isBlank()) "请先测试连接并选择音乐目录，或手动填写 /Music" else null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(18.dp)) }
        item { ConnectionHeroIllustration() }
        item {
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "通过 FN Connect 远程播放家里 NAS 中的音乐",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ConnectionModeSegmentedControl(
                selected = form.mode,
                onSelected = { onFormChange(form.copy(mode = it)) },
            )
            Text(
                connectionModeDescription(form.mode),
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    placeholder = "NAS 名称",
                    leadingIcon = Icons.Rounded.Home,
                    error = nameError,
                )
                AppTextField(
                    value = form.inputAddress,
                    onValueChange = { onFormChange(form.copy(inputAddress = it)) },
                    placeholder = addressPlaceholder(form.mode),
                    leadingIcon = Icons.Rounded.Link,
                    error = addressError,
                )
                if (form.mode == NasConnectionMode.FN_CONNECT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            "哪里找 FN ID？",
                            modifier = Modifier
                                .clickable { showHelp = true }
                                .padding(end = 4.dp, top = 2.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (form.mode == NasConnectionMode.FN_CONNECT &&
                    form.inputAddress.isNotBlank() &&
                    !form.inputAddress.startsWith("http", ignoreCase = true)
                ) {
                    Text(
                        "如果自动连接失败，请改填完整远程访问地址。",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (form.inputAddress.startsWith("http://", ignoreCase = true)) {
                    Text(
                        "HTTP 连接未加密，建议使用 HTTPS。",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                AppTextField(
                    value = form.username,
                    onValueChange = { onFormChange(form.copy(username = it)) },
                    placeholder = "用户名",
                    leadingIcon = Icons.Rounded.Person,
                    error = userError,
                )
                AppPasswordField(
                    value = form.password,
                    onValueChange = { onFormChange(form.copy(password = it)) },
                    placeholder = "密码",
                    error = passwordError,
                )
                MusicDirectoryField(
                    value = directoryDisplay,
                    enabled = canChooseDirectory,
                    onClick = onOpenDirectoryPicker,
                )
                if (directoryError != null) {
                    Text(
                        directoryError,
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!canChooseDirectory) {
                    Text(
                        "测试连接成功后可选择音乐目录，也可以手动填写路径。",
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "高级：手动填写路径",
                    modifier = Modifier
                        .clickable { showManualPath = true }
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                AppSecondaryButton(
                    text = "测试连接",
                    onClick = {
                        attemptedSubmit = true
                        onTest()
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                )
                AppPrimaryButton(
                    text = "保存并进入音乐库",
                    onClick = {
                        attemptedSubmit = true
                        onSave()
                    },
                    enabled = !isBusy,
                    isLoading = isBusy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SecurityHint("App 只读取音乐文件，不会修改 NAS 文件")
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ConnectionHeroIllustration() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 74.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Home,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                )
                Spacer(Modifier.height(8.dp))
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 88.dp, height = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun MusicDirectorySelectorRow(
    form: NasForm,
    canChooseDirectory: Boolean,
    onClick: () -> Unit,
) {
    val display = form.selectedMusicDisplayPath
        .ifBlank { form.musicRootPath.takeIf { it.isNotBlank() }?.let { "已手动填写路径：$it" }.orEmpty() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canChooseDirectory, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text("音乐目录", fontWeight = FontWeight.SemiBold)
                Text(
                    display.ifBlank { if (canChooseDirectory) "未选择" else "登录后选择" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(">", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManualPathDialog(
    initialPath: String,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onTest: (String) -> Unit,
) {
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级：手动填写路径") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "仅当你知道 NAS 的真实访问路径时使用。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("路径") },
                    placeholder = { Text("/Music") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(path) }, enabled = path.isNotBlank() && !isBusy) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onTest(path) }, enabled = path.isNotBlank() && !isBusy) {
                    Text("测试路径")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
private fun ScanPromptDialog(
    directory: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描音乐库") },
        text = {
            Text("是否现在扫描这个目录中的音乐？\n\n${directory.ifBlank { "已选择的音乐目录" }}")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("现在扫描") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerScreen(state: AppUiState, viewModel: AppViewModel) {
    val picker = state.directoryPicker
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择音乐目录") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (picker.breadcrumbs.size > 1) viewModel.pickerGoUp() else viewModel.closeDirectoryPicker()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshDirectoryPicker) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        bottomBar = {
            SurfaceLike {
                Button(
                    onClick = viewModel::chooseCurrentPickerDirectory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !picker.isLoading,
                ) {
                    Text("选择当前文件夹作为音乐目录")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "请选择 NAS 中保存歌曲的文件夹",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("当前 NAS：${state.connectionForm.name.ifBlank { "家里的飞牛" }}")
                Text(
                    "当前路径：${picker.current.displayPath}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (picker.isLoading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (picker.directories.isEmpty() && !picker.isLoading) {
                item { EmptyCard("当前目录没有更多文件夹，你也可以选择当前目录作为音乐目录。") }
            }
            items(picker.directories, key = { it.remotePath }) { directory ->
                DirectoryPickerRow(directory = directory, onClick = { viewModel.enterPickerDirectory(directory) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun DirectoryPickerRow(
    directory: com.fnnas.music.network.NasDirectory,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = directory.canEnter, onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(directory.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                directory.itemCount?.let {
                    Text(
                        "包含 $it 个项目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(">", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionModeSelector(
    selected: NasConnectionMode,
    onSelected: (NasConnectionMode) -> Unit,
) {
    ConnectionModeSegmentedControl(selected = selected, onSelected = onSelected)
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
    var showSearch by remember { mutableStateOf(false) }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("新建歌单") },
            text = {
                AppTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = "歌单名称",
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("音乐库", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
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
                NasStatusPill(
                    serverName = state.nasServer?.name ?: "家里的飞牛",
                    onClick = { viewModel.selectTab(MainTab.Settings) },
                )
                if (state.scanProgress.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    AppCard {
                        Text("正在扫描音乐库", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "已发现 ${state.scanProgress.discovered} 首歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (showSearch || state.searchQuery.isNotBlank()) {
                item {
                    AppTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        placeholder = "搜索歌曲、歌手、专辑或路径",
                        leadingIcon = Icons.Rounded.Search,
                    )
                }
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
                    RecentPlaybackCard(state.recent, onClick = { })
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        LibraryMetricCard(
                            icon = Icons.Rounded.Favorite,
                            iconTint = AppFavorite,
                            title = "收藏歌曲",
                            value = "${state.favorites.size} 首",
                            onClick = { },
                        )
                        LibraryMetricCard(
                            icon = Icons.Rounded.MusicNote,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "全部歌曲",
                            value = "${state.tracks.size} 首",
                            onClick = { },
                        )
                    }
                }
                item {
                    AppSectionTitle("我的歌单") {
                        TextButton(onClick = { showPlaylistDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建")
                        }
                    }
                    if (state.playlists.isNotEmpty()) {
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
                trackSection("全部歌曲", state.tracks, "请先扫描音乐库", state, viewModel)
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun RecentPlaybackCard(tracks: List<TrackEntity>, onClick: () -> Unit) {
    AppCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIconBadge(
                icon = Icons.Rounded.LibraryMusic,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "最近播放",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val coverCount = if (tracks.isEmpty()) 5 else tracks.take(5).size
            repeat(coverCount) {
                AlbumCover(
                    modifier = Modifier.size(52.dp),
                    cornerRadius = 10.dp,
                    iconSize = 22.dp,
                )
            }
        }
    }
}

@Composable
private fun RowScope.LibraryMetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIconBadge(icon = icon, tint = iconTint)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    item {
        AppSectionTitle(title) {
            if (title == "全部歌曲") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "排序",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (tracks.isEmpty()) {
        item {
            EmptyStatePanel(
                title = if (state.scanProgress.isRunning) {
                    "正在扫描音乐库\n已发现 ${state.scanProgress.discovered} 首歌曲"
                } else {
                    "当前目录没有发现音乐文件"
                },
                actionText = if (state.scanProgress.isRunning) null else "重新扫描",
                onAction = if (state.scanProgress.isRunning) null else viewModel::scanLibrary,
            )
        }
    } else {
        items(tracks.take(60), key = { it.id }) { track ->
            TrackRow(
                track = track,
                playlists = state.playlists,
                isCurrent = state.currentTrackId == track.id,
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.selectTab(MainTab.Library) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "返回音乐库")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { }) {
                    Icon(Icons.Rounded.Cast, contentDescription = "投放设备")
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                }
            }
        }
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumCover(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(1f),
                    cornerRadius = 24.dp,
                    iconSize = 92.dp,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "还没有播放歌曲",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        track?.artist ?: "从音乐库或文件夹选择一首歌",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityChip("NAS")
                        QualityChip(if (track?.localCachePath != null) "已缓存" else "远程播放")
                    }
                }
                if (track != null) {
                    IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                        FavoriteIcon(isFavorite = track.isFavorite, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        item {
            PlaybackProgress(state, onSeek = viewModel::seekTo)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerControlButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "随机播放",
                    onClick = viewModel::toggleShuffle,
                    selected = state.isShuffleEnabled,
                )
                PlayerControlButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    onClick = viewModel::previous,
                )
                PlayerControlButton(
                    icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    onClick = viewModel::togglePlayback,
                    large = true,
                )
                PlayerControlButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    onClick = viewModel::next,
                )
                PlayerControlButton(
                    icon = Icons.Rounded.Repeat,
                    contentDescription = "循环播放",
                    onClick = viewModel::cycleRepeatMode,
                    selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerControlButton(
                    icon = if (track?.isFavorite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "收藏",
                    label = "收藏",
                    selected = track?.isFavorite == true,
                    onClick = { track?.let(viewModel::toggleFavorite) },
                    modifier = Modifier.weight(1f),
                )
                PlayerControlButton(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "播放列表",
                    label = "播放列表",
                    onClick = { viewModel.selectTab(MainTab.Library) },
                    modifier = Modifier.weight(1f),
                )
                PlayerControlButton(
                    icon = Icons.Rounded.Repeat,
                    contentDescription = "重复播放",
                    label = "重复播放",
                    selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = viewModel::cycleRepeatMode,
                    modifier = Modifier.weight(1f),
                )
                PlayerControlButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "随机播放",
                    label = "随机播放",
                    selected = state.isShuffleEnabled,
                    onClick = viewModel::toggleShuffle,
                    modifier = Modifier.weight(1f),
                )
                PlayerControlButton(
                    icon = Icons.Rounded.CloudDownload,
                    contentDescription = "缓存",
                    label = "缓存",
                    selected = track?.localCachePath != null,
                    onClick = { track?.let(viewModel::cacheTrack) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QualityChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, viewModel: AppViewModel) {
    var showConnectionEditor by remember { mutableStateOf(false) }
    val server = state.nasServer
    val musicDirectory = server?.selectedMusicDisplayPath
        ?.ifBlank { server.selectedMusicRemotePath.ifBlank { server.musicRootPath } }
        ?: "/Music"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.selectTab(MainTab.Library) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingGroupCard("连接设置") {
                SettingRow(
                    title = "连接方式",
                    value = server?.let { connectionModeLabel(it.mode) } ?: "未连接",
                    onClick = { showConnectionEditor = !showConnectionEditor },
                )
                SettingRow(
                    title = "当前 NAS",
                    value = server?.name ?: "先连接你的飞牛 NAS",
                    onClick = { showConnectionEditor = !showConnectionEditor },
                )
                SettingRow(
                    title = "音乐目录",
                    value = musicDirectory,
                    onClick = viewModel::openDirectoryPicker,
                )
                SettingRow(
                    title = "重新连接",
                    value = if (state.isBusy) "连接中" else null,
                    onClick = viewModel::testCurrentConnection,
                )
                SettingRow(
                    title = if (showConnectionEditor) "收起连接编辑" else "修改连接",
                    onClick = { showConnectionEditor = !showConnectionEditor },
                )
                SettingRow(
                    title = "删除绑定",
                    onClick = viewModel::deleteBinding,
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                )
            }
        }
        if (showConnectionEditor) {
            item {
                AppCard {
                    NasInlineEditor(
                        form = state.connectionForm,
                        isBusy = state.isBusy,
                        canChooseDirectory = state.isConnectionTested || state.nasServer != null,
                        onFormChange = viewModel::updateConnectionForm,
                        onTest = { viewModel.testConnection() },
                        onOpenDirectoryPicker = viewModel::openDirectoryPicker,
                        onManualPathSave = viewModel::setManualMusicPath,
                        onManualPathTest = viewModel::testManualMusicPath,
                        onSave = { viewModel.saveNas() },
                    )
                }
            }
        }
        item {
            SettingGroupCard("播放设置") {
                SettingSwitchRow(
                    title = "允许移动网络播放",
                    subtitle = "将消耗移动数据流量",
                    checked = state.settings.allowMobileCache,
                    onCheckedChange = viewModel::setAllowMobileCache,
                )
                SettingSwitchRow(
                    title = "启动时自动连接",
                    checked = state.settings.autoConnectOnStart,
                    onCheckedChange = viewModel::setAutoConnect,
                )
                SettingSwitchRow(
                    title = "启动时自动扫描",
                    subtitle = "更新音乐库",
                    checked = state.settings.autoScanOnStart,
                    onCheckedChange = viewModel::setAutoScan,
                )
            }
        }
        item {
            SettingGroupCard("缓存设置") {
                SettingRow(
                    title = "缓存上限",
                    value = formatBytes(state.settings.cacheLimitBytes),
                    showChevron = false,
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CacheLimitChip("1 GB", 1024L * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                    CacheLimitChip("2 GB", 2L * 1024 * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                    CacheLimitChip("5 GB", 5L * 1024 * 1024 * 1024, state.settings.cacheLimitBytes, viewModel::setCacheLimit)
                }
                SettingRow(
                    title = "已使用缓存",
                    value = formatBytes(state.cacheBytes),
                    showChevron = false,
                )
                SettingRow(
                    title = "清理缓存",
                    onClick = viewModel::clearCache,
                )
            }
        }
        item {
            SettingGroupCard("界面设置") {
                SettingRow(
                    title = "主题",
                    value = when (state.settings.themeMode) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    },
                    showChevron = false,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("跟随系统", "system", state.settings.themeMode, viewModel::setThemeMode)
                    ModeChip("浅色", "light", state.settings.themeMode, viewModel::setThemeMode)
                    ModeChip("深色", "dark", state.settings.themeMode, viewModel::setThemeMode)
                }
                SettingRow(
                    title = "语言",
                    value = if (state.settings.language == "en") "English" else "简体中文",
                    showChevron = false,
                )
            }
        }
        item {
            AppCard {
                Text("飞牛音乐", style = MaterialTheme.typography.titleMedium)
                Text("0.4.0 · 私有 NAS 音乐播放器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun NasInlineEditor(
    form: NasForm,
    isBusy: Boolean,
    canChooseDirectory: Boolean,
    onFormChange: (NasForm) -> Unit,
    onTest: () -> Unit,
    onOpenDirectoryPicker: () -> Unit,
    onManualPathSave: (String) -> Unit,
    onManualPathTest: (String) -> Unit,
    onSave: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    var showManualPath by remember { mutableStateOf(false) }

    if (showHelp) {
        FnIdHelpDialog(onDismiss = { showHelp = false })
    }
    if (showManualPath) {
        ManualPathDialog(
            initialPath = form.selectedMusicRemotePath.ifBlank { form.musicRootPath },
            isBusy = isBusy,
            onDismiss = { showManualPath = false },
            onSave = {
                onManualPathSave(it)
                showManualPath = false
            },
            onTest = onManualPathTest,
        )
    }

    val directoryDisplay = form.selectedMusicDisplayPath
        .ifBlank { form.selectedMusicRemotePath }
        .ifBlank { form.musicRootPath }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("修改连接时需要重新输入密码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("连接方式", fontWeight = FontWeight.SemiBold)
        ConnectionModeSelector(
            selected = form.mode,
            onSelected = { onFormChange(form.copy(mode = it)) },
        )
        AppTextField(
            value = form.name,
            onValueChange = { onFormChange(form.copy(name = it)) },
            placeholder = "NAS 名称",
            leadingIcon = Icons.Rounded.Home,
        )
        AppTextField(
            value = form.inputAddress,
            onValueChange = { onFormChange(form.copy(inputAddress = it)) },
            placeholder = addressPlaceholder(form.mode),
            leadingIcon = Icons.Rounded.Link,
        )
        if (form.mode == NasConnectionMode.FN_CONNECT) {
            TextButton(onClick = { showHelp = true }) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("哪里找 FN ID？")
            }
        }
        if (form.inputAddress.startsWith("http://", ignoreCase = true)) {
            Text(
                "HTTP 连接未加密，建议使用 HTTPS。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        AppTextField(
            value = form.username,
            onValueChange = { onFormChange(form.copy(username = it)) },
            placeholder = "用户名",
            leadingIcon = Icons.Rounded.Person,
        )
        AppPasswordField(
            value = form.password,
            onValueChange = { onFormChange(form.copy(password = it)) },
            placeholder = "密码",
        )
        MusicDirectoryField(
            value = directoryDisplay,
            enabled = canChooseDirectory,
            onClick = onOpenDirectoryPicker,
        )
        TextButton(onClick = { showManualPath = true }) {
            Text("高级：手动填写路径")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AppSecondaryButton(
                text = "测试",
                onClick = onTest,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
            )
            AppSecondaryButton(
                text = "选择目录",
                onClick = onOpenDirectoryPicker,
                enabled = !isBusy && canChooseDirectory,
                modifier = Modifier.weight(1f),
            )
            AppPrimaryButton(
                text = "保存",
                onClick = onSave,
                enabled = !isBusy,
                isLoading = isBusy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    playlists: List<PlaylistSummary>,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onCache: () -> Unit,
    onAddToPlaylist: (PlaylistSummary) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        TrackListItem(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ").ifBlank { track.fileName },
            duration = track.durationMs?.takeIf { it > 0L }?.let(::formatDuration) ?: "--:--",
            isCurrent = isCurrent,
            onClick = onPlay,
            onMore = { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("播放") }, onClick = { menuOpen = false; onPlay() })
            DropdownMenuItem(
                text = { Text(if (track.isFavorite) "取消收藏" else "收藏") },
                onClick = { menuOpen = false; onFavorite() },
            )
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
    val progress = if (state.playbackDurationMs > 0L) {
        state.playbackPositionMs.toFloat() / state.playbackDurationMs.toFloat()
    } else {
        0f
    }
    MiniPlayerBar(
        title = track.title,
        artist = track.artist.orEmpty(),
        isPlaying = state.isPlaying,
        progress = progress,
        onOpen = { viewModel.selectTab(MainTab.Player) },
        onPlaylist = { viewModel.selectTab(MainTab.Library) },
        onToggle = viewModel::togglePlayback,
        onNext = viewModel::next,
    )
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
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(position),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
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
