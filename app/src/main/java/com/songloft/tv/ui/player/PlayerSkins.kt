package com.songloft.tv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songloft.tv.ui.theme.PlayerColors
import com.songloft.tv.util.AppText as Text

/** 海报皮肤：左侧满高海报 + 右侧详情面板。无封面时回退到氛围底色 + 黑胶唱盘。 */
@Composable
fun PosterPlayerSkin(
    artwork: PlayerArtwork,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val hasPoster = artwork.bitmap != null
    if (hasPoster) {
        PosterBackdrop(
            artwork.bitmap!!,
            artwork.ambience,
            artwork.posterSurface,
            artwork.posterBrightness
        )
    } else {
        AmbientBackdrop(artwork.ambience)
    }

    Row(modifier.fillMaxSize()) {
        // 海报已铺满左侧，此处仅留白；无封面时显示黑胶唱盘
        Box(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight()
                .padding(start = 56.dp, end = 8.dp, top = 40.dp, bottom = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!hasPoster) {
                VinylDisc(
                    coverUrl = null,
                    isPlaying = uiState.isPlaying,
                    modifier = Modifier.sizeIn(maxWidth = 380.dp, maxHeight = 380.dp)
                )
            }
        }

        // 右侧：标题 / 音质徽章 / 歌手 / 歌词
        Column(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight()
                .padding(start = 8.dp, end = 64.dp, top = 72.dp, bottom = 40.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = uiState.currentSong?.title ?: "",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = PlayerColors.TextPrimary,
                    maxLines = 1,
                    // 占满剩余宽度 + 跑马灯：短名不滚、超长才来回滚动
                    modifier = Modifier.weight(1f).basicMarquee()
                )
                val format = uiState.currentSong?.format
                if (!format.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    FormatBadge(format)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = uiState.currentSong?.artist ?: "",
                fontSize = 17.sp,
                color = PlayerColors.TextSecondary,
                maxLines = 1,
                // 歌手名同样：超长跑马灯，短名保持原样
                modifier = Modifier.fillMaxWidth().basicMarquee()
            )

            Spacer(Modifier.height(40.dp))

            LyricsPanel(
                lyrics = uiState.lyrics,
                currentIndex = uiState.currentLyricIndex,
                currentPosition = uiState.currentPosition,
                highlightColor = lyricHighlightColor(uiState),
                fontSize = uiState.lyricFontSize,
                // 顶部有歌名/歌手占位，高亮行上移到距歌词区顶部 96dp
                focusTopDp = 96,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 经典大屏皮肤：模糊封面背景 + 左侧方封面/歌名 + 右侧居中歌词。 */
@Composable
fun ClassicPlayerSkin(
    artwork: PlayerArtwork,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val bitmap = artwork.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp)
        )
        Box(Modifier.fillMaxSize().background(PlayerColors.Scrim))
    } else {
        AmbientBackdrop(artwork.ambience)
    }

    Row(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .sizeIn(maxWidth = 300.dp, maxHeight = 300.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(54.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = uiState.currentSong?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxSize(0.4f)
                        )
                    }
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
        }

        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            LyricsPanel(
                lyrics = uiState.lyrics,
                currentIndex = uiState.currentLyricIndex,
                currentPosition = uiState.currentPosition,
                highlightColor = lyricHighlightColor(uiState),
                fontSize = uiState.lyricFontSize
            )
        }
    }
}

/** 两种皮肤共用的歌词高亮色解析：1/默认白色，2 跟随主题色。 */
@Composable
private fun lyricHighlightColor(uiState: PlayerUiState): Color =
    when (uiState.lyricHighlightColor) {
        2 -> MaterialTheme.colorScheme.primary
        else -> PlayerColors.TextPrimary
    }
