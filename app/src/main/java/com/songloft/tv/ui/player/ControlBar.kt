package com.songloft.tv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songloft.tv.domain.PlayMode
import com.songloft.tv.ui.theme.PlayerColors

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
    Column(
        modifier = modifier
            .background(PlayerColors.BarBackground)
            .padding(horizontal = 48.dp, vertical = 16.dp)
    ) {
        val progress = if (uiState.duration > 0) {
            uiState.currentPosition.toFloat() / uiState.duration.toFloat()
        } else 0f

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(uiState.currentPosition),
                fontSize = 12.sp,
                color = PlayerColors.TextTertiary
            )

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
                modifier = Modifier.weight(1f)
            )

            Text(
                text = formatTime(uiState.duration),
                fontSize = 12.sp,
                color = PlayerColors.TextTertiary
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportButton(Icons.Rounded.SkipPrevious, "上一曲", onPrevious)
            val playIcon = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
            TransportButton(
                playIcon,
                if (uiState.isPlaying) "暂停" else "播放",
                onPlayPause,
                isLarge = true,
                focusRequester = playPauseFocusRequester
            )
            TransportButton(Icons.Rounded.SkipNext, "下一曲", onNext)
            // 电台是持续流媒体，没有队列顺序/循环概念，隐藏播放模式键
            if (uiState.currentSong?.type != "radio") {
                val modeIcon = when (uiState.playMode) {
                    PlayMode.ORDER -> Icons.AutoMirrored.Rounded.PlaylistPlay
                    PlayMode.LOOP -> Icons.Rounded.Repeat
                    PlayMode.SINGLE -> Icons.Rounded.RepeatOne
                    PlayMode.RANDOM -> Icons.Rounded.Shuffle
                }
                TransportButton(modeIcon, "播放模式", onCyclePlayMode)
            }
            TransportButton(
                if (uiState.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "收藏",
                onToggleFavorite
            )
            // 电台是持续流媒体，无歌词概念，隐藏重新获取歌词键
            if (uiState.currentSong?.type != "radio") {
                TransportButton(
                    Icons.Rounded.Refresh,
                    "重新获取歌词",
                    onRefreshLyrics,
                    loading = isLyricRefreshing
                )
            }
            // === K 歌入口独立按钮（麦克风图标）===
            if (uiState.currentSong?.type != "radio") {
                TransportButton(
                    Icons.Rounded.Mic,
                    "K 歌模式",
                    onClick = onEnterKaraokeMode,
                    focusRequester = micButtonFocusRequester
                )
            }
            // 电台是持续流媒体，音效无意义，隐藏音效键（均衡器/音效任一开启才显示入口）
            if (onToggleSound != null && uiState.currentSong?.type != "radio") {
                TransportButton(
                    Icons.Rounded.GraphicEq,
                    "音效",
                    onToggleSound,
                    focusRequester = soundButtonFocusRequester
                )
            }
            TransportButton(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列", onToggleQueue, focusRequester = queueButtonFocusRequester)
        }
    }
}
