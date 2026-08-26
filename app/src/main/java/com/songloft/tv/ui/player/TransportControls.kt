package com.songloft.tv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.songloft.tv.ui.theme.PlayerColors

private const val SEEK_STEP_MS = 10_000L

@Composable
fun SeekBar(
    progress: Float,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var trackWidth by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .height(20.dp)
            .padding(horizontal = 12.dp)
            .onSizeChanged { trackWidth = it.width }
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
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isFocused) 6.dp else 4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isFocused) PlayerColors.TrackBgFocused else PlayerColors.TrackBg)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(PlayerColors.TextPrimary)
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
    loading: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(120),
        label = "transportScale"
    )

    val size = if (isLarge) 64.dp else 48.dp
    val iconSize = if (isLarge) 32.dp else 24.dp

    Box(
        modifier = Modifier
            .scale(scale)
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(
                if (isFocused) PlayerColors.ControlBgFocused
                else PlayerColors.ControlBg
            )
            .then(
                if (isFocused) Modifier.border(2.dp, PlayerColors.ControlBorder, RoundedCornerShape(50))
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
                color = PlayerColors.TextPrimary
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = PlayerColors.TextPrimary,
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
