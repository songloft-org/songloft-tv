package com.songloft.tv.data.config

import android.util.Base64
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
    private val onConfig: ((server: String, username: String, password: String) -> Unit)? = null,
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
    private val onOrderQueue: (() -> List<Song>)? = null
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
            return if (server.isBlank() || username.isBlank() || password.isBlank()) {
                html(Response.Status.BAD_REQUEST, resultPage("配置失败", "请完整填写服务器地址、账号和密码。"))
            } else {
                onConfig(server, username, password)
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
        if (session.method == Method.GET && session.uri == "/order/queue") {
            val queue = onOrderQueue?.invoke().orEmpty()
            val json = queue.mapIndexed { index, song ->
                """{"index":$index,"title":${esc(song.title)},"artist":${esc(song.artist)},"id":${song.id}}"""
            }.joinToString(",", "[", "]")
            return json(Response.Status.OK, json)
        }
        if (session.method == Method.POST && session.uri == "/order/search") {
            session.parseBody(HashMap())
            val onOrderSearch = onOrderSearch
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式，无法搜索。")
            val keyword = session.parameters["keyword"]?.firstOrNull()?.trim().orEmpty()
            if (keyword.isBlank()) return text(Response.Status.BAD_REQUEST, "请输入搜索关键字。")
            val results = runCatching { onOrderSearch(keyword) }.getOrDefault(emptyList())
            return json(Response.Status.OK, gson.toJson(results))
        }
        if (session.method == Method.POST && session.uri == "/order/add") {
            session.parseBody(HashMap())
            val onOrderAdd = onOrderAdd
                ?: return text(Response.Status.BAD_REQUEST, "电视端当前不在 K 歌模式，无法点歌。")
            val songJson = session.parameters["song"]?.firstOrNull().orEmpty()
            val song = runCatching { gson.fromJson(songJson, Song::class.java) }.getOrNull()
                ?: return text(Response.Status.BAD_REQUEST, "歌曲数据无效。")
            onOrderAdd(song)
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
        return html(Response.Status.OK, PAGE)
    }

    private fun esc(value: String?): String {
        val s = value.orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$s\""
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
            <title>$title</title>
            <style>body{font-family:sans-serif;background:#111827;color:#eee;
            display:flex;flex-direction:column;align-items:center;justify-content:center;
            min-height:90vh;margin:0;padding:16px}h2{color:#8fb0e8}</style></head>
            <body><h2>$title</h2><p>$message</p></body></html>
        """.trimIndent()

        private val PAGE = """
            <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Songloft TV</title>
            <style>
            body{font-family:sans-serif;background:#111827;color:#eee;margin:0;padding:24px}
            h2{color:#8fb0e8;text-align:center}
            .tabs{display:flex;margin-bottom:8px;border-bottom:1px solid #374151}
            .tab{flex:1;padding:12px;font-size:16px;text-align:center;color:#bbb;
            background:none;border:none;border-bottom:2px solid transparent}
            .tab.active{color:#8fb0e8;border-bottom-color:#415F91;font-weight:bold}
            .panel{display:none}
            .panel.active{display:block}
            label{display:block;margin:16px 0 6px;font-size:14px;color:#bbb}
            input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;
            border:1px solid #374151;border-radius:8px;background:#1f2937;color:#eee}
            .pw-wrap{position:relative}
            .pw-wrap input{padding-right:44px}
            .pw-toggle{position:absolute;right:6px;top:50%;transform:translateY(-50%);
            background:none;border:none;cursor:pointer;padding:8px;display:flex;
            align-items:center;justify-content:center;color:#9ca3af}
            .pw-toggle svg{width:20px;height:20px;display:block}
            button.submit{width:100%;margin-top:24px;padding:14px;font-size:16px;font-weight:bold;
            border:none;border-radius:8px;background:#415F91;color:#fff}
            #searchStatus{margin-top:16px;font-size:14px;text-align:center;color:#8fb0e8;min-height:20px}
            #logList{margin-top:16px}
            #logList .hint{font-size:14px;text-align:center;color:#6b7280}
            #logList a{display:block;padding:12px;margin-bottom:8px;font-size:14px;
            border:1px solid #374151;border-radius:8px;background:#1f2937;color:#8fb0e8;
            text-decoration:none;word-break:break-all}
             #logList a span{color:#6b7280;font-size:12px;margin-left:8px}
             .feedback{display:block;margin-top:32px;text-align:center;font-size:13px;color:#6b7280}
             .feedback a{color:#8fb0e8;text-decoration:none}
             .order-sep{margin:20px 0 8px;padding-top:12px;border-top:1px solid #374151;
             font-size:14px;color:#8fb0e8;text-align:center}
             #orderResults a,#orderQueue a{display:flex;align-items:center;justify-content:space-between;
             padding:12px;margin-bottom:8px;font-size:14px;border:1px solid #374151;border-radius:8px;
             background:#1f2937;color:#eee;text-decoration:none}
             #orderResults a span,#orderQueue a span{color:#6b7280;font-size:12px;margin-left:8px;
             overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:55%}
             .act{padding:6px 10px;margin-left:8px;font-size:13px;border:none;border-radius:6px;
             background:#415F91;color:#fff;cursor:pointer}
             .act.del{background:#7f1d1d}
             </style></head><body>
            <h2>Songloft TV</h2>
            <div class="tabs">
              <button class="tab" id="tabConfig" onclick="showTab('config')">登录配置</button>
              <button class="tab" id="tabSearch" onclick="showTab('search')">搜索</button>
              <button class="tab" id="tabOrder" onclick="showTab('order')">点歌</button>
              <button class="tab" id="tabLogs" onclick="showTab('logs')">日志</button>
            </div>
            <div class="panel" id="panelConfig">
              <form method="post" action="/submit">
                <label>服务器地址</label>
                <input name="server" type="url" placeholder="http://192.168.1.100:58091" required>
                <label>账号</label>
                <input name="username" type="text" placeholder="admin" required>
                <label>密码</label>
                <div class="pw-wrap">
                  <input id="pw" name="password" type="password" placeholder="输入密码" required>
                  <button type="button" class="pw-toggle" id="pwToggle" onclick="togglePw()" aria-label="显示密码">
                    <svg id="pwEye" viewBox="0 0 24 24" fill="currentColor"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>
                    <svg id="pwEyeOff" viewBox="0 0 24 24" fill="currentColor" style="display:none"><path d="M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z"/></svg>
                  </button>
                </div>
                <button class="submit" type="submit">提交到电视</button>
              </form>
            </div>
            <div class="panel" id="panelSearch">
              <form id="searchForm">
                <label>搜索关键字</label>
                <input name="keyword" id="keyword" type="text" placeholder="输入歌曲、歌手或专辑" required>
                <button class="submit" type="submit">搜索</button>
              </form>
              <div id="searchStatus"></div>
            </div>
            <div class="panel" id="panelLogs">
              <div id="logList"><div class="hint">加载中…</div></div>
            </div>
            <div class="panel" id="panelOrder">
              <form id="orderSearchForm">
                <label>搜索歌曲</label>
                <input name="keyword" id="orderKeyword" type="text" placeholder="输入歌曲、歌手或专辑" required>
                <button class="submit" type="submit">搜索</button>
              </form>
              <div id="orderSearchStatus" class="hint"></div>
              <div id="orderResults"></div>
              <div class="order-sep">当前播放队列</div>
              <div id="orderQueue"><div class="hint">加载中…</div></div>
            </div>
            <div class="feedback">遇到问题？
              <a href="https://github.com/boluofan/songloft-tv/issues" target="_blank" rel="noopener">问题反馈</a>
            </div>
            <script>
            function showTab(name){
              ['Config','Search','Order','Logs'].forEach(function(t){
                var k=t.toLowerCase();
                document.getElementById('tab'+t).classList.toggle('active',name===k);
                document.getElementById('panel'+t).classList.toggle('active',name===k);
              });
              if(name==='logs')loadLogs();
              if(name==='order')loadOrder();
            }
            function togglePw(){
              var input=document.getElementById('pw');
              var show=input.type==='password';
              input.type=show?'text':'password';
              document.getElementById('pwEye').style.display=show?'none':'';
              document.getElementById('pwEyeOff').style.display=show?'':'none';
              document.getElementById('pwToggle').setAttribute('aria-label',show?'隐藏密码':'显示密码');
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
              el.innerHTML='<div class="hint">加载中…</div>';
              fetch('/logs').then(function(r){return r.json();}).then(function(list){
                if(!list.length){
                  el.innerHTML='<div class="hint">暂无日志。请先在电视端 设置 → 日志 → 导出日志</div>';
                  return;
                }
                el.innerHTML=list.map(function(f){
                  return '<a href="/logs/download?name='+encodeURIComponent(f.name)+'" download>'+
                    f.name+'<span>'+fmtSize(f.size)+' · '+fmtTime(f.mtime)+'</span></a>';
                }).join('');
              }).catch(function(){
                el.innerHTML='<div class="hint">加载失败，请刷新重试</div>';
              });
            }
            showTab(location.hash==='#search'?'search':location.hash==='#order'?'order':location.hash==='#logs'?'logs':'config');
            document.getElementById('orderSearchForm').addEventListener('submit',function(e){
              e.preventDefault();
              var status=document.getElementById('orderSearchStatus');
              var keyword=document.getElementById('orderKeyword').value.trim();
              if(!keyword){status.textContent='请输入搜索关键字';return;}
              status.textContent='搜索中...';
              fetch('/order/search',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'keyword='+encodeURIComponent(keyword)})
                .then(function(r){return r.json().then(function(list){
                  if(!Array.isArray(list)){status.textContent='搜索失败';return;}
                  status.textContent='找到 '+list.length+' 首';
                  renderOrderResults(list);
                });})
                .catch(function(){status.textContent='搜索失败，请确认电视端在 K 歌模式';
                  status.style.color='#f87171';});
            });
            function renderOrderResults(list){
              var el=document.getElementById('orderResults');
              if(!list.length){el.innerHTML='<div class="hint">无结果</div>';return;}
              el.innerHTML=list.map(function(s){
                var json=JSON.stringify(s).replace(/"/g,'&quot;');
                return '<a href="javascript:void(0)"><span>'+escHtml(s.title)+' - '+escHtml(s.artist||'')+
                  '</span><button class="act" onclick="orderAdd(\''+encodeURIComponent(json)+'\')">点歌</button></a>';
              }).join('');
            }
            function escHtml(t){return (t||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
            window.orderAdd=function(enc){
              var song=decodeURIComponent(enc);
              fetch('/order/add',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'song='+encodeURIComponent(song)})
                .then(function(r){return r.text().then(function(t){
                  loadOrder();
                });});
            };
            function loadOrder(){
              fetch('/order/queue').then(function(r){return r.json();}).then(function(list){
                var el=document.getElementById('orderQueue');
                if(!list.length){el.innerHTML='<div class="hint">队列为空</div>';return;}
                el.innerHTML=list.map(function(s){
                  return '<a href="javascript:void(0)"><span>'+escHtml(s.title)+' - '+escHtml(s.artist||'')+
                    '</span><span>'+
                    '<button class="act" onclick="orderTop('+s.index+')">置顶</button>'+
                    '<button class="act del" onclick="orderRemove('+s.index+')">删除</button>'+
                    '</span></a>';
                }).join('');
              }).catch(function(){
                document.getElementById('orderQueue').innerHTML='<div class="hint">加载失败</div>';
              });
            }
            window.orderTop=function(index){
              fetch('/order/top',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'index='+index}).then(function(){loadOrder();});
            };
            window.orderRemove=function(index){
              fetch('/order/remove',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'index='+index}).then(function(){loadOrder();});
            };
            setInterval(function(){
              if(document.getElementById('panelOrder').classList.contains('active'))loadOrder();
            },5000);
            document.getElementById('searchForm').addEventListener('submit',function(e){
              e.preventDefault();
              var status=document.getElementById('searchStatus');
              var keyword=document.getElementById('keyword').value.trim();
              if(!keyword){status.textContent='请输入搜索关键字';return;}
              status.textContent='发送中...';
              fetch('/search',{method:'POST',
                headers:{'Content-Type':'application/x-www-form-urlencoded'},
                body:'keyword='+encodeURIComponent(keyword)})
                .then(function(r){return r.text().then(function(t){
                  status.textContent=t;
                  status.style.color=r.ok?'#8fb0e8':'#f87171';
                });})
                .catch(function(){status.textContent='发送失败，请确认电视端仍在搜索页';
                  status.style.color='#f87171';});
            });
            </script>
            </body></html>
        """.trimIndent()
    }
}
