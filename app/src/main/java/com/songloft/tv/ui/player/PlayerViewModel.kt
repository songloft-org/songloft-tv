package com.songloft.tv.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songloft.tv.data.config.ConfigWebServer
import com.songloft.tv.data.model.LyricLine
import com.songloft.tv.data.model.Song
import com.songloft.tv.data.model.Track
import com.songloft.tv.data.repository.FavoriteRepository
import com.songloft.tv.data.repository.SongRepository
import com.songloft.tv.data.storage.PreferencesDataStore
import com.songloft.tv.domain.LyricParser
import com.songloft.tv.domain.PlayMode
import com.songloft.tv.domain.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class PlayerUiState(
    val currentSong: Song? = null,
    val currentTrack: Track? = null,
    val availableTracks: List<Track> = emptyList(),
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val playMode: PlayMode = PlayMode.ORDER,
    val lyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1,
    val showControls: Boolean = true,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val showQueueDrawer: Boolean = false,
    val showSoundPanel: Boolean = false,
    val isVideoMode: Boolean = false,
    val isBuffering: Boolean = false,
    val isFavorite: Boolean = false,
    val isLyricRefreshing: Boolean = false,
    // 均衡器（来自 playerController.state，频段增益单位 dB）
    val eqSupported: Boolean = false,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqPresetKeys: List<String> = emptyList(),
    val eqPresetNames: List<String> = emptyList(),
    val eqBands: List<Int> = emptyList(),
    val eqBandFrequencies: List<Int> = emptyList(),
    val eqBandLevelMin: Int = -1500,
    val eqBandLevelMax: Int = 1500,
    // 音效模式（来自 playerController.state）
    val sfxSupported: Boolean = false,
    val sfxEnabled: Boolean = false,
    val sfxMode: String = "virtualizer",
    val sfxStrength: Int = 50,
    val sfxModeKeys: List<String> = emptyList(),
    val sfxModeNames: List<String> = emptyList(),
    val sfxModeSupported: List<Boolean> = emptyList(),
    val sfxOnA2dp: Boolean = false,
    val vocalRemovalEnabled: Boolean = false,
    val lyricHighlightColor: Int = 2,
    val lyricFontSize: Int = 30,
    // K 歌模式开关
    val karaokeModeEnabled: Boolean = false,
    // K 歌"扫码点歌"服务器地址（null 表示未开启）
    val karaokeOrderUrl: String? = null,
    // K 歌独立播放列表（与主页队列隔离）；非 K 歌模式时为空
    val karaokeList: List<Song> = emptyList(),
    // 当前是否处于"伴唱"：双音轨资源看所选音轨，否则看人声消除开关
    val isAccompanimentOn: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val songRepository: SongRepository,
    private val favoriteRepository: FavoriteRepository,
    private val dataStore: PreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var lyricSongId: Long? = null

    override fun onCleared() {
        stopKaraokeOrderServer()
    }

    init {
        viewModelScope.launch {
            favoriteRepository.ensureFavoriteIdsLoaded()
        }
        viewModelScope.launch {
            favoriteRepository.favoriteIds.collect { ids ->
                _uiState.update {
                    it.copy(isFavorite = it.currentSong?.id?.let { id -> ids?.contains(id) } == true)
                }
            }
        }
        viewModelScope.launch {
            dataStore.lyricHighlightColor.collect { mode ->
                _uiState.update { it.copy(lyricHighlightColor = mode) }
            }
        }
        viewModelScope.launch {
            dataStore.lyricFontSize.collect { size ->
                _uiState.update { it.copy(lyricFontSize = size) }
            }
        }
        viewModelScope.launch {
            playerController.state.collect { s ->
                _uiState.update {
                    it.copy(
                        currentSong = s.currentSong,
                        currentTrack = s.currentTrack,
                        availableTracks = s.embeddedTracks.ifEmpty { s.currentSong?.tracks.orEmpty() },
                        isPlaying = s.isPlaying,
                        isBuffering = s.isBuffering,
                        duration = s.duration,
                        playMode = s.playMode,
                        queue = s.queue,
                        currentIndex = s.currentIndex,
                        karaokeList = s.karaokeList,
                        isVideoMode = s.currentSong?.isVideo == true,
                        eqSupported = s.eqSupported,
                        eqEnabled = s.eqEnabled,
                        eqPreset = s.eqPreset,
                        eqPresetKeys = s.eqPresetKeys,
                        eqPresetNames = s.eqPresetNames,
                        eqBands = s.eqBands,
                        eqBandFrequencies = s.eqBandFrequencies,
                        eqBandLevelMin = s.eqBandLevelMin,
                        eqBandLevelMax = s.eqBandLevelMax,
                        sfxSupported = s.sfxSupported,
                        sfxEnabled = s.sfxEnabled,
                        sfxMode = s.sfxMode,
                        sfxStrength = s.sfxStrength,
                        sfxModeKeys = s.sfxModeKeys,
                        sfxModeNames = s.sfxModeNames,
                        sfxModeSupported = s.sfxModeSupported,
                        sfxOnA2dp = s.sfxOnA2dp,
                        vocalRemovalEnabled = s.vocalRemovalEnabled,
                        isAccompanimentOn = playerController.isAccompanimentOn()
                    )
                }
                val songId = s.currentSong?.id
                if (songId != null && songId != lyricSongId) {
                    lyricSongId = songId
                    if (s.currentSong?.type != "radio") {
                        loadLyrics(songId)
                    } else {
                        // 电台是持续流媒体，无歌词，直接清空避免残留上一首歌词
                        _uiState.update {
                            it.copy(lyrics = emptyList(), currentLyricIndex = -1, isLyricRefreshing = false)
                        }
                    }
                    _uiState.update {
                        it.copy(isFavorite = favoriteRepository.favoriteIds.value?.contains(songId) == true)
                    }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isPlaying) {
                    updatePosition(playerController.currentPosition())
                    val duration = playerController.duration()
                    if (duration > 0 && duration != _uiState.value.duration) {
                        _uiState.update { it.copy(duration = duration) }
                    }
                }
                // 逐字歌词需要更高刷新率才能平滑高亮
                val hasWords = _uiState.value.lyrics.any { it.hasWords }
                delay(if (hasWords) 60L else 500L)
            }
        }
    }

    fun playSong(song: Song, queue: List<Song> = listOf(song), index: Int = 0) {
        playerController.play(queue, index)
    }

    fun togglePlay() = playerController.togglePlay()

    fun seekTo(position: Long) {
        playerController.seekTo(position)
        updatePosition(position)
    }

    fun seekBy(deltaMs: Long) {
        val duration = playerController.duration()
        val target = (playerController.currentPosition() + deltaMs)
            .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        seekTo(target)
    }

    fun nextTrack() = playerController.next()

    /** 重唱：当前曲目从头开始（K 歌模式使用） */
    fun reSing() = playerController.seekTo(0)

    fun previousTrack() = playerController.previous()

    fun playAt(index: Int) = playerController.playAt(index)

    fun cyclePlayMode() = playerController.cyclePlayMode()

    fun switchTrack(track: Track) = playerController.switchTrack(track)

    fun cycleAudioTrack() {
        val s = _uiState.value
        val tracks = s.availableTracks
        if (tracks.size < 2) return
        val currentIndex = tracks.indexOfFirst { it.id == s.currentTrack?.id }
        switchTrack(tracks[(currentIndex + 1) % tracks.size])
    }

    fun toggleAccompaniment() = playerController.toggleAccompanimentMode()

    // ===== 扫码点歌（队列管理）=====
    fun addToQueue(song: com.songloft.tv.data.model.Song) = playerController.addToQueue(song)
    fun moveQueueToTop(index: Int) = playerController.moveToTop(index)
    fun removeFromQueue(index: Int) = playerController.removeFromQueue(index)

    fun withPlayer(action: (androidx.media3.common.Player) -> Unit) = playerController.withPlayer(action)

    fun toggleFavorite() {
        val song = _uiState.value.currentSong ?: return
        viewModelScope.launch {
            favoriteRepository.toggleFavorite(song).onFailure { e ->
                Log.w("PlayerViewModel", "toggleFavorite failed for song ${song.id}", e)
            }
        }
    }

    fun toggleQueueDrawer() {
        _uiState.update { it.copy(showQueueDrawer = !it.showQueueDrawer) }
    }

    fun closeQueueDrawer() {
        _uiState.update { it.copy(showQueueDrawer = false) }
    }

    fun toggleSoundPanel() {
        val opening = !_uiState.value.showSoundPanel
        _uiState.update { it.copy(showSoundPanel = opening) }
        // 打开时刷新两组能力与状态（设备切换后数据可能过期）
        if (opening) {
            playerController.refreshEqInfo()
            playerController.refreshSfxInfo()
        }
    }

    fun closeSoundPanel() {
        _uiState.update { it.copy(showSoundPanel = false) }
    }

    fun setSfxMode(mode: String) = playerController.setSfxMode(mode)

    fun setSfxStrength(strength: Int) = playerController.setSfxStrength(strength)

    fun setEqualizerEnabled(enabled: Boolean) = playerController.setEqualizerEnabled(enabled)

    fun setEqualizerPreset(preset: String) = playerController.setEqualizerPreset(preset)

    fun setEqualizerBand(bandIndex: Int, levelDb: Int) = playerController.setEqualizerBand(bandIndex, levelDb)

    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun updatePosition(position: Long) {
        val lyrics = _uiState.value.lyrics
        val index = lyrics.indexOfLast { it.time <= position }
        _uiState.update {
            it.copy(currentPosition = position, currentLyricIndex = index)
        }
    }

    fun hideControls() {
        _uiState.update { it.copy(showControls = false) }
    }

    fun showControls() {
        _uiState.update { it.copy(showControls = true) }
    }

    private fun loadLyrics(songId: Long) {
        _uiState.update { it.copy(lyrics = emptyList(), currentLyricIndex = -1, isLyricRefreshing = false) }
        viewModelScope.launch {
            songRepository.getSongLyric(songId).onSuccess { resp ->
                val parsed = LyricParser.parsePayload(
                    lyric = resp.lyric,
                    tlyric = resp.tlyric,
                    rlyric = resp.rlyric,
                    lxlyric = resp.lxlyric
                )
                _uiState.update { it.copy(lyrics = parsed) }
            }
        }
    }

    /** 手动重新拉取歌词：refresh=1 让服务端跳过自动缓存（空/scraped/cached）重跑歌词插件搜索；权威歌词（file/embedded/manual）不被覆盖 */
    fun refreshLyrics() {
        val songId = lyricSongId ?: return
        if (_uiState.value.isLyricRefreshing) return
        _uiState.update { it.copy(isLyricRefreshing = true) }
        viewModelScope.launch {
            songRepository.getSongLyric(songId, refresh = true).onSuccess { resp ->
                val parsed = LyricParser.parsePayload(
                    lyric = resp.lyric,
                    tlyric = resp.tlyric,
                    rlyric = resp.rlyric,
                    lxlyric = resp.lxlyric
                )
                _uiState.update { it.copy(lyrics = parsed) }
            }
            _uiState.update { it.copy(isLyricRefreshing = false) }
        }
    }
    
    // ========== K 歌模式相关 ==========

    private var karaokeOrderServer: ConfigWebServer? = null

    fun enterKaraokeMode() {
        _uiState.update { it.copy(karaokeModeEnabled = true) }
        // 进入 K 歌默认原唱：不切换音轨/不消人声，保持主播放器原样
        // 建立独立的 K 歌播放列表（备份并隔离主页队列）
        playerController.enterKaraoke()
        startKaraokeOrderServer()
    }

    fun exitKaraokeMode() {
        _uiState.update { it.copy(karaokeModeEnabled = false) }
        // 退出时停止扫码点歌服务
        stopKaraokeOrderServer()
        // 退出 K 歌：还原主页播放队列
        playerController.exitKaraoke()
        // 退出前强制切回原唱，保证主播放器始终为原唱
        playerController.restoreOriginal()
    }

    // ===== K 歌独立列表管理（与扫码点歌共用同一份列表）=====
    fun karaokeAdd(song: com.songloft.tv.data.model.Song) = playerController.karaokeAdd(song)
    fun karaokeMoveTop(index: Int) = playerController.karaokeMoveTop(index)
    fun karaokeRemove(index: Int) = playerController.karaokeRemove(index)
    fun karaokePlayAt(index: Int) = playerController.karaokePlayAt(index)

    // ===== 扫码点歌（局域网 Web 服务）=====
    // 借用 ConfigWebServer 已有的"点歌"页签：手机扫码后可在「点歌」页搜索/加入/置顶/删除歌曲。
    private fun startKaraokeOrderServer() {
        if (karaokeOrderServer != null) return
        val ip = ConfigWebServer.localIpAddress() ?: return
        for (port in KARAOKE_ORDER_PORTS) {
            val server = ConfigWebServer(
                port,
                onOrderSearch = { keyword ->
                    runBlocking {
                        songRepository.getSongs(keyword = keyword, limit = 50)
                            .getOrNull()?.songs.orEmpty()
                    }
                },
                onOrderAdd = { karaokeAdd(it) },
                onOrderTop = { karaokeMoveTop(it) },
                onOrderRemove = { karaokeRemove(it) },
                onOrderQueue = { playerController.getKaraokeList() }
            )
            if (runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }.isSuccess) {
                karaokeOrderServer = server
                _uiState.update { it.copy(karaokeOrderUrl = "http://$ip:$port/#order") }
                return
            }
        }
    }

    private fun stopKaraokeOrderServer() {
        karaokeOrderServer?.stop()
        karaokeOrderServer = null
        _uiState.update { it.copy(karaokeOrderUrl = null) }
    }

    companion object {
        private val KARAOKE_ORDER_PORTS = intArrayOf(18911, 18912, 18913, 18914)
    }
}
