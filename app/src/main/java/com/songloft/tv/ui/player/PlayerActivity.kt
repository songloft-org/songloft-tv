package com.songloft.tv.ui.player

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.songloft.tv.data.api.UrlHelper
import com.songloft.tv.domain.KeyMappingManager
import com.songloft.tv.domain.MappingTarget
import com.songloft.tv.ui.components.CoverImage
import com.songloft.tv.ui.components.tvFocusable
import com.songloft.tv.ui.karaoke.KaraokePlayerScreen
import com.songloft.tv.ui.karaoke.KaraokeQueueList
import com.songloft.tv.ui.theme.PlayerColors
import com.songloft.tv.ui.theme.TvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject
    lateinit var keyMappingManager: KeyMappingManager

    private lateinit var viewModel: PlayerViewModel

    /** 用户自定义按键映射：原伴唱切换键（K 歌模式下）拦截处理，其余命中映射表的 keycode 翻译成标准功能键 keycode 后继续分发 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyMappingManager.matchSpecialKey(event.keyCode) == MappingTarget.ACCOMPANIMENT &&
            viewModel.uiState.value.karaokeModeEnabled
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                viewModel.toggleAccompaniment()
            }
            return true
        }
        return super.dispatchKeyEvent(keyMappingManager.translateEvent(event))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

        setContent {
            TvTheme {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // K 歌模式常亮：唱歌时长时间不按遥控器，防止熄屏/屏保（View.keepScreenOn 即 FLAG_KEEP_SCREEN_ON）
    val view = LocalView.current
    DisposableEffect(uiState.karaokeModeEnabled) {
        view.keepScreenOn = uiState.karaokeModeEnabled
        onDispose { view.keepScreenOn = false }
    }

    var interactionCount by remember { mutableIntStateOf(0) }
    val controlBarFocus = remember { FocusRequester() }
    val micButtonFocus = remember { FocusRequester() }
    val queueDrawerFocus = remember { FocusRequester() }
    val soundPanelFocus = remember { FocusRequester() }
    val soundButtonFocus = remember { FocusRequester() }
    val queueButtonFocus = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    
    BackHandler {
        when {
            uiState.showExitKaraokeConfirm -> viewModel.dismissExitKaraokeConfirm()
            uiState.showQueueDrawer -> viewModel.closeQueueDrawer()
            uiState.showSoundPanel -> viewModel.closeSoundPanel()
            uiState.showControls -> viewModel.hideControls()
            uiState.karaokeModeEnabled -> viewModel.requestExitKaraoke()
            else -> onBack()
        }
    }

    LaunchedEffect(uiState.isPlaying, uiState.showControls, interactionCount, uiState.controlsPersistent) {
        if (uiState.isPlaying && uiState.showControls && !uiState.controlsPersistent) {
            delay(10_000)
            viewModel.hideControls()
        }
    }

    // 音效面板 10s 无操作自动关闭（与功能菜单一致，任意按键重置计时）
    LaunchedEffect(uiState.isPlaying, uiState.showSoundPanel, interactionCount) {
        if (uiState.isPlaying && uiState.showSoundPanel) {
            delay(10_000)
            viewModel.closeSoundPanel()
        }
    }

    LaunchedEffect(uiState.showControls, uiState.showQueueDrawer, uiState.showSoundPanel, uiState.karaokeModeEnabled) {
        // 等待 AnimatedVisibility 完成组合后再请求焦点
        delay(100)
        runCatching {
            when {
                uiState.showQueueDrawer -> queueDrawerFocus.requestFocus()
                uiState.showSoundPanel -> soundPanelFocus.requestFocus()
                uiState.karaokeModeEnabled -> playPauseFocusRequester.requestFocus()
                uiState.showControls -> controlBarFocus.requestFocus()
            }
        }
    }

    // 队列抽屉关闭后，焦点回到控制栏的"播放队列"按钮（按钮不可见时兜底到播放/暂停）
    var queueDrawerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.showQueueDrawer) {
        if (queueDrawerWasOpen && !uiState.showQueueDrawer) {
            delay(120)
            runCatching { queueButtonFocus.requestFocus() }
                .onFailure { runCatching { playPauseFocusRequester.requestFocus() } }
        }
        queueDrawerWasOpen = uiState.showQueueDrawer
    }

    // 音效面板关闭后，焦点回到控制栏的音效按钮（按钮不可见时兜底到播放/暂停）
    var soundPanelWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.showSoundPanel) {
        if (soundPanelWasOpen && !uiState.showSoundPanel) {
            delay(100)
            runCatching { soundButtonFocus.requestFocus() }
                .onFailure { runCatching { controlBarFocus.requestFocus() } }
        }
        soundPanelWasOpen = uiState.showSoundPanel
    }

    var didSeekDuringPress by remember { mutableStateOf(false) }
    val seekStepMs = 10_000L
    
    // === K 歌模式入口按钮聚焦监听 ===
    var karaokeButtonFocused by remember { mutableStateOf(false) }
    LaunchedEffect(karaokeButtonFocused) {
        if (karaokeButtonFocused) {
            delay(200) // 延迟进入，避免误触
            // 如果需要键盘触发，可在此添加逻辑
        }
    }

    // 退出 K 歌模式后，焦点回到主播放器控制栏的"麦克风（K 歌入口）"按钮
    var wasKaraokeMode by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.karaokeModeEnabled) {
        if (wasKaraokeMode && !uiState.karaokeModeEnabled) {
            delay(120)
            runCatching { micButtonFocus.requestFocus() }
        }
        wasKaraokeMode = uiState.karaokeModeEnabled
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerColors.Background)
            .pointerInput(uiState.showControls, uiState.showQueueDrawer, uiState.showSoundPanel) {
                // 控制栏/面板弹出时点击其他区域关闭；子节点消费的事件不会触发此回调
                // 关闭优先级与 BackHandler 一致：队列抽屉 → 音效面板 → 控制栏
                if (uiState.showQueueDrawer) {
                    detectTapGestures { viewModel.closeQueueDrawer() }
                } else if (uiState.showSoundPanel) {
                    detectTapGestures { viewModel.closeSoundPanel() }
                } else if (uiState.showControls) {
                    detectTapGestures { viewModel.hideControls() }
                }
            }
            .onPreviewKeyEvent { event ->
                val controlsHidden = !uiState.showControls && !uiState.showQueueDrawer && !uiState.showSoundPanel
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        interactionCount++
                        when (event.key) {
                            // 优先于焦点链处理，保证抽屉/面板/工具栏一次返回即关闭
                            Key.Back -> {
                                when {
                                    uiState.showQueueDrawer -> {
                                        viewModel.closeQueueDrawer()
                                        true
                                    }
                                    uiState.showSoundPanel -> {
                                        viewModel.closeSoundPanel()
                                        true
                                    }
                                    uiState.showControls -> {
                                        viewModel.hideControls()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                                viewModel.togglePlay(); true
                            }
                            Key.MediaNext, Key.MediaSkipForward -> {
                                viewModel.nextTrack(); true
                            }
                            Key.MediaPrevious, Key.MediaSkipBackward -> {
                                viewModel.previousTrack(); true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> {
                                if (controlsHidden) {
                                    if (event.nativeKeyEvent.repeatCount > 0) {
                                        didSeekDuringPress = true
                                        viewModel.seekBy(
                                            if (event.key == Key.DirectionLeft) -seekStepMs else seekStepMs
                                        )
                                    }
                                    true
                                } else false
                            }
                            Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> {
                                if (controlsHidden) {
                                    viewModel.showControls()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        when (event.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                if (controlsHidden) {
                                    if (!didSeekDuringPress) {
                                        if (event.key == Key.DirectionLeft) viewModel.previousTrack()
                                        else viewModel.nextTrack()
                                    }
                                    didSeekDuringPress = false
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        // === 渲染两种模式 ===
        when {
            // K 歌模式（独立维护的全屏双行歌词界面）
            uiState.karaokeModeEnabled -> {
                KaraokePlayerScreen(
                    uiState = uiState,
                    orderUrl = uiState.karaokeOrderUrl,
                    accompanimentOn = uiState.isAccompanimentOn,
                    onBack = { viewModel.requestExitKaraoke() },
                    onPlayPause = { viewModel.togglePlay() },
                    onNext = { viewModel.nextTrack() },
                    onSeek = { viewModel.seekTo(it) },
                    onSeekBy = { viewModel.seekBy(it) },
                    onCyclePlayMode = { viewModel.cyclePlayMode() },
                    onToggleFavorite = { viewModel.toggleFavorite() },
                    onReSing = { viewModel.reSing() },
                    onToggleAccompaniment = { viewModel.toggleAccompaniment() },
                onToggleQueue = { viewModel.toggleQueueDrawer() },
                playPauseFocusRequester = playPauseFocusRequester,
                queueButtonFocusRequester = queueButtonFocus,
                backButtonFocusRequester = micButtonFocus,
                    onShowControls = { viewModel.showControls() }
                )
            }
            
            // 正常视频播放模式
            uiState.isVideoMode -> {
                VideoPlayer(
                    withPlayer = viewModel::withPlayer,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // 正常音乐播放模式
            else -> {
                UrlHelper.resolve(uiState.currentSong?.coverUrl)?.let { cover ->
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(60.dp),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PlayerColors.Scrim)
                    )
                }
                
                Row(modifier = Modifier.fillMaxSize()) {
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
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val highlightColor = when (uiState.lyricHighlightColor) {
                            2 -> MaterialTheme.colorScheme.primary
                            else -> PlayerColors.TextPrimary
                        }
                        LyricsPanel(
                            lyrics = uiState.lyrics,
                            currentIndex = uiState.currentLyricIndex,
                            currentPosition = uiState.currentPosition,
                            highlightColor = highlightColor,
                            fontSize = uiState.lyricFontSize
                        )
                    }
                }
            }
        }

        // 左上角返回按钮：与控制栏同显同隐（10s 无操作自动隐藏、点击空白/返回键先关控制栏）
        // K 歌模式下不显示返回按钮（已内置在底部控制栏）
        if (!uiState.karaokeModeEnabled) {
            AnimatedVisibility(
                visible = uiState.showControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                TransportButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        }

        // 控制栏：K 歌模式下不显示（有独立的 KaraokePlaybackScreen 控制栏）
        if (!uiState.karaokeModeEnabled) {
            AnimatedVisibility(
                visible = uiState.showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) { detectTapGestures { } } // 消费控制栏区域点击，避免触发外部关闭
            ) {
                ControlBar(
                    uiState = uiState,
                    onPlayPause = { viewModel.togglePlay() },
                    onNext = { viewModel.nextTrack() },
                    onPrevious = { viewModel.previousTrack() },
                    onSeek = { viewModel.seekTo(it) },
                    onCyclePlayMode = { viewModel.cyclePlayMode() },
                    onToggleQueue = { viewModel.toggleQueueDrawer() },
                    // 音效总入口：均衡器或音效任一开启即显示（总开关在设置页）
                    onToggleSound = if (uiState.eqEnabled || uiState.sfxEnabled) ({ viewModel.toggleSoundPanel() }) else null,
                    onToggleFavorite = { viewModel.toggleFavorite() },
                    onCycleAudioTrack = { viewModel.cycleAudioTrack() },
                    onRefreshLyrics = { viewModel.refreshLyrics() },
                    onEnterKaraokeMode = { viewModel.enterKaraokeMode() },
                    micButtonFocusRequester = micButtonFocus,
                    playPauseFocusRequester = controlBarFocus,
                    queueButtonFocusRequester = queueButtonFocus,
                    soundButtonFocusRequester = soundButtonFocus,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 控制栏隐藏时的触屏入口：用 pointerInput 而非 clickable，避免进入遥控器焦点链
            AnimatedVisibility(
                visible = !uiState.showControls && !uiState.showQueueDrawer && !uiState.showSoundPanel,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(PlayerColors.TouchEntryBg)
                        .pointerInput(Unit) { detectTapGestures { viewModel.showControls() } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "显示控制栏",
                        tint = PlayerColors.TouchEntryIcon,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // 队列抽屉（独立模态窗口，焦点锁定在弹窗内，方向键不会跳到主界面）
        if (uiState.showQueueDrawer) {
            Dialog(
                onDismissRequest = { viewModel.toggleQueueDrawer() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInHorizontally { -it },
                        exit = slideOutHorizontally { -it },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        if (uiState.karaokeModeEnabled) {
                            KaraokeQueueList(
                                queue = uiState.karaokeList,
                                currentIndex = uiState.currentIndex,
                                onClose = { viewModel.toggleQueueDrawer() },
                                onSongClick = { viewModel.karaokePlayAt(it) },
                                onMoveTop = { viewModel.karaokeMoveTop(it) },
                                onRemove = { viewModel.karaokeRemove(it) },
                                initialFocusRequester = queueDrawerFocus,
                                modifier = Modifier.fillMaxHeight().width(400.dp)
                            )
                        } else {
                            QueueDrawer(
                                queue = uiState.queue,
                                currentIndex = uiState.currentIndex,
                                onClose = { viewModel.toggleQueueDrawer() },
                                onSongClick = { viewModel.playAt(it) },
                                initialFocusRequester = queueDrawerFocus,
                                modifier = Modifier.fillMaxHeight().width(400.dp)
                            )
                        }
                    }
                }
            }
        }

        // 合并音效面板（独立模态窗口，焦点锁定在弹窗内，方向键不会跳到底部播放器）
        if (uiState.showSoundPanel) {
            Dialog(
                onDismissRequest = { viewModel.closeSoundPanel() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInHorizontally { it },
                        exit = slideOutHorizontally { it },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        SoundPanel(
                            sfxSupported = uiState.sfxSupported,
                            sfxOnA2dp = uiState.sfxOnA2dp,
                            sfxMode = uiState.sfxMode,
                            sfxStrength = uiState.sfxStrength,
                            sfxModeKeys = uiState.sfxModeKeys,
                            sfxModeNames = uiState.sfxModeNames,
                            sfxModeSupported = uiState.sfxModeSupported,
                            onSetSfxMode = { viewModel.setSfxMode(it) },
                            onSetSfxStrength = { viewModel.setSfxStrength(it) },
                            eqSupported = uiState.eqSupported,
                            eqEnabled = uiState.eqEnabled,
                            eqPreset = uiState.eqPreset,
                            eqPresetKeys = uiState.eqPresetKeys,
                            eqPresetNames = uiState.eqPresetNames,
                            eqBands = uiState.eqBands,
                            eqBandFrequencies = uiState.eqBandFrequencies,
                            eqBandLevelMin = uiState.eqBandLevelMin,
                            eqBandLevelMax = uiState.eqBandLevelMax,
                            onSetEqEnabled = { viewModel.setEqualizerEnabled(it) },
                            onSetEqPreset = { viewModel.setEqualizerPreset(it) },
                            onSetEqBand = { bandIndex, levelDb ->
                                viewModel.setEqualizerBand(bandIndex, levelDb)
                            },
                            initialFocusRequester = soundPanelFocus,
                            modifier = Modifier.fillMaxHeight().width(420.dp)
                        )
                    }
                }
            }
        }

        if (uiState.showExitKaraokeConfirm) {
            ExitKaraokeConfirmDialog(
                onConfirm = { viewModel.exitKaraokeMode() },
                onDismiss = { viewModel.dismissExitKaraokeConfirm() }
            )
        }
    }
}

@Composable
private fun ExitKaraokeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "确定退出 K 歌吗？",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
            Row {
                ExitKaraokeDialogButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus)
                )
                Spacer(Modifier.width(16.dp))
                ExitKaraokeDialogButton(
                    text = "退出",
                    onClick = onConfirm
                )
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

@Composable
private fun ExitKaraokeDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .tvFocusable(cornerRadius = 8.dp, onClick = onClick)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 28.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
