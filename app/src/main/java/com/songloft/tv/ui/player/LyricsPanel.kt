package com.songloft.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songloft.tv.data.model.LyricLine
import com.songloft.tv.data.model.LyricWord
import com.songloft.tv.ui.theme.PlayerColors

@Composable
fun LyricsPanel(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    currentPosition: Long,
    modifier: Modifier = Modifier,
    highlightColor: Color = PlayerColors.TextPrimary,
    fontSize: Int = 30
) {
    val listState = rememberLazyListState()

    val activeSize = fontSize.sp
    val activeLineHeight = (fontSize * 42 / 30).sp
    val inactiveSize = (fontSize * 22 / 30).sp
    val inactiveLineHeight = (fontSize * 30 / 30).sp
    val translationSize = (fontSize * 16 / 30).sp

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < lyrics.size) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无歌词",
                fontSize = 18.sp,
                color = PlayerColors.LyricsInactive
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            contentPadding = PaddingValues(vertical = 200.dp)
        ) {
            itemsIndexed(lyrics) { index, line ->
                val isActive = index == currentIndex
                val distance = kotlin.math.abs(index - currentIndex)
                val alpha = if (isActive) 1f else (0.85f - 0.08f * distance).coerceIn(0.45f, 0.85f)

                if (isActive && line.hasWords) {
                    SmoothWordHighlightLine(
                        line = line,
                        progressMs = currentPosition,
                        highlightColor = highlightColor,
                        fontSize = fontSize
                    )
                } else {
                    Text(
                        text = line.text.ifEmpty { "···" },
                        fontSize = if (isActive) activeSize else inactiveSize,
                        lineHeight = if (isActive) activeLineHeight else inactiveLineHeight,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) highlightColor else PlayerColors.TextPrimary.copy(alpha = alpha),
                        style = TextStyle(shadow = PlayerColors.LyricShadow),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                }

                if (isActive && line.translation != null) {
                    Text(
                        text = line.translation,
                        fontSize = translationSize,
                        color = PlayerColors.LyricsWord,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 逐字高亮播放行
 *
 * 严格职责分离：
 * - 换行（字符 ↔ 像素 X/Y 映射）：由 Text composable 自己处理，本函数完全不感知。
 *   Text 用 fillMaxWidth + textAlign=Center 自然按可用宽度折行，与未播放行同款。
 * - 逐字高亮（按 word 染色）：本函数根据 words 时间戳把每个 word 内的 char 标记为
 *   "已唱/未唱"，生成 AnnotatedString，每个 span 独立着色。Text 拿到 AnnotatedString
 *   后按统一 layout 排版，位置天然对齐，跨折行也自动正确。
 *
 * 不再使用 clipRect / drawWithContent / Canvas——这些字内像素级过度方案在折行点上
 * 反复出现"靠右/闪回"问题，本方案彻底回归"逐字按 char 切色"，与换行逻辑完全解耦。
 * 末字"提前亮"：lit > 0 时把整 word 标为高亮色，避免 (word.length * lit).toInt() 截
 * 断导致末字始终 dim。
 */
@Composable
private fun SmoothWordHighlightLine(
    line: LyricLine,
    progressMs: Long,
    highlightColor: Color,
    fontSize: Int = 30
) {
    val words = line.words ?: return
    if (words.isEmpty()) return

    val style = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 42 / 30).sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        shadow = PlayerColors.LyricShadow
    )

    val annotated = remember(line.text, progressMs) {
        buildWordHighlightAnnotatedString(
            words = words,
            progressMs = progressMs,
            highlightColor = highlightColor,
            inactiveColor = PlayerColors.LyricsInactive
        )
    }

    Text(
        text = annotated,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp)
    )
}

/**
 * 逐字高亮：把每个 word 内的 char 按时间戳切分为"已唱 / 未唱"两段，用 AnnotatedString
 * 不同 SpanStyle 着色。约定 line.text = words[].text 顺序拼接（LyricParser 保证）。
 */
private fun buildWordHighlightAnnotatedString(
    words: List<LyricWord>,
    progressMs: Long,
    highlightColor: Color,
    inactiveColor: Color
): androidx.compose.ui.text.AnnotatedString {
    val spanLit = SpanStyle(color = highlightColor)
    val spanDim = SpanStyle(color = inactiveColor)

    return buildAnnotatedString {
        for (word in words) {
            val lit = when {
                progressMs >= word.end -> 1f
                progressMs <= word.start -> 0f
                word.end > word.start ->
                    ((progressMs - word.start).toFloat() / (word.end - word.start)).coerceIn(0f, 1f)
                else -> 0f
            }
            // 末字"提前亮"：lit > 0 时整 word 都高亮，否则整 word dim。
            // 这是 AnnotatedString 的极限——按 word 整字切色，不能做到字内像素级。
            if (lit > 0f) {
                withStyle(spanLit) { append(word.text) }
            } else {
                withStyle(spanDim) { append(word.text) }
            }
        }
    }
}
