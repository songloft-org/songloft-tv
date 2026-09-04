package com.songloft.tv.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import com.songloft.tv.ui.settings.CrashReporter.handleCrash

@SuppressLint("Registered")
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val delegate = Thread.getDefaultUncaughtExceptionHandler()!!

    override fun uncaughtException(t: Thread, e: Throwable) {
        handleCrash(context, e)
        delegate.uncaughtException(t, e)

        // 兜底：如果原 Handler 没有退出，10 秒后强制杀进程
        crashProcess()
    }

    private fun crashProcess() {
        Thread.sleep(1_000L)
        Process.killProcess(Process.myPid())
        System.exit(10)
    }
}
