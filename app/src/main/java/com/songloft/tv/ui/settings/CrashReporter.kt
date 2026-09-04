package com.songloft.tv.ui.settings

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {

    private const val CRASH_DIR = "crash"
    private const val MAX_KEEP = 5

    fun handleCrash(context: Context, throwable: Throwable) {
        try {
            val dir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
            rotate(dir)
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$timestamp.log").apply { createNewFile() }
            StringWriter().use { sw ->
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                // 附加当前线程未处理异常信息
                for ((thread, frames) in Thread.getAllStackTraces()) {
                    if (thread.name != "main") continue
                    pw.println()
                    pw.println("=== Thread: $thread ===")
                    for (f in frames) {
                        pw.println(f.toString())
                    }
                }
            }.let { sw -> file.writeText(sw.toString()) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 获取最近的崩溃日志列表 */
    fun getRecentCrashes(context: Context): List<File> {
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /** 清理旧日志，只保留最近 MAX_KEEP 个 */
    private fun rotate(dir: File) {
        val files = dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
        if (files != null && files.size >= MAX_KEEP) {
            files.sortedBy { it.lastModified() }.take(files.size - MAX_KEEP + 1).forEach { it.delete() }
        }
    }

    /** 清除所有崩溃日志 */
    fun clearCrashes(context: Context): Int {
        val dir = File(context.filesDir, CRASH_DIR)
        val files = dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") } ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.delete()) deleted++
        }
        return deleted
    }

    @Suppress("unused")
    fun createShareIntent(context: Context, content: String): android.content.Intent {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
        )
        file.parentFile?.mkdirs()
        file.writeText(content)
        val uri = android.net.Uri.fromFile(file)
        return android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Songloft TV 崩溃日志")
            putExtra(android.content.Intent.EXTRA_TEXT, "")
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    /** 加载指定崩溃文件内容 */
    fun loadCrashContent(context: Context, file: File): String {
        return file.readText()
    }

    /** 导出最新崩溃日志到外部存储并返回分享 Intent */
    fun exportLatestCrash(context: Context): android.content.Intent? {
        val crashes = getRecentCrashes(context)
        if (crashes.isEmpty()) return null
        val latest = crashes.first()
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
        )
        file.parentFile?.mkdirs()
        file.writeText(latest.readText())
        val uri = android.net.Uri.fromFile(file)
        return android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Songloft TV 崩溃日志")
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    /** 复制到剪贴板 */
    fun copyToClipboard(context: Context, content: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SongloftTV Crash Log", content)
        clipboard.setPrimaryClip(clip)
        return true
    }
}
