package com.songloft.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.songloft.tv.data.model.Song
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.key.key

/**
 * KTV 全屏卡拉 OK 播放界面
 *
 * 布局结构（KTV 经典双行）：
 * - 全屏封面背景 + 暗色渐变遮罩
 * - 中上部：歌曲名 + 歌手名
 * - 中部下方：双行歌词视图（半透明黑色框内）
 * - 底部：左下角返回键 + 右下角控制栏（上一首/暂停/下一首/原唱伴唱）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokePlaybackScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    lyrics: List<com.songloft.tv.data.model.LyricLine>?,
    coverUrl: String? = null,
    progressMs: Long = 0L,
    durationMs: Long = 0L,
    vocalRemovalEnabled: Boolean = false,
    onToggleVocalRemoval: () -> Unit = {},
    onExitKaraoke: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    playPauseFocusRequester: FocusRequester? = null
) {
    // 5 秒自动虚化逻辑
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun activateControls() {
        controlsVisible = true
        lastInteraction = System.currentTimeMillis()
    }

    LaunchedEffect(lastInteraction) {
        controlsVisible = true
        delay(5000)
        if (System.currentTimeMillis() - lastInteraction >= 5000L) {
            controlsVisible = false
        }
    }

    val controlAlpha = if (controlsVisible) 1f else 0.15f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .onFocusChanged { if (it.hasFocus) activateControls() }
            .pointerInput(Unit) { detectAnyKeyInput { activateControls() } }
    ) {
        // 背景图片（专辑封面模糊）
        coverUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.3f)
            )
        }

        // 暗色渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC0C1222),
                            Color(0x990C1222),
                            Color(0xCC0C1222)
                        )
                    )
                )
        )

        // 前景内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部：歌曲信息
            Column(modifier = Modifier.padding(top = 48.dp)) {
                Text(
                    text = currentSong?.title ?: "",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = currentSong?.artist ?: "",
                    fontSize = 20.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            // 下部：歌词区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
            ) {
                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp)
                )

                // 进度细线
                if (durationMs > 0L) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .height(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color.Gray.copy(alpha = 0.4f))
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressMs.toFloat() / durationMs.coerceAtLeast(1))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color.Cyan)
                        )
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
        }

        // 底部控制栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左下角：返回按钮
            MiniIconButton(
                onClick = { activateControls(); onExitKaraoke() },
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.alpha(controlAlpha)
            )

            // 右下角：控制栏
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniIconButton(
                    onClick = { activateControls(); onPrevious() },
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.alpha(controlAlpha)
                )

                Spacer(Modifier.width(20.dp))

                MiniIconButton(
                    onClick = { activateControls(); onPlayPause() },
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    primary = true,
                    focusRequester = playPauseFocusRequester,
                    modifier = Modifier.alpha(controlAlpha)
                )

                Spacer(Modifier.width(20.dp))

                MiniIconButton(
                    onClick = { activateControls(); onNext() },
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.alpha(controlAlpha)
                )

                Spacer(Modifier.width(20.dp))

                // 原唱/伴唱切换
                VocalToggleButton(
                    label = if (vocalRemovalEnabled) "原唱" else "伴唱",
                    onClick = { activateControls(); onToggleVocalRemoval() },
                    modifier = Modifier.alpha(controlAlpha)
                )
            }
        }
    }

    // 进入页面时聚焦播放/暂停按钮
    LaunchedEffect(Unit) {
        try {
            playPauseFocusRequester?.requestFocus()
        } catch (_: Exception) {}
    }
}

/**
 * 迷你 Surface 按钮（圆形）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val buttonSize = if (primary) 72.dp else 56.dp
    val iconSize = if (primary) 36.dp else 28.dp

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(50)),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(50),
            focusedShape = RoundedCornerShape(50)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.Cyan else Color.White,
            contentColor = if (primary) Color.Black else Color.Black,
            focusedContainerColor = if (primary) Color.Cyan.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.3f),
            focusedContentColor = Color.Black
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

private fun detectAnyKeyInput(action: () -> Unit): (KeyEvent) -> Boolean = { event ->
    val isNavigationKey = when (event.key) {
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
        Key.Enter, Key.Back, Key.MediaPlayPause,
        Key.VolumeUp, Key.VolumeDown -> true
        else -> false
    }

    if (isNavigationKey) {
        action()
        false
    } else {
        false
    }
}

/**
 * 原唱/伴唱切换按钮（KTV 风格）
 */
@Composable
private fun VocalToggleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonSize = 64.dp

    Surface(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(32.dp),
            focusedShape = RoundedCornerShape(32.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.Cyan.copy(alpha = 0.3f),
            focusedContentColor = Color.White
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}
