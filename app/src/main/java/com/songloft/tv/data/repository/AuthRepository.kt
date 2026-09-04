package com.songloft.tv.data.repository

import com.google.gson.Gson
import com.songloft.tv.data.api.ApiClient
import com.songloft.tv.data.storage.PreferencesDataStore
import retrofit2.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val dataStore: PreferencesDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ApiClient.onTokensRefreshed = { access, refresh ->
            scope.launch { dataStore.setTokens(access, refresh) }
        }
    }

    suspend fun login(serverUrl: String, username: String, password: String, rememberMe: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                ApiClient.initialize(serverUrl)
                com.songloft.tv.data.api.UrlHelper.initialize(serverUrl)
                val response = ApiClient.getApi().login(
                    mapOf("username" to username, "password" to password)
                )
                ApiClient.authInterceptor.accessToken = response.accessToken
                ApiClient.authInterceptor.refreshToken = response.refreshToken
                dataStore.setServerUrl(serverUrl)
                dataStore.setTokens(response.accessToken, response.refreshToken, rememberMe, if (rememberMe) password else null)
                true
            }.mapLoginFailure()
        }

    /**
     * tv-helper 插件远程一键登录：宿主插件 token 永不过期，无需 refresh token，
     * 写入空 refresh token 即可（token 失效只发生在宿主重启后重新签发，届时回到配置页重新配对）。
     */
    suspend fun applyRemoteLogin(serverUrl: String, accessToken: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                ApiClient.initialize(serverUrl)
                com.songloft.tv.data.api.UrlHelper.initialize(serverUrl)
                ApiClient.authInterceptor.accessToken = accessToken
                ApiClient.authInterceptor.refreshToken = null
                ApiClient.getApi().health()
                dataStore.setServerUrl(serverUrl)
                dataStore.setTokens(accessToken, "")
                true
            }.getOrDefault(false)
        }

    /**
     * 服务器返回 401 时 Retrofit 抛出的 HttpException.message 只是 "HTTP 401"，
     * 真实原因在响应体 JSON（如 {"error":"用户名或密码错误"}）里，这里解析出来替换。
     */
    private fun <T> Result<T>.mapLoginFailure(): Result<T> {
        val e = exceptionOrNull() ?: return this
        // 仅转换 HttpException；IOException 保持原样，让 ViewModel 继续换协议重试
        if (e !is HttpException) return this
        val body = e.response()?.errorBody()?.string().orEmpty()
        val parsed = runCatching { Gson().fromJson(body, LoginErrorBody::class.java) }.getOrNull()
        val message = parsed?.error
            ?: parsed?.detail
            ?: e.message()
            ?: "HTTP ${e.code()}"
        return Result.failure(IllegalStateException(message))
    }

    private data class LoginErrorBody(
        val error: String? = null,
        val detail: String? = null
    )

    suspend fun tryAutoLogin(): Boolean {
        return withContext(Dispatchers.IO) {
            val token = dataStore.accessToken.first()
            val refresh = dataStore.refreshToken.first()
            val url = dataStore.serverUrl.first()

            if (token.isNullOrEmpty() || url.isNullOrEmpty()) return@withContext false

            runCatching {
                ApiClient.initialize(url)
                com.songloft.tv.data.api.UrlHelper.initialize(url)
                ApiClient.authInterceptor.accessToken = token
                ApiClient.authInterceptor.refreshToken = refresh
                ApiClient.getApi().health()
                true
            }.getOrDefault(false)
        }
    }

    suspend fun logout() {
        dataStore.clearTokens()
        ApiClient.authInterceptor.accessToken = null
        ApiClient.authInterceptor.refreshToken = null
    }

    /** 清除服务器配置与登录状态（设置页「清除配置」） */
    suspend fun clearAllAuth() {
        dataStore.clearAllAuth()
        ApiClient.authInterceptor.accessToken = null
        ApiClient.authInterceptor.refreshToken = null
    }
}
