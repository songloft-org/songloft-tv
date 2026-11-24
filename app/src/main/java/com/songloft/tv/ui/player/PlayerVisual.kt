package com.songloft.tv.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.request.ImageRequest
import com.songloft.tv.data.api.UrlHelper
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.theme.PlayerColors
import com.songloft.tv.util.AppText as Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 播放器封面资源：封面位图 + 氛围色 + 与海报右缘调和的详情面板底色 + 海报亮度。 */
data class PlayerArtwork(
    val bitmap: ImageBitmap?,
    val ambience: Color,
    val posterSurface: Color,
    /** 海报亮度度量（0..1，取亮度 80 分位，避免暗部拉低整体均值）；用于自适应过渡区宽度。 */
    val posterBrightness: Float = 0.30f
)

/**
 * 加载封面并提取两种颜色（算法对齐 fn-music 参考播放器）：
 * - ambience：高饱和、明度适中的主色相 → 锁死饱和度/明度的沉稳暗彩，用于无封面回退背景。
 * - posterSurface：从海报右缘 38% 区域取样，Oklab 加权后色调映射（低彩度、中等明度、保证白字对比），
 *   使右侧详情面板与海报自然衔接，消除硬切边。
 * 封面加载失败时 bitmap 为 null（UI 回退到黑胶唱盘），两色回退到固定深灰绿。
 */
@Composable
fun rememberPlayerArtwork(coverUrl: String?): PlayerArtwork {
    val context = LocalContext.current
    val fallbackSurface = posterSurfaceColor(PlayerColors.AmbientFallback)
    // 注意：这里不能用 remember(coverUrl) 当 key。切歌 coverUrl 一变，旧 state 会被丢弃，
    // 画面立刻退回唱机，直到新图异步加载完 —— 表现为「切歌时封面闪回唱机/加载不出来」。
    // state 只创建一次，由下面的 LaunchedEffect 负责在加载完成后更新。
    var art by remember {
        mutableStateOf(PlayerArtwork(null, PlayerColors.AmbientFallback, fallbackSurface))
    }

    LaunchedEffect(coverUrl) {
        val url = UrlHelper.resolve(coverUrl)
        if (url.isNullOrBlank()) {
            art = PlayerArtwork(null, PlayerColors.AmbientFallback, fallbackSurface)
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // 必须用 App 里 ImageLoaderFactory 提供的全局单例（挂了 AuthInterceptor 带 JWT）。
                // 之前 new ImageLoader(context) 是裸实例、无鉴权，封面接口直接 401 ——
                // 首次进播放器、播放器内切歌都取不到图；只有从悬浮播放器重进时，
                // 因单例已把图写进共享磁盘缓存才「碰巧」显示出来。
                val loader = Coil.imageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false) // 需读取像素，禁用硬件位图
                    .build()
                val drawable = loader.execute(request).drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    val ambience = ambienceFromBitmap(bitmap) ?: PlayerColors.AmbientFallback
                    PlayerArtwork(
                        bitmap = bitmap.asImageBitmap(),
                        ambience = ambience,
                        posterSurface = posterSurfaceFromBitmap(bitmap) ?: posterSurfaceColor(ambience),
                        posterBrightness = computePosterBrightness(bitmap)
                    )
                } else null
            }.getOrNull()
        }
        // 竞态保护：慢图后到时如果 coverUrl 已经换了，丢弃旧结果，别盖掉新封面。
        if (!isActive) return@LaunchedEffect
        art = loaded ?: PlayerArtwork(null, PlayerColors.AmbientFallback, fallbackSurface)
    }

    return art
}

/** 兼容旧调用：只要氛围色 */
@Composable
fun rememberAmbienceColor(coverUrl: String?): Color = rememberPlayerArtwork(coverUrl).ambience

private fun ambienceFromBitmap(source: Bitmap): Color? {
    // 缩小后采样，避免大图逐像素遍历
    val small = Bitmap.createScaledBitmap(source, 40, 40, true)
    var sinSum = 0f
    var cosSum = 0f
    var satSum = 0f
    var weight = 0f
    val hsv = FloatArray(3)
    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val argb = small.getPixel(x, y)
            if ((argb shr 24) and 0xFF < 128) continue // 跳过透明像素
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val sat = hsv[1]
            val value = hsv[2]
            if (sat < 0.10f || value < 0.12f || value > 0.98f) continue
            // 高饱和、明度适中者权重更大
            val w = sat * value
            val rad = Math.toRadians(hsv[0].toDouble())
            sinSum += sin(rad).toFloat() * w
            cosSum += cos(rad).toFloat() * w
            satSum += sat * w
            weight += w
        }
    }
    if (weight < 0.0001f) return PlayerColors.AmbientFallback
    val hue = ((Math.toDegrees(atan2(sinSum, cosSum).toDouble()) + 360) % 360).toFloat()
    val saturation = ((satSum / weight) * 0.55f).coerceIn(0.16f, 0.34f)
    val mapped = floatArrayOf(hue, saturation, 0.32f) // 明度锁死，保证白字可读
    return Color(android.graphics.Color.HSVToColor(mapped))
}

// ===== 海报面板色：Oklab 取样 + 色调映射（对齐参考项目 artworkPosterSurfaceColor）=====

// 目标：右侧详情面板为「深、近中性、带一丝海报色相」的暗色（对齐参考播放器实测 ~#243024，
// Oklab L≈0.29 / 彩度≈0.03）。彩度与明度都压得比通用算法更低，避免亮海报取样后变成
// 发灰发绿的「橄榄色」脏底色。
private const val POSTER_MAX_CHROMA = 0.030f
private const val POSTER_MIN_LIGHTNESS = 0.20f
private const val POSTER_MAX_LIGHTNESS = 0.30f
private const val POSTER_TEXT_CONTRAST = 4.8f

private data class Oklab(val lightness: Float, val a: Float, val b: Float)

/** 从海报右缘 38% 区域取加权平均色 → 色调映射，得到沉稳、可读的详情面板底色。 */
private fun posterSurfaceFromBitmap(source: Bitmap): Color? {
    if (source.width == 0 || source.height == 0) return null
    val small = Bitmap.createScaledBitmap(source, 32, 32, true)
    val edgeStart = (small.width * 0.62f).toInt().coerceIn(0, small.width - 1)
    var lSum = 0f
    var aSum = 0f
    var bSum = 0f
    var totalWeight = 0f
    for (y in 0 until small.height) {
        for (x in edgeStart until small.width) {
            val argb = small.getPixel(x, y)
            if ((argb shr 24) and 0xFF < 128) continue
            val c = Color(argb)
            val lab = c.toOklab()
            // 极暗/极亮像素降权，避免高光或纯黑主导
            val weight = when {
                lab.lightness < 0.04f -> 0.35f
                lab.lightness > 0.97f -> 0.45f
                else -> 1f
            }
            lSum += lab.lightness * weight
            aSum += lab.a * weight
            bSum += lab.b * weight
            totalWeight += weight
        }
    }
    if (totalWeight <= 0f) return null
    return toneMapPosterSurface(
        Oklab(lSum / totalWeight, aSum / totalWeight, bSum / totalWeight)
    )
}

/** 由单个颜色生成海报面板色（无封面时的回退路径）。 */
private fun posterSurfaceColor(color: Color): Color = toneMapPosterSurface(color.toOklab())

/**
 * 海报亮度度量（0..1）：只采海报**右侧**区域（x≥55%，紧邻过渡带/面板侧）的 sRGB 亮度
 * （0.2126R+0.7152G+0.0722B）80 分位。
 *
 * 为什么只采右侧：过渡带就在海报右缘，决定"突兀感"的是右缘那片像素的亮度；
 * 整图均值/分位会被左侧深色主体拉偏。取 80 分位而非均值，可稳健捕捉亮天空/高光，
 * 又不会被右侧局部暗角过度拉低。
 */
private fun computePosterBrightness(source: Bitmap): Float {
    if (source.width == 0 || source.height == 0) return 0.30f
    val small = Bitmap.createScaledBitmap(source, 24, 24, true)
    val edgeStart = (small.width * 0.55f).toInt().coerceIn(0, small.width - 1)
    val values = ArrayList<Float>((small.width - edgeStart) * small.height)
    for (y in 0 until small.height) {
        for (x in edgeStart until small.width) {
            val argb = small.getPixel(x, y)
            if ((argb shr 24) and 0xFF < 128) continue
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            values.add(0.2126f * r + 0.7152f * g + 0.0722f * b)
        }
    }
    if (values.isEmpty()) return 0.30f
    values.sort()
    val idx = ((values.size - 1) * 0.80f).toInt().coerceIn(0, values.size - 1)
    return values[idx]
}

private fun toneMapPosterSurface(source: Oklab): Color {
    val chroma = sqrt(source.a * source.a + source.b * source.b)
    val chromaScale = if (chroma > POSTER_MAX_CHROMA) POSTER_MAX_CHROMA / chroma else 1f
    var mapped = Oklab(
        lightness = source.lightness.coerceIn(POSTER_MIN_LIGHTNESS, POSTER_MAX_LIGHTNESS),
        a = source.a * chromaScale,
        b = source.b * chromaScale
    )
    var surface = mapped.toColor()
    // 逐步压暗直到白字对比达标
    while (
        contrastRatio(PlayerColors.TextPrimary, surface) < POSTER_TEXT_CONTRAST &&
        mapped.lightness > POSTER_MIN_LIGHTNESS
    ) {
        mapped = mapped.copy(
            lightness = (mapped.lightness - 0.01f).coerceAtLeast(POSTER_MIN_LIGHTNESS)
        )
        surface = mapped.toColor()
    }
    return surface
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.relativeLuminance(), second.relativeLuminance())
    val darker = minOf(first.relativeLuminance(), second.relativeLuminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun Color.relativeLuminance(): Float =
    0.2126f * srgbToLinear(red) + 0.7152f * srgbToLinear(green) + 0.0722f * srgbToLinear(blue)

private fun Color.toOklab(): Oklab {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)
    val l = 0.41222146f * r + 0.53633255f * g + 0.051445995f * b
    val m = 0.2119035f * r + 0.6806995f * g + 0.10739696f * b
    val s = 0.08830246f * r + 0.28171885f * g + 0.6299787f * b
    val lr = Math.cbrt(l.toDouble()).toFloat()
    val mr = Math.cbrt(m.toDouble()).toFloat()
    val sr = Math.cbrt(s.toDouble()).toFloat()
    return Oklab(
        lightness = 0.21045426f * lr + 0.7936178f * mr - 0.004072047f * sr,
        a = 1.9779985f * lr - 2.4285922f * mr + 0.4505937f * sr,
        b = 0.025904037f * lr + 0.78277177f * mr - 0.80867577f * sr
    )
}

private fun Oklab.toColor(): Color {
    val lr = lightness + 0.39633778f * a + 0.21580376f * b
    val mr = lightness - 0.105561346f * a - 0.06385417f * b
    val sr = lightness - 0.08948418f * a - 1.2914855f * b
    val l = lr * lr * lr
    val m = mr * mr * mr
    val s = sr * sr * sr
    return Color(
        red = linearToSrgb(4.0767417f * l - 3.3077116f * m + 0.23096994f * s),
        green = linearToSrgb(-1.268438f * l + 2.6097574f * m - 0.34131938f * s),
        blue = linearToSrgb(-0.0041960863f * l - 0.7034186f * m + 1.7076147f * s)
    )
}

private fun srgbToLinear(component: Float): Float {
    val safe = component.coerceIn(0f, 1f)
    return if (safe <= 0.04045f) safe / 12.92f
    else ((safe + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
}

private fun linearToSrgb(component: Float): Float {
    val srgb = if (component <= 0.0031308f) component * 12.92f
    else 1.055f * component.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    return srgb.coerceIn(0f, 1f)
}

/**
 * 沉浸式氛围背景：横向渐变（氛围色 → 深色）+ 整体压暗 + 底部渐暗托住控制栏。
 */
@Composable
fun AmbientBackdrop(ambience: Color, modifier: Modifier = Modifier) {
    val animated by animateColorAsState(
        targetValue = ambience,
        animationSpec = tween(durationMillis = 650, easing = LinearEasing),
        label = "ambience"
    )
    Canvas(modifier.fillMaxSize()) {
        val base = PlayerColors.BackgroundDeep
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    animated,
                    lerp(animated, base, 0.42f),
                    lerp(animated, base, 0.72f)
                )
            )
        )
        drawRect(Color.Black.copy(alpha = 0.18f)) // 压制氛围色亮度
        drawRect(Color.White.copy(alpha = 0.02f)) // 极淡提亮增加质感
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)),
                startY = size.height * 0.55f,
                endY = size.height
            )
        )
    }
}

/**
 * 大海报模式（默认）：左侧 58% 满高封面 + 自适应宽幅渐变衔接 + 右侧详情面板。
 *
 * 过渡区宽度按海报亮度自适应：
 * - 深色海报：亮度低，海报到面板色本就接近，用较窄过渡区（起点 0.34）即可自然衔接；
 * - 亮色海报：亮度高，落差大，过渡区自动加宽（起点最宽到 0.20），把明度差摊到更长距离，
 *   并用 smoothstep 曲线（两端缓、中间匀）让暗部下落更平顺，不再出现"墙"。
 * 面板底色的计算全程用「面板色 + 递增透明度」做真正的交叉淡入，避免 Color.Transparent
 * （黑+透明）在中段插值压暗产生的暗带。
 */
@Composable
fun PosterBackdrop(
    bitmap: ImageBitmap,
    ambience: Color,
    panelColor: Color,
    posterBrightness: Float = 0.30f,
    modifier: Modifier = Modifier
) {
    // 亮度归一：80 分位亮度 0.40 以下视为暗海报 → 0；0.85 以上视为很亮 → 1
    val brightness = ((posterBrightness - 0.40f) / 0.45f).coerceIn(0f, 1f)
    // 自适应过渡起点：暗 → 0.34（窄、紧贴面板）；亮 → 0.20（宽、提前缓慢压暗）
    val fadeStart = 0.34f + (0.20f - 0.34f) * brightness
    // 越亮越多分段，过渡越平滑
    val steps = 8 + (brightness * 4f).toInt()
    val fadeStops = posterFadeStops(panelColor, start = fadeStart, end = 0.52f, steps = steps)

    Box(modifier.fillMaxSize()) {
        // 底层氛围色（海报只覆盖左侧 58%，缝隙处透出同色系底色）
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.horizontalGradient(
                    0f to ambience,
                    1f to lerp(ambience, PlayerColors.BackgroundDeep, 0.05f)
                )
            )
        }

        // 左侧海报：58% 满高裁切
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.58f)
                .align(Alignment.TopStart)
        )

        // 自适应柔和过渡带（终点固定在 52%，与右侧面板起点对齐，无接缝）
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(*fadeStops))
        )

        // 右侧详情面板：从 52% 开始（与右侧歌词列起点对齐），左缘即面板色，向右极轻微收深
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.48f)
                .align(Alignment.CenterEnd)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            panelColor,
                            lerp(panelColor, PlayerColors.BackgroundDeep, 0.10f)
                        )
                    )
                )
        )

    }
}

/**
 * 生成海报→面板的过渡渐变停靠点：在 [start, end] 区间按 smoothstep 曲线把面板色透明度 0→1，
 * 两端缓、中间匀，视觉上比线性更顺滑；steps 越多越平滑（这里 8 段已足够 TV 大屏）。
 */
private fun posterFadeStops(
    panel: Color,
    start: Float,
    end: Float,
    steps: Int
): Array<Pair<Float, Color>> {
    val safeSteps = steps.coerceAtLeast(2)
    return Array(safeSteps + 1) { i ->
        val t = i.toFloat() / safeSteps
        val eased = t * t * (3f - 2f * t) // smoothstep
        val x = start + (end - start) * t
        x to panel.copy(alpha = eased)
    }
}

/* ---------------------------------------------------------------------------
 * 唱臂几何（0..1 归一化，相对唱机正方形区域；x 向右、y 向下）
 * 改位置只需要动下面这几个数字，不用碰绘制逻辑。
 * ------------------------------------------------------------------------- */
/** 支点：面板右上角（唱片外，紧贴唱片右上边缘） */
private const val ARM_PIVOT_X = 0.900f
private const val ARM_PIVOT_Y = 0.095f
/** 折点：从支点近乎垂直向下、略偏左 */
private const val ARM_ELBOW_X = 0.845f
private const val ARM_ELBOW_Y = 0.400f
/** 唱针：折向左下，落在唱片右下沟槽上（距圆心约 0.32，沟槽区） */
private const val ARM_NEEDLE_X = 0.685f
private const val ARM_NEEDLE_Y = 0.755f
/** 暂停抬臂角度：绕支点逆时针，把唱针向右外抬离盘面 */
private const val ARM_PAUSED_TILT = -14f
/**
 * 拾音头（唱针那段青色探头）相对臂身的夹角，单位度。
 * 90 = 与臂身垂直；调大 → 探头更朝左上翘起；调小 → 更随臂身方向。
 * 超过 180 就会折回唱片外侧，不建议。
 */
private const val ARM_HEAD_ANGLE = 80f
/** 拾音头长度（相对区域短边） */
private const val ARM_HEAD_LENGTH = 0.042f

/** 黑胶唱盘：仅在无封面或封面加载失败时使用。唱臂随播放状态抬落，并带轻微波动。 */
@Composable
fun VinylDisc(
    coverUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 唱片旋转：仅播放时转
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            rotation.snapTo(rotation.value % 360f)
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(durationMillis = 24_000, easing = LinearEasing)
            )
        }
    }

    // 唱臂抬落：播放时落到唱片沟槽（0°）；暂停时绕支点向外抬起，模拟抬臂离盘
    val armLift by animateFloatAsState(
        targetValue = if (isPlaying) 0f else ARM_PAUSED_TILT,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "armLift"
    )
    // 播放时的轻微波动：唱针落在沟槽里随转盘微微颤动
    val wobbleTransition = rememberInfiniteTransition(label = "armWobble")
    val armWobble by wobbleTransition.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "armWobbleValue"
    )
    val armAngle = armLift + if (isPlaying) armWobble else 0f

    // 状态相关配色：播放时唱臂/唱针更亮，暂停时压暗
    val armColor = if (isPlaying) Color(0xFFE6E9E3) else Color(0xFFB4B9B2)
    val pivotHighlight = if (isPlaying) Color(0xFFEFF2ED) else Color(0xFFC9CEC8)
    val needleColor = if (isPlaying) PlayerColors.Teal.copy(alpha = 0.85f) else Color(0xFF8A938D)

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val boxSize = minOf(maxWidth, maxHeight)
        // 关键：必须 Center，否则唱片会贴左上角而唱臂按满框坐标绘制，导致位置错位
        Box(Modifier.size(boxSize), contentAlignment = Alignment.Center) {
            val recordSize = boxSize * 0.84f
            val labelSize = boxSize * 0.44f
            // 旋转层：唱片 + 中心封面标签
            Box(
                Modifier
                    .size(recordSize)
                    .graphicsLayer { rotationZ = rotation.value },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = minOf(size.width, size.height) / 2f
                    drawCircle(Color(0xFF101313), radius)
                    drawCircle(
                        Color(0xFF242928),
                        radius * 0.96f,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    for (ring in 1..14) {
                        val ringRadius = radius * (0.52f + ring * 0.029f)
                        drawCircle(
                            Color.White.copy(alpha = if (ring % 3 == 0) 0.09f else 0.045f),
                            ringRadius,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    drawArc(
                        color = PlayerColors.Teal.copy(alpha = if (isPlaying) 0.50f else 0.26f),
                        startAngle = -18f,
                        sweepAngle = 36f,
                        useCenter = false,
                        topLeft = Offset(radius * 0.18f, radius * 0.18f),
                        size = Size(radius * 1.64f, radius * 1.64f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Box(
                    Modifier
                        .size(labelSize)
                        .clip(CircleShape)
                ) {
                    CoverImage(
                        url = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFCDD4D0))
                )
            }
            // 唱臂：整层围绕支点（右上角）旋转，抬落 + 播放时的轻微波动
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = armAngle
                        transformOrigin = TransformOrigin(ARM_PIVOT_X, ARM_PIVOT_Y)
                    }
            ) {
                val pivot = Offset(size.width * ARM_PIVOT_X, size.height * ARM_PIVOT_Y)
                val elbow = Offset(size.width * ARM_ELBOW_X, size.height * ARM_ELBOW_Y)
                val needle = Offset(size.width * ARM_NEEDLE_X, size.height * ARM_NEEDLE_Y)
                // 所有粗细/半径按区域尺寸自适应，避免大屏上支点被顶边裁掉
                val unit = minOf(size.width, size.height)
                val armWidth = unit * 0.016f
                val headWidth = unit * 0.015f
                // 拾音头：把臂身方向（折点→唱针）顺时针旋转 ARM_HEAD_ANGLE 度，
                // 得到朝左上拐的探头；改角度不用碰这里的计算
                val armVec = needle - elbow
                val armLen = max(hypot(armVec.x, armVec.y), 0.0001f)
                val ux = armVec.x / armLen
                val uy = armVec.y / armLen
                val headRad = ARM_HEAD_ANGLE * (PI.toFloat() / 180f)
                val headCos = cos(headRad)
                val headSin = sin(headRad)
                val headEnd = needle + Offset(
                    (ux * headCos - uy * headSin) * unit * ARM_HEAD_LENGTH,
                    (ux * headSin + uy * headCos) * unit * ARM_HEAD_LENGTH
                )

                // 臂身路径：支点 → 折点 → 唱针（圆角折弯，避免生硬尖角）
                val armPath = Path().apply {
                    moveTo(pivot.x, pivot.y)
                    lineTo(elbow.x, elbow.y)
                    lineTo(needle.x, needle.y)
                }
                // 投影：整体右下偏移一点，让唱臂看起来悬在盘面之上
                val shadowShift = Offset(unit * 0.005f, unit * 0.008f)
                val shadowPath = Path().apply {
                    moveTo(pivot.x + shadowShift.x, pivot.y + shadowShift.y)
                    lineTo(elbow.x + shadowShift.x, elbow.y + shadowShift.y)
                    lineTo(needle.x + shadowShift.x, needle.y + shadowShift.y)
                }
                drawPath(
                    shadowPath,
                    Color.Black.copy(alpha = 0.42f),
                    style = Stroke(
                        width = armWidth + unit * 0.004f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                // 臂身：一次描边，粗细统一，折点圆滑
                drawPath(
                    armPath,
                    armColor,
                    style = Stroke(
                        width = armWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                // 拾音头（唱针）：按 ARM_HEAD_ANGLE 从唱针朝左上拐出 + 针头圆点
                drawLine(
                    Color.Black.copy(alpha = 0.42f),
                    needle + shadowShift,
                    headEnd + shadowShift,
                    headWidth + unit * 0.004f,
                    StrokeCap.Round
                )
                drawLine(
                    needleColor,
                    needle,
                    headEnd,
                    headWidth,
                    StrokeCap.Round
                )
                drawCircle(needleColor, unit * 0.011f, headEnd)
                // 支点：外圈深色底座 + 金属亮面 + 中心轴孔
                drawCircle(Color(0xFF1C2120), unit * 0.052f, pivot)
                drawCircle(armColor.copy(alpha = 0.55f), unit * 0.042f, pivot)
                drawCircle(pivotHighlight, unit * 0.032f, pivot)
                drawCircle(Color(0xFF3A413F), unit * 0.013f, pivot)
            }
        }
    }
}

/** 音质徽章（FLAC / MP3 等），细边框小胶囊。 */
@Composable
fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = format.uppercase(),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
