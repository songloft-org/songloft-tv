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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 逐字像素级平滑高亮（双层裁剪方案）
 *
 * 底层灰色全文 + 顶层高亮文本按进度 clipRect 逐行揭示。
 * 利用 TextLayoutResult 获取每个字符的精确像素位置，实现子字符级平滑扫动。
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

    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // 底层：灰色未播放文本
        Text(
            text = line.text,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 42 / 30).sp,
            fontWeight = FontWeight.Bold,
            color = PlayerColors.LyricsInactive,
            style = TextStyle(shadow = PlayerColors.LyricShadow),
            textAlign = TextAlign.Center,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 顶层：高亮文本，按进度裁剪揭示
        val lr = layout
        if (lr != null) {
            Text(
                text = line.text,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 42 / 30).sp,
                fontWeight = FontWeight.Bold,
                color = highlightColor,
                style = TextStyle(shadow = PlayerColors.LyricShadow),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val coveredChars = calculateCoveredChars(progressMs, words)
                        if (coveredChars <= 0f) return@drawWithContent
                        if (coveredChars >= line.text.length) {
                            drawContent()
                            return@drawWithContent
                        }

                        var remaining = coveredChars
                        for (lineIdx in 0 until lr.lineCount) {
                            val lineStart = lr.getLineStart(lineIdx)
                            val lineEnd = lr.getLineEnd(lineIdx)
                            val lineLen = lineEnd - lineStart
                            if (lineLen <= 0) continue
                            if (remaining <= 0f) break

                            val take = minOf(remaining, lineLen.toFloat())
                            val boundaryX = if (take >= lineLen) {
                                lr.getLineRight(lineIdx)
                            } else {
                                val base = take.toInt()
                                val frac = take - base
                                val boundaryOffset = lineStart + base
                                val x1 = lr.getHorizontalPosition(boundaryOffset, usePrimaryDirection = true)
                                // 关键修复：x2 不能越过当前视觉行边界，否则会插值到下一行
                                // 的起始位置（可能远在左侧），导致高亮"回退"
                                val x2 = if (boundaryOffset + 1 >= lineEnd) {
                                    lr.getLineRight(lineIdx)
                                } else {
                                    lr.getHorizontalPosition(boundaryOffset + 1, usePrimaryDirection = true)
                                }
                                x1 + (x2 - x1) * frac
                            }

                            clipRect(
                                left = lr.getLineLeft(lineIdx),
                                top = lr.getLineTop(lineIdx),
                                right = boundaryX,
                                bottom = lr.getLineBottom(lineIdx)
                            ) {
                                this@drawWithContent.drawContent()
                            }
                            remaining -= take
                        }
                    }
            )
        }
    }
}

/**
 * 计算当前应高亮的字符数（含小数，实现子字符级精度）。
 */
private fun calculateCoveredChars(progressMs: Long, words: List<LyricWord>): Float {
    var totalChars = 0f

    for (word in words) {
        when {
            progressMs >= word.end -> {
                totalChars += word.text.length
            }
            progressMs > word.start && word.end > word.start -> {
                val wordProgress = ((progressMs - word.start).toFloat() / (word.end - word.start).toFloat())
                    .coerceIn(0f, 1f)
                totalChars += wordProgress * word.text.length
                break
            }
            else -> break
        }
    }

    return totalChars
}
