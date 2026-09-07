package com.songloft.tv.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 全局字体缩放比例 CompositionLocal，默认 1.0 */
val LocalFontScale = compositionLocalOf { 1f }

/** 各档位字体缩放比例 */
object FontScalePreset {
    val Values = listOf("小", "中", "大", "特大")
    fun scaleForIndex(index: Int): Float = when (index) {
        0 -> 0.85f
        1 -> 1.0f
        2 -> 1.2f
        else -> 1.35f
    }
}

/** 将 TextStyle 的所有尺寸（fontSize、lineHeight）按指定缩放比例放大 */
fun TextStyle.applyFontScale(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale
)

/**
 * 受字体缩放影响的 Text 组件。
 * 自动读取 LocalFontScale.current 对 fontSize / lineHeight 进行缩放。
 */
@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: TextUnit? = null,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val scale = LocalFontScale.current
    val baseFontSize = fontSize ?: style?.fontSize ?: 14.sp
    val resolvedFontSize = baseFontSize * scale
    val resolvedLineHeight = lineHeight?.takeIf { it.value > 0 }?.let { it * scale }
    val resolvedColor = color ?: style?.color ?: Color.Unspecified
    val resolvedFontStyle = fontStyle ?: style?.fontStyle
    val resolvedFontWeight = fontWeight ?: style?.fontWeight
    val resolvedFontFamily = fontFamily ?: style?.fontFamily
    val resolvedLetterSpacing = letterSpacing ?: style?.letterSpacing
    val resolvedTextDecoration = textDecoration ?: style?.textDecoration
    val resolvedTextAlign = textAlign ?: style?.textAlign

    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = resolvedColor,
        fontSize = resolvedFontSize,
        fontStyle = resolvedFontStyle,
        fontWeight = resolvedFontWeight,
        fontFamily = resolvedFontFamily,
        letterSpacing = resolvedLetterSpacing ?: TextUnit.Unspecified,
        textDecoration = resolvedTextDecoration,
        textAlign = resolvedTextAlign,
        lineHeight = resolvedLineHeight ?: TextUnit.Unspecified,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
    )
}
