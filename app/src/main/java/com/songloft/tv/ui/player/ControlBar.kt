package com.songloft.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songloft.tv.domain.PlayMode
import com.songloft.tv.ui.theme.PlayerColors
import com.songloft.tv.util.AppText as Text
import androidx.compose.ui.focus.FocusRequester

@Composable
fun ControlBar(
    uiState: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleSound: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit = {},
    onCycleAudioTrack: () -> Unit = {},  // 保留以便支持多音轨歌曲
    onRefreshLyrics: () -> Unit = {},
    onEnterKaraokeMode: () -> Unit = {},  // K 歌入口回调
    isLyricRefreshing: Boolean = false,
    playPauseFocusRequester: FocusRequester? = null,
    micButtonFocusRequester: FocusRequester? = null,
    queueButtonFocusRequester: FocusRequester? = null,
    soundButtonFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val isRadio = uiState.currentSong?.type == "radio"
    val progress = if (uiState.duration > 0) {
        uiState.currentPosition.toFloat() / uiState.duration.toFloat()
    } else 0f

    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x99090807),
                        Color(0xE6080807)
                    )
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 22.dp, bottom = 20.dp)
    ) {
        // 进度条（上方）+ 时间标签（下方），对齐参考项目
        SeekBar(
            progress = progress,
            onSeekBy = { delta ->
                val target = (uiState.currentPosition + delta)
                    .coerceIn(0L, uiState.duration.coerceAtLeast(0L))
                onSeek(target)
            },
            onSeekTo = { ratio ->
                val target = (ratio * uiState.duration.toFloat())
                    .toLong()
                    .coerceIn(0L, uiState.duration.coerceAtLeast(0L))
                onSeek(target)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(uiState.currentPosition),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = formatTime(uiState.duration),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
        }

        Spacer(Modifier.height(14.dp))

        // 三区布局：左侧辅助 / 中央播放 / 右侧功能
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左区：收藏 + 播放模式（radio 无队列顺序概念，隐藏模式键）
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                TransportButton(
                    icon = if (uiState.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "收藏",
                    onClick = onToggleFavorite
                )
                if (!isRadio) {
                    Spacer(Modifier.width(12.dp))
                    val modeIcon = when (uiState.playMode) {
                        PlayMode.ORDER -> Icons.AutoMirrored.Rounded.PlaylistPlay
                        PlayMode.LOOP -> Icons.Rounded.Repeat
                        PlayMode.SINGLE -> Icons.Rounded.RepeatOne
                        PlayMode.RANDOM -> Icons.Rounded.Shuffle
                    }
                    TransportButton(modeIcon, "播放模式", onCyclePlayMode)
                }
            }

            // 中央区：上一曲 / 播放暂停 / 下一曲（播放按钮强调）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                TransportButton(Icons.Rounded.SkipPrevious, "上一曲", onPrevious)
                val playIcon = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                TransportButton(
                    playIcon,
                    if (uiState.isPlaying) "暂停" else "播放",
                    onPlayPause,
                    isLarge = true,
                    emphasized = true,
                    focusRequester = playPauseFocusRequester
                )
                TransportButton(Icons.Rounded.SkipNext, "下一曲", onNext)
            }

            // 右区：重新获取歌词 / K 歌 / 音效 / 队列
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (!isRadio) {
                    TransportButton(
                        Icons.Rounded.Refresh,
                        "重新获取歌词",
                        onRefreshLyrics,
                        loading = isLyricRefreshing
                    )
                    Spacer(Modifier.width(12.dp))
                    TransportButton(
                        Icons.Rounded.Mic,
                        "K 歌模式",
                        onClick = onEnterKaraokeMode,
                        focusRequester = micButtonFocusRequester
                    )
                }
                if (onToggleSound != null && !isRadio) {
                    Spacer(Modifier.width(12.dp))
                    TransportButton(
                        Icons.Rounded.GraphicEq,
                        "音效",
                        onToggleSound,
                        focusRequester = soundButtonFocusRequester
                    )
                }
                Spacer(Modifier.width(12.dp))
                TransportButton(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "播放队列",
                    onToggleQueue,
                    focusRequester = queueButtonFocusRequester
                )
            }
        }
    }
}
