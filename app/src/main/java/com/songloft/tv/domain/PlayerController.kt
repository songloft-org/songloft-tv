package com.songloft.tv.domain

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.songloft.tv.MusicService
import com.songloft.tv.data.api.ApiClient
import com.songloft.tv.data.api.UrlHelper
import com.songloft.tv.data.model.Song
import com.songloft.tv.data.model.Track
import com.songloft.tv.data.repository.SongRepository
import com.songloft.tv.data.storage.PreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.round

// 固定均衡器预设（借鉴 songloft-player 的 10 段曲线与中文名）：
// 不吃设备系统预设，名称恒定中文、听感跨设备一致；"custom" 表示手动调出的自定义曲线
private val EQ_PRESETS = linkedMapOf(
    "flat" to ("平坦" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    "rock" to ("摇滚" to listOf(5, 4, 2, 0, -1, 1, 3, 4, 5, 4)),
    "pop" to ("流行" to listOf(-1, 2, 4, 5, 4, 2, 0, -1, -1, -1)),
    "jazz" to ("爵士" to listOf(4, 3, 1, 2, -1, -1, 0, 2, 3, 4)),
    "classical" to ("古典" to listOf(5, 4, 3, 2, -1, -1, 0, 3, 4, 5)),
    "bass_boost" to ("低音提升" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)),
    "treble_boost" to ("高音增强" to listOf(0, 0, 0, 0, 0, 0, 2, 4, 5, 6)),
    "vocal" to ("人声" to listOf(-2, -1, 0, 3, 5, 5, 3, 1, 0, -2))
)
private val EQ_PRESET_KEYS = EQ_PRESETS.keys.toList() + "custom"
private val EQ_PRESET_NAMES = EQ_PRESETS.values.map { it.first } + "自定义"

// 预设曲线的参考频点（Hz），与 EQ_PRESETS 的增益一一对应
private val EQ_PRESET_FREQS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

// 音效模式：与均衡器独立叠加；单模式互斥
// 开关由设置页总开关控制，模式列表不含"关闭"；支持矩阵顺序与 SFX_MODE_KEYS 一一对应（MusicService.SfxType 声明序）
private val SFX_MODES = linkedMapOf(
    "virtualizer" to "环绕立体声",
    "bass_boost" to "低音增强",
    "loudness" to "响度增强",
    "reverb" to "音乐厅混响"
)
private val SFX_MODE_KEYS = SFX_MODES.keys.toList()
private val SFX_MODE_NAMES = SFX_MODES.values.toList()

// 伴唱模式下被临时替换的音效设置（开关/模式/强度），切回原唱时还原
private data class SfxBackup(val enabled: Boolean, val mode: String, val strength: Int)

enum class PlayMode { ORDER, LOOP, SINGLE, RANDOM }

data class PlaybackState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val currentSong: Song? = null,
    val currentTrack: Track? = null,
    // 媒体文件内嵌的多条音轨（如 MKV 中的原唱/伴奏），由 onTracksChanged 检测
    val embeddedTracks: List<Track> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0L,
    val playMode: PlayMode = PlayMode.ORDER,
    // 播放上下文（服务端播放历史）：playlist 传歌单 ID，分面传 artist/album/year 等
    val contextType: String? = null,
    val contextKey: String? = null,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemaining: Int = 0,
    val sleepAfterSongs: Int = 0,
    val sleepAfterSongsRemaining: Int = 0,
    // 均衡器（来自 MusicService 的 audiofx.Equalizer，频段增益单位 dB）
    val eqSupported: Boolean = false,
    val eqEnabled: Boolean = false,
    // 预设 key（"flat"/"rock"/...，"custom" = 自定义曲线），名称与增益见 EQ_PRESETS
    val eqPreset: String = "flat",
    val eqPresetKeys: List<String> = EQ_PRESET_KEYS,
    val eqPresetNames: List<String> = EQ_PRESET_NAMES,
    val eqBands: List<Int> = emptyList(),
    val eqBandFrequencies: List<Int> = emptyList(),
    val eqBandLevelMin: Int = -1500,
    val eqBandLevelMax: Int = 1500,
    // 音效模式（audiofx 效果器，与均衡器独立叠加）：开关 / 模式 key / 强度 0-100
    val sfxEnabled: Boolean = false,
    val sfxMode: String = "virtualizer",
    val sfxStrength: Int = 50,
    val sfxModeKeys: List<String> = SFX_MODE_KEYS,
    val sfxModeNames: List<String> = SFX_MODE_NAMES,
    // 各模式在当前输出设备上的可用性（顺序同 sfxModeKeys）
    val sfxModeSupported: List<Boolean> = emptyList(),
    // 当前输出设备是否支持至少一种音效（设置页开启校验用）
    val sfxSupported: Boolean = false,
    // 蓝牙 A2DP 输出时多数 audiofx 效果不生效，UI 需提示
    val sfxOnA2dp: Boolean = false,
    // 服务端实际生效的模式（设备切换后可能暂时停用）
    val sfxActiveMode: String = "off",
    val vocalRemovalEnabled: Boolean = false,
    val vocalRemovalSupported: Boolean = false,
    // 预转码状态
    val isPreTranscoding: Boolean = false,
    // K 歌独立播放列表：与主页播放队列完全隔离，退出 K 歌后还原主页队列
    val karaokeActive: Boolean = false,
    val karaokeList: List<Song> = emptyList()
)

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val dataStore: PreferencesDataStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioQuality: String? = null
    private var preTranscodeEnabledCache: Boolean = false // 预转码总开关状态

    private var eqEnabledCache: Boolean = false
    private var eqBandsCache: List<Int> = emptyList()

    private var sfxEnabledCache: Boolean = false
    private var sfxModeCache: String = "virtualizer"
    private var sfxStrengthCache: Int = 50
    private var vocalRemovalEnabledCache: Boolean = false

    // 伴唱模式下音效的运行时备份（不落盘）：切伴唱时备份当前音效并临时切响度，回原唱时还原
    private var sfxBackup: SfxBackup? = null
    
    // ===== 预转码相关字段 =====
    private var preTranscodeJob: Job? = null
    private val PRE_TRANSCODE_DELAY_MS = 60_000L      // 第一次延迟：60 秒
    private val PRE_TRANSCODE_DELAY_AFTER_MODE_CHANGE = 6_000L // 模式切换后延迟：6 秒
    private var isFirstPreTranscode = true // 标记是否为首次预转码
    private var lastPlayedSongId: Long? = null // 记录最后一次触发预转码的歌曲 ID

    // 输出设备切换（HDMI/蓝牙/内置喇叭）后音效能力可能变化，主动刷新让 UI 实时感知
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshSfxInfo()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshSfxInfo()
        }
    }

    init {
        scope.launch {
            dataStore.audioQuality.collect { audioQuality = it?.takeIf { q -> q.isNotBlank() } }
        }
        // 预转码总开关监听
        scope.launch {
            dataStore.preTranscodeEnabled.collect { enabled ->
                val wasEnabled = preTranscodeEnabledCache
                preTranscodeEnabledCache = enabled ?: false
                _state.update { it.copy(isPreTranscoding = it.isPreTranscoding) } // 保持状态同步
                // 如果用户关闭了预转码开关，取消所有正在进行的预转码任务
                if (wasEnabled && !preTranscodeEnabledCache) {
                    cancelPreTranscode()
                    isFirstPreTranscode = true
                    lastPlayedSongId = null
                    Log.d(TAG, "用户关闭预转码总开关，已取消所有预转码任务")
                }
            }
        }
        // 均衡器配置持久化闭环：UI 只写 DataStore，这里缓存并推送给 MusicService
        scope.launch {
            combine(dataStore.eqEnabled, dataStore.eqBands) { enabled, bands ->
                enabled to parseBands(bands)
            }.collect { (enabled, bands) ->
                eqEnabledCache = enabled
                eqBandsCache = bands
                _state.update { it.copy(eqEnabled = enabled, eqBands = bands) }
                if (controller != null) sendEqApply(enabled, bands)
            }
        }
        scope.launch {
            dataStore.eqPreset.collect { preset ->
                _state.update { it.copy(eqPreset = preset) }
            }
        }
        // 播放模式持久化闭环：重启后恢复上次模式，并在控制器已连接时即时重放
        scope.launch {
            dataStore.playMode.collect { mode ->
                val playMode = runCatching { PlayMode.valueOf(mode) }.getOrDefault(PlayMode.ORDER)
                _state.update { it.copy(playMode = playMode) }
                controller?.let { applyPlayMode(it, playMode) }
            }
        }
        // 音效模式配置持久化闭环，同均衡器
        scope.launch {
            combine(dataStore.sfxEnabled, dataStore.sfxMode, dataStore.sfxStrength) { enabled, mode, strength ->
                Triple(enabled, mode, strength)
            }.collect { (enabled, mode, strength) ->
                // 旧版本可能存有 "off"，模式列表已不含"关闭"，归一化为默认模式
                val effectiveMode = if (mode == "off") "virtualizer" else mode
                sfxEnabledCache = enabled
                sfxModeCache = effectiveMode
                sfxStrengthCache = strength
                _state.update { it.copy(sfxEnabled = enabled, sfxMode = effectiveMode, sfxStrength = strength) }
                if (controller != null) sendSfxApply(enabled, effectiveMode, strength)
            }
        }
        // AudioDeviceCallback 为 API 23+，低版本设备跳过（音效能力按连接时查询为准）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .registerAudioDeviceCallback(audioDeviceCallback, null)
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // K 歌独立列表：进入时备份主页队列，退出时还原，期间所有增删/置顶只作用于 karaokeList
    private var mainQueueBackup: List<Song>? = null
    private var mainIndexBackup: Int = -1
    private var mainPosBackup: Long = 0L

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousSong = _state.value.currentSong
            val wasKaraoke = _state.value.karaokeActive
            val activeList = if (wasKaraoke) _state.value.karaokeList else _state.value.queue
            val oldIndex = _state.value.currentIndex
            val song = activeList.firstOrNull { it.id.toString() == mediaItem?.mediaId }
            val newIndex = controller?.currentMediaItemIndex ?: -1

            reportTransition(previousSong, song, reason)
            countDownSleepAfterSongs(previousSong, song, reason)
            _state.update {
                it.copy(
                    currentSong = song,
                    currentIndex = controller?.currentMediaItemIndex ?: -1,
                    // 音轨切换会重建同一首歌的 MediaItem，此时保留已选音轨
                    currentTrack = if (song != null && song.id == previousSong?.id) {
                        it.currentTrack
                    } else {
                        song?.tracks?.firstOrNull()
                    },
                    embeddedTracks = if (song != null && song.id == previousSong?.id) {
                        it.embeddedTracks
                    } else {
                        emptyList()
                    },
                    duration = ((song?.duration ?: 0.0) * 1000).toLong()
                )
            }
            // 新歌默认回到第一轨（原唱），伴唱音效覆盖还原（同曲音轨切换保留覆盖）
            if (song != null && song.id != previousSong?.id) {
                setTrackSfxOverride(accompaniment = false)
                if (_state.value.vocalRemovalEnabled) {
                    setVocalRemovalEnabled(false)
                }
            }

            // K 歌：仅当上一首"自然播放结束"（唱完）才从独立列表中移除，列表始终只保留未唱歌曲；
            // 切歌/跳过不视为唱过，保留在列表中。移除后当前曲前移一位。该操作只影响 karaokeList，
            // 不会改动主播放器 queue。
            if (wasKaraoke && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                && previousSong != null && song != null && song.id != previousSong.id
                && oldIndex in _state.value.karaokeList.indices && oldIndex < newIndex
            ) {
                val newList = _state.value.karaokeList.toMutableList().apply { removeAt(oldIndex) }
                _state.update { it.copy(karaokeList = newList, currentIndex = newIndex - 1) }
                withController { c -> if (oldIndex in 0 until c.mediaItemCount) c.removeMediaItem(oldIndex) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "onPlaybackStateChanged: $name")
            val duration = controller?.duration ?: 0L
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    duration = if (duration > 0) duration else it.duration
                )
            }
            // 音频会话在首次播放时才创建，均衡器可能晚于控制器连接就绪，播放就绪后重试一次
            if (playbackState == Player.STATE_READY) {
                if (_state.value.eqBandFrequencies.isEmpty()) retryEqSetup()
                if (_state.value.sfxModeSupported.isEmpty()) retrySfxSetup()
                controller?.let { checkVocalRemovalSupport(it) }
                startPreTranscode()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName} | ${error.message}", error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            Log.d(TAG, "onTracksChanged: audioTracks=${audioGroups.size}")
            if (audioGroups.size > 1) {
                val embedded = audioGroups.mapIndexed { index, group ->
                    val format = group.getTrackFormat(0)
                    Track(
                        id = "$EMBEDDED_TRACK_PREFIX$index",
                        name = format.label ?: "音轨 ${index + 1}",
                        url = ""
                    )
                }
                val selectedIndex = audioGroups.indexOfFirst { it.isSelected }
                _state.update {
                    it.copy(
                        embeddedTracks = embedded,
                        currentTrack = embedded.getOrNull(selectedIndex) ?: it.currentTrack
                    )
                }
            } else if (_state.value.embeddedTracks.isNotEmpty()) {
                _state.update { it.copy(embeddedTracks = emptyList()) }
            }
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        val future = controllerFuture ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicService::class.java))
        ).buildAsync().also { controllerFuture = it }

        future.addListener({
            runCatching {
                val c = future.get()
                if (controller == null) {
                    controller = c
                    c.addListener(listener)
                    // 先恢复已保存配置，再查询能力与频段数据，保证 info 反映应用后的状态
                    sendEqApply(eqEnabledCache, eqBandsCache)
                    checkEqSupport(c)
                    queryEqInfo(c)
                    sendSfxApply(sfxEnabledCache, sfxModeCache, sfxStrengthCache)
                    checkSfxSupport(c)
                    querySfxInfo(c)
                    sendVocalRemovalApply(vocalRemovalEnabledCache)
                    checkVocalRemovalSupport(c)
                }
                action(c)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(
        queue: List<Song>,
        index: Int,
        contextType: String? = null,
        contextKey: String? = null
    ) {
        val song = queue.getOrNull(index) ?: return
        _state.update {
            it.copy(
                queue = queue,
                currentIndex = index,
                currentSong = song,
                currentTrack = song.tracks?.firstOrNull(),
                duration = (song.duration * 1000).toLong(),
                contextType = contextType,
                contextKey = contextKey
            )
        }
        withController { c ->
            c.setMediaItems(queue.map { buildMediaItem(it) }, index, 0L)
            applyPlayMode(c, _state.value.playMode)
            c.prepare()
            c.play()
        }
        // 新播放从第一轨（原唱）开始，伴唱音效覆盖还原（同曲重播时 onMediaItemTransition 不触发）
        setTrackSfxOverride(accompaniment = false)
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun playAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    fun seekTo(position: Long) = withController { it.seekTo(position) }

    fun setPlayMode(mode: PlayMode) {
        val oldMode = _state.value.playMode
        _state.update { it.copy(playMode = mode) }
        scope.launch { dataStore.setPlayMode(mode.name) }
        withController { applyPlayMode(it, mode) }
        // 播放模式变更时重置预转码
        if (oldMode != mode) {
            onPlayModeChanged(oldMode, mode)
        }
    }

    fun cyclePlayMode() {
        setPlayMode(
            when (_state.value.playMode) {
                PlayMode.ORDER -> PlayMode.LOOP
                PlayMode.LOOP -> PlayMode.SINGLE
                PlayMode.SINGLE -> PlayMode.RANDOM
                PlayMode.RANDOM -> PlayMode.ORDER
            }
        )
    }

    fun switchTrack(track: Track) {
        if (track.id.startsWith(EMBEDDED_TRACK_PREFIX)) {
            switchEmbeddedTrack(track)
            return
        }
        val song = _state.value.currentSong ?: return
        withController { c ->
            val position = c.currentPosition
            val index = c.currentMediaItemIndex
            c.replaceMediaItem(index, buildMediaItem(song, track))
            c.prepare()
            c.seekTo(index, position)
            c.play()
        }
        _state.update { it.copy(currentTrack = track) }
        syncSfxWithTrack(track)
        setVocalRemovalEnabled(false)
    }

    private fun switchEmbeddedTrack(track: Track) {
        val groupIndex = track.id.removePrefix(EMBEDDED_TRACK_PREFIX).toIntOrNull() ?: return
        withController { c ->
            val audioGroups = c.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val group = audioGroups.getOrNull(groupIndex) ?: return@withController
            c.trackSelectionParameters = c.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
            _state.update { it.copy(currentTrack = track) }
        }
        syncSfxWithTrack(track)
        setVocalRemovalEnabled(false)
    }

    // 原伴唱音效联动：第 1 条音轨视为原唱，其余视为伴唱（与 ControlBar 的按钮状态一致）
    private fun syncSfxWithTrack(track: Track) {
        val accompaniment = when {
            track.id.startsWith(EMBEDDED_TRACK_PREFIX) ->
                (track.id.removePrefix(EMBEDDED_TRACK_PREFIX).toIntOrNull() ?: 0) > 0
            else ->
                (_state.value.currentSong?.tracks?.indexOfFirst { it.id == track.id } ?: 0) > 0
        }
        setTrackSfxOverride(accompaniment)
    }

    // 伴唱：备份当前音效（开关/模式/强度）并临时切响度；回原唱：还原备份。
    // 仅设备支持响度音效时生效，且只改运行缓存不写 DataStore，进程重启后仍是用户原设置
    private fun setTrackSfxOverride(accompaniment: Boolean) {
        if (!accompaniment) {
            val backup = sfxBackup ?: return
            sfxBackup = null
            applySfxOverride(backup.enabled, backup.mode, backup.strength)
            Log.d(TAG, "伴唱结束，音效还原：enabled=${backup.enabled} mode=${backup.mode} strength=${backup.strength}")
            return
        }
        if (sfxBackup != null) return
        val loudnessIndex = SFX_MODE_KEYS.indexOf("loudness")
        if (loudnessIndex < 0 || !_state.value.sfxModeSupported.getOrElse(loudnessIndex) { false }) return
        val s = _state.value
        sfxBackup = SfxBackup(s.sfxEnabled, s.sfxMode, s.sfxStrength)
        applySfxOverride(true, "loudness", s.sfxStrength)
        Log.d(TAG, "切伴唱，音效备份：enabled=${s.sfxEnabled} mode=${s.sfxMode} strength=${s.sfxStrength}，临时切响度")
    }

    // 直接应用音效（绕过 DataStore 闭环），同步缓存与状态；服务重连/播放就绪时会按缓存重放
    private fun applySfxOverride(enabled: Boolean, mode: String, strength: Int) {
        sfxEnabledCache = enabled
        sfxModeCache = mode
        sfxStrengthCache = strength
        _state.update { it.copy(sfxEnabled = enabled, sfxMode = mode, sfxStrength = strength) }
        sendSfxApply(enabled, mode, strength)
    }

    fun currentPosition(): Long = controller?.currentPosition ?: 0L

    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: _state.value.duration

    fun withPlayer(action: (Player) -> Unit) = withController(action)

    fun setEqualizerEnabled(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (!enabled) {
            scope.launch { dataStore.setEqEnabled(false) }
            _state.update { it.copy(eqEnabled = false) }
            onResult?.invoke(true)
            return
        }
        // 开启前校验设备能力，不支持则不写入配置
        scope.launch {
            val supported = checkEqualizerSupport()
            _state.update { it.copy(eqSupported = supported) }
            if (supported) {
                dataStore.setEqEnabled(true)
                _state.update { it.copy(eqEnabled = true) }
            }
            onResult?.invoke(supported)
        }
    }

    // 面板打开时刷新：能力 + 频段数据（数据需音频会话就绪后才有）
    fun refreshEqInfo() = withController { c ->
        checkEqSupport(c)
        queryEqInfo(c)
    }

    private suspend fun checkEqualizerSupport(): Boolean = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            withController { c ->
                val future = c.sendCustomCommand(
                    SessionCommand(MusicService.EQ_CHECK, Bundle.EMPTY), Bundle.EMPTY
                )
                future.addListener({
                    val supported = runCatching {
                        val r = future.get()
                        r.resultCode == SessionResult.RESULT_SUCCESS &&
                            r.extras?.getBoolean(MusicService.EXTRA_SUPPORTED, false) == true
                    }.getOrDefault(false)
                    if (cont.isActive) cont.resume(supported)
                }, ContextCompat.getMainExecutor(context))
            }
        }
    } ?: false

    private fun checkEqSupport(c: MediaController) {
        val future = c.sendCustomCommand(
            SessionCommand(MusicService.EQ_CHECK, Bundle.EMPTY), Bundle.EMPTY
        )
        future.addListener({
            runCatching {
                val r = future.get()
                val supported = r.resultCode == SessionResult.RESULT_SUCCESS &&
                    r.extras?.getBoolean(MusicService.EXTRA_SUPPORTED, false) == true
                Log.d(TAG, "eq/check：设备支持均衡器 = $supported")
                _state.update { it.copy(eqSupported = supported) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setEqualizerPreset(preset: String) {
        if (preset !in EQ_PRESET_KEYS) return
        val bands = resolvePresetBands(preset)
        scope.launch {
            dataStore.setEqPreset(preset)
            dataStore.setEqBands(formatBands(bands))
        }
        _state.update { it.copy(eqPreset = preset, eqBands = bands) }
    }

    fun setEqualizerBand(bandIndex: Int, levelDb: Int) {
        val bands = eqBandsCache.toMutableList()
        if (bandIndex !in bands.indices) return
        val min = _state.value.eqBandLevelMin / 100
        val max = _state.value.eqBandLevelMax / 100
        bands[bandIndex] = levelDb.coerceIn(min, max)
        scope.launch {
            dataStore.setEqBands(formatBands(bands))
            // 手动调频段即视为自定义曲线
            dataStore.setEqPreset("custom")
        }
        _state.update { it.copy(eqBands = bands, eqPreset = "custom") }
    }

    // 预设曲线（10 频点）按对数频率插值到设备实际频段；设备频段未知时按已知段数截取
    private fun resolvePresetBands(preset: String): List<Int> {
        val curve = EQ_PRESETS[preset]?.second ?: return eqBandsCache
        val deviceFreqs = _state.value.eqBandFrequencies
        if (deviceFreqs.isEmpty()) {
            val knownCount = eqBandsCache.size
            return if (knownCount == 0) curve else curve.take(knownCount)
        }
        return deviceFreqs.map { freq -> interpolateGain(freq, EQ_PRESET_FREQS, curve) }
    }

    // 对数频率线性插值（同 songloft-player 的 mpv EQ 映射方式）
    private fun interpolateGain(freq: Int, freqs: List<Int>, gains: List<Int>): Int {
        val logFreq = ln(freq.toDouble())
        val logLow = ln(freqs.first().toDouble())
        val logHigh = ln(freqs.last().toDouble())
        val gain = when {
            logFreq <= logLow -> gains.first().toDouble()
            logFreq >= logHigh -> gains.last().toDouble()
            else -> {
                var result = gains.last().toDouble()
                for (i in 0 until freqs.size - 1) {
                    val lo = ln(freqs[i].toDouble())
                    val hi = ln(freqs[i + 1].toDouble())
                    if (logFreq in lo..hi) {
                        val t = (logFreq - lo) / (hi - lo)
                        result = gains[i] + t * (gains[i + 1] - gains[i])
                        break
                    }
                }
                result
            }
        }
        return round(gain).toInt()
    }

    private fun sendEqApply(enabled: Boolean, bands: List<Int>) {
        val c = controller ?: return
        val args = Bundle().apply {
            putBoolean(MusicService.EXTRA_ENABLED, enabled)
            putIntArray(MusicService.EXTRA_BANDS, bands.toIntArray())
        }
        c.sendCustomCommand(SessionCommand(MusicService.EQ_APPLY, Bundle.EMPTY), args)
    }

    private fun retryEqSetup() {
        val c = controller ?: return
        Log.d(TAG, "播放就绪，重试均衡器（apply + check + query）")
        sendEqApply(eqEnabledCache, eqBandsCache)
        checkEqSupport(c)
        queryEqInfo(c)
    }

    private fun queryEqInfo(c: MediaController) {
        val future = c.sendCustomCommand(SessionCommand(MusicService.EQ_INFO, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            runCatching {
                val result = future.get()
                // 频段数据需音频会话就绪（播放中）才有；失败不影响能力判断（eq/check 负责）
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    Log.w(TAG, "eq/info 响应异常：code=${result.resultCode}")
                    return@addListener
                }
                val extras = result.extras ?: run {
                    Log.w(TAG, "eq/info 无返回数据")
                    return@addListener
                }
                if (!extras.getBoolean(MusicService.EXTRA_SUPPORTED, false)) {
                    Log.w(TAG, "eq/info 绑定失败（音频会话未就绪或设备无 audiofx）")
                    return@addListener
                }
                Log.d(TAG, "eq/info 成功：${extras.getInt(MusicService.EXTRA_BAND_COUNT)} 段")
                _state.update {
                    it.copy(
                        eqSupported = true,
                        eqBandFrequencies = extras.getIntArray(MusicService.EXTRA_CENTER_FREQS)?.toList() ?: emptyList(),
                        eqBandLevelMin = extras.getInt(MusicService.EXTRA_LEVEL_MIN),
                        eqBandLevelMax = extras.getInt(MusicService.EXTRA_LEVEL_MAX),
                        eqEnabled = extras.getBoolean(MusicService.EXTRA_ENABLED, false),
                        eqBands = extras.getIntArray(MusicService.EXTRA_BANDS)?.toList() ?: emptyList()
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setSfxEnabled(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (!enabled) {
            scope.launch { dataStore.setSfxEnabled(false) }
            _state.update { it.copy(sfxEnabled = false) }
            onResult?.invoke(true)
            return
        }
        // 开启前校验设备能力，不支持则不写入配置
        scope.launch {
            val supported = checkSfxSupport()
            if (supported) {
                dataStore.setSfxEnabled(true)
                _state.update { it.copy(sfxEnabled = true) }
            }
            onResult?.invoke(supported)
        }
    }

    fun setSfxMode(mode: String) {
        if (mode !in SFX_MODE_KEYS) return
        scope.launch {
            dataStore.setSfxMode(mode)
            // 面板内无"关闭"选项，选择模式即视为开启（总开关在设置页）
            dataStore.setSfxEnabled(true)
        }
        _state.update { it.copy(sfxMode = mode, sfxEnabled = true) }
    }

    fun setSfxStrength(strength: Int) {
        val s = strength.coerceIn(0, 100)
        scope.launch { dataStore.setSfxStrength(s) }
        _state.update { it.copy(sfxStrength = s) }
    }

    // 面板打开时刷新：能力矩阵 + 当前生效状态（数据不依赖音频会话，静态查询）
    fun refreshSfxInfo() = withController { c ->
        checkSfxSupport(c)
        querySfxInfo(c)
    }

    // 清空播放缓存（服务未运行时会拉起服务执行，成功回调 true）
    fun clearPlayCache(onResult: ((Boolean) -> Unit)? = null) {
        withController { c ->
            val future = c.sendCustomCommand(
                SessionCommand(MusicService.CACHE_CLEAR, Bundle.EMPTY), Bundle.EMPTY
            )
            future.addListener({
                val ok = runCatching {
                    future.get().resultCode == SessionResult.RESULT_SUCCESS
                }.getOrDefault(false)
                Log.i(TAG, "cache/clear 结果：$ok")
                onResult?.invoke(ok)
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // 缓存设置变更：服务未运行则下次拉起自动生效；已运行且未播放则重启服务立即生效；
    // 正在播放则不打扰（保持下次生效）。重启后旧连接失效，主动释放，下次播放重建
    fun applyCacheSetting() {
        val c = controller ?: return
        if (!c.isConnected) return
        val playing = c.isPlaying
        c.sendCustomCommand(SessionCommand(MusicService.CACHE_APPLY, Bundle.EMPTY), Bundle.EMPTY)
        if (!playing) {
            c.release()
            controller = null
            // 旧 future 已指向被释放的 controller，必须一并清空，否则下次连接会复用已释放实例
            controllerFuture = null
        }
    }

    private suspend fun checkSfxSupport(): Boolean = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            withController { c ->
                val future = c.sendCustomCommand(
                    SessionCommand(MusicService.SFX_CHECK, Bundle.EMPTY), Bundle.EMPTY
                )
                future.addListener({
                    val supported = runCatching {
                        val r = future.get()
                        r.resultCode == SessionResult.RESULT_SUCCESS &&
                            r.extras?.getBoolean(MusicService.EXTRA_SUPPORTED, false) == true
                    }.getOrDefault(false)
                    if (cont.isActive) cont.resume(supported)
                }, ContextCompat.getMainExecutor(context))
            }
        }
    } ?: false

    private fun checkSfxSupport(c: MediaController) {
        val future = c.sendCustomCommand(
            SessionCommand(MusicService.SFX_CHECK, Bundle.EMPTY), Bundle.EMPTY
        )
        future.addListener({
            runCatching {
                val r = future.get()
                val supported = r.resultCode == SessionResult.RESULT_SUCCESS &&
                    r.extras?.getBoolean(MusicService.EXTRA_SUPPORTED, false) == true
                Log.d(TAG, "sfx/check：设备支持音效 = $supported")
                _state.update { it.copy(sfxSupported = supported) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun sendSfxApply(enabled: Boolean, mode: String, strength: Int) {
        val c = controller ?: return
        val args = Bundle().apply {
            putBoolean(MusicService.EXTRA_ENABLED, enabled)
            putString(MusicService.EXTRA_MODE, mode)
            putInt(MusicService.EXTRA_STRENGTH, strength)
        }
        c.sendCustomCommand(SessionCommand(MusicService.SFX_APPLY, Bundle.EMPTY), args)
    }

    private fun retrySfxSetup() {
        val c = controller ?: return
        Log.d(TAG, "播放就绪，重试音效（apply + check + query）")
        sendSfxApply(sfxEnabledCache, sfxModeCache, sfxStrengthCache)
        checkSfxSupport(c)
        querySfxInfo(c)
    }

    private fun querySfxInfo(c: MediaController) {
        val future = c.sendCustomCommand(SessionCommand(MusicService.SFX_INFO, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            runCatching {
                val result = future.get()
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    Log.w(TAG, "sfx/info 响应异常：code=${result.resultCode}")
                    return@addListener
                }
                val extras = result.extras ?: return@addListener
                val matrix = extras.getBooleanArray(MusicService.EXTRA_SUPPORTED_MATRIX)
                // 矩阵顺序与 SFX_MODE_KEYS 一一对应（virtualizer/bass_boost/loudness/reverb）
                val modeSupported = buildList {
                    matrix?.forEach { add(it) }
                    while (size < SFX_MODE_KEYS.size) add(false)
                }
                val active = extras.getString(MusicService.EXTRA_ACTIVE_MODE) ?: "off"
                Log.d(TAG, "sfx/info：矩阵=$modeSupported, A2DP=${extras.getBoolean(MusicService.EXTRA_A2DP, false)}")
                _state.update {
                    it.copy(
                        sfxSupported = extras.getBoolean(MusicService.EXTRA_SUPPORTED, false),
                        sfxModeSupported = modeSupported,
                        sfxOnA2dp = extras.getBoolean(MusicService.EXTRA_A2DP, false),
                        sfxActiveMode = active
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setVocalRemovalEnabled(enabled: Boolean) {
        _state.update { it.copy(vocalRemovalEnabled = enabled) }
        vocalRemovalEnabledCache = enabled
        withController { c ->
            val args = Bundle().apply { putBoolean(MusicService.EXTRA_ENABLED, enabled) }
            c.sendCustomCommand(SessionCommand(MusicService.VOCAL_REMOVE_APPLY, Bundle.EMPTY), args)
        }
    }

    fun toggleAccompanimentMode() {
        val s = _state.value
        val multiTrack = (s.currentSong?.hasMultiTrack == true) || s.embeddedTracks.size > 1
        if (multiTrack) {
            val next = if (s.currentTrack == s.currentSong?.tracks?.first()) s.currentSong?.tracks?.getOrNull(1)
            else s.currentSong?.tracks?.first()
            next?.let { switchTrack(it) }
            setVocalRemovalEnabled(false)
        } else {
            setVocalRemovalEnabled(!s.vocalRemovalEnabled)
        }
    }

    /** 强制回到原唱：双音轨资源切回第一轨，否则关闭人声消除；伴唱音效覆盖随之还原 */
    fun restoreOriginal() {
        val s = _state.value
        val multiTrack = (s.currentSong?.hasMultiTrack == true) || s.embeddedTracks.size > 1
        if (multiTrack) {
            val first = s.currentSong?.tracks?.firstOrNull() ?: return
            if (s.currentTrack != first) switchTrack(first)
        } else {
            if (s.vocalRemovalEnabled) setVocalRemovalEnabled(false)
        }
    }

    /** 当前是否处于"伴唱"：双音轨资源看所选音轨，否则看人声消除开关 */
    fun isAccompanimentOn(): Boolean {
        val s = _state.value
        val multiTrack = (s.currentSong?.hasMultiTrack == true) || s.embeddedTracks.size > 1
        return if (multiTrack) {
            val first = s.currentSong?.tracks?.firstOrNull()
            s.currentTrack != first
        } else {
            s.vocalRemovalEnabled
        }
    }

    // ===== 播放队列管理（供"扫码点歌"使用）=====

    /** 点歌：追加到播放队列末尾 */
    fun addToQueue(song: Song) {
        if (_state.value.queue.any { it.id == song.id }) {
            Log.d(TAG, "点歌跳过：队列已存在 ${song.title}")
            return
        }
        val queue = _state.value.queue + song
        _state.update { it.copy(queue = queue) }
        withController { c -> c.addMediaItem(buildMediaItem(song)) }
        Log.d(TAG, "点歌成功：${song.title}，队列 ${queue.size} 首")
    }

    /** 置顶：移动到当前播放曲的下一首（下一个播放） */
    fun moveToTop(index: Int) {
        val queue = _state.value.queue
        val current = _state.value.currentIndex
        if (index !in queue.indices || index == current) return
        val song = queue[index]
        val newQueue = queue.toMutableList().apply {
            removeAt(index)
            add(current + 1, song)
        }
        _state.update { it.copy(queue = newQueue) }
        withController { c ->
            val target = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
            runCatching { c.moveMediaItem(index, target) }
        }
    }

    /** 删除：从队列移除指定曲目（不允许删除正在播放的曲目） */
    fun removeFromQueue(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices || index == _state.value.currentIndex) return
        val newQueue = queue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = newQueue) }
        withController { c ->
            if (index in 0 until c.mediaItemCount) runCatching { c.removeMediaItem(index) }
        }
    }

    /** 当前播放队列快照（供扫码点歌页读取） */
    fun getQueue(): List<Song> = _state.value.queue

    // ===== K 歌独立播放列表（与主页队列隔离）=====
    // 进入时备份主页队列并在引擎中载入同一份副本，退出时还原主页队列与进度，
    // 因此期间所有增删/置顶只影响 karaokeList，不会改动主页播放队列。

    /** 进入 K 歌：备份主页队列，载入 K 歌独立列表（初始为当前主页队列副本） */
    fun enterKaraoke() {
        if (_state.value.karaokeActive) return
        val backup = _state.value.queue
        mainQueueBackup = backup
        mainIndexBackup = _state.value.currentIndex
        mainPosBackup = controller?.currentPosition ?: 0L
        val list = backup.toList()
        _state.update { it.copy(karaokeActive = true, karaokeList = list) }
        loadIntoEngine(list, _state.value.currentIndex, mainPosBackup)
    }

    /** 退出 K 歌：还原主页队列与播放进度，清空 K 歌列表 */
    fun exitKaraoke() {
        if (!_state.value.karaokeActive) return
        val backup = mainQueueBackup ?: emptyList()
        val idx = mainIndexBackup.coerceIn(0, (backup.size - 1).coerceAtLeast(0))
        val pos = mainPosBackup
        mainQueueBackup = null
        _state.update {
            it.copy(
                karaokeActive = false,
                karaokeList = emptyList(),
                queue = backup,
                currentIndex = idx
            )
        }
        loadIntoEngine(backup, idx, pos)
    }

    /** 仅操作播放引擎媒体项，不触碰主页队列状态（供 K 歌进入/退出时整体替换播放列表） */
    private fun loadIntoEngine(list: List<Song>, index: Int, posMs: Long) {
        withController { c ->
            val start = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            c.setMediaItems(list.map { buildMediaItem(it) }, start, posMs)
            applyPlayMode(c, _state.value.playMode)
            c.prepare()
            c.play()
        }
    }

    /** K 歌点歌：追加到独立列表末尾（与扫码点歌共用） */
    fun karaokeAdd(song: Song) {
        if (!_state.value.karaokeActive) return
        if (_state.value.karaokeList.any { it.id == song.id }) return
        val newList = _state.value.karaokeList + song
        _state.update { it.copy(karaokeList = newList) }
        withController { c -> c.addMediaItem(buildMediaItem(song)) }
    }

    /** K 歌置顶：移动到当前演唱曲的下一首（下一个演唱） */
    fun karaokeMoveTop(index: Int) {
        if (!_state.value.karaokeActive) return
        val list = _state.value.karaokeList
        val cur = _state.value.currentIndex
        if (index !in list.indices || index == cur) return
        val song = list[index]
        val newList = list.toMutableList().apply {
            removeAt(index)
            add((cur + 1).coerceIn(0, size), song)
        }
        _state.update { it.copy(karaokeList = newList) }
        withController { c ->
            val target = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
            runCatching { c.moveMediaItem(index, target) }
            _state.update { it.copy(currentIndex = c.currentMediaItemIndex) }
        }
    }

    /** K 歌删除：从独立列表移除（不允许删除正在演唱的曲目） */
    fun karaokeRemove(index: Int) {
        if (!_state.value.karaokeActive) return
        val list = _state.value.karaokeList
        if (index !in list.indices || index == _state.value.currentIndex) return
        val newList = list.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(karaokeList = newList) }
        withController { c ->
            if (index in 0 until c.mediaItemCount) runCatching { c.removeMediaItem(index) }
            _state.update { it.copy(currentIndex = c.currentMediaItemIndex) }
        }
    }

    /** K 歌指定演唱某曲 */
    fun karaokePlayAt(index: Int) {
        if (!_state.value.karaokeActive) return
        withController { c -> if (index in 0 until c.mediaItemCount) c.seekToDefaultPosition(index) }
    }

    /** K 歌独立列表快照（供扫码点歌页读取） */
    fun getKaraokeList(): List<Song> = _state.value.karaokeList

    private fun sendVocalRemovalApply(enabled: Boolean) {
        val c = controller ?: return
        val args = Bundle().apply { putBoolean(MusicService.EXTRA_ENABLED, enabled) }
        c.sendCustomCommand(SessionCommand(MusicService.VOCAL_REMOVE_APPLY, Bundle.EMPTY), args)
    }

    private fun checkVocalRemovalSupport(c: MediaController) {
        val future = c.sendCustomCommand(
            SessionCommand(MusicService.VOCAL_REMOVE_CHECK, Bundle.EMPTY), Bundle.EMPTY
        )
        future.addListener({
            runCatching {
                val r = future.get()
                val supported = r.resultCode == SessionResult.RESULT_SUCCESS &&
                    r.extras?.getBoolean(MusicService.EXTRA_SUPPORTED, false) == true
                val enabled = r.extras?.getBoolean(MusicService.EXTRA_ENABLED, false) == true
                _state.update {
                    it.copy(
                        vocalRemovalSupported = supported,
                        vocalRemovalEnabled = enabled
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun parseBands(s: String): List<Int> =
        s.split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun formatBands(bands: List<Int>): String = bands.joinToString(",")

    private var sleepJob: Job? = null

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        _state.update {
            it.copy(
                sleepTimerMinutes = minutes,
                sleepTimerRemaining = minutes,
                sleepAfterSongs = 0,
                sleepAfterSongsRemaining = 0
            )
        }
        if (minutes > 0) {
            sleepJob = scope.launch {
                var remaining = minutes
                while (remaining > 0) {
                    delay(60_000L)
                    remaining--
                    _state.update { it.copy(sleepTimerRemaining = remaining) }
                }
                controller?.pause()
                _state.update { it.copy(sleepTimerMinutes = 0, sleepTimerRemaining = 0) }
            }
        }
    }

    fun setSleepAfterSongs(count: Int) {
        sleepJob?.cancel()
        sleepJob = null
        _state.update {
            it.copy(
                sleepTimerMinutes = 0,
                sleepTimerRemaining = 0,
                sleepAfterSongs = count,
                sleepAfterSongsRemaining = count
            )
        }
    }

    private fun countDownSleepAfterSongs(previousSong: Song?, song: Song?, reason: Int) {
        val remaining = _state.value.sleepAfterSongsRemaining
        if (remaining <= 0) return
        // 只统计自然播完的歌曲，音轨切换重建的同曲 MediaItem 不计数
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
        if (previousSong == null || previousSong.id == song?.id) return
        val next = remaining - 1
        _state.update { it.copy(sleepAfterSongsRemaining = next) }
        if (next == 0) {
            controller?.pause()
            _state.update { it.copy(sleepAfterSongs = 0, sleepAfterSongsRemaining = 0) }
        }
    }

    // ===== 预转码功能相关 =====
    
    /** 检查是否应该启用预转码 */
    private fun shouldEnablePreTranscode(): Boolean {
        // 1. 预转码总开关必须打开
        if (!preTranscodeEnabledCache) {
            Log.d(TAG, "预转码跳过：总开关已关闭")
            return false
        }
        
        // 2. 单曲循环不适合预转码
        val currentMode = _state.value.playMode
        if (currentMode == PlayMode.SINGLE) {
            Log.d(TAG, "预转码跳过：${currentMode.name}")
            return false
        }
        
        // 3. 当前必须是 MP3 转码模式
        val isMp3Transcode = audioQuality == "mp3"
        if (!isMp3Transcode) {
            Log.d(TAG, "预转码跳过：音频格式为 ${audioQuality ?: "原始"}")
            return false
        }
        
        return true
    }

    /** 启动预转码流程 */
    fun startPreTranscode() {
        if (!shouldEnablePreTranscode()) {
            cancelPreTranscode()
            return
        }
        
        // 检查是否是新歌开始播放（且没有正在进行的预转码任务）
        val currentState = _state.value
        val isNewSong = _state.value.currentSong?.id != lastPlayedSongId
        
        cancelPreTranscode()
        
        scope.launch {
            val currentSong = currentState.currentSong ?: return@launch
            
            // 计算延迟时间：
            // - 新歌开始或从未预转过：min(60 秒，歌曲总时长/2)
            // - 同一首歌的模式切换后：min(6 秒，剩余时长/2)
            val songDurationMs = (currentSong.duration * 1000).toLong()
            val currentPosition = controller?.currentPosition ?: 0L
            val remainingTime = maxOf(0L, songDurationMs - currentPosition)
            
            // 延迟基准：新歌用 60 秒，同一首歌用 6 秒
            val baseDelay = if (isNewSong || isFirstPreTranscode) PRE_TRANSCODE_DELAY_MS 
                            else PRE_TRANSCODE_DELAY_AFTER_MODE_CHANGE
            val delayTime = minOf(baseDelay, remainingTime / 2)
            
            Log.d(TAG, "[PRE_TRANSCODE] ${if (isNewSong) "新歌" else "同曲"}播放，延迟 $delayTime ms 后预转码 (剩余时长=${remainingTime}ms)")
            delay(delayTime)
            
            // 检查是否仍处于稳定播放期（未发生新歌切换）
            if (_state.value.currentSong?.id == currentSong.id) {
                isFirstPreTranscode = false // 首次预转码完成后，后续都按 6 秒延迟
                lastPlayedSongId = currentSong.id // 记录已播放的歌曲
                // 延迟结束后，根据当前实际播放模式计算下一首
                tryTranscodeNext(currentSong, _state.value.currentIndex)
            }
        }
    }

    /** 尝试预转码指定歌曲的下一首 */
    private suspend fun tryTranscodeNext(currentSong: Song, currentIndex: Int) {
        val currentState = _state.value
        val queue = currentState.queue
        
        if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size - 1) {
            Log.d(TAG, "[PRE_TRANSCODE] 无下一首歌曲可预转码")
            return
        }
        
        // 确定下一首索引
        val nextIndex = when (currentState.playMode) {
            PlayMode.ORDER -> {
                // 顺序播放：当前 +1
                currentIndex + 1
            }
            PlayMode.LOOP -> {
                // 列表循环：当前 +1（取模）
                (currentIndex + 1) % queue.size
            }
            PlayMode.SINGLE -> {
                // 单曲循环不需要预转码（shouldEnablePreTranscode 已过滤）
                currentIndex
            }
            PlayMode.RANDOM -> {
                // 随机模式：通过 currentQueueItemIndex 获取下一首
                var nextIdx = -1
                withController { controller ->
                    val mediaItemCount = controller.mediaItemCount
                    if (mediaItemCount <= 1) {
                        Log.w(TAG, "[PRE_TRANSCODE] 随机模式下媒体项少于 2 个，跳过预转码")
                    } else {
                        // ExoPlayer 在随机模式下会自动打乱队列，currentMediaItemIndex 返回的是当前正在播放的索引
                        // 下一首就是 currentMediaItemIndex + 1（如果到末尾则回绕）
                        nextIdx = (controller.currentMediaItemIndex + 1) % mediaItemCount
                    }
                }
                nextIdx
            }
        }
        
        if (nextIndex < 0 || nextIndex >= queue.size) {
            Log.d(TAG, "[PRE_TRANSCODE] 下一首索引无效：$nextIndex")
            return
        }
        
        val nextSong = queue[nextIndex]
        Log.d(TAG, "[PRE_TRANSCODE] 准备预转码下一首：${nextSong.title} (ID: ${nextSong.id}, 模式：${currentState.playMode.name})")
        
        _state.update { it.copy(isPreTranscoding = true) }
        
        try {
            // 构建预转码 URL
            val transcodeUrl = UrlHelper.songPlayUrl(
                songId = nextSong.id,
                transcodeFormat = "mp3",
                track = null,
                isVideo = false,
                sourceFormat = nextSong.format
            )
            
            // 发起 HTTP 请求触发转码（GET + 立即断开）
            performPrefetchRequest(transcodeUrl)
            
            Log.d(TAG, "[PRE_TRANSCODE] 预转码成功：${nextSong.title}")
        } catch (e: Exception) {
            Log.e(TAG, "[PRE_TRANSCODE] 预转码失败", e)
        } finally {
            _state.update { it.copy(isPreTranscoding = false) }
        }
    }

    /** 触发预转码 HTTP 请求（GET + 立即断开） */
    private suspend fun performPrefetchRequest(url: String) {
        // 复用 ApiClient 的 authInterceptor，确保带上 authorization token
        val client = if (ApiClient.isInitialized()) {
            val authInterceptor = ApiClient.authInterceptor
            okhttp3.OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } else {
            throw Exception("ApiClient not initialized")
        }
        
        // 使用 HEAD 请求，避免下载内容
        val request = Request.Builder().url(url).head().build()
        
        try {
            // 在 IO 线程执行请求
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "[PRE_TRANSCODE] 请求成功：$url")
                    } else {
                        throw Exception("预转码请求失败：${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[PRE_TRANSCODE] 请求异常 (不影响后续播放): ${e.message}")
            throw e
        } finally {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    /** 取消预转码任务 */
    private fun cancelPreTranscode() {
        preTranscodeJob?.cancel()
        preTranscodeJob = null
    }

    /** 播放模式变更时重置预转码（仅当首次预转码已完成时才重置） */
    fun onPlayModeChanged(oldMode: PlayMode, newMode: PlayMode) {
        if (oldMode != newMode) {
            // 仅在首次预转码完成后，才响应模式切换并重置计时器
            if (!isFirstPreTranscode) {
                Log.d(TAG, "[PRE_TRANSCODE] 播放模式从 $oldMode 切换到 $newMode，重置预转码")
                cancelPreTranscode()
                startPreTranscode()
            } else {
                Log.d(TAG, "[PRE_TRANSCODE] 首次 60 秒延迟中切换模式 ($oldMode→$newMode)，不重置计时器")
            }
        }
    }
    // ===== 预转码功能结束 =====

    private fun reportTransition(previousSong: Song?, song: Song?, reason: Int) {
        // 音轨切换会重建同一首歌的 MediaItem，不重复上报
        if (previousSong?.id == song?.id) return
        scope.launch {
            previousSong?.let { prev ->
                when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ->
                        songRepository.reportPlayed(prev.id, "finish")
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ->
                        songRepository.reportPlayed(prev.id, "skip")
                    else -> Result.success(Unit)
                }
            }
            song?.let {
                songRepository.reportPlayed(
                    it.id,
                    "play",
                    contextType = _state.value.contextType,
                    contextKey = _state.value.contextKey
                )
            }
        }
    }

    private fun applyPlayMode(c: MediaController, mode: PlayMode) {
        when (mode) {
            PlayMode.ORDER -> {
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.shuffleModeEnabled = false
            }
            PlayMode.LOOP -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = false
            }
            PlayMode.SINGLE -> {
                c.repeatMode = Player.REPEAT_MODE_ONE
                c.shuffleModeEnabled = false
            }
            PlayMode.RANDOM -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = true
            }
        }
    }

    private fun buildMediaItem(song: Song, track: Track? = null): MediaItem {
        val radioUrl = if (song.type == "radio") UrlHelper.resolve(song.url) else null
        val uri = UrlHelper.resolve(track?.url)
            ?: radioUrl
            ?: UrlHelper.songPlayUrl(
                song.id,
                transcodeFormat = audioQuality,
                track = track?.id,
                isVideo = song.isVideo,
                sourceFormat = song.format
            ).also { Log.d(TAG, "buildMediaItem: audioQuality=$audioQuality isVideo=${song.isVideo} sourceFormat=${song.format} -> $it") }
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .apply { if (uri.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(UrlHelper.resolve(song.coverUrl)?.let(Uri::parse))
                    .build()
            )
            .build()
    }

    companion object {
        private const val TAG = "PlayerController"
        private const val EMBEDDED_TRACK_PREFIX = "embedded:"
    }
}
