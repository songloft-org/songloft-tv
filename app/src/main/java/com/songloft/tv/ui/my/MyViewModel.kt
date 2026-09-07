package com.songloft.tv.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songloft.tv.data.api.StatsHistoryRecord
import com.songloft.tv.data.model.Song
import com.songloft.tv.data.repository.FavoriteRepository
import com.songloft.tv.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyUiState(
    val favoriteSongs: List<Song> = emptyList(),
    val favoriteRadios: List<Song> = emptyList(),
    val playbackHistory: List<StatsHistoryRecord> = emptyList(),
    val historyFavorites: Map<Long, Boolean> = emptyMap(),
    val selectedTab: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class MyViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyUiState())
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

    init {
        loadFavorites()
        loadPlaybackHistory()
        // 其他页面收藏/取消收藏时，收藏 id 集变化，静默刷新列表保持同步
        viewModelScope.launch {
            favoriteRepository.favoriteIds
                .filterNotNull()
                .distinctUntilChanged()
                .drop(1)
                .collect { loadFavorites(showLoading = false) }
        }
    }

    fun loadFavorites(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(isLoading = true, error = null) }
            favoriteRepository.getFavorites()
                .onSuccess { songs ->
                    val (radios, normals) = songs.partition { it.type == "radio" }
                    // 收藏歌单按加入顺序追加，倒序使最新收藏排在最前
                    _uiState.update {
                        it.copy(
                            favoriteSongs = normals.reversed(),
                            favoriteRadios = radios.reversed(),
                            isLoading = false
                        )
                    }
                }
                .onFailure { e ->
                    if (showLoading) _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    private fun loadPlaybackHistory() {
        viewModelScope.launch {
            statsRepository.getHistory(limit = 10, offset = 0)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(playbackHistory = page.records.take(10))
                    }
                }
                .onFailure { }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun removeFavorite(song: Song) {
        val prevSongs = _uiState.value.favoriteSongs
        val prevRadios = _uiState.value.favoriteRadios
        _uiState.update {
            if (song.type == "radio") it.copy(favoriteRadios = it.favoriteRadios - song)
            else it.copy(favoriteSongs = it.favoriteSongs - song)
        }
        viewModelScope.launch {
            favoriteRepository.removeFavorite(song).onFailure {
                _uiState.update { it.copy(favoriteSongs = prevSongs, favoriteRadios = prevRadios) }
            }
        }
    }

    /** 将播放历史记录转为可展示的 Song 对象 */
    fun recordToSong(record: StatsHistoryRecord): Song = Song(
        id = record.songId,
        type = record.type ?: "remote",
        title = record.title.ifBlank { "未知曲目" },
        artist = record.artist.takeIf { it.isNotBlank() },
        album = record.album.takeIf { it?.isNotBlank() == true },
        duration = record.duration ?: 0.0,
        url = null,
        coverUrl = null,
        isVideo = false
    )

    fun toggleFavoriteFromHistory(record: StatsHistoryRecord) {
        val wasFav = isHistoryRecordFavorite(record)
        val newFavorites = _uiState.value.historyFavorites + (record.songId to !wasFav)
        _uiState.update { it.copy(historyFavorites = newFavorites) }
        val song = recordToSong(record)
        viewModelScope.launch {
            favoriteRepository.toggleFavorite(song).onFailure {
                // 失败回滚：恢复原始状态
                val reverted = _uiState.value.historyFavorites + (record.songId to wasFav)
                _uiState.update { it.copy(historyFavorites = reverted) }
            }
        }
    }

    fun isHistoryRecordFavorite(record: StatsHistoryRecord): Boolean {
        // 优先使用乐观更新后的本地状态，未手动切换过则检查服务端收藏列表
        _uiState.value.historyFavorites[record.songId]?.let { return it }
        return _uiState.value.favoriteSongs.any { it.id == record.songId } ||
            _uiState.value.favoriteRadios.any { it.id == record.songId }
    }
}
