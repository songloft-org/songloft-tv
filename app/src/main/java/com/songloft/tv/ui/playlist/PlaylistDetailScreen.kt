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
import com.songloft.tv.data.model.Song
import com.songloft.tv.ui.navigation.ListBackToTopHandler

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    viewModel: PlaylistViewModel = hiltViewModel(),
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> },
    onShufflePlay: (List<Song>) -> Unit = {},
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
                                text = "${maxOf(uiState.total, uiState.songs.size)} 首歌曲",
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
                                    label = "播放全部",
                                    onClick = {
                                        if (uiState.songs.isNotEmpty()) onSongClick(uiState.songs, 0)
                                    },
                                    focusRequester = playAllFocus
                                )
                                ActionButton(
                                    icon = Icons.Rounded.Shuffle,
                                    label = "随机播放",
                                    onClick = {
                                        if (uiState.songs.isNotEmpty()) onShufflePlay(uiState.songs)
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
                                onClick = { onSongClick(uiState.songs, index) },
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
            .clickable { onClick() }
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

