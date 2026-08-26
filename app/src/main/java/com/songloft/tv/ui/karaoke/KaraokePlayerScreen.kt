package com.songloft.tv.ui.karaoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import com.songloft.tv.data.api.UrlHelper
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.player.PlayerUiState
import com.songloft.tv.ui.player.TransportButton
import com.songloft.tv.ui.theme.PlayerColors

/**
 * 独立维护的 K 歌全屏播放界面。
 *
 * 与主播放器保持一致的视觉语言：
 * - 放大封面模糊作背景（同主播放器）
 * - 左上角返回按钮（同主播放器）
 * - 底部功能键复用主播放器 [com.songloft.tv.ui.player.TransportButton] 样式
 * - 右上角"扫码点歌"二维码（独立入口）
 */
@Composable
fun KaraokePlayerScreen(
    uiState: PlayerUiState,
    orderUrl: String?,
    accompanimentOn: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onToggleAccompaniment: () -> Unit,
    onToggleQueue: () -> Unit,
    playPauseFocusRequester: FocusRequester? = null,
    backButtonFocusRequester: FocusRequester? = null
) {
    val showControls = uiState.showControls
    val backFocusRequester = backButtonFocusRequester ?: remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().background(PlayerColors.Background)) {
        // 背景：放大封面模糊（同主播放器音乐模式）
        UrlHelper.resolve(uiState.currentSong?.coverUrl)?.let { cover ->
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(60.dp),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(PlayerColors.Scrim))
        }

        // 中部：歌词 + 歌曲信息
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(96.dp))

            // 歌曲信息（与主播放器左侧封面区同款样式）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .sizeIn(maxWidth = 220.dp, maxHeight = 220.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(40.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CoverImage(
                        url = uiState.currentSong?.coverUrl,
                        contentDescription = uiState.currentSong?.title,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = uiState.currentSong?.title ?: "",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PlayerColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.currentSong?.artist ?: "",
                    fontSize = 14.sp,
                    color = PlayerColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(32.dp))

            // 双行歌词
            KaraokeLyricsView(
                lyrics = uiState.lyrics,
                progressMs = uiState.currentPosition,
                isPlaying = uiState.isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .fillMaxHeight(0.55f)
            )
        }

        // 左上角返回按钮（同主播放器）
        if (showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                TransportButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    focusRequester = backFocusRequester
                )
            }
        }

        // 右上角扫码点歌二维码
        orderUrl?.let { url ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                KaraokeQrCode(url = url)
            }
        }

        // 底部控制栏（同主播放器样式）
        if (showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                KaraokeControlBar(
                    uiState = uiState,
                    accompanimentOn = accompanimentOn,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onSeekBy = onSeekBy,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleFavorite = onToggleFavorite,
                    onRefreshLyrics = onRefreshLyrics,
                    onToggleAccompaniment = onToggleAccompaniment,
                    onToggleQueue = onToggleQueue,
                    playPauseFocusRequester = playPauseFocusRequester,
                    backButtonFocusRequester = backFocusRequester
                )
            }
        }
    }
}
