package com.songloft.tv.data.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.songloft.tv.domain.KeyMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = "songloft_tv_settings")

@Singleton
class PreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val THEME_COLOR = stringPreferencesKey("theme_color")
        private val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        // 开机自动续播：冷启动登录完成后恢复上次队列与进度并开始播放
        private val AUTO_RESUME_ON_LAUNCH = booleanPreferencesKey("auto_resume_on_launch")
        // 启动时若存在播放中歌曲（续播恢复或后台播放存活）自动进入全屏播放器
        private val AUTO_OPEN_PLAYER_ON_LAUNCH = booleanPreferencesKey("auto_open_player_on_launch")
        // 播放器底部控制栏常驻：开启后不自动隐藏
        private val PLAYER_CONTROLS_PERSISTENT = booleanPreferencesKey("player_controls_persistent")
        private val SCREENSAVER_TIMEOUT_MINUTES = intPreferencesKey("screensaver_timeout_minutes")
        private val USE_CUSTOM_KEYBOARD = booleanPreferencesKey("use_custom_keyboard")
        private val IGNORED_VERSION_CODE = intPreferencesKey("ignored_version_code")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_PRESET = stringPreferencesKey("eq_preset")
        private val EQ_BANDS = stringPreferencesKey("eq_bands")
        private val SFX_ENABLED = booleanPreferencesKey("sfx_enabled")
        private val SFX_MODE = stringPreferencesKey("sfx_mode")
        private val SFX_STRENGTH = intPreferencesKey("sfx_strength")
        private val PRE_TRANSCODE_ENABLED = booleanPreferencesKey("pre_transcode_enabled")
        val LYRIC_HIGHLIGHT_COLOR = intPreferencesKey("lyric_highlight_color")
        val LYRIC_FONT_SIZE = intPreferencesKey("lyric_font_size")
        private val PLAY_MODE = stringPreferencesKey("play_mode")
        private val PLAY_CACHE_MB = intPreferencesKey("play_cache_mb")
        private val CACHE_SERVER_URL = stringPreferencesKey("cache_server_url")
        // 用户置顶歌单 id（逗号分隔），下标 0 最前
        private val PINNED_PLAYLISTS = stringPreferencesKey("pinned_playlists")
        // 首次启动已展示版权/免责声明（任意方式关闭即视为已展示，不再弹出）
        private val DISCLAIMER_SHOWN = booleanPreferencesKey("disclaimer_shown")
        // 自定义按键映射：用户物理按键 keycode，0 = 未自定义（跟随系统默认键）
        private val KEY_MAPPING_UP = intPreferencesKey("key_mapping_up")
        private val KEY_MAPPING_DOWN = intPreferencesKey("key_mapping_down")
        private val KEY_MAPPING_LEFT = intPreferencesKey("key_mapping_left")
        private val KEY_MAPPING_RIGHT = intPreferencesKey("key_mapping_right")
        private val KEY_MAPPING_BACK = intPreferencesKey("key_mapping_back")
        private val KEY_MAPPING_CONFIRM = intPreferencesKey("key_mapping_confirm")
        private val KEY_MAPPING_TOP = intPreferencesKey("key_mapping_top")
        private val KEY_MAPPING_BOTTOM = intPreferencesKey("key_mapping_bottom")
        private val KEY_MAPPING_ACCOMPANIMENT = intPreferencesKey("key_mapping_accompaniment")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[SERVER_URL] }
    val themeMode: Flow<Int> = context.dataStore.data.map { it[THEME_MODE] ?: 0 }
    val themeColor: Flow<String> = context.dataStore.data.map { it[THEME_COLOR] ?: "indigo" }
    val audioQuality: Flow<String?> = context.dataStore.data.map { it[AUDIO_QUALITY] }
    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN] }
    val backgroundPlayback: Flow<Boolean> = context.dataStore.data.map { it[BACKGROUND_PLAYBACK] ?: true }
    val autoResumeOnLaunch: Flow<Boolean> = context.dataStore.data.map { it[AUTO_RESUME_ON_LAUNCH] ?: false }
    val autoOpenPlayerOnLaunch: Flow<Boolean> = context.dataStore.data.map { it[AUTO_OPEN_PLAYER_ON_LAUNCH] ?: false }
    val playerControlsPersistent: Flow<Boolean> = context.dataStore.data.map { it[PLAYER_CONTROLS_PERSISTENT] ?: false }
    // 屏保等待时间：分钟，0 = 关闭（默认）
    val screensaverTimeoutMinutes: Flow<Int> = context.dataStore.data.map { it[SCREENSAVER_TIMEOUT_MINUTES] ?: 0 }
    val useCustomKeyboard: Flow<Boolean> = context.dataStore.data.map { it[USE_CUSTOM_KEYBOARD] ?: true }
    val ignoredVersionCode: Flow<Int> = context.dataStore.data.map { it[IGNORED_VERSION_CODE] ?: 0 }
    // 均衡器配置：开关 / 预设 key（"flat"/"rock"/...，"custom" = 自定义曲线）/ 频段增益 dB（逗号分隔）
    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[EQ_ENABLED] ?: false }
    val eqPreset: Flow<String> = context.dataStore.data.map { it[EQ_PRESET] ?: "flat" }
    val eqBands: Flow<String> = context.dataStore.data.map { it[EQ_BANDS] ?: "" }
    // 音效模式：总开关 / 模式 key（"virtualizer"/"bass_boost"/"loudness"/"reverb"）/ 强度 0-100
    val sfxEnabled: Flow<Boolean> = context.dataStore.data.map { it[SFX_ENABLED] ?: false }
    val sfxMode: Flow<String> = context.dataStore.data.map { it[SFX_MODE] ?: "virtualizer" }
    val sfxStrength: Flow<Int> = context.dataStore.data.map { it[SFX_STRENGTH] ?: 50 }
    // 预转码开关（默认关闭），需先启用 MP3 转码才有效
    val preTranscodeEnabled: Flow<Boolean> = context.dataStore.data.map { it[PRE_TRANSCODE_ENABLED] ?: false }
    // 播放模式：PlayMode.name（"ORDER"/"LOOP"/"SINGLE"/"RANDOM"），默认顺序播放
    val playMode: Flow<String> = context.dataStore.data.map { it[PLAY_MODE] ?: "ORDER" }
    // 歌词高亮颜色：1=白色，2=跟随主题色（默认）
    val lyricHighlightColor: Flow<Int> = context.dataStore.data.map { it[LYRIC_HIGHLIGHT_COLOR] ?: 2 }
    // 歌词字号：当前句字号 sp，默认 30；非当前句/翻译行按比例派生
    val lyricFontSize: Flow<Int> = context.dataStore.data.map { it[LYRIC_FONT_SIZE] ?: 30 }
    // 播放缓存：MB，0=关闭（默认）；仅当缓存归属服务器与当前 serverUrl 一致时才复用缓存目录
    val playCacheMb: Flow<Int> = context.dataStore.data.map { it[PLAY_CACHE_MB] ?: 0 }
    val cacheServerUrl: Flow<String?> = context.dataStore.data.map { it[CACHE_SERVER_URL] }
    val pinnedPlaylistIds: Flow<List<Long>> = context.dataStore.data.map {
        it[PINNED_PLAYLISTS]?.split(",")?.mapNotNull { s -> s.toLongOrNull() } ?: emptyList()
    }
    val disclaimerShown: Flow<Boolean> = context.dataStore.data.map { it[DISCLAIMER_SHOWN] ?: false }
    val keyMapping: Flow<KeyMapping> = context.dataStore.data.map {
        KeyMapping(
            up = it[KEY_MAPPING_UP] ?: 0,
            down = it[KEY_MAPPING_DOWN] ?: 0,
            left = it[KEY_MAPPING_LEFT] ?: 0,
            right = it[KEY_MAPPING_RIGHT] ?: 0,
            back = it[KEY_MAPPING_BACK] ?: 0,
            confirm = it[KEY_MAPPING_CONFIRM] ?: 0,
            top = it[KEY_MAPPING_TOP] ?: 0,
            bottom = it[KEY_MAPPING_BOTTOM] ?: 0,
            accompaniment = it[KEY_MAPPING_ACCOMPANIMENT] ?: 0
        )
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url }
    }

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setThemeColor(name: String) {
        context.dataStore.edit { it[THEME_COLOR] = name }
    }

    suspend fun setAudioQuality(quality: String) {
        context.dataStore.edit { it[AUDIO_QUALITY] = quality }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { it[BACKGROUND_PLAYBACK] = enabled }
    }

    suspend fun setAutoResumeOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_RESUME_ON_LAUNCH] = enabled }
    }

    suspend fun setAutoOpenPlayerOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_OPEN_PLAYER_ON_LAUNCH] = enabled }
    }

    suspend fun setPlayerControlsPersistent(enabled: Boolean) {
        context.dataStore.edit { it[PLAYER_CONTROLS_PERSISTENT] = enabled }
    }

    suspend fun setScreensaverTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { it[SCREENSAVER_TIMEOUT_MINUTES] = minutes }
    }

    suspend fun setUseCustomKeyboard(enabled: Boolean) {
        context.dataStore.edit { it[USE_CUSTOM_KEYBOARD] = enabled }
    }

    suspend fun setIgnoredVersionCode(code: Int) {
        context.dataStore.edit { it[IGNORED_VERSION_CODE] = code }
    }

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[EQ_ENABLED] = enabled }
    }

    suspend fun setEqPreset(preset: String) {
        context.dataStore.edit { it[EQ_PRESET] = preset }
    }

    suspend fun setEqBands(bands: String) {
        context.dataStore.edit { it[EQ_BANDS] = bands }
    }

    suspend fun setSfxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SFX_ENABLED] = enabled }
    }

    suspend fun setSfxMode(mode: String) {
        context.dataStore.edit { it[SFX_MODE] = mode }
    }

    suspend fun setSfxStrength(strength: Int) {
        context.dataStore.edit { it[SFX_STRENGTH] = strength }
    }

    suspend fun setPreTranscodeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PRE_TRANSCODE_ENABLED] = enabled }
    }

    suspend fun setPlayMode(mode: String) {
        context.dataStore.edit { it[PLAY_MODE] = mode }
    }

    suspend fun setLyricHighlightColor(mode: Int) {
        context.dataStore.edit { it[LYRIC_HIGHLIGHT_COLOR] = mode }
    }

    suspend fun setLyricFontSize(size: Int) {
        context.dataStore.edit { it[LYRIC_FONT_SIZE] = size }
    }

    suspend fun setPlayCacheMb(mb: Int) {
        context.dataStore.edit { it[PLAY_CACHE_MB] = mb }
    }

    suspend fun setCacheServerUrl(url: String) {
        context.dataStore.edit { it[CACHE_SERVER_URL] = url }
    }

    suspend fun setPinnedPlaylistIds(ids: List<Long>) {
        context.dataStore.edit { it[PINNED_PLAYLISTS] = ids.joinToString(",") }
    }

    suspend fun setDisclaimerShown() {
        context.dataStore.edit { it[DISCLAIMER_SHOWN] = true }
    }

    suspend fun setKeyMapping(m: KeyMapping) {
        context.dataStore.edit {
            it[KEY_MAPPING_UP] = m.up
            it[KEY_MAPPING_DOWN] = m.down
            it[KEY_MAPPING_LEFT] = m.left
            it[KEY_MAPPING_RIGHT] = m.right
            it[KEY_MAPPING_BACK] = m.back
            it[KEY_MAPPING_CONFIRM] = m.confirm
            it[KEY_MAPPING_TOP] = m.top
            it[KEY_MAPPING_BOTTOM] = m.bottom
            it[KEY_MAPPING_ACCOMPANIMENT] = m.accompaniment
        }
    }

    suspend fun setTokens(access: String, refresh: String) {
        context.dataStore.edit {
            it[ACCESS_TOKEN] = access
            it[REFRESH_TOKEN] = refresh
        }
    }

    suspend fun clearTokens() {
        context.dataStore.edit {
            it.remove(ACCESS_TOKEN)
            it.remove(REFRESH_TOKEN)
        }
    }
}
