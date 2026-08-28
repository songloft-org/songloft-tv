# Songloft TV — 实现文档

> 基于当前代码整理（v1.1.5+），描述实际实现，区别于 [design.md](design.md) 的设计稿。

---

## 1. 总体架构

单模块 `:app`，包根 `com.songloft.tv`，分层：

```
UI (Compose Screen + ViewModel, Hilt + StateFlow)
   │
domain/PlayerController ── MediaController ──> MusicService (ExoPlayer + MediaSession)
   │                                             └─ data/cache (SimpleCache + RoutingDataSource)
data/repository ──> data/api (Retrofit + OkHttp) ──> Songloft 后端 /api/v1
   │
data/storage/PreferencesDataStore (DataStore)
```

关键决策：

- **ExoPlayer 实例只存在于 `MusicService`**（MediaSessionService）。UI 层全部通过单例 `PlayerController` 内的 Media3 `MediaController` 与之交互，因此退出播放器页面或主界面后播放可继续。
- **主界面不使用 Navigation Compose**：`MainActivity.TvApp` 用 `mutableStateOf<Screen>` 手写状态导航；全屏播放器是独立 `PlayerActivity`。
- 依赖注入用 Hilt（KSP），仓库层方法统一 `withContext(Dispatchers.IO) { runCatching {...} }` 返回 `Result`。

## 2. 数据层

### 2.1 API 接口（`data/api/SongloftApi.kt`）

baseUrl 为 `{serverUrl}/api/v1/`，全部 suspend 方法：

| 方法 | HTTP | 路径 | 说明 |
|---|---|---|---|
| `login` | POST | `auth/login` | 返回 `LoginResponse`（access_token/refresh_token） |
| `getSongs` | GET | `songs` | limit/offset/keyword/artist/album/year |
| `getSongPlayUrl` | GET | `songs/{id}/play` | 可选 quality |
| `getSongLyric` | GET | `songs/{id}/lyric` | lyric/tlyric/rlyric/lxlyric |
| `getFacets` | GET | `songs/facets` | field=artist/album/year |
| `getSongNames` | GET | `songs/names` | field=title/artist，去重全量名字，供拼音检索 |
| `reportPlayed` | POST | `songs/{id}/played` | type=play/finish/skip；source 固定 `tv`；play 时带 context_type/context_key（歌单或分面，写入服务端播放历史） |
| `getPlaylists` | GET | `playlists` | 可选 type |
| `getPlaylistDetail` | GET | `playlists/{id}` | |
| `getPlaylistSongs` | GET | `playlists/{id}/songs` | limit/offset |
| `addSongsToPlaylist` | POST | `playlists/{id}/songs` | Body `AddSongsRequest(song_ids)` |
| `removeSongFromPlaylist` | DELETE | `playlists/{id}/songs/{songId}` | |
| `getConfig`/`setConfig` | GET/PUT | `config/{key}` | |
| `health` | GET | `health` | 连通性探测 |
| `getStatsSummary` | GET | `jsplugin/stats/api/stats/summary` | 播放统计插件：概览汇总，可选 `from`/`to`（毫秒时间戳，`[from, to)` 区间） |
| `getStatsTrends` | GET | `jsplugin/stats/api/stats/trends` | 播放统计插件：最近 N 天播放趋势，`days` 默认 7（上限 90） |
| `getStatsHourly` | GET | `jsplugin/stats/api/stats/hourly` | 播放统计插件：时段分布（凌晨/上午/下午/晚上） |
| `getStatsHistory` | GET | `jsplugin/stats/api/history/raw` | 播放统计插件：原始播放记录分页，`limit` 默认 20（上限 100）/`offset`，响应含 `hasMore` |

注意：各方法直接返回具体响应类（`SongListResponse` 等），无统一包装类。统计插件接口响应为 `{ success, data, error? }` 包装（`StatsSummaryResponse` 等），Repository 层统一校验 `success` 后解包，失败返回 `Result.failure`。

### 2.2 认证机制

- **`ApiClient`**（object 单例）：`initialize(url)` 幂等；OkHttp 挂 `AuthInterceptor` + BODY 日志 + `TokenAuthenticator`，超时 30s；暴露 `onTokensRefreshed` 回调供持久化。
- **`AuthInterceptor`**：内存持有 access/refresh token（运行时唯一来源），为请求添加 `Authorization: Bearer <token>`。
- **`TokenAuthenticator`**（401 触发）：
  1. 无 refresh token 或重试 ≥2 次即放弃；
  2. `synchronized` 内并发去重——若内存 token 已与失败请求头不同，说明他人已刷新，直接重试；
  3. 否则用**独立无拦截器 OkHttpClient** POST `{baseUrl}/api/v1/auth/refresh`（body `{"refresh_token": ...}`），成功则更新内存 token → 触发回调写回 DataStore → 重试原请求。
- **Token 链路**：登录成功写内存 + DataStore；启动时 `AuthRepository.tryAutoLogin()` 从 DataStore 回填并调 `health()` 验证；登出两处同时清空。

### 2.3 数据模型（`data/model/`）

- `Song`：id/type("local"/"radio")/title/artist/album/duration(秒)/url/coverUrl/isVideo/fileSize/`tracks: List<Track>?`；`hasMultiTrack` = tracks>1（双音轨）。
- `Track`：id/name/url/quality。多文件音轨每轨独立 URL；内嵌音轨 id 为 `embedded:<groupIndex>`。
- `Playlist`：id/name/description/coverUrl/songCount/type("normal"/"radio")/labels；`isBuiltIn`（labels 含 `built_in`，收藏歌单）、`isHidden`。
- `LyricLine`：time/text/`words: List<LyricWord>?`（逐字）/translation/romaji。
- `FacetItem`：value/count/coverUrl。

### 2.4 Repository（均 `@Singleton`）

- **AuthRepository**：`login`（初始化 ApiClient/UrlHelper + 存 token）、`tryAutoLogin`、`logout`；init 中注册 token 刷新回调持久化。
- **FavoriteRepository**：收藏基于服务端 **`built_in` 标签歌单**实现——`type=normal` 歌单收藏歌曲、`type=radio` 歌单收藏电台；内部缓存 type→playlistId 映射；`getFavorites` 拉取所有内置歌单歌曲合并。
- **PlaylistRepository**：歌单列表/详情/歌曲（详情页按 500 首/页循环拉取直至全量，修复原先只显示前 50 首的问题）；置顶由用户自定义（长按歌单置顶/取消置顶，上限 8 个，DataStore 持久化）。
- **SongRepository**：`getSongs`、`getFacets`、`getSongLyric`（歌词全空抛异常）、`reportPlayed`、`getLibraryStats`（分页拉全库统计，上限 5000 首）。
- **StatsRepository**：播放统计插件（`jsplugin/stats`）数据源，`getSummary(range)`（range 由 `StatsRange` 枚举换算 from/to 时间戳：全部/今日/本周[周一起]/本月）、`getTrends(days)`、`getHourly()`、`getHistory(limit, offset)`；任一接口失败返回 Result.failure，UI 层据此回退。
- **UpdateRepository**：GitHub Release 更新检查 + APK 下载，见 §5。

### 2.5 存储（`data/storage/PreferencesDataStore.kt`）

DataStore 名 `songloft_tv_settings`，27 个 key：`server_url`、`theme_mode`(Int，0=跟随系统/1=浅色/2=深色/3=暗夜)、`theme_color`(String，默认 `"indigo"`)、`audio_quality`、`access_token`、`refresh_token`、`background_playback`、`use_custom_keyboard`、`ignored_version_code`、`eq_enabled`(Boolean)、`eq_preset`(Int，系统预设下标，-1=自定义)、`eq_bands`(String，逗号分隔 dB)、`sfx_enabled`(Boolean)、`sfx_mode`(String，virtualizer/bass_boost/loudness/reverb)、`sfx_strength`(Int，0-100)、`lyric_highlight_color`(Int)、`lyric_font_size`(Int，默认 30)、`play_cache_mb`(Int，MB，0=关闭)、`cache_server_url`(String，缓存归属服务器)、`key_mapping_up/down/left/right/back/confirm/top/bottom`(Int，自定义按键映射，0=未设置)。均以 Flow 暴露；按键映射以 `KeyMapping` 数据类聚合暴露，`setKeyMapping` 单次原子写 6+2 个 key。

### 2.6 播放缓存（`data/cache/PlaybackCache.kt`）

- **PlaybackCache**（object）：缓存目录 `cacheDir/play_cache`，提供 `dir`/`clear`（递归删除）/`usage`（统计文件总字节）。目录专用，勿存放其他文件。
- **RoutingDataSource**：按 URL 路由的数据源包装。`open(dataSpec)` 时若 `uri.lastPathSegment` 以 `.m3u8` 结尾（HLS 直播清单）走纯 upstream——缓存会让 live 清单永久命中旧版本导致 `PlaylistStuckException` 卡死；其余资源（音频/视频/HLS 分片）走 CacheDataSource。其余方法（read/close/getUri/getResponseHeaders/addTransferListener）转发到当前选中源。

### 2.7 UrlHelper（`data/api/UrlHelper.kt`）

- `songPlayUrl(id, quality?, track?)` → `/api/v1/songs/{id}/play?quality=..&track=..`
- `songCoverUrl` / `playlistCoverUrl`
- `resolve(url?)`：后端相对路径 → 绝对 URL（已带 http(s) 原样返回）。

## 3. 播放体系

### 3.1 MusicService（`MusicService.kt`）

`@AndroidEntryPoint`（Hilt 注入 PreferencesDataStore）。ExoPlayer 真正的创建位置。 自定义 `DataSource.Factory`：每次创建 `DefaultHttpDataSource` 时**动态**读取 `AuthInterceptor.accessToken` 附加 `Authorization` 头（JWT 会运行期刷新，不能固化）。
  ExoPlayer 通过自定义 `DefaultRenderersFactory` 注入 `VocalRemovalProcessor` 到 `DefaultAudioSink`（音频处理链 PCM 流出 AudioTrack 前，与 audiofx HAL 层均衡器/音效正交叠加）。AudioSession 在 ExoPlayer 构建时即分配。

- AudioAttributes（music/media + 音频焦点）、`setHandleAudioBecomingNoisy(true)`。
- MediaSession 的 sessionActivity 指向 `PlayerActivity`（点通知回播放器）。
- `onTaskRemoved`：仅在未播放或队列为空时 `stopSelf()` —— 播放中移除任务不停止，天然后台播放（**没有**用户可见的后台播放开关，设置页的"背景播放"项仅控制退出应用时是否 stopService）。
- 通知使用 MediaSessionService 默认 MediaNotification，元数据来自 MediaItem 的 MediaMetadata。
- **播放缓存**（`onCreate` 中初始化，runCatching 兜底失败回退纯流式）：
  - 读 `play_cache_mb`/`serverUrl`/`cacheServerUrl`；大小 >0 且缓存归属服务器与当前不同 → `PlaybackCache.clear` 后建 `SimpleCache`（`LeastRecentlyUsedCacheEvictor(缓存MB × 1MB)` + `StandaloneDatabaseProvider`，media3 1.5.0 三参构造）；大小 =0 → 清空目录且 cache 为 null；随后写回 `cache_server_url`。
  - 数据源链：upstream（带 JWT 头）→ CacheDataSource（`FileDataSource` 读 + `CacheDataSink` 5MB 分片写 + `FLAG_IGNORE_CACHE_ON_ERROR`，缓存读写失败不影响播放）→ RoutingDataSource（m3u8 清单绕开缓存）。
  - `CacheDataSource.EventListener` 打缓存命中日志（每次资源加载节流一条）与忽略缓存告警；缓存大小下次服务启动生效，运行中变更走 `cache/apply` 命令（未播放则 stopSelf 重启，播放中不打扰）。
- **均衡器**：audioSessionId 在 ExoPlayer 构建时即分配（AudioTrack 复用同一 id），`ensureEqualizer()` 按需懒创建 `android.media.audiofx.Equalizer`，失败（无 audiofx HAL）时每次命令到达重试。
- **音效**（与均衡器独立叠加、单模式互斥）：四类效果器 Virtualizer/BassBoost/LoudnessEnhancer/PresetReverb 按需创建（`ensureSfx`），`applySfx(mode, strength)` 先禁用全部再启用选中模式；强度 0-100 映射 audiofx 参数（BassBoost 上限 600 防破音，PresetReverb 按段映射）；输出设备切换（HDMI/蓝牙/内置，API 23+ `AudioDeviceCallback`）或音频会话变化时释放全部效果器待重建，A2DP 状态经 `sfx/info` 上报供 UI 提示。
- **自定义命令**（`onConnect` 授权，`onCustomCommand` 返回 `ListenableFuture<SessionResult>`）：
  - `eq/apply`、`eq/info`、`eq/check`：均衡器开关/预设/频段增益应用、能力与状态回传、静态能力校验；
  - `sfx/apply`、`sfx/info`、`sfx/check`：音效应用（enabled=false 时强制 mode=off）、能力矩阵（逐效果器查询 `AudioEffect.queryEffects()`）+ A2DP 状态 + 当前生效模式回传、静态能力校验；
   - `cache/clear`：遍历 `cache.getKeys()` 逐个 `removeResource` 清空；`cache/apply`：未播放则 stopSelf 立即生效，播放中保持下次生效。
   - `vocal/apply`、`vocal/check`：DSP 人声消除（Mid/Side 分频段降人声）开关应用、能力与状态回传（`isActive()` 反映当前音轨格式是否为 16-bit PCM stereo）；
   - `onDestroy` 释放顺序：mediaSession → player → `cache?.release()` → 均衡器/音效 → `vocalRemovalProcessor.reset()` → 注销设备回调。

### 3.2 PlayerController（`domain/PlayerController.kt`）

`@Singleton`，通过 `withController(action)` 懒建 `MediaController` 连接 MusicService。暴露 `state: StateFlow<PlaybackState>`（queue/currentIndex/currentSong/currentTrack/embeddedTracks/isPlaying/isBuffering/duration/playMode/睡眠定时器状态）。

- **队列**：`play(queue, index, contextType?, contextKey?)` 先同步更新 state（UI 提前展示），再 `setMediaItems` + `prepare` + `play`；context 随 state 保存，仅在 `play` 事件上报时携带（歌单详情页传 `playlist`+歌单 ID，分面页传 `artist/album/year`+取值，搜索/收藏等扁平列表不传）。
- **播放控制**：`togglePlay()` 切换播放/暂停；`pause()` 强制暂停（K 歌退出时使用）。
- **播放模式**：`enum PlayMode { ORDER, LOOP, SINGLE, RANDOM }`，映射到 ExoPlayer 的 repeatMode + shuffleModeEnabled；`cyclePlayMode()` 轮转。
- **双音轨切换 `switchTrack(track)`**，两种机制：
  1. 服务端多文件音轨：记录进度 → `replaceMediaItem` 重建 MediaItem → `seekTo` 续播；`onMediaItemTransition` 中同曲 id 不重置 currentTrack；
  2. 内嵌音轨（如 MKV 多音轨，`onTracksChanged` 中检出多个音频 TrackGroup）：`TrackSelectionOverride` 无缝选轨，不重建。
- **URI 构建 `buildMediaItem`** 优先级：track.url → 电台 song.url（type=radio）→ `UrlHelper.songPlayUrl(id, quality, track)`；以 `.m3u8` 结尾时显式设 `APPLICATION_M3U8` MimeType 走 HLS。
- **播放上报**：转场时上一首按原因报 `finish`（自然播完）/`skip`（手动切），新歌报 `play`；source 固定 `tv`（来源统计），`play` 事件带当前播放上下文（见上）。
- **睡眠定时器**（两种互斥）：`setSleepTimer(minutes)` 协程每分钟递减；`setSleepAfterSongs(count)` 在自然转场时递减；归零 pause。UI 入口在设置页。
- **均衡器**：`PlaybackState` 暴露 eqSupported/eqEnabled/eqPreset/eqBands/eqBandFrequencies/eqBandLevelMin/Max/eqPresetNames。`init` 中 `combine(eqEnabled, eqPreset, eqBands)` 收集 DataStore flow——UI 修改只写 DataStore，闭环自动 `sendEqApply`（幂等）；连接时**先应用缓存配置再 `queryEqInfo`**（保证 info 反映应用后状态），播放就绪（STATE_READY）且仍不支持时 `retryEqSetup` 重试（音频会话晚于连接就绪的冷启动竞态）。`setEqualizerBand` 手动调频段时自动将 preset 置 -1（自定义曲线）。
- **音效**：`PlaybackState` 暴露 sfxEnabled/sfxMode/sfxStrength/sfxSupportedMatrix/sfxA2dpActive/sfxActiveMode；与均衡器同构的 DataStore 闭环（`sfx_enabled`/`sfx_mode`/`sfx_strength`），连接时先应用缓存再 `querySfxInfo`；`sfx/info` 回传能力矩阵与 A2DP 状态，UI 据此显示"当前设备不支持音效"提示或蓝牙输出提醒。
- **原伴唱音效联动**：切到伴唱（第 1 条音轨视为原唱，其余视为伴唱）且设备支持响度音效时，把当前音效（开关/模式/强度）备份到内存（`sfxBackup`，不写 DataStore），临时切到响度模式；切回原唱、切到新歌或重新播放时还原备份。覆盖只改运行缓存（`sfxEnabledCache` 等），服务重连/播放就绪重放时按缓存生效，进程重启后仍是用户原设置。
  - **缓存命令**：`clearPlayCache(onResult)` 发 `cache/clear`，回调透传成功与否；`applyCacheSetting()` 发 `cache/apply`，若当前未播放则 `release()` 并**同时清空 controllerFuture**（旧 future 指向已释放的 controller，不清会导致下次连接复用已释放实例），让服务停止后下次播放按新大小生效。
  - **人声消除（DSP fallback）**：`PlaybackState` 暴露 `vocalRemovalEnabled`/`vocalRemovalSupported`。`toggleAccompaniment()` → `toggleAccompanimentMode()`：多音轨（服务端 tracks>1 或内嵌音轨>1）时切轨道（Scheme B），单音轨时切换 DSP 开关（Scheme A），二者互斥。`switchTrack` 切到真双音轨时强制 `setVocalRemovalEnabled(false)`；`onMediaItemTransition` 新歌自动重置 DSP 为关闭。`setVocalRemovalEnabled` 改变 state 并发 `vocal/apply` 命令；连接时发送缓存状态 + `vocal/check` 查询能力。STATE_READY 时重试检查（`configure()` 后 `isActive()` 才反映真实格式支持）。

### 3.3 LyricParser（`domain/LyricParser.kt`）

`parsePayload(lyric, tlyric, rlyric, lxlyric)`：

- 优先级：lxlyric（洛雪逐字格式 `<偏移,时长>字`）→ lyric 含逐字标记 → 标准 LRC 逐行；
- 标准 LRC 支持一行多时间标签；逐字支持相对偏移和绝对 `[[mm:ss.xx]]` 两种，跨行修补末字结束时间；
- `mergeTranslations`：翻译/罗马音按时间最近邻匹配（容差 600ms）合并进主歌词行。

### 3.4 播放器 UI（`ui/player/`）

- **PlayerActivity**：独立 Activity，仅承载 `PlayerScreen`。
- **PlayerViewModel**：collect PlayerController.state 映射 UiState；进度轮询自适应——有逐字歌词时 60ms（卡拉 OK 平滑），否则 500ms；收藏乐观更新、失败回滚。K 歌退出走两步确认（`requestExitKaraoke` → `showExitKaraokeConfirm` → 弹窗确认 → `exitKaraokeMode`），退出时暂停主播放器。
- **交互**：控制栏 10s 无操作自动隐藏；控制隐藏时——左右键长按连续 ±10s seek、短按切歌、上下/OK 唤出控制栏；媒体键直达（MediaNext/Previous/PlayPause）。控制栏左上角带返回按钮（`PlayerActivity` 内 BackHandler/按钮返回主界面）。
- **两种模式**：视频（全屏 `VideoPlayer` = PlayerView 绑定 MediaController，多音轨时右上角 TrackChips）；音频（封面 blur(60dp) 毛玻璃背景 + 左封面/右 `LyricsPanel`）。
- **LyricsPanel**：自动滚动居中；逐字行渲染 KaraokeLine（按 word start/end 进度逐字点亮）；附带翻译行。
  - **ControlBar**：SeekBar（支持触屏点击定位 + 拖拽 seek）+ 上一曲/播放暂停/下一曲/播放模式/收藏/重新获取歌词/均衡器/队列按钮（重新获取歌词走 `refresh=1` 重跑服务端歌词插件搜索，请求中按钮显示加载圈）。原唱/伴唱按钮对所有非电台歌曲显示：多音轨时 `cycleAudioTrack` 切轨道（Scheme B），单音轨时 `toggleAccompaniment` 开关 DSP 人声消除（Scheme A）。
- **QueueDrawer**：左侧 400dp 抽屉，当前曲高亮，自动滚到当前位置，点击条目跳播（`PlayerController.playAt(index)`）。
- **EqPanel**：右侧 440dp 抽屉（与队列抽屉对称），开关 chip（开启/关闭）+ 系统预设 chip（FlowRow 前 6 个）+ 频段增益条（聚焦时左右键 ±1dB 步进，范围 levelMin/100..levelMax/100，手绘轨道+拇指复用 ControlBar SeekBar 样式）；`eqSupported=false` 时仅显示"当前设备不支持均衡器"；Back/点击外部关闭，Back 优先级：队列抽屉 > EQ 面板 > 控制栏。

### 3.5 K 歌模式（借鉴 [NASMusicTV](https://github.com/hxzhang2000/NASMusicTV)）

K 歌模式提供 KTV 风格的全屏演唱体验，与主播放器共享同一 ExoPlayer 实例，UI 完全独立。

#### 3.5.1 架构概览

```
PlayerViewModel
  ├─ enterKaraokeMode()  → PlayerController.enterKaraoke()（备份主页队列，载入 K 歌独立列表）
  ├─ exitKaraokeMode()   → PlayerController.exitKaraoke()（还原主页队列 + 暂停）
  └─ karaokeAdd/MoveTop/Remove/PlayAt → 操作独立列表
         │
ui/karaoke/
  ├─ KaraokePlayerScreen   # 全屏 KTV 界面（双行歌词 + 控制栏 + 二维码）
  ├─ KaraokeLyricsView     # 双行歌词视图 + 逐字高亮渲染
  ├─ KaraokeControlBar     # K 歌专用控制栏（重唱/原伴唱/队列/上下首）
  ├─ KaraokeQueueList      # K 歌队列管理（独立模态抽屉）
  └─ KaraokeQrCode         # 扫码点歌二维码展示
```

#### 3.5.2 独立播放列表

`PlayerController` 维护 K 歌独立列表（`karaokeList`），与主页队列完全隔离：

- **进入 K 歌**（`enterKaraoke()`）：备份主页队列/索引/进度到 `mainQueueBackup`/`mainIndexBackup`/`mainPosBackup`，从当前播放曲开始截取未唱歌曲载入引擎。
- **退出 K 歌**（`exitKaraoke()`）：还原主页队列与进度，清空 K 歌列表。退出时默认暂停主播放器，由用户主动继续播放。
- **期间操作**：`karaokeAdd`/`karaokeMoveTop`/`karaokeRemove`/`karaokePlayAt` 只作用于独立列表，不改动主页队列。
- **自然播放结束**：`onMediaItemTransition` 中 K 歌模式下，唱完的歌曲自动从独立列表移除（切歌/跳过不移除）。

#### 3.5.3 逐字卡拉 OK 高亮

借鉴 NASMusicTV 的双层 clipRect 裁剪方案，实现像素级平滑高亮：

- **底层**：灰色未播放全文。
- **顶层**：高亮文本按进度 `clipRect` 逐行揭示。
- **坐标计算**：利用 `TextLayoutResult` 获取每个字符的精确像素位置（`getHorizontalPosition`/`getLineLeft`/`getLineRight`），边界插值到半字粒度实现连续扫动。
- **越行修复**：`boundaryOffset + 1 >= lineEnd` 时用 `getLineRight(lineIdx)` 代替 `getHorizontalPosition`，避免插值目标跳到下一视觉行首字坐标导致高亮"回退"。
- **两种渲染路径**：
  - `KaraokeLyricsView`（K 歌双行视图）：基于 `karaokePacingFraction`（幂函数 `progress^0.6` 前快后慢）驱动逐行裁剪。
  - `LyricsPanel.SmoothWordHighlightLine`（播放器逐字行）：基于 `calculateCoveredChars`（按 word 时间戳累计已唱字符数 + 当前字进度比例）驱动裁剪。

#### 3.5.4 KTV 双行歌词视图

`KaraokeLyricsView` 固定显示两行（当前演唱行 + 下一行预览），滚动窗口机制避免整组替换跳动：

- 50ms 高频本地时钟插值（避免 ExoPlayer 回调跳动）。
- 幂函数 pacing（`progress^0.6`）模拟前快后慢节奏。
- 上行靠左（经典卡拉 OK 样式），下行靠右预览。

#### 3.5.5 扫码点歌

K 歌模式下启动 `ConfigWebServer`（NanoHTTPD），候选端口 18911-18914：

- 手机扫码访问 `http://<局域网IP>:<端口>/#order`，可搜索/加入/置顶/删除歌曲。
- 回调走 NanoHTTPD 工作线程，`PlayerViewModel` 内全部切到主线程执行（避免 MediaController 跨线程异常）。
- 二维码由 `KaraokeQrCode` 组件展示在 K 歌界面右上角。
- 退出 K 歌时自动停止 Web 服务。

#### 3.5.6 退出确认弹窗

按返回键或点击返回按钮时，先弹出「确定退出 K 歌吗？」确认对话框（默认焦点在"取消"），确认后才执行退出流程。`BackHandler` 优先级：退出确认弹窗 > 队列抽屉 > 音效面板 > 控制栏 > K 歌模式 > 返回。

## 4. UI 层

### 4.1 导航

`ui/navigation/Screen.kt`：sealed class，顶级 Tab 为 Home/Search/Playlists/My（`TvBottomNav` 底栏），二级页 Settings、Stats、PlaylistDetail(id)、SongFilter(field,value)、FacetList(field)。`MainActivity.TvApp` 用 `when(currentScreen)` 切换，`BackHandler` 定义回退链（PlaylistDetail→Playlists，Settings→My，其余→Home）。

**焦点与返回键（遥控器模式）**：

- `TabBarBridge`（compositionLocal）：`tabFocusRequester` 挂在当前选中 Tab 上（`TvBottomNav` 选中项挂 `focusRequester`），`hasFocus` 跟踪 Tab 栏焦点态；`focusTabBar()` 请求 Tab 栏焦点。
- `ListBackToTopHandler`（`ui/navigation/BackToTop.kt`）统一三段式返回：① 列表非顶部 → 回顶并聚焦顶部元素；② 列表在顶部但顶部元素未聚焦 → 聚焦顶部元素；③ 焦点已在顶部元素且列表在顶部 → 聚焦底部 Tab 栏。禁用条件：触摸模式（焦点请求无效，直接穿透弹退出确认）、Tab 栏已有焦点（穿透回首页/上一级）、二级界面焦点已在返回按钮（穿透返回）。
- `jumpToTabBar` 参数（一级 tab 页专用）：焦点已在顶部元素且列表在顶部时**不穿透**，先跳底部 Tab 栏。首页（`topFocusInList=true`，顶部=管理歌单）、搜索（顶部=搜索框）、歌单（顶部=全部过滤 chip）、我的（顶部=收藏歌曲 chip）四个 tab 页均启用，返回链路统一为：回顶 → 聚焦顶部按钮 → 跳 Tab 栏 → 回首页 → 退出确认。
- 首页默认焦点落在**底部 Tab 栏当前选中的「首页」按钮**（`HomeScreen` 中 `defaultFocusTarget = tabBarBridge?.tabFocusRequester ?: topFocus`，无 bridge 环境回退管理歌单）；其余 tab 页默认焦点在各页顶部元素（搜索框/过滤 chip/设置按钮）。
- `ScreenFocusRestorer`：二级界面返回时恢复点击来源元素焦点（`restorableFocus` 按 pendingKey 挂 requester，`RestoreFocusEffect` 按帧重试），优先级高于默认焦点。
- **自定义按键映射**（`domain/KeyMappingManager.kt`）：8 个功能键（上/下/左/右/返回/确认 + 特殊业务键"返回顶部/返回底部"），用户可把遥控器/方向盘的任意物理键录制映射到功能键（录制式交互：设置页弹窗内按下目标物理键即捕获 keycode，0=未设置）。`MainActivity` 与 `PlayerActivity` 覆写 `Activity.dispatchKeyEvent`：命中映射表的 keycode 经 `translateEvent` 翻译为标准 keycode（DPAD_UP=19/DOWN=20/LEFT=21/RIGHT=22/BACK=4/CENTER=23）后继续分发，Compose 层焦点导航、`BackHandler`、媒体键监听自动生效，无需改任何监听点；DOWN/UP 成对翻译保持 repeatCount/scanCode 等字段（长按 seek 不受影响）。录制对话框利用 Compose `Dialog` 独立窗口特性（不经过 Activity.dispatchKeyEvent）在内容根部 `onPreviewKeyEvent` 捕获**原始** keycode；KEYCODE_UNKNOWN 拒绝、录制非"返回"项时按返回取消、重复占用其他功能键提示。
- **返回顶部/返回底部**（`PageScrollBridge`，compositionLocal）：特殊业务键由 `MainActivity.dispatchKeyEvent` 拦截（`matchSpecialKey` 命中即消费事件），调用当前组合页面注册的滚动回调。`ListBackToTopHandler` 统一为 8 个长列表页注册：返回顶部 = 滚到列表第一项并聚焦顶部返回按钮；返回底部 = 焦点直接跳到底部 Tab 栏（首页/搜索/歌单/我的，便于快速切换页面）。设置页单独注册：返回顶部 = 聚焦顶部返回按钮并滚回顶部；返回底部 = 聚焦最下方"退出登录"按钮。页面销毁时注销回调。

启动流程：`MainApp` 观察 `AuthViewModel.authState`，`LoggedIn` 进 TvApp，否则显示 `AuthSetupScreen`。有播放时右下角悬浮 `FloatingPlayerBar`。

### 4.2 各页面

| 页面 | 实现要点 |
|---|---|
| 首页 Home | 5 个 async 并发拉统计/歌手/专辑/年份/歌单；统计卡 ×4、歌单 4 列网格（≤8，用户置顶在前）、歌手/专辑两列（各 6）、年份胶囊行（8）；最下方「年份速览」动态切换：仅预取统计插件 summary（全部区间，不带参数），成功则展示「播放统计」概览（全部/今日/本周/本月 Tab，切换其他区间时按需请求该区间 summary，右上角查看全部进统计页），失败则回退年份速览；概览区「本月」Tab 与「查看全部」用 `focusProperties` 双向焦点跳转 |
| 统计 Stats | 播放统计插件（jsplugin/stats）子界面：全部/今日/本周/本月时间 Tab、概览卡 ×4（播放次数/听歌时长/不同歌曲/不同艺术家）、艺术家排行 top4、歌曲排行 top3、听歌趋势（7/30 天柱状图）、专辑排行 top3、时段分布、来源分布 top3、歌曲类型 top3、最近播放 top3；各卡片标题栏带「刷新」按钮 |
| 搜索 Search | 300ms 防抖搜索；**无关键词时直接分页浏览曲库**（首次进入即展示，滚动接近底部懒加载下一页，列表顶部显示"共 N 首"）；自定义 `TvKeyboard`（左侧 8×4 字母方阵+功能键、右侧 4×4 数字/符号方阵可切换 + 一次性 Shift，特殊键用字符串协议"←退格/清空/确定"）；热门标签取 artist facet 前 10；拼音/首字母候选取 `songs/names`（title+artist 去重全量）经 `PinyinMatcher` 索引，输入 ≥2 个字母时匹配候选（旧服务器无该接口时回退 artist facet 值） |
| 分类 FacetList | 全部歌手/专辑/年份，3 列网格 → 点击进 SongFilter |
| 筛选 FilteredSongs | 按 artist/album/year 拉 500 首列表 |
| 歌单 Playlists | 全部/普通/电台 FilterChip 过滤，4 列网格；长按弹窗确认置顶/取消置顶（上限 8 个，置顶排最前；满 8 个时确认后自动顶掉最早置顶）；详情页有"播放全部/随机播放" |
| 我的 My | 收藏按 `song.type` partition 为歌曲/电台两个 Tab；右上角进设置 |
| 设置 Settings | 见 4.3 |
| 配置 AuthSetup | 见 4.4 |

### 4.3 设置页

- **主题模式**：跟随系统(0)/浅色(1)/深色(2)/**暗夜(3)** 写 DataStore `theme_mode`；`TvTheme` 直接订阅同一 key，即时全局换肤（暗夜为冷蓝紫暗调变体，见 4.5）。
- **主题色调**：黛青蓝(`"indigo"`，默认)/薄荷绿(`"emerald"`)/珊瑚粉(`"sakura"`)/蜜橘橙(`"honey"`)，写 DataStore `theme_color`；选项带对应主题色色块预览（种子色参考 songloft-player 主题 primary 色值）；`TvTheme` 订阅同一 key 动态构造 colorScheme，切换即全局替换，播放器深色 UI 不随色调变化。
- **服务器**：显示当前服务器地址，点击跳配置页。
- **音质**：原始("")/mp3/flac 写 DataStore，PlayerController 取流时读取拼入 quality 参数。
- **播放缓存**：`CacheSizeRow` 调节 0-1024MB（步进 128，D-Pad 左右键 + 触屏滑动，0=关闭、1024=1 GB、其余 xxx MB，"恢复默认"=0）；提示"未播放时修改立即生效，播放中将于下次播放生效；设为 0 即关闭并清空缓存；更换服务器后自动清空"；下方显示**当前占用**（`PlaybackCache.usage` 统计目录文件字节）+ **清除缓存**按钮（`clearPlayCache` 发 `cache/clear`）；占用数值在设置页可见期间**每 5 秒心跳刷新**（`cacheUsageTicker` flow + `repeatOnLifecycle(STARTED)` collect，离开页面自动停止）。
- **背景播放**：退出应用时是否 stopService（关闭则退出时结束后台播放）。
- **自定义键盘**：使用 `TvKeyboard` 或系统键盘。
- **按键设置**（自定义遥控器按键映射）：二级弹窗内 8 个配置项——上/下/左/右/返回/确认 + 返回顶部/返回底部，点击任意一项进入**录制模式**（"请按下您希望作为【X】使用的按键"，按下物理键即保存并关闭；无法识别的键拒绝、录制非"返回"项时按返回取消、已分配给其他功能键的按键提示不可用），末尾"恢复默认"一键重置；持久化 `key_mapping_*`，重启后生效，机制见 §4.1。适用于 keycode 非标的遥控器/车机方向盘按键。
- **睡眠定时**：分钟定时 + 播完 N 首两种（互斥），直接调 PlayerController（不持久化），剩余量实时回显。
- **音效开关**：音效 + 均衡器二合一总开关（开启时分别校验能力，两者均不支持弹"当前设备不支持音效"对话框；关闭时同时关 EQ/SFX）。
- **歌词**：高亮色（默认跟随主题色/白色）+ 字号调节（`LyricSizeRow`，D-Pad 左右键 + 触屏滑动，30-60sp）。
- **日志导出**：`logcat -d` 逐行脱敏（Authorization/Cookie 头、JSON token/password 字段、URL token 参数、裸 JWT 四个正则）后写系统下载目录（API 29+ 用 MediaStore）。
- **帮助**：操作说明对话框（返回键用法/歌单置顶/播放器快捷键/自定义按键四个区块，内容可滚动），共享组件 `ui/components/HelpDialog.kt`。
- **版权与免责声明**：首次进入主界面弹窗展示（`disclaimer_shown` DataStore 标记，任意方式关闭即视为已展示不再弹出）；底部「操作说明」按钮先关闭免责声明再打开 `HelpDialog`，返回键只回到主界面、不会回到免责声明。
- **关于**：运行时读 versionName、检查更新（`UpdateRepository`，见 §5）、项目地址、开源组件列表。
- **重启应用**：整行可聚焦项（`SettingsItem` 样式），点击重启（发启动 Intent 后 `Runtime.exit`，部分设置如缓存大小需重启服务生效）。
- **危险操作**（红色标题，沉底降低误触）：清除配置、退出登录两个按钮，点击后弹**二次确认对话框**（默认焦点在"取消"，确认键为红色实心），确认才执行。

### 4.4 配置/登录（`ui/config/`）

`AuthState`（sealed）：Loading/NotConfigured/Configured/LoggedIn/Error。启动时读 DataStore serverUrl，非空则 `tryAutoLogin`。两种配置方式并存于 `AuthSetupScreen`：

1. **遥控器手动输入**：三个 InputField（服务器/账号/密码）+ 共享 TvKeyboard，按 activeField 路由按键。
2. **手机扫码**：`startConfigServer()` 在候选端口 18899-18902 启动 `ConfigWebServer`（NanoHTTPD，`GET /` 返回移动端 HTML 表单、`POST /submit` 接收 server/username/password）；电视端 ZXing 生成 `http://<局域网IP>:<端口>` 二维码；手机提交后回调触发登录，成功后停服。

两种方式共用 `login()`：地址无协议前缀时按 `https://` → `http://` 顺序探测登录，仅连接层失败（IOException）才换协议重试，服务器有真实响应（如账号密码错误）直接报错；成功后以实际可用协议的完整 URL 持久化。

### 4.5 通用组件与主题

- **CoverImage**：`UrlHelper.resolve` + Coil AsyncImage，加载中/失败/无 URL 显示音符占位；Coil 的 OkHttpClient 在 `SongloftTvApp`（ImageLoaderFactory）中挂 AuthInterceptor（封面接口需 JWT）。
- **FloatingPlayerBar**：右下角迷你条，未聚焦为 96dp 圆形（仅封面），聚焦展开 300dp 露出标题；播放中封面 10s/圈旋转。
- **TvFocusable / D-Pad 规范**：统一"焦点 = 缩放 1.05-1.1x + primary 边框"模式；`Modifier.tvFocusable()` 是抽象，多数页面内联实现同一模式；无自定义 FocusOrder，依赖 Compose 默认焦点搜索。
- **选中型组件规范**（设置/首页/我的/歌单/统计等页 chip 统一）：选中 = primary 实心填充 + ✓；聚焦 = 缩放 1.1x(120ms tween) + 3dp 描边（选中项用 `SelectedFocusBorder` 白边保证高对比，未选中项用 primary 边）。
- **主题 TvTheme**：种子色由 `ThemeSeeds` + `seedColorFor(name)` 按 DataStore `theme_color` 取值（默认黛青蓝 `0xFF415F91`），动态构造 Light/Dark ColorScheme；composable 内直接订阅 `PreferencesDataStore.THEME_MODE` / `THEME_COLOR` 两个 key，切换即全局换肤。模式 3 为**暗夜模式**（`nightScheme`，冷蓝紫暗调的 darkColorScheme 变体，强调沉浸感）。播放器深色 UI 的固定色集中定义在 `PlayerColors`（不随主题色调变化），语义白描边抽为 `SelectedFocusBorder`。

## 5. 应用更新（`data/repository/UpdateRepository.kt`）

- **镜像代理池**：`MIRRORS` = GitHub 直连 + ghfast.top / gh-proxy.com / ghproxy.net 三个前缀代理；`TlsCompat` 兼容老电视的 TLS 配置，检查 10s / 下载 20s / 探测 8s 超时。
- **检查（并发首胜）**：并发请求全部镜像的 `version.json`，取**延迟最低的成功结果**；版本比较优先 `versionCode`，旧 Release 无该字段时回退 semver 比较（合成 versionCode 供忽略过滤与缓存文件命名）；进程内 `autoCheckDone` 保证启动自动检查只执行一次。
- **下载（先测速再整包）**：并发向各镜像发 `Range: bytes=0-64KB` 探测请求，按（网速、进程内历史稳定度、延迟）排序后从最优镜像整包下载；失败按序回退；完整命中已下载文件直接复用；进度每 256KB 上报一次；临时文件 + rename 原子落盘。
- 下载完成的 APK 经系统安装器安装；`ignored_version_code` 支持"忽略此版本"。UI 在 `MainActivity.TvApp` 的 `UpdateDialog`（启动自动检查 + 设置页关于区手动检查）。

## 6. 已知问题 / 遗留

| 问题 | 位置 |
|---|---|
| StateFlow 初始值硬编码了测试服务器地址/账号/密码（临时测试用，发布前需清理） | `ui/config/AuthViewModel.kt` |
| 设计稿中的"后台播放开关"、"服务器切换"未实现（后台播放为默认行为） | — |
