package com.songloft.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.data.model.Song
import com.songloft.tv.ui.components.SongItemFavoriteMode
import com.songloft.tv.ui.components.SongListItem
import com.songloft.tv.ui.components.generateQrBitmap
import com.songloft.tv.ui.navigation.ListBackToTopHandler

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val useCustomKeyboard by viewModel.useCustomKeyboard.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val remoteUrl by viewModel.remoteUrl.collectAsStateWithLifecycle()
    var showKeyboard by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    val searchBoxFocus = remember { FocusRequester() }
    val keyboardFocus = remember { FocusRequester() }
    val candidateFocus = remember { FocusRequester() }

    LaunchedEffect(showKeyboard) {
        if (showKeyboard) runCatching { keyboardFocus.requestFocus() }
    }
    // 键盘关闭后：有拼音候选时焦点直接落到第一个候选（候选词少时用 D-Pad 很难选中），否则回搜索框
    LaunchedEffect(showKeyboard) {
        if (showKeyboard) return@LaunchedEffect
        if (uiState.candidates.isNotEmpty()) {
            runCatching { candidateFocus.requestFocus() }
        } else {
            runCatching { searchBoxFocus.requestFocus() }
        }
    }
    var searchFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatching { searchBoxFocus.requestFocus() }
    }

    // 每次进入搜索页静默刷新拼音索引（ViewModel 为 Activity 级，init 只在进程启动后执行一次）
    LaunchedEffect(Unit) {
        viewModel.refreshSearchIndex()
    }

    LaunchedEffect(Unit) {
        viewModel.remoteSubmitEvents.collect {
            showQrDialog = false
            showKeyboard = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopRemoteInput() }
    }

    BackHandler(enabled = showKeyboard) {
        showKeyboard = false
        runCatching { searchBoxFocus.requestFocus() }
    }

    ListBackToTopHandler(
        listState,
        topFocus = searchBoxFocus,
        topFocusHasFocus = searchFocused,
        // 焦点在搜索框时按返回键先跳底部 Tab 栏，而非直接回首页
        jumpToTabBar = true,
        enabled = !showKeyboard
    )

    // 无关键词浏览曲库：滚动接近底部时懒加载下一页；切换关键词时重置监听
    LaunchedEffect(uiState.query) {
        if (uiState.query.isNotBlank()) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, totalItems) ->
            if (totalItems > 0 && lastVisible >= totalItems - 3) {
                viewModel.loadMoreBrowse()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        // 键盘弹出时隐藏标题，避免键盘底部操作行（空格/退格/确定）被屏幕裁掉
        if (!showKeyboard) {
            Text(
                text = "搜索音乐",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (searchFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .then(
                        if (searchFocused) Modifier.border(
                            2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)
                        ) else Modifier
                    )
                    .focusRequester(searchBoxFocus)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .then(
                        if (useCustomKeyboard) Modifier.clickable { showKeyboard = !showKeyboard }
                        else Modifier
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (useCustomKeyboard) {
                    Text(
                        text = if (uiState.query.isEmpty()) "点击搜索歌曲..." else uiState.query,
                        fontSize = 20.sp,
                        color = if (uiState.query.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // 系统键盘模式：搜索框为真实输入框，聚焦即弹出系统键盘，也可直接接收硬件键盘
                    BasicTextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChanged,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (uiState.query.isNotEmpty()) {
                var clearFocused by remember { mutableStateOf(false) }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清空",
                    tint = if (clearFocused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (clearFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .then(
                            if (clearFocused) Modifier.border(
                                3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                            ) else Modifier
                        )
                        .onFocusChanged { clearFocused = it.isFocused }
                        .clickable { viewModel.clearSearch() }
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                )
            }
            var qrFocused by remember { mutableStateOf(false) }
            Icon(
                imageVector = Icons.Rounded.QrCode,
                contentDescription = "扫码搜索",
                tint = if (qrFocused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (qrFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .then(
                        if (qrFocused) Modifier.border(
                            3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                        ) else Modifier
                    )
                    .onFocusChanged { qrFocused = it.isFocused }
                    .clickable {
                        viewModel.startRemoteInput()
                        showQrDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 14.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 候选词与"热门搜索"同位，放在结果区之外：键盘弹出时结果区会被压缩，
        // 候选词若在其内会被挤扁变形
        if (uiState.candidates.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(uiState.candidates) { index, candidate ->
                    HotTagChip(
                        tag = candidate,
                        modifier = if (index == 0) Modifier.focusRequester(candidateFocus) else Modifier
                    ) {
                        viewModel.onQueryChanged(candidate)
                        showKeyboard = false
                        runCatching { searchBoxFocus.requestFocus() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 结果区占据剩余高度，键盘固定贴底，保证操作行不被挤出屏幕
        Column(Modifier.weight(1f)) {
            if (uiState.query.isBlank()) {
                // 无关键词：热门搜索标签 + 曲库浏览（首次进入默认展示），滚动接近底部自动加载下一页
                Column {
                    if (uiState.hotTags.isNotEmpty()) {
                        Text(
                            text = "热门搜索",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(uiState.hotTags) { tag ->
                                HotTagChip(tag) { viewModel.onQueryChanged(tag) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    when {
                        uiState.browseSongs.isNotEmpty() -> {
                            Text(
                                text = "共 ${uiState.browseTotal} 首",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            SongResultList(
                                songs = uiState.browseSongs,
                                listState = listState,
                                favoriteIds = favoriteIds,
                                isLoadingMore = uiState.isLoadingMore,
                                onSongClick = { onSongClick(uiState.browseSongs, it) },
                                onFavoriteClick = viewModel::toggleFavorite
                            )
                        }
                        uiState.isBrowseLoading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("加载中...", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        uiState.error != null -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("加载失败：${uiState.error}", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "输入关键词搜索歌曲",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            } else if (uiState.isSearching) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("搜索中...", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else if (uiState.hasSearched && uiState.results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未找到结果", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else if (uiState.results.isNotEmpty()) {
                Text(
                    text = "共 ${uiState.results.size} 首",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SongResultList(
                    songs = uiState.results,
                    listState = listState,
                    favoriteIds = favoriteIds,
                    isLoadingMore = false,
                    onSongClick = { onSongClick(uiState.results, it) },
                    onFavoriteClick = viewModel::toggleFavorite
                )
            } else if (!uiState.hasSearched) {
                HotSearchSection(uiState.hotTags, viewModel::onQueryChanged)
            }
        }

        if (useCustomKeyboard && showKeyboard) {
            TvKeyboard(
                firstKeyFocusRequester = keyboardFocus,
                onKeyPress = { key ->
                    when (key) {
                        "←退格" -> {
                            val current = uiState.query
                            if (current.isNotEmpty()) {
                                viewModel.onQueryChanged(current.substring(0, current.length - 1))
                            }
                        }
                        "清空" -> viewModel.clearSearch()
                        "确定" -> { showKeyboard = false }
                        "空格" -> viewModel.onQueryChanged("${uiState.query} ")
                        else -> viewModel.onQueryChanged("${uiState.query}$key")
                    }
                }
            )
        }
    }

    if (showQrDialog) {
        SearchQrDialog(url = remoteUrl, onDismiss = { showQrDialog = false })
    }
}

@Composable
private fun SearchQrDialog(url: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (url == null) {
                Text(
                    text = "未获取到局域网地址\n请检查电视网络连接",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else {
                val qrBitmap = remember(url) { generateQrBitmap(url) }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "扫码搜索",
                        modifier = Modifier.size(200.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "手机扫码搜索",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "同一局域网内扫码，在手机上输入\n关键字远程搜索，可反复提交",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = url,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SongResultList(
    songs: List<Song>,
    listState: LazyListState,
    favoriteIds: Set<Long>,
    isLoadingMore: Boolean,
    onSongClick: (Int) -> Unit,
    onFavoriteClick: (Song) -> Unit
) {
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        itemsIndexed(songs) { index, song ->
            SongListItem(
                song = song,
                onClick = { onSongClick(index) },
                favoriteMode = SongItemFavoriteMode.TOGGLE,
                isFavorite = song.id in favoriteIds,
                onFavoriteClick = { onFavoriteClick(song) }
            )
        }
        if (isLoadingMore) {
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

@Composable
private fun HotSearchSection(hotTags: List<String>, onTagClick: (String) -> Unit) {
    if (hotTags.isNotEmpty()) {
        Text(
            text = "热门搜索",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(hotTags) { tag ->
                HotTagChip(tag) { onTagClick(tag) }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "输入关键词搜索歌曲",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun HotTagChip(tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "hotTagChipScale"
    )

    Text(
        text = tag,
        fontSize = 14.sp,
        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
        color = if (isFocused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

