package com.songloft.tv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.songloft.tv.ui.theme.PlayerColors

private const val SEEK_STEP_MS = 10_000L

@Composable
fun SeekBar(
    progress: Float,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Float) -> Unit = {},
    interactive: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var trackWidth by remember { mutableIntStateOf(0) }

    val interactionModifiers = if (interactive) {
        Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { onSeekBy(SEEK_STEP_MS); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                // 触屏点击：按点击位置直接定位；消费事件避免触发控制栏外部的关闭手势
                detectTapGestures { offset ->
                    if (trackWidth > 0) onSeekTo((offset.x / trackWidth).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                // 触屏拖动：按下即定位，拖动中持续跟随手指位置
                detectDragGestures(
                    onDragStart = { offset ->
                        if (trackWidth > 0) onSeekTo((offset.x / trackWidth).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (trackWidth > 0) onSeekTo((change.position.x / trackWidth).coerceIn(0f, 1f))
                    }
                )
            }
    } else {
        Modifier
    }

    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(if (interactive) 28.dp else 16.dp)
            .padding(horizontal = 12.dp)
            .onSizeChanged { trackWidth = it.width }
            .then(interactionModifiers),
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isFocused) 5.dp else 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = if (isFocused) 0.38f else 0.24f))
        )
        // 已播进度（主题色，跟随默认/用户所选主题）
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(if (isFocused) 5.dp else 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        // 滑块：聚焦时带主题色光晕 + 白色圆点
        if (interactive) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isFocused) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                    )
                }
                Box(
                    modifier = Modifier
                        .size(if (isFocused) 12.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isLarge: Boolean = false,
    focusRequester: FocusRequester? = null,
    loading: Boolean = false,
    /** 强调按钮（播放/暂停）：常态为浅色实心 + 深色图标 */
    emphasized: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(120),
        label = "transportScale"
    )

    val size = if (isLarge) 60.dp else 46.dp
    val iconSize = if (isLarge) 30.dp else 22.dp

    // 聚焦态沿用原样式（半透明填充 + 描边 + 放大 + 白色图标），
    // 仅把颜色换成主题色（默认主题或用户所选主题）
    val focusedTint = MaterialTheme.colorScheme.primary
    val containerColor = when {
        isFocused -> focusedTint.copy(alpha = 0.35f)
        emphasized -> Color(0xFFF4F2EC)
        else -> PlayerColors.ControlBg
    }
    val contentColor = when {
        isFocused -> PlayerColors.TextPrimary
        emphasized -> Color(0xFF161311)
        else -> PlayerColors.TextPrimary
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .then(
                if (isFocused) Modifier.border(2.dp, focusedTint, RoundedCornerShape(50))
                else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { if (!loading) onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
