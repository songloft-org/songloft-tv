package com.songloft.tv.data.repository

import com.songloft.tv.data.model.AllSongsPlaylist
import com.songloft.tv.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.shuffled
import kotlin.random.Random

/**
 * 歌单封面的「歌曲封面组合」来源（应用级单例）。
 *
 * 歌单没有自定义封面时，客户端从歌单内歌曲里随机抽 3~4 张封面拼成封面。为避免
 * 「每次进页面/重组都换一张」，这里做了两层稳定：
 * 1. **结果缓存并锁定**：组合一旦生成就写入 [covers]，除非 [invalidate] 否则不再重算
 *    （虚拟「全部曲库」的全库缓存会持续追加，锁定后也不会跟着变）。
 * 2. **确定性随机**：采样分页的 offset、抽取张数与洗牌都以歌单 id 为随机种子，
 *    即使缓存被清空（切服务器、重启进程），只要歌单内容没变，重新生成的组合仍一致。
 *
 * 采样不遍历整个歌单：先取第一页拿 total，再按种子随机跳 2 页，最多 3 次请求/歌单；
 * 所有请求共用 4 路信号量，避免列表滚动时打出大量并发。
 */
@Singleton
class PlaylistCoverRepository @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val allSongsCache: AllSongsCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(MAX_CONCURRENT)

    /** 歌单 id → 已锁定的封面组合；空列表表示「查过且没有可用封面」，同样不再重试 */
    private val _covers = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val covers: StateFlow<Map<Long, List<String>>> = _covers.asStateFlow()

    private val inFlight = mutableSetOf<Long>()
    private var generation = 0L

    init {
        // 虚拟「全部曲库」没有服务端详情接口，直接从全库缓存采样；首批到达即锁定，
        // 之后缓存继续追加也不重算，保证首页/歌单页/详情页看到的是同一组封面
        scope.launch {
            allSongsCache.state.collect { cache ->
                val (gen, locked) = synchronized(this@PlaylistCoverRepository) {
                    generation to _covers.value.containsKey(AllSongsPlaylist.ID)
                }
                if (locked) return@collect
                val candidates = cache.songs.coverUrls()
                if (candidates.size < MIN_COVERS && !cache.isComplete) return@collect
                val result = pick(candidates, AllSongsPlaylist.ID)
                if (result.isNotEmpty()) publishIfAbsent(gen, AllSongsPlaylist.ID, result)
            }
        }
    }

    /** 幂等触发：组合已缓存或正在生成时直接复用；虚拟歌单由 init 的收集器负责 */
    fun ensureLoaded(id: Long) {
        if (AllSongsPlaylist.isAllSongs(id)) return
        val gen = synchronized(this) {
            if (id in inFlight || _covers.value.containsKey(id)) return
            inFlight += id
            generation
        }
        scope.launch {
            try {
                // 首页请求失败（网络抖动/未登录）不写缓存，下次重进页面可重试；
                // 成功但确实没有封面才写空列表，避免反复打无效请求
                val candidates = sample(id)
                if (candidates != null) {
                    val result = pick(candidates, id)
                    publish(gen) { if (it.containsKey(id)) it else it + (id to result) }
                }
            } finally {
                synchronized(this@PlaylistCoverRepository) {
                    if (generation == gen) inFlight -= id
                }
            }
        }
    }

    /** 切服务器 / 登出 / 手动刷新：丢弃已锁定组合（种子固定，歌单未变时结果仍一致） */
    fun invalidate() {
        synchronized(this) {
            generation++
            inFlight.clear()
            _covers.value = emptyMap()
        }
    }

    /** 首页 + 随机 offset 追加页，汇总去重的候选封面 URL */
    private suspend fun sample(id: Long): List<String>? {
        val random = Random(id xor SAMPLING_SALT)
        val first = request { playlistRepository.getPlaylistSongs(id, limit = PAGE_SIZE, offset = 0).getOrNull() }
            ?: return null
        val pool = LinkedHashSet<String>()
        first.songs.collectCovers(pool)
        val maxOffset = first.total - PAGE_SIZE
        if (maxOffset > 0) {
            // 至少随机跳一页保证采样覆盖歌单中后段；封面仍不足时才补第二页，
            // 否则列表页滚动时请求量过大（常见歌单只需 2 次请求）
            val extraPages = if (pool.size >= MAX_COVERS) 1 else EXTRA_PAGES
            repeat(extraPages) {
                val offset = random.nextInt(maxOffset + 1)
                val page = request {
                    playlistRepository.getPlaylistSongs(id, limit = PAGE_SIZE, offset = offset).getOrNull()
                }
                page?.songs?.collectCovers(pool)
            }
        }
        return pool.toList()
    }

    private suspend fun <T> request(block: suspend () -> T?): T? = limiter.withPermit { block() }

    private fun List<Song>.coverUrls(): List<String> =
        LinkedHashSet<String>().also { collectCovers(it) }.toList()

    private fun List<Song>.collectCovers(pool: MutableSet<String>) {
        for (song in this) {
            val url = song.coverUrl?.takeIf { it.isNotBlank() } ?: continue
            pool.add(url)
        }
    }

    /** 随机抽 3~4 张；候选不足时按现有张数自适应（2 张 / 1 张由 UI 决定布局） */
    private fun pick(candidates: List<String>, id: Long): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val random = Random(id xor PICK_SALT)
        val count = if (candidates.size >= MAX_COVERS) {
            random.nextInt(MIN_COVERS, MAX_COVERS + 1)
        } else {
            minOf(MIN_COVERS, candidates.size)
        }
        return candidates.shuffled(random).take(count)
    }

    /** 世代隔离：invalidate 之后旧任务的写入全部作废，不会把上一个服务器的封面写回 */
    private fun publish(gen: Long, transform: (Map<Long, List<String>>) -> Map<Long, List<String>>) {
        synchronized(this) {
            if (gen != generation) return
            _covers.value = transform(_covers.value)
        }
    }

    private fun publishIfAbsent(gen: Long, id: Long, urls: List<String>) =
        publish(gen) { if (it.containsKey(id)) it else it + (id to urls) }

    companion object {
        /** 组合目标张数：随机 3 或 4 张，优先 4 张田字格 */
        const val MIN_COVERS = 3
        const val MAX_COVERS = 4
        private const val PAGE_SIZE = 50
        private const val EXTRA_PAGES = 2
        private const val MAX_CONCURRENT = 4
        private const val SAMPLING_SALT = 0x5DEECE66DL
        private const val PICK_SALT = 0x2545F4914F6CDD1DL
    }
}
