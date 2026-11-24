package com.songloft.tv.data.config

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.songloft.tv.BuildConfig
import com.songloft.tv.data.model.Song
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * 局域网 Web 服务：手机扫码打开页签页面，
 * 「登录配置」页签提交服务器地址/账号/密码回电视端登录，
 * 「搜索」页签提交关键字触发电视端搜索，
 * 「日志」页签列出电视端导出的日志文件并支持下载。
 * 同时承担 tv-helper 插件一键登录：
 * startBeacon() 广播设备信息（含配对码）供插件发现，
 * POST /push-token 校验配对码后写入宿主 token 完成远程登录。
 */
class ConfigWebServer(
    port: Int,
    private val onConfig: ((server: String, username: String, password: String, rememberMe: Boolean) -> Unit)? = null,
    private val onSearch: ((keyword: String) -> Unit)? = null,
    private val logsDir: File? = null,
    private val deviceName: String = "",
    @Volatile var pin: String = "",
    private val onPushToken: ((server: String, token: String) -> Unit)? = null,
    // 扫码点歌：歌曲搜索（同步返回结果）、加入队列、置顶、删除、读取队列
    private val onOrderSearch: ((keyword: String) -> List<Song>)? = null,
    private val onOrderAdd: ((song: Song) -> Unit)? = null,
    private val onOrderTop: ((index: Int) -> Unit)? = null,
    private val onOrderRemove: ((index: Int) -> Unit)? = null,
    private val onOrderQueue: (() -> List<Song>)? = null,
    // GitHub 加速源：扫码配置页保存时回调，空串表示恢复直连
    private val onProxyConfig: ((proxy: String) -> Unit)? = null,
    private val initialProxy: String = ""
) : NanoHTTPD(port) {

    private val gson = Gson()

    @Volatile
    private var beaconRunning = false
    private var beaconThread: Thread? = null

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/submit") {
            session.parseBody(HashMap())
            val onConfig = onConfig
                ?: return html(Response.Status.BAD_REQUEST, resultPage("配置失败", "电视端当前不在登录页，无法提交配置。"))
            val params = session.parameters
            val server = params["server"]?.firstOrNull()?.trim().orEmpty()
            val username = params["username"]?.firstOrNull()?.trim().orEmpty()
            val password = params["password"]?.firstOrNull().orEmpty()
            val rememberMe = params["remember_me"]?.firstOrNull() == "on"
            return if (server.isBlank() || username.isBlank() || password.isBlank()) {
                html(Response.Status.BAD_REQUEST, resultPage("配置失败", "请完整填写服务器地址、账号和密码。"))
            } else {
                onConfig(server, username, password, rememberMe)
                html(Response.Status.OK, resultPage("已提交", "电视端正在登录，请查看电视屏幕。"))
            }
        }
        if (session.method == Method.POST && session.uri == "/push-token") {
            session.parseBody(HashMap())
            val onPushToken = onPushToken
                ?: return json(Response.Status.BAD_REQUEST, """{"success":false,"message":"电视端当前不在登录页，无法接收登录推送。"}""")
            val params = session.parameters
            val server = params["server"]?.firstOrNull()?.trim().orEmpty()
            val token = params["token"]?.firstOrNull()?.trim().orEmpty()
            val remotePin = params["pin"]?.firstOrNull()?.trim().orEmpty()
            return when {
                remotePin.isBlank() || remotePin != pin ->
                    json(Response.Status.BAD_REQUEST, """{"success":false,"message":"配对码错误，请核对电视屏幕显示的配对码。"}""")
                server.isBlank() || token.isBlank() ->
                    json(Response.Status.BAD_REQUEST, """{"success":false,"message":"服务器地址或登录凭证缺失。"}""")
                else -> {
                    onPushToken(server, token)
                    json(Response.Status.OK, """{"success":true,"message":"已接收，电视端正在登录。"}""")
                }
            }
        }
        if (session.method == Method.POST && session.uri == "/search") {
            session.parseBody(HashMap())
            val onSearch = onSearch
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在搜索页，无法搜索。")
            val keyword = session.parameters["keyword"]?.firstOrNull()?.trim().orEmpty()
            return if (keyword.isBlank()) {
                text(Response.Status.BAD_REQUEST, "请输入搜索关键字。")
            } else {
                onSearch(keyword)
                text(Response.Status.OK, "已发送，电视端正在搜索。")
            }
        }
        if (session.method == Method.GET && session.uri == "/logs") {
            val files = logsDir?.listFiles { f -> f.isFile }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            val json = files.joinToString(",", "[", "]") {
                """{"name":"${it.name}","size":${it.length()},"mtime":${it.lastModified()}}"""
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        }
        if (session.method == Method.GET && session.uri == "/logs/download") {
            val name = session.parameters["name"]?.firstOrNull().orEmpty()
            if (logsDir == null || name.isBlank() ||
                name.contains('/') || name.contains('\\') || name.contains("..")
            ) {
                return text(Response.Status.BAD_REQUEST, "无效的文件名。")
            }
            val file = File(logsDir, name)
            if (!file.isFile) return text(Response.Status.NOT_FOUND, "文件不存在。")
            val response = newFixedLengthResponse(
                Response.Status.OK, "text/plain; charset=utf-8", FileInputStream(file), file.length()
            )
            response.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            return response
        }
        // tv-helper 插件主动嗅探：局域网扫描时 GET /probe 返回设备信息（与 beacon 同构）
        if (session.method == Method.GET && session.uri == "/probe") {
            return json(Response.Status.OK, beaconJson())
        }
        // ===== 扫码点歌接口 =====
        // 当前 K 歌歌单（禁用缓存：手机端轮询需实时反映已唱移除/置顶变化）
        if (session.method == Method.GET && session.uri.startsWith("/order/queue")) {
            val queue = onOrderQueue?.invoke().orEmpty()
            val json = queue.mapIndexed { index, song ->
                """{"index":$index,"title":${esc(song.title)},"artist":${esc(song.artist)},"id":${song.id}}"""
            }.joinToString(",", "[", "]")
            return json(Response.Status.OK, json).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
        if (session.method == Method.POST && session.uri == "/order/search") {
            session.parseBody(HashMap())
            val onOrderSearch = onOrderSearch
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式，无法搜索。")
            val keyword = session.parameters["keyword"]?.firstOrNull()?.trim().orEmpty()
            if (keyword.isBlank()) return text(Response.Status.BAD_REQUEST, "请输入搜索关键字。")
            val results = runCatching { onOrderSearch(keyword) }
                .getOrDefault(emptyList())
                .filter { it.type != "radio" }
            return json(Response.Status.OK, gson.toJson(results))
        }
        if (session.method == Method.POST && session.uri == "/order/add") {
            try {
                session.parseBody(HashMap())
            } catch (e: Throwable) {
                Log.e("SongloftOrderAdd", "parseBody failed", e)
                return text(Response.Status.INTERNAL_ERROR, "parse error: ${e.message}")
            }
            val onOrderAdd = onOrderAdd
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式，无法点歌。")
            val songJson = session.parameters["song"]?.firstOrNull().orEmpty()
            val song = runCatching { gson.fromJson(songJson, Song::class.java) }.getOrNull()
                ?: return text(Response.Status.BAD_REQUEST, "歌曲数据无效。")
            try {
                onOrderAdd(song)
            } catch (e: Throwable) {
                Log.e("SongloftOrderAdd", "onOrderAdd threw", e)
                return text(Response.Status.INTERNAL_ERROR, "onOrderAdd error: ${e.message}")
            }
            return text(Response.Status.OK, "已点歌：${song.title}")
        }
        if (session.method == Method.POST && session.uri == "/order/top") {
            session.parseBody(HashMap())
            val onOrderTop = onOrderTop
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式。")
            val index = session.parameters["index"]?.firstOrNull()?.toIntOrNull() ?: -1
            if (index < 0) return text(Response.Status.BAD_REQUEST, "无效序号。")
            onOrderTop(index)
            return text(Response.Status.OK, "已置顶。")
        }
        if (session.method == Method.POST && session.uri == "/order/remove") {
            session.parseBody(HashMap())
            val onOrderRemove = onOrderRemove
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式。")
            val index = session.parameters["index"]?.firstOrNull()?.toIntOrNull() ?: -1
            if (index < 0) return text(Response.Status.BAD_REQUEST, "无效序号。")
            onOrderRemove(index)
            return text(Response.Status.OK, "已删除。")
        }
        // GitHub 加速源：扫码配置页保存（空串 = 直连）
        if (session.method == Method.POST && session.uri == "/proxy") {
            session.parseBody(HashMap())
            val raw = session.parameters["proxy"]?.firstOrNull()?.trim().orEmpty()
            if (raw.isNotBlank() && normalizeProxy(raw) == null) {
                return text(Response.Status.BAD_REQUEST, "代理地址需为 http(s):// 开头的有效网址。")
            }
            val onProxyConfig = onProxyConfig
                ?: return text(Response.Status.BAD_REQUEST, "电视端未开启代理配置。")
            val value = normalizeProxy(raw).orEmpty()
            onProxyConfig(value)
            // 返回纯文本：前端 fetch 直接把结果写进页内状态区，不再跳转整页
            return text(
                Response.Status.OK,
                if (value.isBlank()) "已恢复直连，后续更新不使用代理。"
                else "代理源已保存，后续更新优先使用：$value"
            )
        }
        return html(Response.Status.OK, page(initialProxy))
    }

    private fun esc(value: String?): String {
        val s = value.orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$s\""
    }

    /** 代理前缀归一化：http(s) 协议 + 非空主机，统一补尾部 /；空串表示直连。 */
    private fun normalizeProxy(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return if (value.endsWith("/")) value else "$value/"
    }

    private fun html(status: Response.Status, content: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", content)

    private fun json(status: Response.Status, content: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", content)

    private fun text(status: Response.Status, content: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", content)

    /** 向局域网广播设备信息（含配对码），供 tv-helper 插件发现；2 秒一次，直到 stopBeacon() */
    fun startBeacon() {
        if (beaconRunning) return
        beaconRunning = true
        val payloadText = beaconPayload()
        val payload = payloadText.toByteArray(StandardCharsets.UTF_8)
        val jsonText = beaconJson()
        val targets = beaconTargets()
        beaconThread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                while (beaconRunning) {
                    runCatching {
                        targets.forEach { target ->
                            socket.send(DatagramPacket(payload, payload.size, target, BEACON_PORT))
                        }
                        android.util.Log.i(TAG, "beacon 已广播: $jsonText | base64: $payloadText")
                    }.onFailure { e ->
                        android.util.Log.w(TAG, "beacon 广播失败", e)
                    }
                    Thread.sleep(BEACON_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                android.util.Log.w("ConfigWebServer", "beacon 广播失败", e)
            } finally {
                runCatching { socket?.close() }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopBeacon() {
        beaconRunning = false
        beaconThread?.interrupt()
        beaconThread = null
    }

    private fun beaconJson(): String {
        val name = deviceName.ifBlank { "Songloft TV" }.replace("\"", "\\\"")
        val ip = localIpAddress().orEmpty()
        // 只广播设备/应用信息，配对码不上广播（用户登录时手动输入 TV 屏幕显示的码）
        return """{"app":"songloft-tv","name":"$name","ip":"$ip","port":${getListeningPort()},"version":"${BuildConfig.VERSION_NAME}"}"""
    }

    private fun beaconPayload(): String {
        // 显式 Base64 编码：tv-helper 插件需先解宿主 UDP API 的 base64 层再解协议层，
        // 保证中文设备名经 UTF-8 传输后不乱码
        return Base64.encodeToString(beaconJson().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    /** 广播目标：255.255.255.255 + 各网卡定向广播地址。热点与蜂窝同时在线时，
     *  255.255.255.255 可能只从默认路由（蜂窝）口发出导致客户端收不到，需按接口定向广播 */
    private fun beaconTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName(BEACON_ADDRESS)) }
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .distinct()
            .forEach { targets.add(it) }
        return targets
    }

    companion object {
        private const val TAG = "ConfigWebServer"
        private const val AI_PROMPT =
            "请帮我找几个目前可用的 GitHub 文件加速/反代服务（GitHub proxy mirror），" +
            "要求：1) 免费、无需注册；2) 支持代理 github.com 和 raw.githubusercontent.com 的文件下载；" +
            "3) 用法是在原始 URL 前拼接代理前缀，如 https://代理地址/https://github.com/...。" +
            "请给出 3-5 个可用的代理地址（以 https:// 开头、/ 结尾），并注明各自的特点。"
        private const val BEACON_ADDRESS = "255.255.255.255"
        private const val BEACON_PORT = 18910
        private const val BEACON_INTERVAL_MS = 2_000L

        fun localIpAddress(): String? =
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress

        private fun resultPage(title: String, message: String) = """
            <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="theme-color" content="#f2f2f7">
            <title>$title</title>
            <style>
            :root{--bg:#f2f2f7;--card:#fff;--label:#000;--secondary:rgba(60,60,67,.6);--tint:#007aff}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--label);
            font:17px/1.35 -apple-system,BlinkMacSystemFont,"PingFang SC","HarmonyOS Sans SC",system-ui,sans-serif;
            display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:90vh;padding:16px}
            .card{width:100%;max-width:420px;background:var(--card);border-radius:16px;padding:28px 20px;text-align:center;
            box-shadow:0 1px 3px rgba(0,0,0,.06)}
            h2{margin:0 0 10px;font-size:22px;font-weight:700;color:var(--tint)}
            p{margin:0;color:var(--secondary);font-size:15px}
            a{display:inline-block;margin-top:22px;color:var(--tint);text-decoration:none;font-weight:600}
            </style></head><body><div class="card"><h2>$title</h2><p>$message</p><a href="/">返回配置页</a></div></body></html>

        """.trimIndent()

        /** 把 Kotlin 字符串转成可安全嵌入 <script> 的 JS 字符串字面量。 */
        private fun jsString(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            return "\"$escaped\""
        }

        private fun page(initialProxy: String): String {
            val promptJs = jsString(AI_PROMPT)
            val proxyJs = jsString(initialProxy)
            return """
            <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <meta name="theme-color" content="#f2f2f7" media="(prefers-color-scheme: light)">
            <meta name="theme-color" content="#000000" media="(prefers-color-scheme: dark)">
            <title>Songloft TV · 配置</title>
            <style>
            :root{--bg:#f2f2f7;--card:#fff;--label:#000;--secondary:rgba(60,60,67,.6);
            --tertiary:rgba(60,60,67,.3);--sep:rgba(60,60,67,.18);--tint:#007aff;
            --fill:rgba(118,118,128,.12);--danger:#ff3b30;--bar:rgba(249,249,249,.82);
            --bar-line:rgba(0,0,0,.14);--tabbar:calc(56px + env(safe-area-inset-bottom))}
            @media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--label:#fff;
            --secondary:rgba(235,235,245,.6);--tertiary:rgba(235,235,245,.3);--sep:rgba(84,84,88,.6);
            --tint:#0a84ff;--fill:rgba(118,118,128,.24);--danger:#ff453a;
            --bar:rgba(22,22,22,.82);--bar-line:rgba(255,255,255,.12)}}
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            html{-webkit-text-size-adjust:100%}
            body{margin:0;background:var(--bg);color:var(--label);
            font:17px/1.35 -apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","HarmonyOS Sans SC",system-ui,sans-serif}
            button,input,pre{font:inherit;color:inherit}
            [hidden]{display:none!important}
            main{max-width:720px;margin:0 auto;padding:env(safe-area-inset-top) 16px calc(var(--tabbar) + 32px)}
            header{padding:28px 4px 4px}
            header h1{font-size:34px;font-weight:700;letter-spacing:-.02em;margin:0}
            header p{margin:2px 0 0;color:var(--secondary);font-size:15px}
            h2{font-size:13px;font-weight:400;color:var(--secondary);margin:24px 16px 7px}
            .foot{color:var(--secondary);font-size:13px;margin:7px 16px 0}
            .foot.err{color:var(--danger)}
            .list{background:var(--card);border-radius:12px;overflow:hidden}
            .item{position:relative;display:flex;align-items:center;gap:12px;width:100%;min-height:48px;
            padding:11px 16px;border:0;background:none;text-align:left;text-decoration:none}
            .item+.item::before{content:"";position:absolute;top:0;left:16px;right:0;height:.5px;background:var(--sep)}
            button.item:active,a.item:active,label.item:active{background:var(--fill)}
            .item .txt{flex:1;min-width:0}
            .item .sub{display:block;font-size:13px;color:var(--secondary);margin-top:2px;line-height:1.35}
            .item .name{flex:none;width:80px;color:var(--label)}
            .field input{flex:1;min-width:0;font-size:16px;background:none;border:0;outline:none;padding:0}
            .field input::placeholder{color:var(--tertiary)}
            .link{appearance:none;border:0;background:none;color:var(--tint);padding:0;cursor:pointer;font-size:15px;font-weight:600;white-space:nowrap}
            .link.danger{color:var(--danger)}
            .switch{appearance:none;-webkit-appearance:none;position:relative;flex-shrink:0;width:51px;height:31px;margin:0;
            border-radius:16px;background:var(--fill);cursor:pointer}
            .switch::after{content:"";position:absolute;top:2px;left:2px;width:27px;height:27px;border-radius:50%;background:#fff;
            box-shadow:0 3px 8px rgba(0,0,0,.15),0 1px 1px rgba(0,0,0,.16);transition:transform .25s cubic-bezier(.23,1,.32,1)}
            .switch:checked{background:#34c759}
            .switch:checked::after{transform:translateX(20px)}
            .btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;padding:14px;border:0;
            border-radius:12px;background:var(--tint);color:#fff;font-weight:600;text-decoration:none;cursor:pointer;margin-top:12px}
            .btn:active{opacity:.8}
            nav{position:fixed;left:0;right:0;bottom:0;z-index:10;height:var(--tabbar);padding-bottom:env(safe-area-inset-bottom);
            display:flex;justify-content:center;background:var(--bar);box-shadow:0 -.5px 0 var(--bar-line);
            -webkit-backdrop-filter:saturate(180%) blur(20px);backdrop-filter:saturate(180%) blur(20px)}
            nav button{flex:1;max-width:160px;border:0;background:none;padding:8px 0 0;color:var(--secondary);
            display:flex;flex-direction:column;align-items:center;gap:2px;font-size:10.5px;font-weight:500;cursor:pointer}
            nav button[aria-selected="true"]{color:var(--tint)}
            @media (hover:hover) and (pointer:fine){button.item:hover,a.item:hover,label.item:hover{background:var(--fill)}}
            @media (min-width:900px){main{max-width:1000px;padding-left:40px;padding-right:40px}}
            </style></head><body>
            <main>
            <header><h1>Songloft TV</h1><p>局域网配置 · 手机扫码即可管理</p></header>
            <section id="panelConfig">
              <h2>登录配置</h2>
              <div class="list">
                <form id="configForm" method="post" action="/submit">
                  <label class="item field"><span class="name">服务器</span><input name="server" type="url" placeholder="http://192.168.1.100:58091" required></label>
                  <label class="item field"><span class="name">账号</span><input name="username" type="text" placeholder="admin" required></label>
                  <label class="item field"><span class="name">密码</span><input id="pw" name="password" type="password" placeholder="输入密码" required><button type="button" class="link" id="pwToggle" onclick="togglePw()">显示</button></label>
                  <label class="item"><span class="txt">记住登录<span class="sub">下次启动自动回填账号和密码</span></span><input class="switch" type="checkbox" name="remember_me" id="rememberMe"></label>
                </form>
              </div>
              <button class="btn" type="submit" form="configForm">提交到电视</button>
            </section>
            <section id="panelSearch" hidden>
              <h2>搜索音乐</h2>
              <div class="list">
                <form class="item field" id="searchForm"><span class="name">关键字</span><input id="keyword" name="keyword" type="text" placeholder="歌曲、歌手或专辑" required><button class="link" type="submit">搜索</button></form>
              </div>
              <p class="foot" id="searchStatus"></p>
            </section>
            <section id="panelOrder" hidden>
              <h2>扫码点歌</h2>
              <p class="foot" style="margin:0 4px">先在电视上进入 K 歌模式，再搜索并点歌</p>
              <div class="list" style="margin-top:12px">
                <form class="item field" id="orderSearchForm"><span class="name">歌曲</span><input id="orderKeyword" name="keyword" type="text" placeholder="歌曲或歌手" required><button class="link" type="submit">搜索</button></form>
              </div>
              <p class="foot" id="orderSearchStatus"></p>
              <div id="orderResults"></div>
              <h2>当前播放队列</h2>
              <div class="list" id="orderQueue"><div class="item"><span class="txt">加载中…</span></div></div>
            </section>
            <section id="panelLogs" hidden>
              <h2>日志文件</h2>
              <div class="list" id="logList"><div class="item"><span class="txt">加载中…</span></div></div>
              <p class="foot">可在电视端 设置 → 日志 → 导出日志</p>
            </section>
            <section id="panelProxy" hidden>
              <h2>GitHub 加速源</h2>
              <div class="list">
                <label class="item field"><span class="name">代理地址</span><input id="proxyInput" type="url" placeholder="https://your-proxy.com/" spellcheck="false"></label>
                <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:0 16px 12px;
                font-size:13px;color:var(--secondary)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></svg>
                  <button class="link" type="button" style="font-size:13px;font-weight:400" onclick="copyPrompt()">复制 Prompt 让 AI 帮你找</button>
                  <span style="color:var(--tertiary)">·</span>
                  <a class="link" style="font-size:13px;font-weight:400" href="https://chat.deepseek.com/" target="_blank" rel="noopener" onclick="copyPrompt()">DeepSeek</a>
                  <span style="color:var(--tertiary)">·</span>
                  <a class="link" style="font-size:13px;font-weight:400" href="https://chat.qwen.ai/" target="_blank" rel="noopener" onclick="copyPrompt()">千问</a>
                </div>
              </div>
              <p class="foot" id="copyStatus"></p>
              <p class="foot" id="proxyStatus"></p>
              <button class="btn" type="button" onclick="saveProxy()">保存代理源</button>
            </section>
            <p class="foot">遇到问题？<a class="link" href="https://github.com/boluofan/songloft-tv/issues" target="_blank" rel="noopener">问题反馈</a></p>
            </main>
            <nav role="tablist">
              <button role="tab" id="tabConfig" aria-selected="true" onclick="showTab('config')"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/></svg><span>配置</span></button>
              <button role="tab" id="tabSearch" aria-selected="false" onclick="showTab('search')"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/></svg><span>搜索</span></button>
              <button role="tab" id="tabOrder" aria-selected="false" onclick="showTab('order')"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/></svg><span>点歌</span></button>
              <button role="tab" id="tabLogs" aria-selected="false" onclick="showTab('logs')"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h6"/></svg><span>日志</span></button>
              <button role="tab" id="tabProxy" aria-selected="false" onclick="showTab('proxy')"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L4 14h7l-1 8 9-12h-7l1-8z"/></svg><span>加速源</span></button>
            </nav>
            <script>
            var aiPrompt = $promptJs;
            function showTab(name){
              ['Config','Search','Order','Logs','Proxy'].forEach(function(t){
                var k=t.toLowerCase(), on=name===k;
                document.getElementById('tab'+t).setAttribute('aria-selected',on?'true':'false');
                document.getElementById('panel'+t).hidden=!on;
              });
              if(name==='logs')loadLogs();
              if(name==='order')loadOrder();
              if(name==='proxy')initProxy();
            }
            function initProxy(){
              document.getElementById('proxyInput').value=$proxyJs;
            }
            function setStatus(el,msg,ok){
              el.textContent=msg;
              el.className=ok===undefined?'foot':(ok?'foot':'foot err');
            }
            function saveProxy(){
              var value=document.getElementById('proxyInput').value.trim();
              var status=document.getElementById('proxyStatus');
              setStatus(status,'保存中...');
              fetch('/proxy',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'proxy='+encodeURIComponent(value)})
                .then(function(r){return r.text().then(function(t){setStatus(status,t,r.ok);});})
                .catch(function(){setStatus(status,'保存失败，请确认电视端仍在本页面',false);});
            }
            function copyPrompt(){
              var status=document.getElementById('copyStatus');
              var done=function(){setStatus(status,'已复制，粘贴到 AI 对话框即可');setTimeout(function(){status.textContent='';},3000);};
              var fallback=function(){
                var ta=document.createElement('textarea');
                ta.value=aiPrompt;ta.style.position='fixed';ta.style.opacity='0';
                document.body.appendChild(ta);ta.focus();ta.select();
                try{document.execCommand('copy');done();}
                catch(e){setStatus(status,'复制失败，请稍后重试',false);}
                document.body.removeChild(ta);
              };
              if(navigator.clipboard&&navigator.clipboard.writeText){
                navigator.clipboard.writeText(aiPrompt).then(done,fallback);
              }else fallback();
            }
            function togglePw(){
              var input=document.getElementById('pw'),btn=document.getElementById('pwToggle');
              var show=input.type==='password';
              input.type=show?'text':'password';
              btn.textContent=show?'隐藏':'显示';
            }
            function fmtSize(n){
              if(n>=1048576)return (n/1048576).toFixed(1)+' MB';
              if(n>=1024)return (n/1024).toFixed(1)+' KB';
              return n+' B';
            }
            function fmtTime(t){
              var d=new Date(t),p=function(x){return x<10?'0'+x:x};
              return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '+
                p(d.getHours())+':'+p(d.getMinutes());
            }
            function loadLogs(){
              var el=document.getElementById('logList');
              el.innerHTML='<div class="item"><span class="txt">加载中…</span></div>';
              fetch('/logs').then(function(r){return r.json();}).then(function(list){
                if(!list.length){
                  el.innerHTML='<div class="item"><span class="txt">暂无日志，请先在电视端 设置 → 日志 → 导出日志</span></div>';
                  return;
                }
                el.innerHTML=list.map(function(f){
                  return '<a class="item" href="/logs/download?name='+encodeURIComponent(f.name)+'" download>'+
                    '<span class="txt">'+escHtml(f.name)+'<span class="sub">'+fmtSize(f.size)+' · '+fmtTime(f.mtime)+'</span></span></a>';
                }).join('');
              }).catch(function(){
                el.innerHTML='<div class="item"><span class="txt">加载失败，请刷新重试</span></div>';
              });
            }
            showTab(location.hash==='#search'?'search':location.hash==='#order'?'order':
              location.hash==='#logs'?'logs':location.hash==='#proxy'?'proxy':'config');
            document.getElementById('orderSearchForm').addEventListener('submit',function(e){
              e.preventDefault();
              var status=document.getElementById('orderSearchStatus');
              var keyword=document.getElementById('orderKeyword').value.trim();
              if(!keyword){setStatus(status,'请输入搜索关键字',false);return;}
              setStatus(status,'搜索中...');
              fetch('/order/search',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'keyword='+encodeURIComponent(keyword)})
                .then(function(r){return r.json().then(function(list){
                  if(!Array.isArray(list)){setStatus(status,'搜索失败',false);return;}
                  setStatus(status,'找到 '+list.length+' 首',true);
                  renderOrderResults(list);
                });})
                .catch(function(){setStatus(status,'搜索失败，请确认电视端在 K 歌模式',false);});
            });
            function renderOrderResults(list){
              var el=document.getElementById('orderResults');
              if(!list.length){el.innerHTML='<div class="list"><div class="item"><span class="txt">无结果</span></div></div>';return;}
              el.innerHTML='<div class="list">'+list.map(function(s){
                var json=JSON.stringify(s);
                return '<div class="item"><span class="txt">'+escHtml(s.title)+' - '+escHtml(s.artist||'')+
                  '</span><button class="link" data-order-add="'+escHtml(json)+'">点歌</button></div>';
              }).join('')+'</div>';
              el.querySelectorAll('button[data-order-add]').forEach(function(btn){
                btn.addEventListener('click',function(){orderAdd(btn.getAttribute('data-order-add'));});
              });
            }
            function escHtml(t){return (t||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
            window.orderAdd=function(songJson){
              fetch('/order/add',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'song='+encodeURIComponent(songJson)})
                .then(function(){loadOrder();}).catch(function(){loadOrder();});
            };
            function loadOrder(){
              fetch('/order/queue?t='+Date.now(),{cache:'no-store'}).then(function(r){return r.json();}).then(function(list){
                var el=document.getElementById('orderQueue');
                if(!list.length){el.innerHTML='<div class="item"><span class="txt">队列为空</span></div>';return;}
                el.innerHTML=list.map(function(s){
                  return '<div class="item" data-index="'+s.index+'"><span class="txt">'+escHtml(s.title)+' - '+escHtml(s.artist||'')+
                    '</span><span><button class="link" data-act="top">置顶</button>'+
                    '<button class="link danger" data-act="remove" style="margin-left:14px">删除</button></span></div>';
                }).join('');
                el.querySelectorAll('.item').forEach(function(row){
                  var idx=row.getAttribute('data-index');
                  row.querySelector('button[data-act="top"]').addEventListener('click',function(){orderTop(idx);});
                  row.querySelector('button[data-act="remove"]').addEventListener('click',function(){orderRemove(idx);});
                });
              }).catch(function(){
                document.getElementById('orderQueue').innerHTML='<div class="item"><span class="txt">加载失败</span></div>';
              });
            }
            window.orderTop=function(index){
              fetch('/order/top',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'index='+index}).then(function(){loadOrder();});
            };
            window.orderRemove=function(index){
              if(!confirm('确定要从 K 歌歌单中删除这首歌吗？'))return;
              fetch('/order/remove',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'index='+index}).then(function(){loadOrder();});
            };
            setInterval(function(){
              if(!document.getElementById('panelOrder').hidden)loadOrder();
            },5000);
            document.getElementById('searchForm').addEventListener('submit',function(e){
              e.preventDefault();
              var status=document.getElementById('searchStatus');
              var keyword=document.getElementById('keyword').value.trim();
              if(!keyword){setStatus(status,'请输入搜索关键字',false);return;}
              setStatus(status,'发送中...');
              fetch('/search',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'keyword='+encodeURIComponent(keyword)})
                .then(function(r){return r.text().then(function(t){setStatus(status,t,r.ok);});})
                .catch(function(){setStatus(status,'发送失败，请确认电视端仍在搜索页',false);});
            });
            </script>
            </body></html>

        """.trimIndent()
        }
    }
}
