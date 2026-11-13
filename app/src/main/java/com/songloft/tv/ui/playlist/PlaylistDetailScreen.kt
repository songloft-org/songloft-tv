package com.songloft.tv.ui.playlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.songloft.tv.util.AppText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.components.SongItemFavoriteMode
import com.songloft.tv.ui.components.SongListItem
import com.songloft.tv.data.model.AllSongsPlaylist
import com.songloft.tv.data.model.Song
import com.songloft.tv.ui.navigation.ListBackToTopHandler

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    viewModel: PlaylistViewModel = hiltViewModel(),
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> },
    onShufflePlay: (List<Song>) -> Unit = {},
    /** 全库缓存尚未加载完就开播：MainActivity 据此在后台续载时把新歌追加进当前播放队列 */
    onAllSongsEarlyPlay: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.detailState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val playAllFocus = remember { FocusRequester() }
    var backButtonHasFocus by remember { mutableStateOf(false) }
    // 电台歌单无「播放全部/随机播放」按钮，返回键 ↓ 直接落到列表第一首
    val isRadioPlaylist = uiState.playlist?.type == "radio"
    // 虚拟「全部曲库」由后台缓存持续追加：只要有歌就能立刻播，不必等全量载入完成；
    // 未载入完时按钮文案明确为「已载入」，MainActivity 会在后续分页到达时补齐播放队列
    val isAllSongs = AllSongsPlaylist.isAllSongs(playlistId)
    val canPlayAll = uiState.songs.isNotEmpty()
    val markEarlyPlay = {
        if (isAllSongs && uiState.isLoadingAll) onAllSongsEarlyPlay()
    }
    // 首批还没回来时按钮显示「载入中…」；已有部分歌曲则明确告知播的是「已载入」部分
    val playAllLabel = when {
        isAllSongs && uiState.isLoadingAll && uiState.songs.isEmpty() -> "载入中…"
        uiState.isLoadingAll -> "播放已载入"
        else -> "播放全部"
    }
    val shuffleLabel = when {
        isAllSongs && uiState.isLoadingAll && uiState.songs.isEmpty() -> "载入中…"
        uiState.isLoadingAll -> "随机播放已载入"
        else -> "随机播放"
    }

    ListBackToTopHandler(listState, topFocus, topFocusHasFocus = backButtonHasFocus)

    LaunchedEffect(Unit) { runCatching { topFocus.requestFocus() } }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistDetail(playlistId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearDetail() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            BackButton(
                onBack,
                focusRequester = topFocus,
                onFocusChanged = { backButtonHasFocus = it },
                modifier = if (isRadioPlaylist) Modifier
                else Modifier.focusProperties { down = playAllFocus }
            )
        }

        Spacer(Modifier.height(16.dp))

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
            else -> {
                val playlist = uiState.playlist
                if (playlist != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            CoverImage(
                                url = playlist.coverUrl,
                                contentDescription = playlist.name,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(Modifier.width(20.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isAllSongs && uiState.isLoadingAll) {
                                    // serverTotal 是服务端原始总数（含电台），仅作进度分母参考
                                    if (uiState.allServerTotal > 0) {
                                        "已载入 ${uiState.songs.size} 首 · 曲库约 ${uiState.allServerTotal} 首 · 后台载入中…"
                                    } else {
                                        "已载入 ${uiState.songs.size} 首 · 后台载入中…"
                                    }
                                } else {
                                    "${maxOf(uiState.total, uiState.songs.size)} 首歌曲"
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            if (playlist.description != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = playlist.description,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!isRadioPlaylist) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActionButton(
                                    icon = Icons.Rounded.PlayArrow,
                                    label = playAllLabel,
                                    enabled = canPlayAll,
                                    onClick = {
                                        if (uiState.songs.isNotEmpty()) {
                                            markEarlyPlay()
                                            onSongClick(uiState.songs, 0)
                                        }
                                    },
                                    focusRequester = playAllFocus
                                )
                                ActionButton(
                                    icon = Icons.Rounded.Shuffle,
                                    label = shuffleLabel,
                                    enabled = canPlayAll,
                                    onClick = {
                                        if (uiState.songs.isNotEmpty()) {
                                            markEarlyPlay()
                                            onShufflePlay(uiState.songs)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(uiState.songs) { index, song ->
                            SongListItem(
                                song = song,
                                onClick = {
                                    markEarlyPlay()
                                    onSongClick(uiState.songs, index)
                                },
                                favoriteMode = SongItemFavoriteMode.TOGGLE,
                                isFavorite = song.id in favoriteIds,
                                onFavoriteClick = { viewModel.toggleFavorite(song) },
                                showAlbumInSubtitle = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackButton(
    onBack: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "backScale"
    )

    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .clickable { onBack() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(text = "返回", fontSize = 16.sp, color = color)
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "actionButtonScale"
    )
    val color = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .alpha(if (enabled) 1f else 0.5f)
            // 保持 clickable 可聚焦，避免载入期间返回键的 down 焦点链断掉
            .clickable { if (enabled) onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

