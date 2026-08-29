package com.songloft.tv

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songloft.tv.data.model.Song
import com.songloft.tv.data.storage.PreferencesDataStore
import com.songloft.tv.data.storage.ResumeSnapshotStore
import com.songloft.tv.domain.KeyMappingManager
import com.songloft.tv.domain.MappingTarget
import com.songloft.tv.domain.PlayMode
import com.songloft.tv.domain.PlayerController
import com.songloft.tv.ui.config.AuthSetupScreen
import com.songloft.tv.ui.config.AuthState
import com.songloft.tv.ui.config.AuthViewModel
import com.songloft.tv.ui.components.DisclaimerDialog
import com.songloft.tv.ui.components.FloatingPlayerBar
import com.songloft.tv.ui.components.HelpDialog
import com.songloft.tv.ui.components.LocalFloatingPlayerFocusRequester
import com.songloft.tv.ui.components.tvFocusable
import com.songloft.tv.ui.home.HomeScreen
import com.songloft.tv.ui.library.FacetListScreen
import com.songloft.tv.ui.library.FilteredSongsScreen
import com.songloft.tv.ui.my.MyScreen
import com.songloft.tv.ui.navigation.LocalPageScrollBridge
import com.songloft.tv.ui.navigation.LocalTabBarBridge
import com.songloft.tv.ui.navigation.PageScrollBridge
import com.songloft.tv.ui.navigation.Screen
import com.songloft.tv.ui.navigation.stateKey
import com.songloft.tv.ui.navigation.TabBarBridge
import com.songloft.tv.ui.navigation.TvBottomNav
import com.songloft.tv.ui.player.PlayerActivity
import com.songloft.tv.ui.playlist.PlaylistDetailScreen
import com.songloft.tv.ui.playlist.PlaylistsScreen
import com.songloft.tv.ui.search.SearchScreen
import com.songloft.tv.ui.settings.SettingsScreen
import com.songloft.tv.ui.stats.StatsScreen
import com.songloft.tv.ui.theme.TvTheme
import com.songloft.tv.ui.update.UpdateDialog
import com.songloft.tv.ui.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var preferencesDataStore: PreferencesDataStore

    @Inject
    lateinit var resumeSnapshotStore: ResumeSnapshotStore

    @Inject
    lateinit var keyMappingManager: KeyMappingManager

    /** 全局「返回顶部/返回底部」回调桥，由当前组合中的页面注册滚动实现 */
    val pageScrollBridge = PageScrollBridge()

    /** 用户自定义按键映射：特殊功能键（返回顶部/底部）拦截处理，其余命中映射表的 keycode 翻译成标准功能键后继续分发 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyMappingManager.matchSpecialKey(event.keyCode)) {
                MappingTarget.TOP -> {
                    pageScrollBridge.scrollToTop?.invoke()
                    return true
                }
                MappingTarget.BOTTOM -> {
                    pageScrollBridge.scrollToBottom?.invoke()
                    return true
                }
                else -> {}
            }
        }
        return super.dispatchKeyEvent(keyMappingManager.translateEvent(event))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPageScrollBridge provides pageScrollBridge) {
                TvTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MainApp(
                            preferencesDataStore = preferencesDataStore,
                            resumeSnapshotStore = resumeSnapshotStore,
                            playerController = playerController,
                            onPlaySongs = { songs, index, contextType, contextKey ->
                                openPlayer(songs, index, contextType, contextKey)
                            },
                            onShufflePlay = { songs, contextType, contextKey ->
                                playerController.setPlayMode(PlayMode.RANDOM)
                                openPlayer(songs, Random.nextInt(songs.size), contextType, contextKey)
                            },
                            onOpenPlayer = {
                                startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
                            },
                            onExit = { exitApp() }
                        )
                    }
                }
            }
        }
    }

    private fun openPlayer(
        songs: List<Song>,
        index: Int,
        contextType: String? = null,
        contextKey: String? = null
    ) {
        playerController.play(songs, index, contextType, contextKey)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun exitApp() {
        lifecycleScope.launch {
            val backgroundPlayback = preferencesDataStore.backgroundPlayback.first()
            if (backgroundPlayback) {
                finish()
            } else {
                stopService(Intent(this@MainActivity, MusicService::class.java))
                finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            }
        }
    }
}

@Composable
fun MainApp(
    preferencesDataStore: PreferencesDataStore,
    resumeSnapshotStore: ResumeSnapshotStore,
    playerController: PlayerController,
    onPlaySongs: (List<Song>, Int, String?, String?) -> Unit,
    onShufflePlay: (List<Song>, String?, String?) -> Unit,
    onOpenPlayer: () -> Unit,
    onExit: () -> Unit
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    when (authState) {
        is AuthState.LoggedIn -> TvApp(
            preferencesDataStore, resumeSnapshotStore, authViewModel, playerController,
            onPlaySongs, onShufflePlay, onOpenPlayer, onExit
        )
        else -> AuthSetupScreen(authViewModel)
    }
}

@Composable
fun TvApp(
    preferencesDataStore: PreferencesDataStore,
    resumeSnapshotStore: ResumeSnapshotStore,
    authViewModel: AuthViewModel,
    playerController: PlayerController,
    onPlaySongs: (List<Song>, Int, String?, String?) -> Unit,
    onShufflePlay: (List<Song>, String?, String?) -> Unit,
    onOpenPlayer: () -> Unit,
    onExit: () -> Unit
) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val currentScreen = backStack.last()
    val stateHolder = rememberSaveableStateHolder()
    val tabBarBridge = remember { TabBarBridge() }
    val floatingPlayerFocusRequester = remember { FocusRequester() }
    var showExitDialog by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun push(screen: Screen) {
        backStack.add(screen)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // 首次启动（未展示过版权/免责声明）进入主界面时弹窗；任意方式关闭即标记已展示
    LaunchedEffect(Unit) {
        showDisclaimer = !preferencesDataStore.disclaimerShown.first()
    }

    BackHandler {
        when {
            backStack.size > 1 -> goBack()
            currentScreen != Screen.Home -> backStack[0] = Screen.Home
            else -> showExitDialog = true
        }
    }

    if (showExitDialog) {
        ExitConfirmDialog(
            onConfirm = onExit,
            onDismiss = { showExitDialog = false }
        )
    }

    val updateViewModel: UpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateViewModel.autoCheckOnLaunch() }

    // 开机自动续播：登录完成即恢复上次队列与进度开始播放，悬浮播放器条随之出现；
    // 已有播放队列时跳过（后台播放存活的重启场景，或防止重复恢复）
    LaunchedEffect(Unit) {
        if (!preferencesDataStore.autoResumeOnLaunch.first()) return@LaunchedEffect
        if (playerController.state.value.queue.isNotEmpty()) return@LaunchedEffect
        resumeSnapshotStore.snapshot.first()?.let { snapshot ->
            playerController.resumePlayback(snapshot)
        }
    }

    // 自动进入播放器：开关开启且启动时存在播放中歌曲（后台播放存活，或续播即将恢复）时，
    // 等待 MediaController 连接同步出当前歌曲后直接进入全屏播放器
    LaunchedEffect(Unit) {
        if (!preferencesDataStore.autoOpenPlayerOnLaunch.first()) return@LaunchedEffect
        val hasPlayback = playerController.state.value.currentSong != null ||
            (preferencesDataStore.autoResumeOnLaunch.first() &&
                resumeSnapshotStore.snapshot.first() != null)
        if (!hasPlayback) return@LaunchedEffect
        playerController.state.first { it.currentSong != null }
        onOpenPlayer()
    }
    UpdateDialog(
        state = updateState,
        onStartDownload = updateViewModel::startDownload,
        onIgnore = updateViewModel::ignoreVersion,
        onRetryCheck = updateViewModel::manualCheck,
        onDismiss = updateViewModel::dismiss
    )

    // 「操作说明」按钮：先关闭免责声明并标记已展示，再打开帮助弹窗，返回键只会回到主界面
    if (showDisclaimer) {
        DisclaimerDialog(
            onOpenHelp = {
                showDisclaimer = false
                showHelpDialog = true
                scope.launch { preferencesDataStore.setDisclaimerShown() }
            },
            onDismiss = {
                showDisclaimer = false
                scope.launch { preferencesDataStore.setDisclaimerShown() }
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    CompositionLocalProvider(LocalTabBarBridge provides tabBarBridge) {
        Scaffold(
            bottomBar = {
                TvBottomNav(
                    currentScreen = currentScreen,
                    onScreenSelected = { tab ->
                        if (backStack.size != 1 || backStack[0] != tab) {
                            backStack.clear()
                            backStack.add(tab)
                        }
                    }
                )
            }
        ) { padding ->
            val playbackState by playerController.state.collectAsStateWithLifecycle()

            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                CompositionLocalProvider(
                    LocalFloatingPlayerFocusRequester provides
                        floatingPlayerFocusRequester.takeIf { playbackState.currentSong != null }
                ) {
                    stateHolder.SaveableStateProvider(currentScreen.stateKey) {
                        when (val screen = currentScreen) {
                            Screen.Home -> HomeScreen(
                                onPlaylistClick = { id -> push(Screen.PlaylistDetail(id)) },
                                onArtistClick = { artist -> push(Screen.SongFilter("artist", artist)) },
                                onAlbumClick = { album -> push(Screen.SongFilter("album", album)) },
                                onYearClick = { year -> push(Screen.SongFilter("year", year.toString())) },
                                onViewAll = { field -> push(Screen.FacetList(field)) },
                                onManagePlaylists = { push(Screen.Playlists) },
                                onStatsClick = { push(Screen.Stats) }
                            )
                            Screen.Search -> SearchScreen(
                                onSongClick = { songs, index -> onPlaySongs(songs, index, null, null) }
                            )
                            Screen.Playlists -> PlaylistsScreen(
                                onPlaylistClick = { id -> push(Screen.PlaylistDetail(id)) }
                            )
                            is Screen.PlaylistDetail -> PlaylistDetailScreen(
                                playlistId = screen.playlistId,
                                onSongClick = { songs, index ->
                                    onPlaySongs(songs, index, "playlist", screen.playlistId.toString())
                                },
                                onShufflePlay = { songs ->
                                    onShufflePlay(songs, "playlist", screen.playlistId.toString())
                                },
                                onBack = { goBack() }
                            )
                            Screen.My -> MyScreen(
                                onSongClick = { songs, index -> onPlaySongs(songs, index, null, null) },
                                onNavigateToSettings = { push(Screen.Settings) }
                            )
                            Screen.Settings -> SettingsScreen(
                                onBack = { goBack() },
                                onConfigureServer = { authViewModel.resetToConfig() },
                                onLogout = { authViewModel.logout() }
                            )
                            is Screen.SongFilter -> FilteredSongsScreen(
                                field = screen.field,
                                value = screen.value,
                                onSongClick = { songs, index ->
                                    onPlaySongs(songs, index, screen.field, screen.value)
                                },
                                onBack = { goBack() }
                            )
                            is Screen.FacetList -> FacetListScreen(
                                field = screen.field,
                                onItemClick = { value -> push(Screen.SongFilter(screen.field, value)) },
                                onBack = { goBack() }
                            )
                            Screen.Stats -> StatsScreen(
                                onBack = { goBack() }
                            )
                        }
                    }

                    playbackState.currentSong?.let { song ->
                        FloatingPlayerBar(
                            title = song.title,
                            artist = song.artist,
                            coverUrl = song.coverUrl,
                            isPlaying = playbackState.isPlaying,
                            onClick = onOpenPlayer,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(24.dp)
                                .focusRequester(floatingPlayerFocusRequester)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "确定退出吗？",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            Row {
                ExitDialogButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocusRequester)
                )
                Spacer(Modifier.width(16.dp))
                ExitDialogButton(
                    text = "退出",
                    onClick = onConfirm
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocus()
    }
}

@Composable
private fun ExitDialogButton(
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
