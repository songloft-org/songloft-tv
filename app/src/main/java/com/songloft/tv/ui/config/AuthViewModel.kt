package com.songloft.tv.ui.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songloft.tv.data.config.ConfigWebServer
import com.songloft.tv.data.repository.AuthRepository
import com.songloft.tv.data.storage.PreferencesDataStore
import com.songloft.tv.util.LogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

sealed class AuthState {
    data object Loading : AuthState()
    data object NotConfigured : AuthState()
    data object Configured : AuthState()
    data class LoggedIn(val username: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val dataStore: PreferencesDataStore
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _configUrl = MutableStateFlow<String?>(null)
    val configUrl: StateFlow<String?> = _configUrl.asStateFlow()

    private val _pairingPin = MutableStateFlow("")
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _rememberMe = MutableStateFlow(false)
    val rememberMe: StateFlow<Boolean> = _rememberMe.asStateFlow()

    val useCustomKeyboard: StateFlow<Boolean> = dataStore.useCustomKeyboard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private var configServer: ConfigWebServer? = null
    private var pinRefreshJob: Job? = null

    private val deviceName: String =
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull().orEmpty()
            .ifBlank { Build.MODEL }

    fun startConfigServer() {
        if (configServer != null) return
        val ip = ConfigWebServer.localIpAddress() ?: return
        _pairingPin.value = generatePin()
        for (port in CONFIG_PORTS) {
            val server = ConfigWebServer(
                port,
                onConfig = { serverUrl, username, password, rememberMe ->
                    viewModelScope.launch {
                        _serverUrl.value = serverUrl
                        _username.value = username
                        _password.value = password
                        _rememberMe.value = rememberMe
                        login()
                    }
                },
                logsDir = LogStore.dir(context),
                deviceName = deviceName,
                pin = _pairingPin.value,
                onPushToken = { serverUrl, token ->
                    viewModelScope.launch { handlePushToken(serverUrl, token) }
                }
            )
            if (runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }.isSuccess) {
                configServer = server
                server.startBeacon()
                startPinRefresh()
                _configUrl.value = "http://$ip:$port"
                return
            }
        }
    }

    private fun startPinRefresh() {
        pinRefreshJob?.cancel()
        pinRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(PIN_REFRESH_INTERVAL_MS)
                _pairingPin.value = generatePin()
                configServer?.pin = _pairingPin.value
            }
        }
    }

    private fun stopConfigServer() {
        pinRefreshJob?.cancel()
        pinRefreshJob = null
        configServer?.stopBeacon()
        configServer?.stop()
        configServer = null
        _configUrl.value = null
        _pairingPin.value = ""
    }

    /** tv-helper 插件远程一键登录：校验宿主 token 可用后持久化并进入主界面 */
    private suspend fun handlePushToken(serverUrl: String, token: String) {
        if (authRepository.applyRemoteLogin(serverUrl, token)) {
            _serverUrl.value = serverUrl
            _authState.value = AuthState.LoggedIn("tv-helper")
            stopConfigServer()
        } else {
            _error.value = "远程登录失败：token 无效或服务器不可达，请重试或改用扫码登录"
        }
    }

    private fun generatePin(): String {
        val random = java.security.SecureRandom()
        return (1..4).joinToString("") { random.nextInt(10).toString() }
    }

    /** 无协议前缀时按 https → http 顺序生成候选地址，探测出可用协议 */
    private fun candidateUrls(url: String): List<String> =
        if (url.startsWith("http://") || url.startsWith("https://")) listOf(url)
        else listOf("https://$url", "http://$url")

    override fun onCleared() {
        stopConfigServer()
    }

    init {
        viewModelScope.launch {
            val storedUrl = dataStore.serverUrl.first()
            if (!storedUrl.isNullOrEmpty()) {
                _serverUrl.value = storedUrl
                if (dataStore.rememberMe.first()) {
                    // 勾选了记住登录：回填账号和密码
                    _username.value = "" // 用户名不在 DataStore 中，从 API 响应获取
                    _password.value = dataStore.password.first() ?: ""
                }
                tryAutoLogin()
            } else {
                _authState.value = AuthState.NotConfigured
            }
        }
    }

    private suspend fun tryAutoLogin() {
        val result = authRepository.tryAutoLogin()
        _authState.value = if (result) AuthState.LoggedIn("admin")
        else AuthState.Configured
    }

    fun onServerUrlChanged(url: String) {
        _serverUrl.value = url
        _error.value = null
    }

    fun onUsernameChanged(username: String) {
        _username.value = username
        _error.value = null
    }

    fun onPasswordChanged(password: String) {
        _password.value = password
        _error.value = null
    }

    fun login() {
        val url = _serverUrl.value.trim()
        val username = _username.value.trim()
        val password = _password.value

        if (url.isBlank()) { _error.value = "请输入服务器地址"; return }
        if (username.isBlank()) { _error.value = "请输入账号"; return }
        if (password.isBlank()) { _error.value = "请输入密码"; return }

        _isLoggingIn.value = true
        _error.value = null

        viewModelScope.launch {
            var lastError: Throwable? = null
            for (candidate in candidateUrls(url)) {
                val result = authRepository.login(candidate, username, password, _rememberMe.value)
                if (result.isSuccess) {
                    _serverUrl.value = candidate
                    _isLoggingIn.value = false
                    _authState.value = AuthState.LoggedIn(username)
                    stopConfigServer()
                    return@launch
                }
                lastError = result.exceptionOrNull()
                // 仅连接层失败（IOException）才换协议重试；服务器有真实响应（如账号密码错误）直接报错
                if (lastError !is IOException) break
            }
            _isLoggingIn.value = false
            _error.value = lastError?.message ?: "登录失败"
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _authState.value = AuthState.NotConfigured
        }
    }

    fun resetToConfig() {
        viewModelScope.launch {
            authRepository.clearAllAuth()
            _authState.value = AuthState.NotConfigured
            _serverUrl.value = ""
            _username.value = ""
            _password.value = ""
            _rememberMe.value = false
            _error.value = null
        }
    }

    fun setRememberMe(checked: Boolean) {
        _rememberMe.value = checked
    }

    companion object {
        private val CONFIG_PORTS = intArrayOf(18899, 18900, 18901, 18902)
        private const val PIN_REFRESH_INTERVAL_MS = 60_000L
    }
}
