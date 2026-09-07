package com.songloft.tv.ui.library

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.songloft.tv.util.AppText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.data.model.Song
import com.songloft.tv.ui.components.SongItemFavoriteMode
import com.songloft.tv.ui.components.SongListItem
import com.songloft.tv.ui.navigation.ListBackToTopHandler

@Composable
fun FilteredSongsScreen(
    field: String,
    value: String,
    viewModel: FilteredSongsViewModel = hiltViewModel(),
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    LaunchedEffect(field, value) { viewModel.load(field, value) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    var backButtonHasFocus by remember { mutableStateOf(false) }

    ListBackToTopHandler(listState, topFocus, topFocusHasFocus = backButtonHasFocus)

    LaunchedEffect(Unit) { runCatching { topFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack, focusRequester = topFocus, onFocusChanged = { backButtonHasFocus = it })
            Spacer(Modifier.width(16.dp))
            Text(
                text = uiState.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            if (uiState.total > 0) {
                Text(
                    text = "共 ${uiState.total} 首",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            uiState.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败: ${uiState.error}", color = MaterialTheme.colorScheme.error)
            }
            uiState.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无歌曲", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                itemsIndexed(uiState.songs) { index, song ->
                    SongListItem(
                        song = song,
                        onClick = { onSongClick(uiState.songs, index) },
                        index = index,
                        favoriteMode = SongItemFavoriteMode.TOGGLE,
                        isFavorite = song.id in favoriteIds,
                        onFavoriteClick = { viewModel.toggleFavorite(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, focusRequester: FocusRequester? = null, onFocusChanged: ((Boolean) -> Unit)? = null) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)
                ) else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            tint = if (isFocused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}
