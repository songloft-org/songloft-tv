package com.songloft.tv.ui.playlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.songloft.tv.util.AppText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.components.PinBadge
import com.songloft.tv.ui.components.tvFocusable
import com.songloft.tv.data.model.Playlist
import com.songloft.tv.ui.navigation.DefaultFocusEffect
import com.songloft.tv.ui.navigation.ListBackToTopHandler
import com.songloft.tv.ui.navigation.RestoreFocusEffect
import com.songloft.tv.ui.navigation.rememberScreenFocusRestorer
import com.songloft.tv.ui.navigation.restorableFocus
import com.songloft.tv.ui.theme.SelectedFocusBorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(
    viewModel: PlaylistViewModel = hiltViewModel(),
    onPlaylistClick: (Long) -> Unit = {}
) {
    val uiState by viewModel.listState.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedIds.collectAsStateWithLifecycle()
    // 置顶歌单提到最前（内置收藏已由 repository 固定最前，用户置顶紧随其后）
    val displayPlaylists = remember(uiState.playlists, pinnedIds) {
        orderWithPinnedFirst(uiState.playlists, pinnedIds)
    }
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val restorer = rememberScreenFocusRestorer()
    var topFocusHasFocus by remember { mutableStateOf(false) }
    // 长按歌单后待确认的置顶/取消置顶操作
    var pendingPin by remember { mutableStateOf<Playlist?>(null) }

    ListBackToTopHandler(listState, topFocus, topFocusHasFocus = topFocusHasFocus, jumpToTabBar = true)
    RestoreFocusEffect(restorer)
    DefaultFocusEffect(restorer, topFocus)

    // 滚动接近底部时懒加载下一页；切过滤标签时重置监听
    LaunchedEffect(uiState.selectedType) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, totalItems) ->
            if (totalItems > 0 && lastVisible >= totalItems - 3) {
                viewModel.loadMorePlaylists()
            }
        }
    }

    // 30s 心跳：自动刷新当前歌单列表（离开列表页自动停止，切标签重置计时）
    LaunchedEffect(uiState.selectedType) {
        while (true) {
            delay(30_000)
            viewModel.refreshPlaylists()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "歌单",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip("全部", uiState.selectedType == null, focusRequester = topFocus, onFocusChanged = { topFocusHasFocus = it }) { viewModel.loadPlaylists() }
                FilterChip("普通", uiState.selectedType == "normal") { viewModel.loadPlaylists("normal") }
                FilterChip("电台", uiState.selectedType == "radio") { viewModel.loadPlaylists("radio") }
                RefreshButton(refreshing = uiState.isRefreshing) { viewModel.refreshPlaylists() }
            }
        }

        Spacer(Modifier.height(20.dp))

        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载失败: ${uiState.error}", color = MaterialTheme.colorScheme.error)
                }
            }
            uiState.playlists.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无歌单", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    val rows = displayPlaylists.chunked(4)
                    items(rows.size) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rows[rowIndex].forEach { playlist ->
                                PlaylistGridCard(
                                    playlist = playlist,
                                    isPinned = playlist.id in pinnedIds,
                                    onClick = {
                                        restorer.record("playlist:${playlist.id}")
                                        onPlaylistClick(playlist.id)
                                    },
                                    onLongPress = { pendingPin = playlist },
                                    modifier = Modifier
                                        .weight(1f)
                                        .restorableFocus(restorer, "playlist:${playlist.id}")
                                )
                            }
                            repeat(4 - rows[rowIndex].size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "加载中...",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPin?.let { playlist ->
        val willUnpin = playlist.id in pinnedIds
        if (willUnpin) {
            PinConfirmDialog(
                title = "取消置顶",
                message = "确定取消置顶「${playlist.name}」吗？取消后该歌单将恢复普通排序。",
                onConfirm = {
                    viewModel.togglePin(playlist.id)
                    pendingPin = null
                },
                onDismiss = { pendingPin = null }
            )
        } else {
            // 已满 8 个时明确提示：新置顶会顶掉最早置顶的歌单
            val oldestPinnedName = if (pinnedIds.size >= PlaylistViewModel.MAX_PINNED) {
                pinnedIds.lastOrNull()
                    ?.let { id -> displayPlaylists.find { it.id == id }?.name }
                    ?.let { "「$it」" }
            } else null
            val message = oldestPinnedName?.let {
                "最多只能置顶 ${PlaylistViewModel.MAX_PINNED} 个歌单。置顶「${playlist.name}」后，将自动取消最早置顶的歌单 $it，是否继续？"
            } ?: "置顶「${playlist.name}」后将固定显示在歌单列表最前，是否置顶？"
            PinConfirmDialog(
                title = "置顶歌单",
                message = message,
                onConfirm = {
                    viewModel.togglePin(playlist.id)
                    pendingPin = null
                },
                onDismiss = { pendingPin = null }
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "filterChipScale"
    )

    Text(
        text = if (isSelected) "✓ $label" else label,
        fontSize = 14.sp,
        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
        color = when {
            isSelected -> MaterialTheme.colorScheme.onPrimary
            isFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp,
                    if (isSelected) SelectedFocusBorder else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun RefreshButton(
    refreshing: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .tvFocusable(cornerRadius = 18.dp, onClick = { if (!refreshing) onClick() }),
        contentAlignment = Alignment.Center
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "刷新",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(150),
        label = "playlistGridScale"
    )
    val scope = rememberCoroutineScope()
    // 确认键完全由本卡片接管：短按 KeyUp 触发 onClick，按住 400ms 判定长按触发 onLongPress
    // 注意：不能用 combinedClickable，其按键处理会先于 onPreviewKeyEvent 消费确认键事件，导致长按无法拦截
    var longPressTriggered by remember { mutableStateOf(false) }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
            .then(
                if (isFocused) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .onPreviewKeyEvent { event ->
                val isConfirm = event.key == Key.Enter || event.key == Key.DirectionCenter
                if (isConfirm) {
                    Log.d(TAG, "[keyEvent] type=${event.type} key=${event.key} repeat=${event.nativeKeyEvent.repeatCount} id=${playlist.id}")
                }
                if (!isConfirm) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            longPressTriggered = false
                            Log.d(TAG, "[keyDown0] 启动长按计时 id=${playlist.id}")
                            timerJob = scope.launch {
                                delay(LONG_PRESS_DELAY_MS)
                                Log.d(TAG, "[timer] 计时到期 longPressTriggered=$longPressTriggered id=${playlist.id}")
                                if (!longPressTriggered) {
                                    longPressTriggered = true
                                    Log.d(TAG, "[longPress] 触发长按置顶 id=${playlist.id}")
                                    onLongPress()
                                }
                            }
                        }
                        true // consume，防止其它按键处理响应
                    }
                    KeyEventType.KeyUp -> {
                        // 已失焦（如长按期间已进详情）的松手 KeyUp 放行，避免二次触发
                        if (!isFocused) return@onPreviewKeyEvent false
                        timerJob?.cancel()
                        timerJob = null
                        Log.d(TAG, "[keyUp] longPressTriggered=$longPressTriggered id=${playlist.id}")
                        if (!longPressTriggered) {
                            Log.d(TAG, "[click] KeyUp 判定短按，执行 onClick id=${playlist.id}")
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) {
                    timerJob?.cancel()
                    timerJob = null
                    Log.d(TAG, "[focusLost] 取消计时 id=${playlist.id}")
                }
            }
            .pointerInput(playlist.id) {
                detectTapGestures(
                    onTap = {
                        Log.d(TAG, "[touchClick] 触屏点击 id=${playlist.id}")
                        onClick()
                    },
                    onLongPress = {
                        Log.d(TAG, "[touchLongPress] 触屏长按 id=${playlist.id}")
                        onLongPress()
                    }
                )
            }
            .focusable()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverImage(
                url = playlist.coverUrl,
                contentDescription = playlist.name,
                modifier = Modifier.fillMaxSize()
            )
            if (isPinned) {
                PinBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "${playlist.songCount} 首",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private const val LONG_PRESS_DELAY_MS = 400L
private const val TAG = "PlaylistPin"

@Composable
private fun PinConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 默认焦点落在「取消」，避免误按确认键直接执行置顶/取消置顶
    val cancelFocus = remember { FocusRequester() }
    // 弹窗可能由长按触发，此时确认键仍处于按下状态：吞掉这枚残留按键的重复 KeyDown 和松手 KeyUp，
    // 否则松手瞬间会触发默认焦点的「取消」按钮，弹窗一闪而过
    var sawFreshKeyDown by remember { mutableStateOf(false) }
    var swallowKeyUp by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .onPreviewKeyEvent { event ->
                    val isConfirm = event.key == Key.Enter || event.key == Key.DirectionCenter
                    if (!isConfirm) return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                // 全新按下：放行，由 clickable 在 KeyUp 触发
                                sawFreshKeyDown = true
                                false
                            } else {
                                // 弹窗打开前已按住的键的重复事件，等待吞掉其松手
                                swallowKeyUp = true
                                true
                            }
                        }
                        KeyEventType.KeyUp -> {
                            val shouldSwallow = !sawFreshKeyDown || swallowKeyUp
                            sawFreshKeyDown = false
                            swallowKeyUp = false
                            if (shouldSwallow) true else false
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 36.dp, vertical = 28.dp)
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var cancelFocused by remember { mutableStateOf(false) }
                Text(
                    text = "取消",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (cancelFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                        .then(
                            if (cancelFocused) Modifier.border(
                                3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                            ) else Modifier
                        )
                        .focusRequester(cancelFocus)
                        .onFocusChanged { cancelFocused = it.isFocused }
                        .clickable { onDismiss() }
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                )
                var confirmFocused by remember { mutableStateOf(false) }
                Text(
                    text = "确认",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (confirmFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                        .then(
                            if (confirmFocused) Modifier.border(
                                3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                            ) else Modifier
                        )
                        .onFocusChanged { confirmFocused = it.isFocused }
                        .clickable { onConfirm() }
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                )
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}
