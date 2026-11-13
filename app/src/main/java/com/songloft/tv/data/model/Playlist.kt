package com.songloft.tv.data.model

import com.google.gson.annotations.SerializedName

data class Playlist(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null,
    @SerializedName("song_count") val songCount: Int = 0,
    val type: String = "normal",
    val labels: List<String>? = emptyList()
) {
    val isBuiltIn: Boolean get() = labels?.contains("built_in") == true
    val isHidden: Boolean get() = labels?.contains("hidden") == true
}

/**
 * 「全部曲库（仅音乐）」虚拟歌单。
 *
 * 不是服务端歌单：客户端固定置顶，用负 id 与服务端自增 id 区分；内容实时取自
 * GET /songs 全量分页并剔除电台（type=radio）。曲库新增歌曲无需维护自动进入，
 * 「播放全部」的队列因此是整个音乐曲库，而不是某个歌单或搜索页已加载的一页。
 */
object AllSongsPlaylist {
    const val ID: Long = -1L
    const val NAME = "全部曲库"
    const val DESCRIPTION = "曲库内全部音乐（不含电台），自动同步新增歌曲"
    /** 卡片副标题：曲库总数需进详情页才能确定，列表页不显示数字 */
    const val SUBTITLE = "仅音乐 · 全部歌曲"

    fun isAllSongs(id: Long): Boolean = id == ID

    val playlist: Playlist = Playlist(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        type = "normal"
    )
}
