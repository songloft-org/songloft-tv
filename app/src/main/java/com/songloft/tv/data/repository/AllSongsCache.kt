package com.songloft.tv.data.repository

import android.util.Log
import com.songloft.tv.data.api.SongListResponse
import com.songloft.tv.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「全部曲库（仅音乐）」的进程内缓存状态。
 *
 * @param songs 已载入的音乐（剔除 type=radio），按服务端 offset 顺序追加，只会增长
 * @param serverTotal 服务端 /songs 原始总数（含电台），仅用于进度显示
 * @param generation 每次 invalidate/refresh 自增，用于隔离被取消的旧加载任务
 */
data class AllSongsCacheState(
    val songs: List<Song> = emptyList(),
    val serverTotal: Int = 0,
    val isLoading: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null,
    val generation: Long = 0L
)

/**
 * 全库音乐的应用级后台预载缓存（@Singleton，生命周期不绑定任何页面）。
 *
 * 旧实现是「进入虚拟歌单详情页才串行逐页拉，详情页退出即中断」，曲库大时首屏等待很久。
 * 现在由首页/歌单页创建时调用 [ensureLoading] 提前后台加载，分页按 4 并发批量拉取，
 * 结果逐批追加进 StateFlow；详情页只是订阅缓存，命中后秒开，退出页面也不会打断加载。
 * 服务端切换 / 手动刷新走 [invalidate]，用 generation 防止旧任务把旧服务器数据写回。
 */
@Singleton
class AllSongsCache @Inject constructor(
    private val songRepository: SongRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AllSongsCacheState())
    val state: StateFlow<AllSongsCacheState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** 幂等触发：已有加载任务、或已完成（即便个别分页失败）时直接复用，不重复拉全库 */
    fun ensureLoading() {
        synchronized(this) {
            if (loadJob?.isActive == true) return
            if (_state.value.isComplete) return
            _state.value = _state.value.copy(isLoading = true, error = null)
            loadJob = scope.launch { loadAll(_state.value.generation) }
        }
    }

    /** 丢弃缓存并取消进行中的任务（切换服务器、登出、手动刷新前调用） */
    fun invalidate() {
        synchronized(this) {
            loadJob?.cancel()
            loadJob = null
            _state.value = AllSongsCacheState(generation = _state.value.generation + 1)
        }
    }

    /** 手动刷新：清空缓存并立刻重新后台预载 */
    fun refresh() {
        invalidate()
        ensureLoading()
    }

    /** 只有世代未变才写状态：invalidate 后旧任务的写入全部变成空操作，不会污染新缓存 */
    private fun publish(generation: Long, transform: (AllSongsCacheState) -> AllSongsCacheState) {
        _state.update { if (it.generation == generation) transform(it) else it }
    }

    private suspend fun loadAll(generation: Long) {
        // runCatching 会把取消异常也包成 Result.failure，因此每批前后都要校验世代
        fun alive() = generation == _state.value.generation

        val firstResult = songRepository.getSongs(limit = PAGE_SIZE, offset = 0)
        if (!alive()) return
        val firstPage = firstResult.getOrNull()
        if (firstPage == null) {
            publish(generation) {
                it.copy(isLoading = false, error = firstResult.exceptionOrNull()?.message ?: "无法加载曲库")
            }
            return
        }

        val serverTotal = firstPage.total
        publish(generation) {
            it.copy(
                songs = firstPage.songs.filterNot { song -> song.type == "radio" },
                serverTotal = serverTotal,
                isLoading = true
            )
        }

        // 分页游标按服务端原始条数递增（客户端过滤电台不影响服务端分页，否则会漏歌）
        val maxOffset = minOf(serverTotal, MAX_LIBRARY_SONGS)
        val offsets = mutableListOf<Int>()
        var cursor = PAGE_SIZE
        while (cursor < maxOffset) {
            offsets += cursor
            cursor += PAGE_SIZE
        }

        var lastError: String? = null
        for (batch in offsets.chunked(CONCURRENCY)) {
            if (!alive()) return
            // 同批并发拉取，按 offset 升序合并，保证歌曲顺序与服务端一致
            val results: MutableMap<Int, Result<SongListResponse>> = coroutineScope {
                batch.map { offset ->
                    async { offset to songRepository.getSongs(limit = PAGE_SIZE, offset = offset) }
                }.awaitAll()
            }.toMap().toMutableMap()
            // 偶发网络抖动单页失败时重试一次，避免整页歌曲缺失
            results.filterValues { it.getOrNull() == null }.keys.toList().forEach { offset ->
                results[offset] = songRepository.getSongs(limit = PAGE_SIZE, offset = offset)
            }
            if (!alive()) return
            val merged = _state.value.songs.toMutableList()
            for (offset in batch) {
                val result = results[offset] ?: continue
                val page = result.getOrNull()
                if (page == null) {
                    lastError = result.exceptionOrNull()?.message
                    Log.w(TAG, "全库第 $offset 首起加载失败：${result.exceptionOrNull()?.message}")
                } else {
                    merged.addAll(page.songs.filterNot { song -> song.type == "radio" })
                }
            }
            val batchSongs = merged.toList()
            publish(generation) { it.copy(songs = batchSongs) }
        }

        if (!alive()) return
        publish(generation) { it.copy(isLoading = false, isComplete = true, error = lastError) }
        Log.d(TAG, "全库音乐载入完成：${_state.value.songs.size} 首（服务端原始 $serverTotal 首）")
    }

    companion object {
        const val PAGE_SIZE = 500
        /** 并发页数：4 路并发把 N 页串行耗时压到约 ceil(N / 4)，同时不给家庭服务器太大压力 */
        private const val CONCURRENCY = 4
        /** 安全阀：防止服务端 total 异常导致无限分页 */
        private const val MAX_LIBRARY_SONGS = 20_000
        private const val TAG = "AllSongsCache"
    }
}
