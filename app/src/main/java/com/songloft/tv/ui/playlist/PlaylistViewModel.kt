package com.songloft.tv.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songloft.tv.data.model.AllSongsPlaylist
import com.songloft.tv.data.model.Playlist
import com.songloft.tv.data.model.Song
import com.songloft.tv.data.repository.AllSongsCache
import com.songloft.tv.data.repository.FavoriteRepository
import com.songloft.tv.data.repository.PlaylistCoverRepository
import com.songloft.tv.data.repository.PlaylistRepository
import com.songloft.tv.data.storage.PreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistListUiState(
    val playlists: List<Playlist> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedType: String? = null
)

data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val total: Int = 0,
    /** 虚拟「全部曲库」专用：后台分页仍在载入，此时点播放会先播已载入部分再自动补齐队列 */
    val isLoadingAll: Boolean = false,
    /** 服务端原始总数（含电台），仅用于载入进度显示 */
    val allServerTotal: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val favoriteRepository: FavoriteRepository,
    private val allSongsCache: AllSongsCache,
    private val playlistCoverRepository: PlaylistCoverRepository,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    private val _listState = MutableStateFlow(PlaylistListUiState())
    val listState: StateFlow<PlaylistListUiState> = _listState.asStateFlow()

    /** 无自定义封面的歌单：随机抽出的歌曲封面组合，应用级缓存，跨页面一致 */
    val playlistCovers: StateFlow<Map<Long, List<String>>> = playlistCoverRepository.covers

    fun ensurePlaylistCovers(playlistId: Long) = playlistCoverRepository.ensureLoaded(playlistId)

    /** 虚拟「全部曲库」卡片副标题：数量跟随应用级全库缓存实时更新（已剔除电台） */
    private val _allSongsSubtitle = MutableStateFlow(AllSongsPlaylist.subtitle(0, true))
    val allSongsSubtitle: StateFlow<String> = _allSongsSubtitle.asStateFlow()

    private val _detailState = MutableStateFlow(PlaylistDetailUiState())
    val detailState: StateFlow<PlaylistDetailUiState> = _detailState.asStateFlow()

    private var detailJob: Job? = null
    private var loadMoreJob: Job? = null

    /** 用户置顶歌单 id，下标 0 最前（新置顶排最前） */
    val pinnedIds: StateFlow<List<Long>> = preferencesDataStore.pinnedPlaylistIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<Long>> = favoriteRepository.favoriteIds
        .map { it ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        loadPlaylists()
        allSongsCache.ensureLoading()
        viewModelScope.launch {
            allSongsCache.state.collect { state ->
                _allSongsSubtitle.value = AllSongsPlaylist.subtitle(state.songs.size, state.isLoading)
            }
        }
        // 上限由 8 调整为 7 后，把老配置里多出的置顶项裁掉（下标 0 最新，保留前 7 个）
        viewModelScope.launch {
            val pinned = preferencesDataStore.pinnedPlaylistIds.first()
            if (pinned.size > MAX_PINNED) {
                preferencesDataStore.setPinnedPlaylistIds(pinned.take(MAX_PINNED))
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoriteRepository.toggleFavorite(song) }
    }

    /** 长按歌单：已置顶则取消，未置顶则插入最前；超过上限自动替换最旧的置顶（界面层已弹窗确认） */
    fun togglePin(id: Long) {
        // 虚拟歌单是固定置顶，不参与用户置顶列表
        if (AllSongsPlaylist.isAllSongs(id)) return
        viewModelScope.launch {
            val current = preferencesDataStore.pinnedPlaylistIds.first()
            android.util.Log.d("PlaylistPin", "[togglePin] id=$id current=$current")
            val next = if (id in current) current - id else listOf(id) + current.filterNot { it == id }
            preferencesDataStore.setPinnedPlaylistIds(next.take(MAX_PINNED))
            android.util.Log.d("PlaylistPin", "[togglePin] saved=${next.take(MAX_PINNED)}")
        }
    }

    fun loadPlaylists(type: String? = null) {
        loadMoreJob?.cancel()
        _listState.value = PlaylistListUiState(isLoading = true, error = null, selectedType = type)
        viewModelScope.launch {
            playlistRepository.getPlaylists(type = type, limit = PLAYLIST_PAGE_SIZE).fold(
                onSuccess = { page ->
                    _listState.value = _listState.value.copy(
                        playlists = page.playlists,
                        total = page.total,
                        hasMore = page.playlists.size < page.total,
                        isLoading = false
                    )
                },
                onFailure = { e ->
                    _listState.value = _listState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            )
        }
    }

    /** 滚动到底时追加下一页（懒加载），offset 取当前已加载数量 */
    fun loadMorePlaylists() {
        val state = _listState.value
        if (state.isLoading || state.isLoadingMore || state.isRefreshing || !state.hasMore) return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            val offset = state.playlists.size
            playlistRepository.getPlaylists(
                type = state.selectedType,
                limit = PLAYLIST_PAGE_SIZE,
                offset = offset
            ).fold(
                onSuccess = { page ->
                    val current = _listState.value
                    val merged = (current.playlists + page.playlists).distinctBy { it.id }
                    _listState.value = current.copy(
                        playlists = merged,
                        total = page.total,
                        hasMore = offset + page.playlists.size < page.total,
                        isLoadingMore = false
                    )
                },
                onFailure = {
                    _listState.value = _listState.value.copy(isLoadingMore = false)
                }
            )
        }
    }

    /** 心跳/刷新按钮用：静默刷新当前筛选类型的歌单列表，数据无变化时不替换，避免打断浏览 */
    fun refreshPlaylists() {
        val state = _listState.value
        if (state.isLoading || state.isLoadingMore || state.isRefreshing) return
        val type = state.selectedType
        _listState.value = state.copy(isRefreshing = true)
        viewModelScope.launch {
            playlistRepository.getPlaylists(type = type, limit = PLAYLIST_PAGE_SIZE).fold(
                onSuccess = { page ->
                    val current = _listState.value
                    _listState.value = if (page.playlists.map { it.id } == current.playlists.map { it.id }) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            playlists = page.playlists,
                            total = page.total,
                            hasMore = page.playlists.size < page.total,
                            isRefreshing = false,
                            error = null
                        )
                    }
                },
                onFailure = {
                    // 静默刷新失败：保留现有列表，不打断用户
                    _listState.value = _listState.value.copy(isRefreshing = false)
                }
            )
            prunePinnedPlaylists()
        }
    }

    /** 刷新后校验置顶歌单仍存在：已被服务端删除的置顶 id 从配置清理（首页监听 pinnedPlaylistIds 自动刷新） */
    private suspend fun prunePinnedPlaylists() {
        val pinned = preferencesDataStore.pinnedPlaylistIds.first()
        if (pinned.isEmpty()) return
        val existing = _listState.value.playlists.map { it.id }.toSet()
        val missing = pinned.filterNot { it in existing }
        if (missing.isEmpty()) return
        val removed = missing.filter { playlistRepository.getPlaylistDetail(it).isFailure }
        if (removed.isEmpty()) return
        preferencesDataStore.setPinnedPlaylistIds(pinned.filterNot { it in removed })
        android.util.Log.d("PlaylistPin", "[prune] 置顶歌单已失效，清理：$removed")
    }

    fun loadPlaylistDetail(id: Long) {
        detailJob?.cancel()
        _detailState.value = PlaylistDetailUiState(isLoading = true)
        // 详情页歌曲需显示收藏状态，懒加载收藏 id（首次请求，之后走缓存），与歌曲列表并发加载
        viewModelScope.launch { favoriteRepository.ensureFavoriteIdsLoaded() }
        detailJob = viewModelScope.launch {
            if (AllSongsPlaylist.isAllSongs(id)) {
                // 虚拟歌单没有服务端详情：订阅应用级后台缓存，命中即秒开；
                // 详情页退出只取消订阅，缓存加载任务在 @Singleton 中继续跑，下次进来直接复用
                allSongsCache.ensureLoading()
                allSongsCache.state.collect { cache ->
                    _detailState.value = PlaylistDetailUiState(
                        playlist = AllSongsPlaylist.playlist,
                        songs = cache.songs,
                        total = cache.songs.size,
                        allServerTotal = cache.serverTotal,
                        isLoading = false,
                        isLoadingAll = cache.isLoading,
                        // 部分分页失败时已有歌曲仍可用，只在完全没加载到数据时报错
                        error = cache.error?.takeIf { cache.songs.isEmpty() }
                    )
                }
                return@launch
            }
            playlistRepository.getPlaylistDetail(id).fold(
                onSuccess = { playlist ->
                    _detailState.value = _detailState.value.copy(playlist = playlist)
                    loadAllSongs(id)
                },
                onFailure = { e ->
                    _detailState.value = _detailState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            )
        }
    }

    /** 歌单页手动刷新：歌单列表刷新 + 全库缓存重建（心跳自动刷新不重建，避免每 30s 拉全库） */
    fun refreshAllSongs() {
        allSongsCache.refresh()
        playlistCoverRepository.invalidate()
        refreshPlaylists()
    }

    private suspend fun loadAllSongs(id: Long) {
        var offset = 0
        while (true) {
            val page = playlistRepository.getPlaylistSongs(id, limit = SONGS_PAGE_SIZE, offset = offset)
                .getOrNull() ?: break
            val current = _detailState.value
            _detailState.value = current.copy(
                songs = current.songs + page.songs,
                total = page.total,
                isLoading = false
            )
            offset += page.songs.size
            if (page.songs.isEmpty() || offset >= page.total) break
        }
        _detailState.value = _detailState.value.copy(isLoading = false)
    }

    fun clearDetail() {
        detailJob?.cancel()
        _detailState.value = PlaylistDetailUiState()
    }

    companion object {
        const val SONGS_PAGE_SIZE = 500
        const val PLAYLIST_PAGE_SIZE = 100
        /** 用户置顶歌单上限：首页固定 1 格给虚拟「全部曲库」，剩余 7 格与这里一一对应 */
        const val MAX_PINNED = 7
    }
}

/** 用户置顶歌单提到最前（新置顶最前），其余保持原顺序 */
fun orderWithPinnedFirst(playlists: List<Playlist>, pinnedIds: List<Long>): List<Playlist> {
    val byId = playlists.associateBy { it.id }
    val pinned = pinnedIds.mapNotNull { byId[it] }
    val pinnedSet = pinned.mapTo(mutableSetOf()) { it.id }
    return pinned + playlists.filterNot { it.id in pinnedSet }
}
