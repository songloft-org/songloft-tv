package com.songloft.tv.ui.my

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
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.data.model.Song
import com.songloft.tv.ui.components.SongItemFavoriteMode
import com.songloft.tv.ui.components.SongListItem
import com.songloft.tv.ui.navigation.DefaultFocusEffect
import com.songloft.tv.ui.navigation.ListBackToTopHandler
import com.songloft.tv.ui.navigation.RestoreFocusEffect
import com.songloft.tv.ui.navigation.rememberScreenFocusRestorer
import com.songloft.tv.ui.navigation.restorableFocus
import com.songloft.tv.ui.theme.SelectedFocusBorder

@Composable
fun MyScreen(
    viewModel: MyViewModel = hiltViewModel(),
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> },
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val favoriteRadioFocus = remember { FocusRequester() }
    val restorer = rememberScreenFocusRestorer()
    var topFocusHasFocus by remember { mutableStateOf(false) }

    ListBackToTopHandler(listState, topFocus, topFocusHasFocus = topFocusHasFocus, jumpToTabBar = true)
    RestoreFocusEffect(restorer)
    DefaultFocusEffect(restorer, settingsFocus)

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
                text = "我的",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            SettingsButton(
                onClick = {
                    restorer.record("settings")
                    onNavigateToSettings()
                },
                modifier = Modifier
                    .focusRequester(settingsFocus)
                    .restorableFocus(restorer, "settings")
                    .focusProperties { down = favoriteRadioFocus }
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TabChip("收藏歌曲", uiState.selectedTab == 0, focusRequester = topFocus, onFocusChanged = { topFocusHasFocus = it }) { viewModel.selectTab(0) }
            TabChip("收藏电台", uiState.selectedTab == 1, focusRequester = favoriteRadioFocus) { viewModel.selectTab(1) }
        }

        Spacer(Modifier.height(16.dp))

        val songs = if (uiState.selectedTab == 0) uiState.favoriteSongs else uiState.favoriteRadios

        when {
            uiState.isLoading -> CenterHint("加载中...")
            uiState.error != null -> CenterHint("加载失败：${uiState.error}")
            songs.isEmpty() -> CenterHint(if (uiState.selectedTab == 0) "暂无收藏歌曲" else "暂无收藏电台")
            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(songs) { index, song ->
                        SongListItem(
                            song = song,
                            onClick = { onSongClick(songs, index) },
                            favoriteMode = SongItemFavoriteMode.REMOVE,
                            onFavoriteClick = { viewModel.removeFavorite(song) },
                            showAlbumInSubtitle = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CenterHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun TabChip(
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
        label = "tabChipScale"
    )

    Text(
        text = if (isSelected) "✓ $label" else label,
        fontSize = 15.sp,
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
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
            .padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "settingsScale"
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
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(text = "设置", fontSize = 16.sp, color = color)
    }
}
