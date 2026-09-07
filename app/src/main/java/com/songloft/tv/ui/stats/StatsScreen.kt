package com.songloft.tv.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.songloft.tv.util.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.data.api.StatsHourlyPoint
import com.songloft.tv.data.api.StatsHistoryRecord
import com.songloft.tv.data.api.StatsRankEntry
import com.songloft.tv.data.api.StatsSongEntry
import com.songloft.tv.data.api.StatsSummary
import com.songloft.tv.data.api.StatsTrendPoint
import com.songloft.tv.data.repository.StatsRange
import com.songloft.tv.ui.navigation.ListBackToTopHandler
import com.songloft.tv.ui.navigation.RestoreFocusEffect
import com.songloft.tv.ui.navigation.rememberScreenFocusRestorer
import com.songloft.tv.ui.navigation.restorableFocus
import com.songloft.tv.ui.theme.SelectedFocusBorder
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val restorer = rememberScreenFocusRestorer()
    var backButtonHasFocus by remember { mutableStateOf(false) }

    ListBackToTopHandler(listState, topFocus, topFocusHasFocus = backButtonHasFocus)
    RestoreFocusEffect(restorer)

    LaunchedEffect(Unit) {
        if (restorer.pendingKey == null) runCatching { topFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack, focusRequester = topFocus, onFocusChanged = { backButtonHasFocus = it })
            Spacer(Modifier.width(16.dp))
            Text(
                text = "播放统计",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(16.dp))

        when {
            uiState.isLoading && uiState.summary == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RangeTabs(
                            range = uiState.range,
                            onRangeClick = { viewModel.selectRange(it) },
                            restorer = restorer
                        )
                        if (uiState.error != null) {
                            Text(
                                text = "加载失败：${uiState.error}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        SummaryCards(summary = uiState.summary)
                    }
                }

                item {
                    PanelRow {
                        RankCard(
                            title = "艺术家排行",
                            entries = uiState.summary?.topArtists.orEmpty().take(4),
                            restorer = restorer,
                            keyPrefix = "artist",
                            onRefresh = viewModel::refreshSummary,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        SongRankCard(
                            title = "歌曲排行",
                            entries = uiState.summary?.topSongs.orEmpty().take(3),
                            restorer = restorer,
                            keyPrefix = "song",
                            onRefresh = viewModel::refreshSummary,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }

                item {
                    ColumnCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CardTitle("听歌趋势")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrendToggle(
                                    label = "7天",
                                    isSelected = uiState.trendsDays == 7,
                                    onClick = { viewModel.selectTrendDays(7) },
                                    modifier = Modifier.restorableFocus(restorer, "trend:7")
                                )
                                TrendToggle(
                                    label = "30天",
                                    isSelected = uiState.trendsDays == 30,
                                    onClick = { viewModel.selectTrendDays(30) },
                                    modifier = Modifier.restorableFocus(restorer, "trend:30")
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        TrendChart(points = uiState.trends, compact = uiState.trendsDays == 30)
                    }
                }

                item {
                    PanelRow {
                        RankCard(
                            title = "专辑排行",
                            entries = uiState.summary?.topAlbums.orEmpty().take(3),
                            restorer = restorer,
                            keyPrefix = "album",
                            onRefresh = viewModel::refreshSummary,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        ColumnCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            CardHeader("时段分布", onRefresh = viewModel::refreshHourly)
                            Spacer(Modifier.height(12.dp))
                            HourlyDistribution(points = uiState.hourly)
                        }
                    }
                }

                item {
                    PanelRow {
                        ColumnCard(modifier = Modifier.weight(1f)) {
                            CardHeader("来源分布", onRefresh = viewModel::refreshSummary)
                            Spacer(Modifier.height(12.dp))
                            KeyCountList(
                                entries = uiState.summary?.bySource.orEmpty().mapNotNull { (k, v) ->
                                    sourceLabel(k) to v
                                }.sortedByDescending { it.second }.take(3)
                            )
                        }
                        ColumnCard(modifier = Modifier.weight(1f)) {
                            CardHeader("歌曲类型", onRefresh = viewModel::refreshSummary)
                            Spacer(Modifier.height(12.dp))
                            KeyCountList(
                                entries = uiState.summary?.byMediaType.orEmpty().let { raw ->
                                    listOfNotNull(
                                        raw["local"]?.let { "本地" to it },
                                        raw["remote"]?.let { "网络" to it },
                                        raw["radio"]?.let { "电台" to it },
                                        raw["unknown"]?.let { "未知" to it }
                                    ).sortedByDescending { it.second }.take(3)
                                }
                            )
                        }
                    }
                }

                item {
                    HistoryCard(
                        records = uiState.history,
                        onRefresh = viewModel::refreshHistory
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeTabs(
    range: StatsRange,
    onRangeClick: (StatsRange) -> Unit,
    restorer: com.songloft.tv.ui.navigation.ScreenFocusRestorer
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatsRange.entries.forEach { r ->
            TabChip(
                label = r.label,
                isSelected = range == r,
                onClick = { onRangeClick(r) },
                modifier = Modifier.restorableFocus(restorer, "range:${r.name}")
            )
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "statsTabChipScale"
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
private fun SummaryCards(summary: StatsSummary?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SummaryCard(
            label = "播放次数",
            value = summary?.totalPlays?.let { "$it" } ?: "--",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "听歌时长",
            value = summary?.let { formatDuration(it.totalDurationSec) } ?: "--",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "不同歌曲",
            value = summary?.uniqueSongs?.let { "$it" } ?: "--",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "不同艺术家",
            value = summary?.uniqueArtists?.let { "$it" } ?: "--",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
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
private fun PanelRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        content()
    }
}

@Composable
private fun ColumnCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun CardTitle(title: String) {
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** 卡片标题行，右侧带可聚焦的刷新按钮，保证纯展示卡片也有 D-Pad 可选中入口 */
@Composable
private fun CardHeader(title: String, onRefresh: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardTitle(title)
        if (onRefresh != null) RefreshButton(onClick = onRefresh)
    }
}

@Composable
private fun RefreshButton(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Text(
        text = "刷新",
        fontSize = 13.sp,
        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun RankCard(
    title: String,
    entries: List<StatsRankEntry>,
    restorer: com.songloft.tv.ui.navigation.ScreenFocusRestorer,
    keyPrefix: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    ColumnCard(modifier) {
        CardHeader(title, onRefresh)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            EmptyHint("暂无数据")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEachIndexed { index, entry ->
                    RankRow(
                        rank = index + 1,
                        name = entry.artist ?: entry.album ?: "--",
                        count = entry.plays,
                        modifier = Modifier.restorableFocus(restorer, "$keyPrefix:${index + 1}")
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRankCard(
    title: String,
    entries: List<StatsSongEntry>,
    restorer: com.songloft.tv.ui.navigation.ScreenFocusRestorer,
    keyPrefix: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    ColumnCard(modifier) {
        CardHeader(title, onRefresh)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            EmptyHint("暂无数据")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEachIndexed { index, entry ->
                    SongRankRow(
                        rank = index + 1,
                        entry = entry,
                        modifier = Modifier.restorableFocus(restorer, "$keyPrefix:${index + 1}")
                    )
                }
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, name: String, count: Int, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (rank <= 3) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count 次",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SongRankRow(rank: Int, entry: StatsSongEntry, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (rank <= 3) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title ?: "--",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!entry.artist.isNullOrBlank()) {
                Text(
                    text = entry.artist,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${entry.plays} 次",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TrendToggle(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(120),
        label = "trendToggleScale"
    )

    Text(
        text = if (isSelected) "✓ $label" else label,
        fontSize = 13.sp,
        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
        color = when {
            isSelected -> MaterialTheme.colorScheme.onPrimary
            isFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primary
        },
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                }
            )
            .then(
                if (isFocused) Modifier.border(
                    3.dp,
                    if (isSelected) SelectedFocusBorder else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun TrendChart(points: List<StatsTrendPoint>, compact: Boolean) {
    if (points.isEmpty()) {
        EmptyHint("暂无数据")
        return
    }
    val maxCount = points.maxOf { it.count }.coerceAtLeast(1)
    val labelFont = if (compact) 9.sp else 11.sp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            points.forEach { point ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (point.count > 0) "${point.count}" else "",
                        fontSize = labelFont,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height((20 + (point.count.toFloat() / maxCount) * 76).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (point.count == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            points.forEach { point ->
                Text(
                    text = point.date,
                    fontSize = labelFont,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HourlyDistribution(points: List<StatsHourlyPoint>) {
    if (points.isEmpty()) {
        EmptyHint("暂无数据")
        return
    }
    val total = points.sumOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        points.forEach { point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = point.label,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.width(40.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((point.count.toFloat() / total).coerceIn(0.02f, 1f))
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${point.count}首",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

@Composable
private fun KeyCountList(entries: List<Pair<String, Int>>) {
    if (entries.isEmpty()) {
        EmptyHint("暂无数据")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (name, count) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$count 次",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    records: List<StatsHistoryRecord>,
    onRefresh: () -> Unit
) {
    ColumnCard {
        CardHeader("最近播放", onRefresh = onRefresh)
        Spacer(Modifier.height(12.dp))
        if (records.isEmpty()) {
            EmptyHint("暂无播放记录，开始听歌吧")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                records.forEach { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${record.artist} — ${record.title}",
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "${formatTime(record.timestamp)} · ${mediaLabel(record.type)} · ${sourceLabel(record.source)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 12.dp)
    )
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

// ── 格式化 ──────────────────────────────────────────────────────────────────

fun formatDuration(sec: Double): String {
    if (sec <= 0) return "--"
    val totalSec = sec.roundToInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "$h 小时 $m 分"
        m > 0 -> "$m 分钟"
        else -> "$totalSec 秒"
    }
}

internal fun formatTime(timestamp: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp

    val today = Calendar.getInstance()
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    val todayStart = today.timeInMillis
    val dayStart = with(Calendar.getInstance()) {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    return when {
        dayStart == todayStart -> "今天 $time"
        dayStart == todayStart - 86_400_000L -> "昨天 $time"
        else -> "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $time"
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "songloft-player" -> "客户端"
    "tv" -> "TV 端"
    "miot" -> "智能音箱"
    "web" -> "网页端"
    "mobile" -> "手机端"
    "airplay" -> "AirPlay"
    "bluetooth" -> "蓝牙"
    "unknown" -> "未知"
    else -> source.ifBlank { "未知" }
}

private fun mediaLabel(type: String?): String = when (type) {
    "local" -> "本地"
    "remote" -> "网络"
    "radio" -> "电台"
    else -> "未知"
}
