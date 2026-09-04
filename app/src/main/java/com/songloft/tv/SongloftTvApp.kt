package com.songloft.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.songloft.tv.data.api.ApiClient
import com.songloft.tv.data.api.TlsCompat
import com.songloft.tv.ui.settings.CrashHandler
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient

@HiltAndroidApp
class SongloftTvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        TlsCompat.initialize(this)
        // 注册全局崩溃处理器：闪退时将堆栈写入 filesDir/crash/，用户可在设置页查看/发送
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }

    // 封面接口位于 JWT 鉴权路径下，Coil 需复用 AuthInterceptor 携带 token，否则 401 无法加载
    override fun newImageLoader(): ImageLoader {
        val client = TlsCompat.apply(
            OkHttpClient.Builder()
                .addInterceptor(ApiClient.authInterceptor)
        ).build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
