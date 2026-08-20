package com.songloft.tv

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.BitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.songloft.tv.data.api.ApiClient
import com.songloft.tv.data.cache.Id3SkippingDataSource
import com.songloft.tv.data.cache.PlaybackCache
import com.songloft.tv.data.cache.RoutingDataSource
import com.songloft.tv.data.storage.PreferencesDataStore
import com.songloft.tv.player.audio.VocalRemovalProcessor
import com.songloft.tv.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject lateinit var dataStore: PreferencesDataStore

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var cache: SimpleCache? = null
    private val vocalRemovalProcessor = VocalRemovalProcessor()
    // 音效模式效果器（与均衡器独立叠加，单模式互斥）
    private val sfxEffects = mutableMapOf<SfxType, AudioEffect>()

    private enum class SfxType { VIRTUALIZER, BASS_BOOST, LOUDNESS, REVERB }

    // 缓存命中日志节流：同一资源加载期间只打一次，避免每次 read 都刷日志
    private var cacheHitLoggedForLoad = false

    private val cacheEventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            if (cachedBytesRead > 0 && !cacheHitLoggedForLoad) {
                cacheHitLoggedForLoad = true
                Log.i(TAG, "播放缓存：命中缓存数据（缓存总量 ${cacheSizeBytes / 1024 / 1024} MB）")
            }
        }

        override fun onCacheIgnored(reason: Int) {
            Log.w(TAG, "播放缓存：本次读取忽略缓存（reason=$reason），继续流式播放")
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            onOutputDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            onOutputDevicesChanged()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // 输出设备切换（HDMI/蓝牙/内置喇叭）后旧效果绑定可能失效，先释放，由下一次 sfx/info 重新校验
        // AudioDeviceCallback/getDevices 为 API 23+，低版本设备跳过监听（音效能力按连接时查询为准）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                (getSystemService(AUDIO_SERVICE) as AudioManager)
                    .registerAudioDeviceCallback(audioDeviceCallback, null)
            }
        }

        // 播放缓存：大小（MB，0=关闭）与缓存归属服务器。换服务器后旧缓存必须清空；
        // 清理放在这里（SimpleCache 未打开时），不能在运行中删目录，否则损坏缓存索引
        val cacheMb = runCatching { runBlocking { dataStore.playCacheMb.first() } }.getOrDefault(0)
        val serverUrl = runCatching { runBlocking { dataStore.serverUrl.first() } }.getOrNull().orEmpty()
        val cacheServerUrl = runCatching { runBlocking { dataStore.cacheServerUrl.first() } }.getOrNull()
        if (cacheMb <= 0) {
            PlaybackCache.clear(this)
        } else {
            if (cacheServerUrl != serverUrl) PlaybackCache.clear(this)
            cache = runCatching {
                SimpleCache(
                    PlaybackCache.dir(this),
                    LeastRecentlyUsedCacheEvictor(cacheMb.toLong() * 1024L * 1024L),
                    StandaloneDatabaseProvider(this)
                )
            }.getOrNull()
            if (cache == null) Log.w(TAG, "播放缓存创建失败，回退纯流式（cacheMb=$cacheMb）")
            else Log.i(TAG, "播放缓存已启用：$cacheMb MB，LRU 自动淘汰，目录 ${PlaybackCache.dir(this)}")
        }
        if (serverUrl.isNotBlank()) {
            runBlocking { dataStore.setCacheServerUrl(serverUrl) }
        }

        // 流媒体请求需携带 JWT，token 可能在运行期刷新，故每次创建数据源时读取；
        // ID3 剥离：兼容 bili 下载产生的「ID3v2 标签 + MP4 容器」混合文件
        val id3SkipCache = ConcurrentHashMap<String, Long>()
        val upstreamFactory = DataSource.Factory {
            Id3SkippingDataSource(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .createDataSource()
                    .apply {
                        ApiClient.authInterceptor.accessToken?.let {
                            setRequestProperty("Authorization", "Bearer $it")
                        }
                    },
                id3SkipCache
            )
        }
        // 开启缓存时：非 m3u8 资源经 CacheDataSource（LRU 淘汰），m3u8 直播清单走纯流式
        val dataSourceFactory = cache?.let { cache ->
            val cacheFactory = DataSource.Factory {
                CacheDataSource(
                    cache,
                    upstreamFactory.createDataSource(),
                    FileDataSource(),
                    CacheDataSink(cache, 5 * 1024 * 1024L),
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                    cacheEventListener
                )
            }
            DataSource.Factory {
                RoutingDataSource(
                    cacheFactory.createDataSource(),
                    upstreamFactory.createDataSource(),
                    onCacheLoadStarted = { cacheHitLoggedForLoad = false }
                )
            }
        } ?: upstreamFactory

        val renderersFactory = object : DefaultRenderersFactory(this) {
            @OptIn(UnstableApi::class)
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(vocalRemovalProcessor))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                it.addAnalyticsListener(playbackAnalyticsListener)
            }

        val sessionActivityIntent = Intent(this, PlayerActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知/锁屏封面通过 Media3 内置 BitmapLoader 拉取 artworkUri，
        // 默认实现不带 JWT，封面接口在鉴权路径下会返回 401。
        // 改用 Coil（已挂 AuthInterceptor 携带 token）加载，复用同一鉴权链路。
        val bitmapLoader = CoilBitmapLoader(this)

        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(mediaSessionCallback)
            .setBitmapLoader(bitmapLoader)
            .build()
    }

    // 均衡器按需创建：media3 在 ExoPlayer 构建时即分配 audioSessionId（AudioTrack 复用同一 id），
    // 无需等待播放开始；失败（如设备无 audiofx HAL）时每次命令到达都会重试
    private fun ensureEqualizer(): Equalizer? {
        equalizer?.let { return it }
        val sessionId = player?.audioSessionId ?: return null
        if (sessionId <= 0) return null
        return try {
            Equalizer(0, sessionId).also {
                equalizer = it
                Log.d(TAG, "均衡器创建成功：${it.numberOfBands} 段，${it.numberOfPresets} 个预设（session=$sessionId）")
            }
        } catch (e: Exception) {
            // 记录失败原因，便于区分设备不支持与权限/会话问题
            Log.w(TAG, "创建均衡器失败，audioSession=$sessionId", e)
            null
        }
    }

    private fun ensureSfx(type: SfxType): AudioEffect? {
        sfxEffects[type]?.let { return it }
        val sessionId = player?.audioSessionId ?: return null
        if (sessionId <= 0) return null
        return try {
            val effect: AudioEffect = when (type) {
                // LoudnessEnhancer 构造器无 priority 参数，与其余效果不同
                SfxType.VIRTUALIZER -> Virtualizer(0, sessionId)
                SfxType.BASS_BOOST -> BassBoost(0, sessionId)
                SfxType.LOUDNESS -> LoudnessEnhancer(sessionId)
                SfxType.REVERB -> PresetReverb(0, sessionId)
            }
            sfxEffects[type] = effect
            Log.d(TAG, "音效效果器创建成功：$type（session=$sessionId）")
            effect
        } catch (e: Exception) {
            Log.w(TAG, "创建音效效果器失败：$type，audioSession=$sessionId", e)
            null
        }
    }

    // 单模式互斥：先禁用全部效果，再启用选中的模式；"off" 只做禁用
    private fun applySfx(mode: String, strength: Int): Boolean {
        val type = when (mode) {
            "virtualizer" -> SfxType.VIRTUALIZER
            "bass_boost" -> SfxType.BASS_BOOST
            "loudness" -> SfxType.LOUDNESS
            "reverb" -> SfxType.REVERB
            else -> null
        }
        if (type == null) {
            runCatching { sfxEffects.values.forEach { it.enabled = false } }
            Log.d(TAG, "sfx/apply：mode=off，全部效果已禁用")
            return true
        }
        val ok = runCatching {
            sfxEffects.values.forEach { it.enabled = false }
            val effect = ensureSfx(type) ?: return@runCatching false
            mapSfxStrength(type, effect, strength)
            effect.enabled = true
            true
        }.getOrDefault(false)
        Log.d(TAG, "sfx/apply 结果：$ok（mode=$mode, strength=$strength）")
        return ok
    }

    // 语义强度 0-100 → audiofx 参数；BassBoost 上限 600 防破音，PresetReverb 无强度参数按段映射
    private fun mapSfxStrength(type: SfxType, effect: AudioEffect, strength: Int) {
        val s = strength.coerceIn(0, 100)
        when (type) {
            SfxType.VIRTUALIZER -> (effect as Virtualizer).setStrength((s * 10).toShort())
            SfxType.BASS_BOOST -> (effect as BassBoost).setStrength((s * 10).coerceAtMost(600).toShort())
            SfxType.LOUDNESS -> (effect as LoudnessEnhancer).setTargetGain(s * 20)
            SfxType.REVERB -> (effect as PresetReverb).setPreset(
                when {
                    s < 34 -> PresetReverb.PRESET_MEDIUMROOM
                    s <= 66 -> PresetReverb.PRESET_LARGEROOM
                    else -> PresetReverb.PRESET_LARGEHALL
                }
            )
        }
    }

    // 静态能力矩阵：不依赖音频会话，null 视为全不支持（与 eq/check 一致）
    private fun querySfxMatrix(): Map<SfxType, Boolean> {
        val effects = AudioEffect.queryEffects()
        return SfxType.entries.associateWith { type ->
            val typeUuid = when (type) {
                SfxType.VIRTUALIZER -> AudioEffect.EFFECT_TYPE_VIRTUALIZER
                SfxType.BASS_BOOST -> AudioEffect.EFFECT_TYPE_BASS_BOOST
                SfxType.LOUDNESS -> AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER
                SfxType.REVERB -> AudioEffect.EFFECT_TYPE_PRESET_REVERB
            }
            effects?.any { it.type == typeUuid } == true
        }
    }

    private fun releaseSfxAll() {
        sfxEffects.values.forEach { runCatching { it.release() } }
        sfxEffects.clear()
    }

    private fun isA2dpActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return (getSystemService(AUDIO_SERVICE) as? AudioManager)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } == true
    }

    private fun onOutputDevicesChanged() {
        Log.d(TAG, "输出设备变化（A2DP=${isA2dpActive()}），音效效果已停用，待重新校验")
        releaseSfxAll()
    }

    // 音频会话 id 变化时旧均衡器失效，释放后按需重建（media3 中仅显式 setAudioSessionId 触发）
    private val playerListener = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId <= 0) return
            equalizer?.release()
            equalizer = null
            releaseSfxAll()
            Log.d(TAG, "音频会话变化：$audioSessionId，均衡器/音效按需重建")
        }
    }

    private val playbackAnalyticsListener = object : AnalyticsListener {
        private fun stateName(state: Int) = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            Log.i(TAG, "播放状态变化：${stateName(state)}")
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            Log.i(TAG, "播放中=$isPlaying（${if (isPlaying) "正在输出音频" else "已暂停/停止"}）")
        }

        @OptIn(UnstableApi::class)
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            Log.i(TAG, "网络请求开始：${loadEventInfo.uri}")
        }

        @OptIn(UnstableApi::class)
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val headers = loadEventInfo.responseHeaders
            val contentType = headers["Content-Type"]?.firstOrNull()
            Log.i(
                TAG,
                "网络请求完成：${loadEventInfo.uri} | 字节=${loadEventInfo.bytesLoaded} | " +
                    "耗时=${loadEventInfo.loadDurationMs}ms | Content-Type=$contentType"
            )
        }

        @OptIn(UnstableApi::class)
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            val responseCode = (error as? HttpDataSource.HttpDataSourceException)?.cause
                ?.let { it as? HttpDataSource.InvalidResponseCodeException }?.responseCode
                ?: (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            Log.e(
                TAG,
                "网络请求失败：${loadEventInfo.uri} | " +
                    "HTTP=${responseCode ?: "N/A"} | canceled=$wasCanceled | ${error.message}",
                error
            )
        }

        @OptIn(UnstableApi::class)
        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val labels = audioGroups.mapIndexed { i, g ->
                "${i}:${g.getTrackFormat(0).label ?: g.getTrackFormat(0).sampleMimeType ?: "?"}"
            }
            Log.i(TAG, "音轨就绪：音频轨数=${audioGroups.size} [${labels.joinToString()}]")
        }
    }

    @OptIn(UnstableApi::class)
    private val mediaSessionCallback = object : MediaSession.Callback {
        // 自定义命令必须在此授权，否则分发前被拒（ERROR_PERMISSION_DENIED）
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(EQ_APPLY, Bundle.EMPTY))
                .add(SessionCommand(EQ_INFO, Bundle.EMPTY))
                .add(SessionCommand(EQ_CHECK, Bundle.EMPTY))
                .add(SessionCommand(SFX_APPLY, Bundle.EMPTY))
                .add(SessionCommand(SFX_INFO, Bundle.EMPTY))
                .add(SessionCommand(SFX_CHECK, Bundle.EMPTY))
                .add(SessionCommand(CACHE_CLEAR, Bundle.EMPTY))
                .add(SessionCommand(CACHE_APPLY, Bundle.EMPTY))
                .add(SessionCommand(VOCAL_REMOVE_APPLY, Bundle.EMPTY))
                .add(SessionCommand(VOCAL_REMOVE_CHECK, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            // 音效命令不依赖均衡器，先行分发
            when (customCommand.customAction) {
                CACHE_APPLY -> {
                    // 缓存大小变更：未播放时重启服务，下次播放即按新值生效；播放中不打扰
                    val p = player
                    if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
                        Log.i(TAG, "cache/apply：未在播放，重启服务使缓存设置生效")
                        stopSelf()
                    } else {
                        Log.i(TAG, "cache/apply：正在播放，缓存设置保持下次生效")
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CACHE_CLEAR -> {
                    val ok = runCatching {
                        val c = cache
                        if (c == null) {
                            true
                        } else {
                            c.getKeys().forEach { key -> c.removeResource(key) }
                            Log.i(TAG, "cache/clear：已清空播放缓存")
                            true
                        }
                    }.getOrDefault(false)
                    if (!ok) Log.w(TAG, "cache/clear 执行失败")
                    Futures.immediateFuture(
                        if (ok) SessionResult(SessionResult.RESULT_SUCCESS)
                        else SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    )
                }
                SFX_APPLY -> {
                    // enabled=false 时即使 mode 残留也强制关闭，避免"开关已关但效果仍生效"
                    val mode = if (args.getBoolean(EXTRA_ENABLED, false)) {
                        args.getString(EXTRA_MODE, "off")
                    } else {
                        "off"
                    }
                    val ok = applySfx(mode, args.getInt(EXTRA_STRENGTH, 50))
                    return Futures.immediateFuture(
                        if (ok) SessionResult(SessionResult.RESULT_SUCCESS)
                        else SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    )
                }
                SFX_INFO -> {
                    val matrix = querySfxMatrix()
                    // 矩阵顺序与 SfxType 声明一致：virtualizer/bass_boost/loudness/reverb
                    val extras = Bundle().apply {
                        putBoolean(EXTRA_SUPPORTED, matrix.values.any { it })
                        putBooleanArray(
                            EXTRA_SUPPORTED_MATRIX,
                            SfxType.entries.map { matrix[it] == true }.toBooleanArray()
                        )
                        putBoolean(EXTRA_A2DP, isA2dpActive())
                        putString(
                            EXTRA_ACTIVE_MODE,
                            sfxEffects.entries.firstOrNull { it.value.enabled }?.key?.name?.lowercase() ?: "off"
                        )
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
                }
                // 能力校验：静态查询系统 audiofx HAL，不依赖音频会话，设置页开启时使用
                SFX_CHECK -> {
                    val supported = querySfxMatrix().values.any { it }
                    Log.d(TAG, "sfx/check：设备支持音效 = $supported")
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply { putBoolean(EXTRA_SUPPORTED, supported) }
                        )
                    )
                }
                VOCAL_REMOVE_APPLY -> {
                    val enabled = args.getBoolean(EXTRA_ENABLED, false)
                    vocalRemovalProcessor.setEnabled(enabled)
                    Log.d(TAG, "vocal/apply: enabled=$enabled")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                VOCAL_REMOVE_CHECK -> {
                    val extras = Bundle().apply {
                        putBoolean(EXTRA_SUPPORTED, vocalRemovalProcessor.isActive())
                        putBoolean(EXTRA_ENABLED, vocalRemovalProcessor.isEnabled())
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
                }
            }
            val eq = ensureEqualizer()
            if (eq == null) {
                Log.w(TAG, "收到 ${customCommand.customAction} 但均衡器不可用（未创建或创建失败）")
                return if (customCommand.customAction == EQ_INFO) {
                    Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply { putBoolean(EXTRA_SUPPORTED, false) }
                        )
                    )
                } else {
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return Futures.immediateFuture(
                when (customCommand.customAction) {
                    EQ_APPLY -> {
                        val ok = runCatching {
                            eq.enabled = args.getBoolean(EXTRA_ENABLED, false)
                            args.getIntArray(EXTRA_BANDS)?.let { bands ->
                                val count = minOf(bands.size, eq.numberOfBands.toInt())
                                val range = eq.bandLevelRange
                                for (i in 0 until count) {
                                    val level = (bands[i] * 100)
                                        .coerceIn(range[0].toInt(), range[1].toInt())
                                        .toShort()
                                    eq.setBandLevel(i.toShort(), level)
                                }
                            }
                        }.isSuccess
                        Log.d(TAG, "eq/apply 结果：$ok（enabled=${args.getBoolean(EXTRA_ENABLED, false)}）")
                        if (ok) SessionResult(SessionResult.RESULT_SUCCESS)
                        else SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    }
                    EQ_INFO -> {
                        Log.d(TAG, "eq/info 返回：${eq.numberOfBands} 段")
                        val extras = Bundle().apply {
                            putBoolean(EXTRA_SUPPORTED, true)
                            val bandCount = eq.numberOfBands.toInt()
                            putInt(EXTRA_BAND_COUNT, bandCount)
                            putIntArray(
                                EXTRA_CENTER_FREQS,
                                IntArray(bandCount) { eq.getCenterFreq(it.toShort()) }
                            )
                            val range = eq.bandLevelRange
                            putInt(EXTRA_LEVEL_MIN, range[0].toInt())
                            putInt(EXTRA_LEVEL_MAX, range[1].toInt())
                            putBoolean(EXTRA_ENABLED, eq.enabled)
                            putIntArray(
                                EXTRA_BANDS,
                                IntArray(bandCount) { eq.getBandLevel(it.toShort()).toInt() / 100 }
                            )
                        }
                        SessionResult(SessionResult.RESULT_SUCCESS, extras)
                    }
                    // 能力校验：静态查询系统 audiofx HAL，不依赖音频会话，设置页开启时使用
                    EQ_CHECK -> {
                        val supported = AudioEffect.queryEffects()
                            ?.any { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER } == true
                        Log.d(TAG, "eq/check：设备支持均衡器 = $supported")
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply { putBoolean(EXTRA_SUPPORTED, supported) }
                        )
                    }
                    else -> SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                }
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player?.stop()
            player?.release()
            release()
            mediaSession = null
            this@MusicService.player = null
        }
        runCatching { cache?.release() }
        cache = null
        equalizer?.release()
        equalizer = null
        releaseSfxAll()
        vocalRemovalProcessor.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                (getSystemService(AUDIO_SERVICE) as AudioManager)
                    .unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MusicService"

        const val EQ_APPLY = "eq/apply"
        const val EQ_INFO = "eq/info"
        const val EQ_CHECK = "eq/check"

        const val SFX_APPLY = "sfx/apply"
        const val SFX_INFO = "sfx/info"
        const val SFX_CHECK = "sfx/check"

        const val CACHE_CLEAR = "cache/clear"
        const val CACHE_APPLY = "cache/apply"

        const val VOCAL_REMOVE_APPLY = "vocal/apply"
        const val VOCAL_REMOVE_CHECK = "vocal/check"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_BANDS = "bands"
        const val EXTRA_SUPPORTED = "supported"
        const val EXTRA_BAND_COUNT = "bandCount"
        const val EXTRA_CENTER_FREQS = "centerFreqs"
        const val EXTRA_LEVEL_MIN = "levelMin"
        const val EXTRA_LEVEL_MAX = "levelMax"

        const val EXTRA_MODE = "mode"
        const val EXTRA_STRENGTH = "strength"
        const val EXTRA_SUPPORTED_MATRIX = "supportedMatrix"
        const val EXTRA_A2DP = "a2dp"
        const val EXTRA_ACTIVE_MODE = "activeMode"
    }
}

// 通知/锁屏封面加载器：委托给 Coil（SongloftTvApp 已挂 AuthInterceptor 携带 JWT），
// 解决 Media3 默认 BitmapLoader 不带鉴权导致封面接口 401 的问题
@UnstableApi
private class CoilBitmapLoader(private val context: Context) : BitmapLoader {
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = com.google.common.util.concurrent.SettableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .build()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = Coil.imageLoader(context).execute(request)
                future.set((result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
            } catch (_: Exception) {
                future.set(null)
            }
        }
        return future
    }

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = com.google.common.util.concurrent.SettableFuture.create<Bitmap>()
        CoroutineScope(Dispatchers.IO).launch {
            future.set(BitmapFactory.decodeByteArray(data, 0, data.size))
        }
        return future
    }
}
