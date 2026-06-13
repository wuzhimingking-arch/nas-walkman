package tech.peakedge.naswalkman.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import tech.peakedge.naswalkman.BuildConfig
import tech.peakedge.naswalkman.data.db.MusicFolderEntity
import tech.peakedge.naswalkman.data.db.MusicSourceType
import tech.peakedge.naswalkman.data.db.NasConnectionMode
import tech.peakedge.naswalkman.data.db.PlaylistSummary
import tech.peakedge.naswalkman.data.db.TrackEntity
import tech.peakedge.naswalkman.data.repository.NasForm
import tech.peakedge.naswalkman.network.RemoteItem
import tech.peakedge.naswalkman.ui.components.AlbumCover
import tech.peakedge.naswalkman.ui.components.AppCard
import tech.peakedge.naswalkman.ui.components.AppPasswordField
import tech.peakedge.naswalkman.ui.components.AppPrimaryButton
import tech.peakedge.naswalkman.ui.components.AppSecondaryButton
import tech.peakedge.naswalkman.ui.components.AppSectionTitle
import tech.peakedge.naswalkman.ui.components.AppTextField
import tech.peakedge.naswalkman.ui.components.ConnectionModeSegmentedControl
import tech.peakedge.naswalkman.ui.components.EmptyStatePanel
import tech.peakedge.naswalkman.ui.components.FavoriteIcon
import tech.peakedge.naswalkman.ui.components.MiniPlayerBar
import tech.peakedge.naswalkman.ui.components.MusicDirectoryField
import tech.peakedge.naswalkman.ui.components.NasStatusPill
import tech.peakedge.naswalkman.ui.components.PlayerControlButton
import tech.peakedge.naswalkman.ui.components.SecurityHint
import tech.peakedge.naswalkman.ui.components.SettingGroupCard
import tech.peakedge.naswalkman.ui.components.SettingRow
import tech.peakedge.naswalkman.ui.components.SettingSwitchRow
import tech.peakedge.naswalkman.ui.components.SoftIconBadge
import tech.peakedge.naswalkman.ui.components.TrackListItem
import tech.peakedge.naswalkman.ui.theme.AppFavorite
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
            if (state.selectedTab != MainTab.Player) {
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
            NasConnectionMode.FN_CONNECT -> "请填写 FN ID 或 WebDAV 地址"
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
                "通过 FN ID 或 WebDAV 播放家里 NAS 中的音乐",
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
                        "FN ID 会自动解析为 WebDAV 专用地址，目录读取仍需要飞牛 OS 开启 WebDAV 并授权共享目录。",
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (picker.mode == DirectoryPickerMode.AddMusicSource) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("包含子文件夹", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (picker.includeSubfolders) "扫描当前文件夹及所有下级目录" else "只扫描当前文件夹",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = picker.includeSubfolders,
                                onCheckedChange = viewModel::setDirectoryPickerIncludeSubfolders,
                            )
                        }
                    }
                    Button(
                        onClick = viewModel::chooseCurrentPickerDirectory,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !picker.isLoading,
                    ) {
                        Text(
                            if (picker.mode == DirectoryPickerMode.AddMusicSource) {
                                "添加当前 NAS 文件夹"
                            } else {
                                "选择当前文件夹作为音乐目录"
                            },
                        )
                    }
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
                Text("当前 NAS：${state.connectionForm.name.ifBlank { "家里的 NAS" }}")
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
    directory: tech.peakedge.naswalkman.network.NasDirectory,
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
                Text("2. 在系统设置 > 文件共享协议 > WebDAV 开启文件访问服务。")
                Text("3. 确认 WebDAV 远程访问域名可用。")
                Text("4. 在共享目录或用户权限里，给当前账号授权音乐目录。")
                Text("5. 回到本 App，输入 FN ID 或 WebDAV 地址。")
                Text("6. 再填写 NAS 的用户名和密码，并选择音乐目录。")
                Text(
                    "FN ID 会自动解析为 WebDAV 专用地址，音乐文件读取走 WebDAV 协议。不同系统版本的入口可能略有差异。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun connectionModeDescription(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "推荐。输入 FN ID 后自动连接 WebDAV 专用地址；需要飞牛 OS 开启 WebDAV 并授权目录。"
    NasConnectionMode.REMOTE_URL -> "适合已经配置公网 IP、DDNS、反向代理或 HTTPS 域名的用户。"
    NasConnectionMode.WEBDAV_ADVANCED -> "适合已经知道文件访问服务如何配置的高级用户。"
}

private fun addressLabel(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "FN ID 或 WebDAV 地址"
    NasConnectionMode.REMOTE_URL -> "访问地址"
    NasConnectionMode.WEBDAV_ADVANCED -> "WebDAV 地址"
}

private fun addressPlaceholder(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "例如：your-fnid"
    NasConnectionMode.REMOTE_URL -> "例如：https://your-domain.example.com:443"
    NasConnectionMode.WEBDAV_ADVANCED -> "例如：https://your-domain.example.com:443"
}

private fun connectionModeLabel(mode: NasConnectionMode): String = when (mode) {
    NasConnectionMode.FN_CONNECT -> "FN ID"
    NasConnectionMode.REMOTE_URL -> "远程地址"
    NasConnectionMode.WEBDAV_ADVANCED -> "WebDAV 高级"
}

@Composable
private fun LibraryScreen(state: AppUiState, viewModel: AppViewModel) {
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SongSortMode.Title) }

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
            LibraryHeader(
                title = state.libraryPage.title(),
                canBack = state.libraryPage != LibraryPage.Home,
                showSort = state.libraryPage.supportsSongSort(),
                sortMode = sortMode,
                onSortModeChange = { sortMode = it },
                onBack = viewModel::openLibraryHome,
                onSearch = { showSearch = !showSearch },
                onRefresh = viewModel::scanLibrary,
                onSettings = { viewModel.selectTab(MainTab.Settings) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(LibrarySectionSpacing),
        ) {
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
            if (state.libraryPage == LibraryPage.Home && state.searchQuery.isNotBlank()) {
                trackSection(
                    title = "搜索结果",
                    tracks = sortTracks(state.searchResults, sortMode),
                    emptyText = "没有找到匹配的歌曲",
                    state = state,
                    viewModel = viewModel,
                )
            } else {
                libraryPageContent(
                    state = state,
                    viewModel = viewModel,
                    sortMode = sortMode,
                    onCreatePlaylist = { showPlaylistDialog = true },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

private val LibrarySectionSpacing = 12.dp
private val LibrarySectionTitleBottomSpacing = 8.dp

private enum class SongSortMode(val label: String) {
    Title("按标题"),
    Artist("按歌手"),
    Duration("按时长"),
}

private data class ArtistBucket(
    val name: String,
    val songs: List<TrackEntity>,
)

@Composable
private fun LibraryHeader(
    title: String,
    canBack: Boolean,
    showSort: Boolean,
    sortMode: SongSortMode,
    onSortModeChange: (SongSortMode) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 8.dp, end = 4.dp, top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showSort) {
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "排序")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SongSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                onSortModeChange(mode)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "搜索")
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "扫描音乐库")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "设置")
        }
    }
}

private fun LibraryPage.title(): String = when (this) {
    LibraryPage.Home -> "音乐库"
    LibraryPage.AllSongs -> "全部歌曲"
    LibraryPage.Favorites -> "收藏歌曲"
    LibraryPage.MostPlayed -> "最常播放"
    LibraryPage.Artists -> "按歌手"
    LibraryPage.Singles -> "单曲"
    is LibraryPage.ArtistSongs -> artistName
    is LibraryPage.PlaylistDetail -> name
}

private fun LibraryPage.supportsSongSort(): Boolean = when (this) {
    LibraryPage.Home,
    LibraryPage.Artists,
    LibraryPage.MostPlayed,
    -> false
    else -> true
}

private fun androidx.compose.foundation.lazy.LazyListScope.libraryPageContent(
    state: AppUiState,
    viewModel: AppViewModel,
    sortMode: SongSortMode,
    onCreatePlaylist: () -> Unit,
) {
    when (val page = state.libraryPage) {
        LibraryPage.Home -> libraryHomeContent(state, viewModel, onCreatePlaylist)
        LibraryPage.AllSongs -> trackListPage(
            title = "全部歌曲",
            tracks = state.tracks,
            emptyText = "请先扫描音乐库",
            state = state,
            viewModel = viewModel,
            sortMode = sortMode,
        )
        LibraryPage.Favorites -> trackListPage(
            title = "收藏歌曲",
            tracks = state.favorites,
            emptyText = "还没有收藏歌曲",
            state = state,
            viewModel = viewModel,
            sortMode = sortMode,
            preserveOrder = true,
        )
        LibraryPage.MostPlayed -> trackListPage(
            title = "最常播放",
            tracks = state.mostPlayed.map { it.track },
            emptyText = "暂无播放记录，歌曲真正开始播放后会记录次数",
            state = state,
            viewModel = viewModel,
            sortMode = sortMode,
        )
        LibraryPage.Artists -> artistGroupsPage(state, viewModel)
        LibraryPage.Singles -> trackListPage(
            title = "单曲",
            tracks = artistBuckets(state.tracks).singles,
            emptyText = "暂无单曲",
            state = state,
            viewModel = viewModel,
            sortMode = sortMode,
        )
        is LibraryPage.ArtistSongs -> trackListPage(
            title = page.artistName,
            tracks = state.tracks.filter { artistNameForGrouping(it) == page.artistName },
            emptyText = "这个歌手下暂无歌曲",
            state = state,
            viewModel = viewModel,
            sortMode = sortMode,
        )
        is LibraryPage.PlaylistDetail -> playlistDetailPage(page, state, viewModel, sortMode)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.libraryHomeContent(
    state: AppUiState,
    viewModel: AppViewModel,
    onCreatePlaylist: () -> Unit,
) {
    if (state.musicFolders.isEmpty()) {
        item {
            EmptyStatePanel(
                title = "还没有添加音乐文件夹\n请到设置中添加 NAS 或本地音乐文件夹",
                actionText = "音乐文件夹管理",
                onAction = viewModel::openMusicFolderManager,
            )
        }
        return
    }
    item {
        NasStatusPill(
            serverName = state.nasServer?.name ?: "家里的 NAS",
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
    item {
        RecentPlaybackCard(state.recent, onClick = viewModel::openMostPlayed)
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            LibraryMetricCard(
                icon = Icons.Rounded.Favorite,
                iconTint = AppFavorite,
                title = "收藏歌曲",
                value = "${state.favorites.size} 首",
                onClick = viewModel::openFavorites,
            )
            LibraryMetricCard(
                icon = Icons.Rounded.MusicNote,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "全部歌曲",
                value = "${state.tracks.size} 首",
                onClick = viewModel::openAllSongs,
            )
        }
    }
    item {
        AppSectionTitle("我的歌单") {
            TextButton(onClick = onCreatePlaylist) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        }
        Spacer(Modifier.height(LibrarySectionTitleBottomSpacing))
        if (state.playlists.isEmpty()) {
            EmptyCard("暂无歌单，点击右侧新建")
        } else {
            AppCard(contentPadding = 0.dp) {
                state.playlists.forEachIndexed { index, playlist ->
                    if (index > 0) HorizontalDivider()
                    LibraryMenuRow(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} 首",
                        onClick = { viewModel.openPlaylist(playlist) },
                    )
                }
            }
        }
    }
    item {
        val buckets = artistBuckets(state.tracks)
        AppSectionTitle("分类浏览")
        Spacer(Modifier.height(LibrarySectionTitleBottomSpacing))
        AppCard(contentPadding = 0.dp) {
            LibraryMenuRow(
                icon = Icons.Rounded.PlayCircle,
                title = "最常播放",
                subtitle = if (state.mostPlayed.isEmpty()) "暂无播放记录" else "${state.mostPlayed.size} 首",
                onClick = viewModel::openMostPlayed,
            )
            HorizontalDivider()
            LibraryMenuRow(
                icon = Icons.Rounded.Person,
                title = "按歌手",
                subtitle = "${buckets.artists.size} 位歌手",
                onClick = viewModel::openArtists,
            )
            HorizontalDivider()
            LibraryMenuRow(
                icon = Icons.Rounded.MusicNote,
                title = "单曲",
                subtitle = "${buckets.singles.size} 首",
                onClick = viewModel::openSingles,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackListPage(
    title: String,
    tracks: List<TrackEntity>,
    emptyText: String,
    state: AppUiState,
    viewModel: AppViewModel,
    sortMode: SongSortMode,
    playlistIdForRemove: Long? = null,
    preserveOrder: Boolean = false,
) {
    val filtered = filterTracks(tracks, state.searchQuery)
    val sorted = if (preserveOrder) filtered else sortTracks(filtered, sortMode)
    item {
        Text(
            "${sorted.size} 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    trackSection(
        title = title,
        tracks = sorted,
        emptyText = if (state.searchQuery.isBlank()) emptyText else "没有找到匹配的歌曲",
        state = state,
        viewModel = viewModel,
        showTitle = false,
        playlistIdForRemove = playlistIdForRemove,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistDetailPage(
    page: LibraryPage.PlaylistDetail,
    state: AppUiState,
    viewModel: AppViewModel,
    sortMode: SongSortMode,
) {
    item {
        AppCard {
            Text(page.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${state.selectedPlaylistTracks.size} 首歌曲",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    trackListPage(
        title = page.name,
        tracks = state.selectedPlaylistTracks,
        emptyText = "暂无歌曲，请从歌曲菜单添加",
        state = state,
        viewModel = viewModel,
        sortMode = sortMode,
        playlistIdForRemove = page.id,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistGroupsPage(
    state: AppUiState,
    viewModel: AppViewModel,
) {
    val buckets = artistBuckets(state.tracks)
    if (buckets.artists.isEmpty() && buckets.singles.isEmpty()) {
        item {
            EmptyStatePanel(
                title = "暂无歌手数据\n请先扫描音乐库",
                actionText = "扫描音乐库",
                onAction = viewModel::scanLibrary,
            )
        }
        return
    }
    items(buckets.artists, key = { it.name }) { bucket ->
        LibraryMenuRow(
            icon = Icons.Rounded.Person,
            title = bucket.name,
            subtitle = "${bucket.songs.size} 首",
            onClick = { viewModel.openArtistSongs(bucket.name) },
        )
    }
    if (buckets.singles.isNotEmpty()) {
        item {
            LibraryMenuRow(
                icon = Icons.Rounded.MusicNote,
                title = "单曲",
                subtitle = "${buckets.singles.size} 首",
                onClick = viewModel::openSingles,
            )
        }
    }
}

@Composable
private fun LibraryMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftIconBadge(icon = icon, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

private data class ArtistBuckets(
    val artists: List<ArtistBucket>,
    val singles: List<TrackEntity>,
)

private fun artistBuckets(tracks: List<TrackEntity>): ArtistBuckets {
    val grouped = tracks.groupBy(::artistNameForGrouping)
    val artists = grouped
        .filterValues { it.size >= MIN_ARTIST_GROUP_SIZE }
        .map { (name, songs) -> ArtistBucket(name, songs.sortedBy { it.title.lowercase() }) }
        .sortedWith(compareByDescending<ArtistBucket> { it.songs.size }.thenBy { it.name.lowercase() })
    val singles = grouped
        .filterValues { it.size < MIN_ARTIST_GROUP_SIZE }
        .values
        .flatten()
        .sortedBy { it.title.lowercase() }
    return ArtistBuckets(artists = artists, singles = singles)
}

private fun artistNameForGrouping(track: TrackEntity): String =
    track.artist?.trim()?.takeIf { it.isNotBlank() } ?: "未知歌手"

private fun filterTracks(tracks: List<TrackEntity>, query: String): List<TrackEntity> {
    val keyword = query.trim()
    if (keyword.isBlank()) return tracks
    return tracks.filter { track ->
        listOf(track.title, track.artist, track.album, track.fileName, track.remotePath)
            .filterNotNull()
            .any { it.contains(keyword, ignoreCase = true) }
    }
}

private fun sortTracks(tracks: List<TrackEntity>, sortMode: SongSortMode): List<TrackEntity> = when (sortMode) {
    SongSortMode.Title -> tracks.sortedWith(compareBy({ it.title.lowercase() }, { it.fileName.lowercase() }))
    SongSortMode.Artist -> tracks.sortedWith(compareBy({ artistNameForGrouping(it).lowercase() }, { it.title.lowercase() }))
    SongSortMode.Duration -> tracks.sortedWith(compareByDescending<TrackEntity> { it.durationMs ?: 0L }.thenBy { it.title.lowercase() })
}

private const val MIN_ARTIST_GROUP_SIZE = 3

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
            if (tracks.isEmpty()) {
                repeat(5) {
                    AlbumCover(
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 10.dp,
                        iconSize = 22.dp,
                    )
                }
            } else {
                tracks.take(5).forEach { track ->
                    AlbumCover(
                        modifier = Modifier.size(52.dp),
                        coverPath = track.coverCachePath,
                        cornerRadius = 10.dp,
                        iconSize = 22.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllSongsSectionTitle(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "全部歌曲",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                "${count}首",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.weight(1f))
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
    showTitle: Boolean = true,
    playlistIdForRemove: Long? = null,
) {
    if (showTitle) {
        item {
            if (title == "全部歌曲") {
                AllSongsSectionTitle(tracks.size)
            } else {
                AppSectionTitle(title)
            }
        }
    }
    if (tracks.isEmpty()) {
        item {
            val isAllSongs = title == "全部歌曲"
            val isScanning = state.scanProgress.isRunning && isAllSongs
            val hasNoFolders = state.musicFolders.isEmpty()
            EmptyStatePanel(
                title = if (isScanning) {
                    "正在扫描音乐库\n已发现 ${state.scanProgress.discovered} 首歌曲"
                } else if (isAllSongs && hasNoFolders) {
                    "还没有添加音乐文件夹\n请到设置中添加 NAS 或本地音乐文件夹"
                } else if (isAllSongs) {
                    "音乐库为空\n请重新扫描音乐文件夹"
                } else {
                    emptyText
                },
                actionText = when {
                    isScanning || !isAllSongs -> null
                    hasNoFolders -> "音乐文件夹管理"
                    else -> "扫描音乐库"
                },
                onAction = when {
                    isScanning || !isAllSongs -> null
                    hasNoFolders -> viewModel::openMusicFolderManager
                    else -> viewModel::scanLibrary
                },
            )
        }
    } else {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                playlists = state.playlists,
                isCurrent = state.currentTrackId == track.id,
                isPreparing = state.playbackStatus == PlaybackUiStatus.Loading && state.preparingTrackId == track.id,
                showRemoveFromPlaylist = playlistIdForRemove != null,
                onPlay = { viewModel.playTrack(track, tracks) },
                onFavorite = { viewModel.toggleFavorite(track) },
                onCache = { viewModel.cacheTrack(track) },
                onAddToPlaylists = { playlistIds -> viewModel.addToPlaylists(playlistIds, track) },
                onRemoveFromPlaylist = {
                    playlistIdForRemove?.let { playlistId -> viewModel.removeFromPlaylist(playlistId, track) }
                },
                onVisible = { viewModel.prepareVisibleTrack(track) },
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
    val isPreparing = state.playbackStatus == PlaybackUiStatus.Loading && state.preparingTrackId != null
    val track = state.preparingTrack ?: state.currentTrack
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
                if (state.showLyrics) {
                    LyricsPanel(
                        state = state,
                        onToggleCover = viewModel::toggleLyricsMode,
                        onSeek = viewModel::seekTo,
                        onRetry = viewModel::retryLyrics,
                    )
                } else {
                    AlbumCover(
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .aspectRatio(1f)
                            .clickable(enabled = track != null) { viewModel.toggleLyricsMode() },
                        coverPath = track?.coverCachePath,
                        cornerRadius = 24.dp,
                        iconSize = 92.dp,
                    )
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                            if (isPreparing) "切换中…" else track?.artist ?: "从音乐库或文件夹选择一首歌",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track != null) {
                        IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                            FavoriteIcon(isFavorite = track.isFavorite, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QualityChip("NAS")
                        QualityChip(if (track?.localCachePath != null) "已缓存" else "远程播放")
                    }
                    LyricsToggleChip(
                        showLyrics = state.showLyrics,
                        enabled = track != null,
                        onToggle = viewModel::toggleLyricsMode,
                    )
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
                    onClick = { if (!isPreparing) viewModel.togglePlayback() },
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
private fun LyricsToggleChip(
    showLyrics: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    AssistChip(
        onClick = onToggle,
        enabled = enabled,
        label = { Text(if (showLyrics) "显示封面" else "显示歌词") },
        leadingIcon = {
            Icon(
                if (showLyrics) Icons.Rounded.LibraryMusic else Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun LyricsPanel(
    state: AppUiState,
    onToggleCover: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val lyricsState = state.lyricsState
    Box(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .clickable(onClick = onToggleCover)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.currentTrackId == null -> LyricsMessage("暂无歌词", "先从音乐库或文件夹选择一首歌")
            lyricsState is LyricsUiState.Ready && lyricsState.trackId == state.currentTrackId -> {
                LyricsList(
                    lyrics = lyricsState.lyrics,
                    positionMs = state.playbackPositionMs,
                    onSeek = onSeek,
                )
            }
            lyricsState is LyricsUiState.Error && lyricsState.trackId == state.currentTrackId -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LyricsMessage("歌词加载失败", lyricsState.message)
                    OutlinedButton(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }
            lyricsState is LyricsUiState.Empty && lyricsState.trackId == state.currentTrackId -> {
                LyricsMessage("暂无歌词", lyricsState.message)
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(
                        "正在加载歌词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsList(
    lyrics: tech.peakedge.naswalkman.data.repository.LyricsContent,
    positionMs: Long,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(lyrics, positionMs) {
        lyrics.lines.indexOfLast { line -> line.timeMs?.let { it <= positionMs } == true }
    }
    var autoScrollPaused by remember(lyrics.sourceFileName) { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress, lyrics.sourceFileName) {
        if (listState.isScrollInProgress) {
            autoScrollPaused = true
            delay(3500)
            autoScrollPaused = false
        }
    }
    LaunchedEffect(currentIndex, autoScrollPaused, lyrics.sourceFileName) {
        if (!autoScrollPaused && currentIndex >= 0) {
            listState.animateScrollToItem((currentIndex - 3).coerceAtLeast(0))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 92.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isCurrent = index == currentIndex
            Text(
                line.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (line.timeMs != null) {
                            Modifier.clickable { onSeek(line.timeMs) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = if (isCurrent) {
                    MaterialTheme.typography.titleMedium.copy(lineHeight = 30.sp)
                } else {
                    MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp)
                },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    lyrics.hasTimeline -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun LyricsMessage(title: String, body: String) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 23.sp,
        )
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
    if (state.showMusicFolderManager) {
        MusicFolderManagerScreen(state = state, viewModel = viewModel)
        return
    }
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
                    title = "音乐文件夹管理",
                    value = "${state.musicFolders.size} 个来源",
                    subtitle = "添加 NAS 或本地音乐文件夹",
                    onClick = viewModel::openMusicFolderManager,
                )
                SettingRow(
                    title = "连接方式",
                    value = server?.let { connectionModeLabel(it.mode) } ?: "未连接",
                    onClick = { showConnectionEditor = !showConnectionEditor },
                )
                SettingRow(
                    title = "当前 NAS",
                    value = server?.name ?: "先连接你的 NAS",
                    onClick = { showConnectionEditor = !showConnectionEditor },
                )
                SettingRow(
                    title = "音乐目录",
                    value = musicDirectory,
                    onClick = viewModel::openDirectoryPicker,
                )
                SettingRow(
                    title = "NAS 文件夹",
                    value = "浏览文件夹或手动进入目录",
                    onClick = viewModel::openFolderBrowser,
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
            SettingGroupCard("音乐库维护") {
                SettingRow(
                    title = "获取歌曲信息",
                    value = when {
                        state.songInfoFetchProgress.isRunning -> "进行中"
                        state.songInfoFetchProgress.isComplete -> "已完成"
                        else -> null
                    },
                    subtitle = "读取封面、歌手、时长、歌词、专辑和标题",
                    onClick = viewModel::fetchSongInfo,
                )
                SongInfoFetchProgressView(
                    progress = state.songInfoFetchProgress,
                    onCancel = viewModel::cancelSongInfoFetch,
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
                Text("NAS随身听", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${BuildConfig.VERSION_NAME} · 第三方私有 NAS 音乐播放器",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "NAS随身听是一款用于播放个人 NAS 音乐文件的私有音乐播放器，支持通过 FN ID、远程访问地址、WebDAV 等方式连接自己的 NAS，随时播放家里的音乐。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "本应用为第三方 NAS 音乐播放器，非飞牛官方应用，未与飞牛官方建立授权、合作或从属关系。应用仅用于连接用户自己的 NAS 并播放个人音乐文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "隐私说明：本应用不上传 NAS 地址、账号、密码、音乐列表或播放记录。连接信息仅保存在本机。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "开源地址：https://github.com/wuzhimingking-arch/nas-walkman",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun MusicFolderManagerScreen(state: AppUiState, viewModel: AppViewModel) {
    var localIncludeSubfolders by remember { mutableStateOf(true) }
    var nasIncludeSubfolders by remember { mutableStateOf(true) }
    val localFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { uri ->
            viewModel.addLocalMusicFolder(uri, result.data?.flags ?: 0, localIncludeSubfolders)
        }
    }

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
                IconButton(onClick = viewModel::closeMusicFolderManager) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("音乐文件夹管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingGroupCard("添加音乐文件夹") {
                IncludeSubfolderRow(
                    title = "本地文件夹包含子文件夹",
                    checked = localIncludeSubfolders,
                    onCheckedChange = { localIncludeSubfolders = it },
                )
                SettingRow(
                    title = "添加手机本地文件夹",
                    value = "使用系统文件夹选择器",
                    onClick = { localFolderLauncher.launch(openDocumentTreeIntent()) },
                )
                HorizontalDivider()
                IncludeSubfolderRow(
                    title = "NAS 文件夹包含子文件夹",
                    checked = nasIncludeSubfolders,
                    onCheckedChange = { nasIncludeSubfolders = it },
                )
                SettingRow(
                    title = "添加 NAS 文件夹",
                    value = if (state.nasServer == null) "请先登录 NAS 后再添加 NAS 文件夹" else state.nasServer.name,
                    onClick = { viewModel.openNasFolderPickerForSource(nasIncludeSubfolders) },
                )
            }
        }
        if (state.scanProgress.isRunning) {
            item {
                AppCard {
                    Text("正在扫描音乐文件夹", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "已发现 ${state.scanProgress.discovered} 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.musicFolders.isEmpty()) {
            item {
                EmptyStatePanel(
                    title = "还没有添加音乐文件夹\n请添加 NAS 或本地音乐文件夹",
                    actionText = null,
                    onAction = null,
                )
            }
        } else {
            items(state.musicFolders, key = { it.id }) { folder ->
                MusicFolderCard(
                    folder = folder,
                    onRescan = { viewModel.rescanMusicFolder(folder) },
                    onToggleInclude = { viewModel.setMusicFolderIncludeSubfolders(folder, it) },
                    onDelete = { viewModel.deleteMusicFolder(folder) },
                )
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun IncludeSubfolderRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                if (checked) "扫描当前文件夹及所有下级目录" else "只扫描当前文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MusicFolderCard(
    folder: MusicFolderEntity,
    onRescan: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIconBadge(icon = Icons.Rounded.Folder, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityChip(sourceTypeLabel(folder.sourceType))
            QualityChip(if (folder.includeSubfolders) "包含子文件夹" else "仅当前文件夹")
            folder.songCount?.let { QualityChip("$it 首") }
        }
        IncludeSubfolderRow(
            title = "包含子文件夹",
            checked = folder.includeSubfolders,
            onCheckedChange = onToggleInclude,
        )
        if (BuildConfig.DEBUG && folder.sourceType == MusicSourceType.NAS) {
            NasFolderDebugInfo(folder)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRescan) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("重新扫描")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun NasFolderDebugInfo(folder: MusicFolderEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Debug 扫描信息", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        DebugLine("来源 ID", folder.id.toString())
        DebugLine("保存路径", folder.path)
        DebugLine("includeSubfolders", folder.includeSubfolders.toString())
        DebugLine("上次扫描时间", folder.lastScannedAt?.let(::formatTimestamp).orEmpty())
        DebugLine("上次扫描状态", folder.lastScanStatus.orEmpty())
        DebugLine("上次发现文件数", folder.lastScannedFileCount?.toString().orEmpty())
        DebugLine("上次发现音频数", folder.lastScannedAudioCount?.toString().orEmpty())
        DebugLine("上次错误信息", folder.lastScanError.orEmpty())
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        "$label：${value.ifBlank { "-" }}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun sourceTypeLabel(type: MusicSourceType): String = when (type) {
    MusicSourceType.NAS -> "NAS"
    MusicSourceType.LOCAL -> "本地"
}

private fun openDocumentTreeIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

@Composable
private fun SongInfoFetchProgressView(
    progress: SongInfoFetchProgress,
    onCancel: () -> Unit,
) {
    if (!progress.isRunning && !progress.isComplete && progress.statusText.isBlank()) return

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress.totalCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    progress.currentTrackName.ifBlank { "准备获取歌曲信息" },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "（${progress.currentIndex}/${progress.totalCount}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { progress.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            progress.statusText.ifBlank { "正在读取：封面 / 歌手 / 时长 / 歌词 / 专辑" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress.isComplete && progress.totalCount > 0) {
            Text(
                "总计 ${progress.totalCount} 首，跳过 ${progress.skippedCount} 首，更新 ${progress.successCount} 首，失败 ${progress.failureCount} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress.isRunning) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
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
            Text(
                "FN ID 会自动解析为 WebDAV 专用地址，目录读取仍需要飞牛 OS 开启 WebDAV 并授权共享目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    isPreparing: Boolean,
    showRemoveFromPlaylist: Boolean = false,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onCache: () -> Unit,
    onAddToPlaylists: (Set<Long>) -> Unit,
    onRemoveFromPlaylist: () -> Unit = {},
    onVisible: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var selectedPlaylistIds by remember(track.id, playlists) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(track.id, track.coverCachePath, track.artist, track.durationMs) {
        onVisible()
    }
    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text("加入歌单") },
            text = {
                if (playlists.isEmpty()) {
                    Text("暂无歌单，请先在音乐库新建歌单。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        playlists.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedPlaylistIds = selectedPlaylistIds.toggle(playlist.id)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selectedPlaylistIds.contains(playlist.id),
                                    onCheckedChange = {
                                        selectedPlaylistIds = selectedPlaylistIds.toggle(playlist.id)
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${playlist.trackCount} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedPlaylistIds.isNotEmpty(),
                    onClick = {
                        onAddToPlaylists(selectedPlaylistIds)
                        selectedPlaylistIds = emptySet()
                        showPlaylistPicker = false
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistPicker = false }) { Text("取消") }
            },
        )
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        TrackListItem(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ").ifBlank { track.fileName },
            duration = track.durationMs?.takeIf { it > 0L }?.let(::formatDuration) ?: "--:--",
            isCurrent = isCurrent,
            isPreparing = isPreparing,
            coverPath = track.coverCachePath,
            onClick = onPlay,
            onMore = { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("播放") }, onClick = { menuOpen = false; onPlay() })
            DropdownMenuItem(
                text = { Text(if (track.isFavorite) "取消收藏" else "收藏") },
                onClick = { menuOpen = false; onFavorite() },
            )
            DropdownMenuItem(
                text = { Text("加入歌单") },
                onClick = {
                    menuOpen = false
                    selectedPlaylistIds = emptySet()
                    showPlaylistPicker = true
                },
            )
            DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { menuOpen = false; onCache() })
            if (showRemoveFromPlaylist) {
                DropdownMenuItem(
                    text = { Text("从歌单移除") },
                    onClick = { menuOpen = false; onRemoveFromPlaylist() },
                )
            }
        }
    }
}

private fun Set<Long>.toggle(value: Long): Set<Long> =
    if (contains(value)) this - value else this + value

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
    val isPreparing = state.playbackStatus == PlaybackUiStatus.Loading && state.preparingTrackId != null
    val track = state.preparingTrack ?: state.currentTrack ?: return
    val progress = if (state.playbackDurationMs > 0L) {
        state.playbackPositionMs.toFloat() / state.playbackDurationMs.toFloat()
    } else {
        0f
    }
    MiniPlayerBar(
        title = track.title,
        artist = if (isPreparing) "切换中…" else track.artist.orEmpty(),
        coverPath = track.coverCachePath,
        isPlaying = state.isPlaying && !isPreparing,
        isLoading = isPreparing,
        progress = if (isPreparing) 0f else progress,
        onOpen = { viewModel.selectTab(MainTab.Player) },
        onPlaylist = { viewModel.selectTab(MainTab.Library) },
        onToggle = { if (!isPreparing) viewModel.togglePlayback() },
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
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

private fun formatTimestamp(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

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
