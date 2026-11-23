package com.example.universalnovelreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.universalnovelreader.core.network.NativeHttpClient
import com.example.universalnovelreader.core.parser.AdapterTestResult
import com.example.universalnovelreader.core.parser.SiteAdapterManager
import com.example.universalnovelreader.data.catalog.AdapterCatalogClient
import com.example.universalnovelreader.data.catalog.AdapterPackage
import com.example.universalnovelreader.data.catalog.CustomCatalogSource
import com.example.universalnovelreader.data.csv.CsvSyncManager
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.EpisodeCacheData
import com.example.universalnovelreader.data.model.Novel
import com.example.universalnovelreader.data.model.ReaderSettings
import com.example.universalnovelreader.data.model.SiteAdapter
import com.example.universalnovelreader.data.storage.CacheManager
import com.example.universalnovelreader.worker.AutoSyncManager
import com.example.universalnovelreader.worker.AutoSyncSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class Screen {
    object Bookshelf : Screen()
    data class NovelDetail(val novelId: Long) : Screen()
    data class Reader(val novelId: Long, val episodeIndex: Int) : Screen()
    object Settings : Screen()
    object DefaultReaderSettings : Screen()
    object AdapterStudio : Screen()
    object AdapterMarket : Screen()
    data class AdapterEdit(val adapterId: Long? = null) : Screen()
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    val repository = NovelRepository(application)
    val cacheManager = CacheManager(application)
    val httpClient = NativeHttpClient()
    val csvSyncManager = CsvSyncManager(application, repository)
    val adapterManager = SiteAdapterManager(application, repository, cacheManager, httpClient)
    val catalogClient = AdapterCatalogClient(application, httpClient)

    // ナビゲーション状態
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Bookshelf)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // 本棚の小説一覧
    private val _novels = MutableStateFlow<List<Novel>>(emptyList())
    val novels: StateFlow<List<Novel>> = _novels.asStateFlow()

    // 選択中小説のエピソード一覧
    private val _currentEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val currentEpisodes: StateFlow<List<Episode>> = _currentEpisodes.asStateFlow()

    // 選択中の小説
    private val _selectedNovel = MutableStateFlow<Novel?>(null)
    val selectedNovel: StateFlow<Novel?> = _selectedNovel.asStateFlow()

    // 選択中小説のアダプター（未対応時はnull）
    private val _currentAdapter = MutableStateFlow<SiteAdapter?>(null)
    val currentAdapter: StateFlow<SiteAdapter?> = _currentAdapter.asStateFlow()

    // 登録アダプター一覧
    private val _adapters = MutableStateFlow<List<SiteAdapter>>(emptyList())
    val adapters: StateFlow<List<SiteAdapter>> = _adapters.asStateFlow()

    // リーダー設定（全体デフォルトまたはドメイン別）
    private val _readerSettings = MutableStateFlow(ReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    // 定期更新設定
    private val _autoSyncSettings = MutableStateFlow(AutoSyncManager.loadSettings(application))
    val autoSyncSettings: StateFlow<AutoSyncSettings> = _autoSyncSettings.asStateFlow()

    // カスタムカタログURL一覧
    private val _customCatalogSources = MutableStateFlow(catalogClient.loadCustomSources())
    val customCatalogSources: StateFlow<List<CustomCatalogSource>> = _customCatalogSources.asStateFlow()

    // キャッシュサイズ表示
    val cacheSizeText = MutableStateFlow("計算中...")

    // ローディング状態
    val isLoading = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)

    init {
        loadBookshelf()
        loadAdapters()
        loadDefaultReaderSettings()
        updateCacheSize()
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
        when (screen) {
            is Screen.NovelDetail -> loadNovelDetail(screen.novelId)
            is Screen.Bookshelf -> loadBookshelf()
            is Screen.AdapterStudio -> loadAdapters()
            is Screen.AdapterMarket -> loadAdapters()
            is Screen.AdapterEdit -> loadAdapters()
            is Screen.Settings -> {
                updateCacheSize()
                loadDefaultReaderSettings()
                reloadAutoSyncSettings()
            }
            is Screen.DefaultReaderSettings -> loadDefaultReaderSettings()
            else -> {}
        }
    }

    fun loadBookshelf() {
        viewModelScope.launch {
            _novels.value = repository.getAllNovels()
        }
    }

    fun loadAdapters() {
        viewModelScope.launch {
            _adapters.value = repository.getAllAdapters()
        }
    }

    fun loadDefaultReaderSettings() {
        viewModelScope.launch {
            _readerSettings.value = repository.getDefaultReaderSettings()
        }
    }

    fun updateCacheSize() {
        viewModelScope.launch {
            cacheSizeText.value = cacheManager.getCacheSizeFormatted()
        }
    }

    fun loadNovelDetail(novelId: Long) {
        viewModelScope.launch {
            val novel = repository.getNovelById(novelId)
            _selectedNovel.value = novel
            _currentEpisodes.value = repository.getEpisodesForNovel(novelId)

            if (novel != null) {
                _currentAdapter.value = repository.findAdapterForUrl(novel.topUrl)
                // この小説用の有効なリーダー設定を取得（ドメイン上書きを考慮）
                _readerSettings.value = repository.getEffectiveReaderSettings(novel.siteAdapterId)
            }
        }
    }

    /**
     * URLから小説を直接登録（目次をパース）
     */
    fun registerNovelFromUrl(url: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            isLoading.value = true
            statusMessage.value = "目次情報を取得中..."
            val result = adapterManager.syncNovelIndex(url)
            isLoading.value = false
            statusMessage.value = null

            if (result.isSuccess) {
                loadBookshelf()
                onComplete?.invoke(true)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * 特定の小説の目次を再チェック
     */
    fun refreshNovelIndex(novel: Novel) {
        viewModelScope.launch {
            isLoading.value = true
            statusMessage.value = "目次を更新中..."
            adapterManager.syncNovelIndex(novel.topUrl)
            loadNovelDetail(novel.id)
            loadBookshelf()
            isLoading.value = false
            statusMessage.value = null
        }
    }

    /**
     * 本棚全作品の一括更新チェック
     */
    fun refreshAllNovels() {
        viewModelScope.launch {
            isLoading.value = true
            val novelList = repository.getAllNovels()
            var current = 0
            for (novel in novelList) {
                current++
                statusMessage.value = "更新チェック中 (${current}/${novelList.size}): ${novel.title}"
                try {
                    adapterManager.syncNovelIndex(novel.topUrl)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            loadBookshelf()
            isLoading.value = false
            statusMessage.value = null
        }
    }

    /**
     * エピソード本文の取得（キャッシュ優先、無ければWeb取得）
     */
    suspend fun getEpisodeContent(novelId: Long, episode: Episode): EpisodeCacheData? {
        val result = adapterManager.getEpisodeContent(novelId, episode)
        return result.getOrNull()
    }

    /**
     * 読書位置の自動保存（スクロール停止時や画面離脱時）
     * 進行率が 0.9 (90%) 以上なら自動的に既読フラグをONにする
     */
    fun updateReadingProgress(novelId: Long, episodeId: Long, scrollProgress: Float) {
        viewModelScope.launch {
            val isRead = scrollProgress >= 0.90f
            repository.updateEpisodeScrollAndRead(episodeId, scrollProgress, isRead)
            repository.updateNovelLastRead(novelId, episodeId)
        }
    }

    fun reloadAutoSyncSettings() {
        _autoSyncSettings.value = AutoSyncManager.loadSettings(getApplication())
    }

    fun updateAutoSyncSettings(settings: AutoSyncSettings) {
        _autoSyncSettings.value = AutoSyncManager.saveAndApply(getApplication(), settings)
    }

    fun runAutoSyncNow() {
        AutoSyncManager.enqueueImmediateSync(getApplication())
    }

    /**
     * リーダー設定の更新
     */
    fun saveReaderSettings(settings: ReaderSettings) {
        viewModelScope.launch {
            repository.saveReaderSettings(settings)
            _readerSettings.value = settings
        }
    }

    /**
     * 編集中のサイト定義を使って即時パーステストを実行（本棚DBを汚さない）
     */
    suspend fun testAdapterParse(adapter: SiteAdapter, testUrl: String): AdapterTestResult {
        return adapterManager.testAdapter(adapter, testUrl)
    }

    fun addCustomCatalogSource(name: String, url: String) {
        _customCatalogSources.value = catalogClient.addCustomSource(name, url)
    }

    fun removeCustomCatalogSource(url: String) {
        _customCatalogSources.value = catalogClient.removeCustomSource(url)
    }

    /**
     * マーケット・共有コード・ファイルから取り込んだサイト定義パッケージを保存
     */
    fun importAdapterPackage(pkg: AdapterPackage, onComplete: () -> Unit) {
        viewModelScope.launch {
            val existing = repository.getAllAdapters().firstOrNull { it.adapterId == pkg.adapterId }
            val siteAdapter = pkg.toSiteAdapter(existing)
            repository.insertOrUpdateAdapter(siteAdapter)
            loadAdapters()
            _selectedNovel.value?.let { loadNovelDetail(it.id) }
            onComplete()
        }
    }

    /**
     * アダプターの保存（新規登録または更新）
     */
    fun saveAdapter(adapter: SiteAdapter, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.insertOrUpdateAdapter(adapter)
            loadAdapters()
            // 選択中の小説があればアダプター情報を再取得
            _selectedNovel.value?.let { loadNovelDetail(it.id) }
            onComplete()
        }
    }

    /**
     * アダプターの削除
     */
    fun deleteAdapter(id: Long) {
        viewModelScope.launch {
            repository.deleteAdapter(id)
            loadAdapters()
            _selectedNovel.value?.let { loadNovelDetail(it.id) }
        }
    }

    /**
     * 小説の削除
     */
    fun deleteNovel(novelId: Long) {
        viewModelScope.launch {
            repository.deleteNovel(novelId)
            cacheManager.clearNovelCache(novelId)
            loadBookshelf()
        }
    }

    /**
     * 全キャッシュのクリア
     */
    fun clearAllCache() {
        viewModelScope.launch {
            cacheManager.clearAllCache()
            updateCacheSize()
            _selectedNovel.value?.let { loadNovelDetail(it.id) }
        }
    }
}
