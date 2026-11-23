package com.example.universalnovelreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.universalnovelreader.ui.AdapterEditScreen
import com.example.universalnovelreader.ui.AdapterMarketScreen
import com.example.universalnovelreader.ui.AdapterStudioScreen
import com.example.universalnovelreader.ui.BookshelfScreen
import com.example.universalnovelreader.ui.DefaultReaderSettingsScreen
import com.example.universalnovelreader.ui.NovelDetailScreen
import com.example.universalnovelreader.ui.ReaderScreen
import com.example.universalnovelreader.ui.ReaderViewModel
import com.example.universalnovelreader.ui.Screen
import com.example.universalnovelreader.ui.SettingsScreen

@Composable
fun MainNavigation(
    viewModel: ReaderViewModel = viewModel()
) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    // Android「戻る」ボタンのハンドリング
    BackHandler(enabled = currentScreen !is Screen.Bookshelf) {
        when (currentScreen) {
            is Screen.Reader -> {
                val novelId = (currentScreen as Screen.Reader).novelId
                viewModel.navigateTo(Screen.NovelDetail(novelId))
            }
            is Screen.NovelDetail -> viewModel.navigateTo(Screen.Bookshelf)
            is Screen.Settings -> viewModel.navigateTo(Screen.Bookshelf)
            is Screen.DefaultReaderSettings -> viewModel.navigateTo(Screen.Settings)
            is Screen.AdapterStudio -> viewModel.navigateTo(Screen.Settings)
            is Screen.AdapterMarket -> viewModel.navigateTo(Screen.AdapterStudio)
            is Screen.AdapterEdit -> viewModel.navigateTo(Screen.AdapterStudio)
            else -> {}
        }
    }

    when (val screen = currentScreen) {
        is Screen.Bookshelf -> {
            BookshelfScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.NovelDetail -> {
            NovelDetailScreen(novelId = screen.novelId, viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.Reader -> {
            ReaderScreen(
                novelId = screen.novelId,
                initialEpisodeIndex = screen.episodeIndex,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        is Screen.Settings -> {
            SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.DefaultReaderSettings -> {
            DefaultReaderSettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.AdapterStudio -> {
            AdapterStudioScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.AdapterMarket -> {
            AdapterMarketScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        is Screen.AdapterEdit -> {
            AdapterEditScreen(
                adapterIdToEdit = screen.adapterId,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
