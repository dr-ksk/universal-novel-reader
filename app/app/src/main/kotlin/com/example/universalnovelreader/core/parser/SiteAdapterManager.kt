package com.example.universalnovelreader.core.parser

import android.content.Context
import com.example.universalnovelreader.core.network.HttpStatusException
import com.example.universalnovelreader.core.network.NativeHttpClient
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.EpisodeCacheData
import com.example.universalnovelreader.data.model.Novel
import com.example.universalnovelreader.data.model.SiteAdapter
import com.example.universalnovelreader.data.storage.CacheManager
import com.example.universalnovelreader.engine.ScriptExecutionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecacheAllResult(
    val succeededCount: Int,
    val totalCount: Int,
    val stoppedReason: String? = null
)

data class AdapterTestResult(
    val success: Boolean,
    val message: String,
    val novelTitle: String? = null,
    val author: String? = null,
    val episodeCount: Int = 0,
    val sampleEpisodes: List<String> = emptyList(),
    val firstEpisodeTitle: String? = null,
    val firstEpisodePreview: String? = null,
    val firstEpisodeLength: Int = 0
)

class SiteAdapterManager(
    private val context: Context,
    private val repository: NovelRepository,
    private val cacheManager: CacheManager,
    private val httpClient: NativeHttpClient
) {
    private val scriptEngine = ScriptExecutionBridge(context, repository, cacheManager, httpClient)

    /**
     * URLに対応するアダプターを取得
     */
    suspend fun getAdapterForUrl(url: String): SiteAdapter? {
        return repository.findAdapterForUrl(url)
    }

    /**
     * 目次の同期＆更新チェック
     */
    suspend fun syncNovelIndex(topUrl: String): Result<Novel> = withContext(Dispatchers.IO) {
        try {
            val adapter = getAdapterForUrl(topUrl)
                ?: return@withContext Result.failure(IllegalStateException("No adapter registered for: ${urlDomain(topUrl)}"))

            if (adapter.adapterType == "yaml") {
                val yamlAdapter = YamlJsoupAdapter(adapter.content)
                val allEpisodes = mutableListOf<com.example.universalnovelreader.data.model.ParsedEpisodeSummary>()
                var currentUrl = topUrl
                var pageCount = 0
                val maxPages = 50 // 最大5000話まで巡回

                var firstPageIndex: com.example.universalnovelreader.data.model.ParsedNovelIndex? = null

                while (currentUrl.isNotEmpty() && pageCount < maxPages) {
                    val html = httpClient.getHtml(currentUrl, yamlAdapter.customHeaders)
                    val parsed = yamlAdapter.parseIndex(html, currentUrl)
                    if (firstPageIndex == null) {
                        firstPageIndex = parsed
                    }
                    
                    // インデックスの振り直し（通し番号）
                    for (ep in parsed.episodes) {
                        allEpisodes.add(ep.copy(episodeIndex = allEpisodes.size + 1))
                    }

                    val nextUrl = parsed.nextPageUrl
                    if (!nextUrl.isNullOrEmpty() && nextUrl != currentUrl) {
                        currentUrl = nextUrl
                        pageCount++
                        delay(maxOf(1500L, yamlAdapter.crawlIntervalMs)) // サーバー負荷軽減の安全待機
                    } else {
                        break
                    }
                }

                val finalIndex = firstPageIndex ?: return@withContext Result.failure(Exception("Failed to parse novel index"))
                val parsedIndex = finalIndex.copy(episodes = allEpisodes)

                // 小説情報の取得または登録
                var novel = repository.getNovelByUrl(topUrl)
                val novelId: Long
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                if (novel == null) {
                    novelId = repository.insertNovel(
                        Novel(
                            siteAdapterId = adapter.id,
                            title = parsedIndex.title,
                            author = parsedIndex.author ?: "",
                            synopsis = parsedIndex.synopsis ?: "",
                            topUrl = topUrl,
                            lastEpisodeIndex = parsedIndex.episodes.size,
                            lastCheckedAt = now
                        )
                    )
                    novel = repository.getNovelById(novelId)!!
                } else {
                    novelId = novel.id
                    val updatedNovel = novel.copy(
                        siteAdapterId = adapter.id,
                        title = parsedIndex.title.ifEmpty { novel.title },
                        author = parsedIndex.author?.ifEmpty { novel.author } ?: novel.author,
                        synopsis = parsedIndex.synopsis?.ifEmpty { novel.synopsis } ?: novel.synopsis,
                        lastEpisodeIndex = parsedIndex.episodes.size,
                        lastCheckedAt = now
                    )
                    repository.updateNovel(updatedNovel)
                    novel = updatedNovel
                }

                // エピソード一覧の更新
                for (epSummary in parsedIndex.episodes) {
                    val existing = repository.getEpisodeByUrl(epSummary.url)
                    val episode = existing?.copy(
                        episodeIndex = epSummary.episodeIndex,
                        chapterTitle = epSummary.chapter ?: existing.chapterTitle,
                        title = epSummary.title,
                        updatedAtSite = epSummary.updatedAt ?: existing.updatedAtSite
                    ) ?: Episode(
                        novelId = novelId,
                        episodeIndex = epSummary.episodeIndex,
                        chapterTitle = epSummary.chapter,
                        title = epSummary.title,
                        url = epSummary.url,
                        updatedAtSite = epSummary.updatedAt
                    )
                    repository.insertOrUpdateEpisode(episode)
                }

                Result.success(novel)
            } else {
                // JavaScript形式アダプターの実行
                val jsResult = scriptEngine.executeIndexScript(topUrl, adapter.content)
                if (jsResult.isSuccess) {
                    val novel = repository.getNovelByUrl(topUrl)
                        ?: return@withContext Result.failure(IllegalStateException("Novel saved by script could not be found"))
                    Result.success(novel)
                } else {
                    Result.failure(jsResult.exceptionOrNull() ?: Exception("Unknown JS execution error"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 1話の本文取得（キャッシュ優先、forceRefresh=true の場合はキャッシュを無視してサーバーから強制再キャッシュ）
     */
    suspend fun getEpisodeContent(
        novelId: Long,
        episode: Episode,
        forceRefresh: Boolean = false
    ): Result<EpisodeCacheData> = withContext(Dispatchers.IO) {
        try {
            // 1. すでにキャッシュがあるか確認（強制再キャッシュ時はスキップ）
            if (!forceRefresh) {
                val cached = cacheManager.getEpisode(novelId, episode.episodeIndex)
                if (cached != null) {
                    return@withContext Result.success(cached)
                }
            }

            // 2. アダプターでWebから取得してキャッシュを保存（上書き）
            val adapter = getAdapterForUrl(episode.url)
                ?: return@withContext Result.failure(IllegalStateException("No adapter registered for: ${urlDomain(episode.url)}"))

            if (adapter.adapterType == "yaml") {
                val yamlAdapter = YamlJsoupAdapter(adapter.content)
                val cacheData = fetchAndCacheEpisodeYaml(novelId, episode, yamlAdapter)
                Result.success(cacheData)
            } else {
                if (forceRefresh) {
                    cacheManager.deleteEpisodeCache(novelId, episode.episodeIndex)
                }
                val jsResult = scriptEngine.executeEpisodeScript(episode.url, adapter.content)
                if (jsResult.isSuccess) {
                    val newlyCached = cacheManager.getEpisode(novelId, episode.episodeIndex)
                    if (newlyCached != null) {
                        Result.success(newlyCached)
                    } else {
                        Result.failure(IllegalStateException("Script finished but no episode cache was created"))
                    }
                } else {
                    Result.failure(jsResult.exceptionOrNull() ?: Exception("JS episode parse error"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 指定小説の全話を安全ウェイト付きで強制再キャッシュする
     */
    suspend fun forceRecacheAllEpisodes(
        novelId: Long,
        onProgress: (current: Int, total: Int, episodeTitle: String) -> Unit
    ): RecacheAllResult = withContext(Dispatchers.IO) {
        val episodes = repository.getEpisodesForNovel(novelId)
        if (episodes.isEmpty()) {
            return@withContext RecacheAllResult(0, 0, "エピソードがありません")
        }

        val firstAdapter = getAdapterForUrl(episodes.first().url)
        val intervalMs = if (firstAdapter != null && firstAdapter.adapterType == "yaml") {
            maxOf(1500L, YamlJsoupAdapter(firstAdapter.content).crawlIntervalMs)
        } else {
            2500L
        }

        var succeeded = 0
        var consecutiveErrors = 0
        var stoppedReason: String? = null

        for ((idx, ep) in episodes.withIndex()) {
            onProgress(idx + 1, episodes.size, ep.title)
            val res = getEpisodeContent(novelId, ep, forceRefresh = true)
            if (res.isSuccess) {
                succeeded++
                consecutiveErrors = 0
            } else {
                val ex = res.exceptionOrNull()
                if (ex is HttpStatusException && ex.isRateLimitOrOverloaded()) {
                    stoppedReason = "サーバー応答 (${ex.statusCode}) のため安全のため中断しました"
                    break
                }
                consecutiveErrors++
                if (consecutiveErrors >= 3) {
                    stoppedReason = "連続して取得に失敗したため中断しました"
                    break
                }
            }
            if (idx < episodes.lastIndex) {
                delay(intervalMs)
            }
        }

        RecacheAllResult(
            succeededCount = succeeded,
            totalCount = episodes.size,
            stoppedReason = stoppedReason
        )
    }

    private suspend fun fetchAndCacheEpisodeYaml(
        novelId: Long,
        episode: Episode,
        yamlAdapter: YamlJsoupAdapter
    ): EpisodeCacheData {
        val html = httpClient.getHtml(episode.url, yamlAdapter.customHeaders)
        val parsedEp = yamlAdapter.parseEpisode(html, episode.url)

        // 挿絵画像のキャッシュ処理
        var processedContentHtml = parsedEp.contentHtml
        val savedImagePaths = mutableListOf<String>()

        if (yamlAdapter.cacheImages && parsedEp.imageUrls.isNotEmpty()) {
            for (imgUrl in parsedEp.imageUrls) {
                try {
                    val imgBytes = httpClient.getBytes(imgUrl, yamlAdapter.customHeaders)
                    if (imgBytes.isNotEmpty()) {
                        val localPath = cacheManager.saveImage(novelId, imgUrl, imgBytes)
                        savedImagePaths.add(localPath)
                        // HTML内の画像URLをローカルファイルパスに差し替え
                        processedContentHtml = processedContentHtml.replace(imgUrl, "file://$localPath")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val cachePath = cacheManager.saveEpisode(
            novelId = novelId,
            episodeIndex = episode.episodeIndex,
            episodeTitle = parsedEp.title.ifEmpty { episode.title },
            chapterTitle = episode.chapterTitle,
            contentHtml = processedContentHtml,
            sourceUrl = episode.url,
            prevUrl = parsedEp.prevUrl,
            nextUrl = parsedEp.nextUrl,
            images = savedImagePaths
        )
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        repository.markEpisodeCached(episode.id, cachePath, now)

        return EpisodeCacheData(
            novelId = novelId,
            episodeIndex = episode.episodeIndex,
            episodeTitle = parsedEp.title.ifEmpty { episode.title },
            chapterTitle = episode.chapterTitle,
            contentHtml = processedContentHtml,
            cachedAt = now,
            sourceUrl = episode.url,
            prevUrl = parsedEp.prevUrl,
            nextUrl = parsedEp.nextUrl,
            images = savedImagePaths
        )
    }

    /**
     * 編集中のアダプター定義を使って、DBを汚さずに一覧＋第1話本文のパースを即時テスト
     */
    suspend fun testAdapter(adapter: SiteAdapter, testUrl: String): AdapterTestResult = withContext(Dispatchers.IO) {
        try {
            if (adapter.adapterType == "yaml") {
                val yamlAdapter = YamlJsoupAdapter(adapter.content)
                val indexHtml = httpClient.getHtml(testUrl, yamlAdapter.customHeaders)
                val parsedIndex = yamlAdapter.parseIndex(indexHtml, testUrl)

                var firstEpTitle: String? = null
                var firstEpPreview: String? = null
                var firstEpLen = 0
                val firstEp = parsedIndex.episodes.firstOrNull()
                if (firstEp != null) {
                    try {
                        val epHtml = httpClient.getHtml(firstEp.url, yamlAdapter.customHeaders)
                        val parsedEp = yamlAdapter.parseEpisode(epHtml, firstEp.url)
                        firstEpTitle = parsedEp.title.ifBlank { firstEp.title }
                        val plain = org.jsoup.Jsoup.parse(parsedEp.contentHtml).text().trim()
                        firstEpLen = plain.length
                        firstEpPreview = if (plain.length > 140) plain.take(140) + "…" else plain
                    } catch (e: Exception) {
                        firstEpTitle = firstEp.title
                        firstEpPreview = "本文取得エラー: ${e.message}"
                    }
                }

                AdapterTestResult(
                    success = true,
                    message = "パース成功",
                    novelTitle = parsedIndex.title,
                    author = parsedIndex.author,
                    episodeCount = parsedIndex.episodes.size,
                    sampleEpisodes = parsedIndex.episodes.take(3).map { "${it.episodeIndex}. ${it.title}" },
                    firstEpisodeTitle = firstEpTitle,
                    firstEpisodePreview = firstEpPreview,
                    firstEpisodeLength = firstEpLen
                )
            } else {
                val jsResult = scriptEngine.executeIndexScript(testUrl, adapter.content)
                if (jsResult.isSuccess) {
                    val novel = repository.getNovelByUrl(testUrl)
                    AdapterTestResult(
                        success = true,
                        message = "JSパース成功",
                        novelTitle = novel?.title,
                        author = novel?.author,
                        episodeCount = novel?.lastEpisodeIndex ?: 0
                    )
                } else {
                    AdapterTestResult(
                        success = false,
                        message = "JS実行エラー: ${jsResult.exceptionOrNull()?.message ?: "不明なエラー"}"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AdapterTestResult(
                success = false,
                message = "エラー: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun urlDomain(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }
}
