package com.songloft.tv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.songloft.tv.util.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.components.PinBadge
import com.songloft.tv.data.api.StatsSummary
import com.songloft.tv.data.model.FacetItem
import com.songloft.tv.data.model.Playlist
import com.songloft.tv.data.repository.StatsRange
import com.songloft.tv.ui.navigation.DefaultFocusEffect
import com.songloft.tv.ui.navigation.ListBackToTopHandler
import com.songloft.tv.ui.navigation.RestoreFocusEffect
import com.songloft.tv.ui.navigation.ScreenFocusRestorer
import com.songloft.tv.ui.navigation.rememberScreenFocusRestorer
import com.songloft.tv.ui.navigation.restorableFocus
import com.songloft.tv.ui.stats.formatDuration
import com.songloft.tv.ui.theme.SelectedFocusBorder
import kotlinx.coroutines.ensureActive

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onPlaylistClick: (Long) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onYearClick: (Int) -> Unit = {},
    onViewAll: (String) -> Unit = {},
    onManagePlaylists: () -> Unit = {},
    onStatsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val restorer = rememberScreenFocusRestorer()
    var manageHasFocus by remember { mutableStateOf(false) }

    ListBackToTopHandler(listState, topFocus, topFocusInList = true)
    RestoreFocusEffect(restorer)
    DefaultFocusEffect(restorer, topFocus)

    // 焦点落到顶部「管理歌单」时滚回列表顶，露出上方不可聚焦的标题和统计卡片；
    // 焦点系统自带的 bringIntoView 滚动会取消单次 scrollToItem，故按帧重试直到到顶
    LaunchedEffect(manageHasFocus) {
        if (!manageHasFocus) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (!listState.canScrollBackward) return@LaunchedEffect
            runCatching { listState.scrollToItem(0) }
            ensureActive()
        }
    }

    val retryFocus = remember { FocusRequester() }

    // 数据到达前不渲染列表，避免空数据把静态标题挤在一起；
    // 此时 DefaultFocusEffect 的帧重试已超时，加载完成后需重新请求默认焦点；
    // 有待恢复焦点（从子界面返回）时让位给 RestoreFocusEffect，否则会抢走恢复目标
    LaunchedEffect(uiState.isLoading, uiState.error) {
        if (uiState.isLoading || restorer.pendingKey != null) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            val target = if (uiState.error != null) retryFocus else topFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "加载中...",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    if (uiState.error != null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "加载失败：${uiState.error}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(16.dp))
                var retryFocused by remember { mutableStateOf(false) }
                Text(
                    text = "重试",
                    fontSize = 14.sp,
                    fontWeight = if (retryFocused) FontWeight.Bold else FontWeight.Normal,
                    color = if (retryFocused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (retryFocused) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .onFocusChanged { retryFocused = it.isFocused }
                        .clickable { viewModel.refresh() }
                        .padding(8.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        item {
            Text(
                text = "曲库概览",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            StatsRow(uiState)
        }

        item {
            PlaylistSection(
                playlists = uiState.playlists,
                pinnedIds = uiState.pinnedIds,
                onPlaylistClick = { id ->
                    restorer.record("playlist:$id")
                    onPlaylistClick(id)
                },
                onManagePlaylists = {
                    restorer.record("manage_playlists")
                    onManagePlaylists()
                },
                manageFocusRequester = topFocus,
                onManageFocusChanged = { manageHasFocus = it },
                restorer = restorer
            )
        }

        item {
            ArtistsAlbumsRow(
                artists = uiState.topArtists,
                albums = uiState.topAlbums,
                onArtistClick = { artist ->
                    restorer.record("artist:$artist")
                    onArtistClick(artist)
                },
                onAlbumClick = { album ->
                    restorer.record("album:$album")
                    onAlbumClick(album)
                },
                onViewAll = { field ->
                    restorer.record("viewall:$field")
                    onViewAll(field)
                },
                restorer = restorer
            )
        }

        item {
            when {
                uiState.statsLoading -> StatsOverviewPlaceholder()
                uiState.statsAvailable -> StatsOverviewSection(
                    summaries = uiState.statsSummaries,
                    loadingRanges = uiState.statsLoadingRanges,
                    onRangeSelected = { viewModel.loadStatsRange(it) },
                    onOpenStats = {
                        restorer.record("viewall:stats")
                        onStatsClick()
                    },
                    restorer = restorer
                )
                else -> YearSection(
                    years = uiState.topYears,
                    onYearClick = { year ->
                        restorer.record("year:$year")
                        onYearClick(year)
                    },
                    onViewAllYears = {
                        restorer.record("viewall:year")
                        onViewAll("year")
                    },
                    restorer = restorer
                )
            }
        }
    }
}

@Composable
private fun StatsRow(uiState: HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatCard(label = "全部歌曲", value = "${uiState.totalSongs}")
        StatCard(label = "本地歌曲", value = "${uiState.localSongs}")
        StatCard(label = "总时长", value = uiState.totalDuration.ifEmpty { "--" })
        StatCard(label = "文件大小", value = uiState.totalSize.ifEmpty { "--" })
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PlaylistSection(
    playlists: List<Playlist>,
    pinnedIds: Set<Long>,
    onPlaylistClick: (Long) -> Unit,
    onManagePlaylists: () -> Unit,
    restorer: ScreenFocusRestorer,
    manageFocusRequester: FocusRequester? = null,
    onManageFocusChanged: (Boolean) -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "置顶歌单",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            SectionLink(
                text = "查看全部",
                onClick = onManagePlaylists,
                focusRequester = manageFocusRequester,
                modifier = Modifier
                    .restorableFocus(restorer, "manage_playlists")
                    .onFocusChanged { onManageFocusChanged(it.isFocused) }
            )
        }
        Spacer(Modifier.height(12.dp))

        if (playlists.isEmpty()) {
            Text(
                text = "还没有歌单",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            val rows = playlists.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                isPinned = playlist.id in pinnedIds,
                                onClick = { onPlaylistClick(playlist.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .restorableFocus(restorer, "playlist:${playlist.id}")
                            )
                        }
                        repeat(4 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(150),
        label = "playlistScale"
    )

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
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
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

@Composable
private fun ArtistsAlbumsRow(
    artists: List<FacetItem>,
    albums: List<FacetItem>,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onViewAll: (String) -> Unit,
    restorer: ScreenFocusRestorer
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CategoryColumn(
            title = "主要歌手",
            items = artists,
            keyPrefix = "artist",
            restorer = restorer,
            onClick = onArtistClick,
            onViewAll = { onViewAll("artist") },
            modifier = Modifier.weight(1f)
        )
        CategoryColumn(
            title = "主要专辑",
            items = albums,
            keyPrefix = "album",
            restorer = restorer,
            onClick = onAlbumClick,
            onViewAll = { onViewAll("album") },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CategoryColumn(
    title: String,
    items: List<FacetItem>,
    keyPrefix: String,
    restorer: ScreenFocusRestorer,
    onClick: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            SectionLink(
                text = "查看全部",
                onClick = onViewAll,
                modifier = Modifier.restorableFocus(restorer, "viewall:$keyPrefix")
            )
        }
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                CategoryCard(
                    item = item,
                    onClick = { onClick(item.value) },
                    modifier = Modifier.restorableFocus(restorer, "$keyPrefix:${item.value}")
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    item: FacetItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "categoryScale"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else Color.Transparent
            )
            .then(
                if (isFocused) Modifier.border(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverImage(
                url = item.coverUrl,
                contentDescription = item.value,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${item.count} 首歌曲",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun StatsOverviewSection(
    summaries: Map<StatsRange, StatsSummary>,
    loadingRanges: Set<StatsRange>,
    onRangeSelected: (StatsRange) -> Unit,
    onOpenStats: () -> Unit,
    restorer: ScreenFocusRestorer
) {
    var selectedRangeName by rememberSaveable { mutableStateOf(StatsRange.ALL.name) }
    val selectedRange = runCatching { StatsRange.valueOf(selectedRangeName) }.getOrDefault(StatsRange.ALL)
    val summary = summaries[selectedRange]
    val rangeLoading = selectedRange in loadingRanges
    val viewAllFocus = remember { FocusRequester() }
    val monthTabFocus = remember { FocusRequester() }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "播放统计",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            SectionLink(
                text = "查看全部",
                onClick = onOpenStats,
                focusRequester = viewAllFocus,
                modifier = Modifier
                    .restorableFocus(restorer, "viewall:stats")
                    .focusProperties { left = monthTabFocus }
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsRange.entries.forEach { range ->
                HomeRangeTab(
                    label = range.label,
                    isSelected = range == selectedRange,
                    onClick = {
                        selectedRangeName = range.name
                        onRangeSelected(range)
                    },
                    modifier = Modifier
                        .restorableFocus(restorer, "statsrange:${range.name}")
                        .then(
                            if (range == StatsRange.MONTH) Modifier
                                .focusRequester(monthTabFocus)
                                .focusProperties { right = viewAllFocus }
                            else Modifier
                        )
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatsOverviewCard(
                label = "播放次数",
                value = if (rangeLoading && summary == null) "加载中" else summary?.totalPlays?.let { "$it" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatsOverviewCard(
                label = "听歌时长",
                value = if (rangeLoading && summary == null) "加载中" else summary?.let { formatDuration(it.totalDurationSec) } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatsOverviewCard(
                label = "不同歌曲",
                value = if (rangeLoading && summary == null) "加载中" else summary?.uniqueSongs?.let { "$it" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatsOverviewCard(
                label = "不同艺术家",
                value = if (rangeLoading && summary == null) "加载中" else summary?.uniqueArtists?.let { "$it" } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HomeRangeTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "homeRangeTabScale"
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
        modifier = modifier
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
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp)
    )
}

@Composable
private fun StatsOverviewCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun StatsOverviewPlaceholder() {
    Column {
        Text(
            text = "播放统计",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun YearSection(
    years: List<FacetItem>,
    onYearClick: (Int) -> Unit,
    onViewAllYears: () -> Unit,
    restorer: ScreenFocusRestorer
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "年份速览",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            SectionLink(
                text = "查看全部",
                onClick = onViewAllYears,
                modifier = Modifier.restorableFocus(restorer, "viewall:year")
            )
        }
        Spacer(Modifier.height(12.dp))

        if (years.isEmpty()) {
            Text(
                text = "暂无年份数据",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(years) { year ->
                    YearCard(
                        year = year,
                        onClick = {
                            year.value.toIntOrNull()?.let { onYearClick(it) }
                        },
                        modifier = Modifier.restorableFocus(restorer, "year:${year.value}")
                    )
                }
            }
        }
    }
}

@Composable
private fun YearCard(
    year: FacetItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(150),
        label = "yearScale"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .then(
                if (isFocused) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = year.value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "· ${year.count} 首",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SectionLink(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "sectionLinkScale"
    )

    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
