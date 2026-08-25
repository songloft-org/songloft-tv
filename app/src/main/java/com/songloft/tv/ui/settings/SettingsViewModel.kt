package com.songloft.tv.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songloft.tv.data.api.ApiClient
import com.songloft.tv.data.cache.PlaybackCache
import com.songloft.tv.data.storage.PreferencesDataStore
import com.songloft.tv.data.config.ConfigWebServer
import com.songloft.tv.domain.KeyMapping
import com.songloft.tv.domain.MappingTarget
import com.songloft.tv.domain.PlayerController
import com.songloft.tv.ui.settings.LogDownloadServer
import com.songloft.tv.util.LogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: Int = 0,
    val themeColor: String = "indigo",
    val serverUrl: String = "",
    val audioQuality: String = "",
    val backgroundPlayback: Boolean = true,
    val useCustomKeyboard: Boolean = true,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemaining: Int = 0,
    val sleepAfterSongs: Int = 0,
    val sleepAfterSongsRemaining: Int = 0,
    val eqEnabled: Boolean = false,
    val sfxEnabled: Boolean = false,
    val soundUnsupportedNotice: Boolean = false,
    val logExportStatus: String = "",
    val lyricHighlightColor: Int = 2,
    val lyricFontSize: Int = 30,
    val playCacheMb: Int = 0,
    val playCacheUsageBytes: Long = 0,
    val keyMapping: KeyMapping = KeyMapping(),
    val preTranscodeEnabled: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: PreferencesDataStore,
    private val playerController: PlayerController
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 缓存占用心跳：由设置页在可见期间 collect，播放中缓存增长时数值随之刷新 */
    val cacheUsageTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(CACHE_USAGE_POLL_INTERVAL_MS)
        }
    }

    init {
        viewModelScope.launch {
            dataStore.themeMode.collect { mode ->
                _uiState.value = _uiState.value.copy(themeMode = mode)
            }
        }
        viewModelScope.launch {
            dataStore.themeColor.collect { name ->
                _uiState.value = _uiState.value.copy(themeColor = name)
            }
        }
        viewModelScope.launch {
            dataStore.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url ?: "")
            }
        }
        viewModelScope.launch {
            dataStore.audioQuality.collect { q ->
                _uiState.value = _uiState.value.copy(audioQuality = q ?: "")
            }
        }
        viewModelScope.launch {
            dataStore.backgroundPlayback.collect { enabled ->
                _uiState.value = _uiState.value.copy(backgroundPlayback = enabled)
            }
        }
        viewModelScope.launch {
            dataStore.useCustomKeyboard.collect { enabled ->
                _uiState.value = _uiState.value.copy(useCustomKeyboard = enabled)
            }
        }
        viewModelScope.launch {
            dataStore.lyricHighlightColor.collect { mode ->
                _uiState.value = _uiState.value.copy(lyricHighlightColor = mode)
            }
        }
        viewModelScope.launch {
            dataStore.lyricFontSize.collect { size ->
                _uiState.value = _uiState.value.copy(lyricFontSize = size)
            }
        }
        viewModelScope.launch {
            dataStore.playCacheMb.collect { mb ->
                _uiState.value = _uiState.value.copy(playCacheMb = mb)
                refreshPlayCacheUsage()
            }
        }
        viewModelScope.launch {
            dataStore.keyMapping.collect { mapping ->
                _uiState.value = _uiState.value.copy(keyMapping = mapping)
            }
        }
        viewModelScope.launch {
            dataStore.preTranscodeEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(preTranscodeEnabled = enabled)
            }
        }
        viewModelScope.launch {
            playerController.state.collect { s ->
                _uiState.value = _uiState.value.copy(
                    sleepTimerMinutes = s.sleepTimerMinutes,
                    sleepTimerRemaining = s.sleepTimerRemaining,
                    sleepAfterSongs = s.sleepAfterSongs,
                    sleepAfterSongsRemaining = s.sleepAfterSongsRemaining,
                    eqEnabled = s.eqEnabled,
                    sfxEnabled = s.sfxEnabled
                )
            }
        }
    }

    fun setPreTranscodeEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setPreTranscodeEnabled(enabled) }
    }

    fun setSleepTimer(minutes: Int) {
        playerController.setSleepTimer(minutes)
    }

    fun setSleepAfterSongs(count: Int) {
        playerController.setSleepAfterSongs(count)
    }

    fun setSoundEnabled(enabled: Boolean) {
        if (!enabled) {
            // 直接关闭均衡器与音效，无需校验
            playerController.setEqualizerEnabled(false)
            playerController.setSfxEnabled(false)
            return
        }
        // 开启：分别校验设备能力，均衡器与音效任一支持即可
        var pending = 2
        var anySupported = false
        playerController.setEqualizerEnabled(true) { ok ->
            if (ok) anySupported = true
            if (--pending == 0 && !anySupported) {
                _uiState.update { it.copy(soundUnsupportedNotice = true) }
            }
        }
        playerController.setSfxEnabled(true) { ok ->
            if (ok) anySupported = true
            if (--pending == 0 && !anySupported) {
                _uiState.update { it.copy(soundUnsupportedNotice = true) }
            }
        }
    }

    fun dismissSoundUnsupportedNotice() {
        _uiState.update { it.copy(soundUnsupportedNotice = false) }
    }

    fun setThemeMode(mode: Int) {
        viewModelScope.launch { dataStore.setThemeMode(mode) }
    }

    fun setThemeColor(name: String) {
        viewModelScope.launch { dataStore.setThemeColor(name) }
    }

    fun setAudioQuality(quality: String) {
        viewModelScope.launch { dataStore.setAudioQuality(quality) }
    }

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { dataStore.setBackgroundPlayback(enabled) }
    }

    fun setUseCustomKeyboard(enabled: Boolean) {
        viewModelScope.launch { dataStore.setUseCustomKeyboard(enabled) }
    }

    fun setLyricHighlightColor(mode: Int) {
        viewModelScope.launch { dataStore.setLyricHighlightColor(mode) }
    }

    fun setLyricFontSize(size: Int) {
        viewModelScope.launch { dataStore.setLyricFontSize(size) }
    }

    fun setPlayCacheMb(mb: Int) {
        viewModelScope.launch {
            dataStore.setPlayCacheMb(mb)
            if (mb == 0) {
                // 关闭缓存：立即清空现有占用（播放中只能按 key 删除；服务下次启动时再整目录清理）
                playerController.clearPlayCache { _ -> refreshPlayCacheUsage() }
            }
            // 持久化完成后通知服务：未播放则立即重启，让新大小下次播放即生效
            playerController.applyCacheSetting()
        }
    }

    fun setKeyMapping(target: MappingTarget, keyCode: Int) {
        viewModelScope.launch {
            val current = _uiState.value.keyMapping
            dataStore.setKeyMapping(
                when (target) {
                    MappingTarget.UP -> current.copy(up = keyCode)
                    MappingTarget.DOWN -> current.copy(down = keyCode)
                    MappingTarget.LEFT -> current.copy(left = keyCode)
                    MappingTarget.RIGHT -> current.copy(right = keyCode)
                    MappingTarget.BACK -> current.copy(back = keyCode)
                    MappingTarget.CONFIRM -> current.copy(confirm = keyCode)
                    MappingTarget.TOP -> current.copy(top = keyCode)
                    MappingTarget.BOTTOM -> current.copy(bottom = keyCode)
                }
            )
        }
    }

    fun resetKeyMapping() {
        viewModelScope.launch { dataStore.setKeyMapping(KeyMapping()) }
    }

    fun refreshPlayCacheUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { PlaybackCache.usage(context) }.getOrDefault(0L)
            _uiState.update { it.copy(playCacheUsageBytes = bytes) }
        }
    }

    fun clearPlayCache() {
        playerController.clearPlayCache { _ -> refreshPlayCacheUsage() }
    }

    // 重启应用：先发启动 Intent 再退出进程，由系统重新拉起（部分设置如播放缓存大小需重启生效）
    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    fun clearServerConfig() {
        viewModelScope.launch {
            dataStore.setServerUrl("")
            dataStore.clearTokens()
            ApiClient.authInterceptor.accessToken = null
            ApiClient.authInterceptor.refreshToken = null
        }
    }

    fun exportLogs() {
        _uiState.value = _uiState.value.copy(logExportStatus = "正在导出…")
        viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching {
                val fileName = "songloft-tv-log-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                val process = Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-v", "threadtime"))
                val file = File(LogStore.dir(context), fileName)
                process.inputStream.bufferedReader().use { reader ->
                    file.bufferedWriter().use { writer ->
                        reader.forEachLine { writer.appendLine(sanitizeLogLine(it)) }
                    }
                }
                startLogServer(file)
                if (_logDownloadUrl.value != null) "已导出 $fileName（已脱敏），手机扫码即可下载"
                else "已导出 $fileName（已脱敏），未获取到局域网地址，无法扫码下载"
            }.getOrElse { e -> "导出失败：${e.message}" }
            _uiState.value = _uiState.value.copy(logExportStatus = status)
        }
    }

    private var logServer: LogDownloadServer? = null

    private val _logDownloadUrl = MutableStateFlow<String?>(null)
    val logDownloadUrl: StateFlow<String?> = _logDownloadUrl.asStateFlow()

    private fun startLogServer(file: File) {
        logServer?.stop()
        logServer = null
        _logDownloadUrl.value = null
        val ip = ConfigWebServer.localIpAddress() ?: return
        for (port in LOG_PORTS) {
            val server = LogDownloadServer(port, file)
            if (runCatching { server.start() }.isSuccess) {
                logServer = server
                _logDownloadUrl.value = "http://$ip:$port"
                return
            }
        }
    }

    fun stopLogDownload() {
        logServer?.stop()
        logServer = null
        _logDownloadUrl.value = null
    }

    override fun onCleared() {
        stopLogDownload()
    }

    private fun sanitizeLogLine(line: String): String {
        var s = line
        // HTTP 头：Authorization / Cookie / Set-Cookie
        s = s.replace(SENSITIVE_HEADER_REGEX, "$1: ***")
        // JSON 字段：token / password / secret 等
        s = s.replace(SENSITIVE_JSON_REGEX, "$1***$2")
        // URL 参数或 key=value 形式的 token / password
        s = s.replace(SENSITIVE_PARAM_REGEX, "$1***")
        // 裸 JWT
        s = s.replace(JWT_REGEX, "***.***.***")
        // 隐藏媒体流 URL 的服务器 host[:port]，保留路径 + 查询用于排除 403/404/格式问题。
        // 例：https://host:port/api/v1/songs/123/play?track=vocal&format=mp3
        //   → ：https://***/api/v1/songs/123/play?track=vocal&format=mp3
        s = s.replace(MEDIA_HOST_REGEX) { m -> "${m.groupValues[1]}***${m.groupValues[3]}" }
        // OSS/_presigned 参数 (X-Amz-*)：隐藏取值，保留参数名
        s = s.replace(PRESIGNED_PARAM_REGEX) { m -> "${m.groupValues[1]}=<redacted>" }
        return s
    }

    private companion object {
        const val CACHE_USAGE_POLL_INTERVAL_MS = 5_000L
        val LOG_PORTS = intArrayOf(18907, 18908, 18909)

        val SENSITIVE_HEADER_REGEX =
            Regex("(?i)\\b(authorization|cookie|set-cookie|x-api-key)\\s*:\\s*.*")
        val SENSITIVE_JSON_REGEX =
            Regex("(?i)(\"(?:access_token|refresh_token|token|password|secret)\"\\s*:\\s*\")[^\"]*(\")")
        val SENSITIVE_PARAM_REGEX =
            Regex("(?i)\\b((?:access_token|refresh_token|token|password|secret)=)[^&\\s\"']+")
        val JWT_REGEX =
            Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
        // scheme://host[:port]/ → scheme://***  (隐藏自建服务器地址，保留路径用于排障)
        val MEDIA_HOST_REGEX = Regex("(https?://)[^/]+(:[0-9]+)?(/|$)")
        // OSS/_presigned 参数，例如 ?X-Amz-Signature=abc 或 &X-Amz-Credential=xyz
        val PRESIGNED_PARAM_REGEX = Regex("(?i)([?&][Xx]-Amz-[A-Za-z0-9-]+)=[^&\\s]+")
    }
}
