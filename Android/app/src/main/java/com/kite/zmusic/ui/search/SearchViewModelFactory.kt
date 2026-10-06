package com.kite.zmusic.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kite.zmusic.data.LikedPlaylistRepository
import com.kite.zmusic.data.SearchHistoryRepository
import com.kite.zmusic.data.SearchRepository
import com.kite.zmusic.data.SessionRepository
import com.kite.zmusic.data.platform.MusicPlatformStore
import com.kite.zmusic.data.platform.OpenMusicCatalog

class SearchViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val searchHistory: SearchHistoryRepository,
    private val search: SearchRepository,
    private val likedPlaylistRepository: LikedPlaylistRepository,
    private val platformStore: MusicPlatformStore,
    private val openCatalog: OpenMusicCatalog,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            return SearchViewModel(
                sessionRepository,
                searchHistory,
                search,
                likedPlaylistRepository,
                platformStore,
                openCatalog,
            ) as T
        }
        error("Unknown ViewModel $modelClass")
    }
}
