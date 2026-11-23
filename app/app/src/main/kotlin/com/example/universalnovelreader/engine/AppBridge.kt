package com.example.universalnovelreader.engine

import android.webkit.JavascriptInterface
import com.example.universalnovelreader.core.network.NativeHttpClient
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.Novel
import com.example.universalnovelreader.data.storage.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppBridge(
    private val repository: NovelRepository,
    private val cacheManager: CacheManager,
    private val httpClient: NativeHttpClient,
    private val scope: CoroutineScope,
    private val onIndexSaved: ((topUrl: String) -> Unit)? = null,
    private val onEpisodeSaved: ((episodeUrl: String) -> Unit)? = null,
    private val onToggleMenu: (() -> Unit)? = null,
    private val onError: ((error: String) -> Unit)? = null
) {

    /**
     * ネイティブによる高速HTTP GET通信（HTMLテキストのみ取得）
     * CORS制限なし、OkHttpによる高速通信。
     */
    @JavascriptInterface
    fun httpGet(urlString: String, headersJson: String? = null): String {
        return kotlinx.coroutines.runBlocking {
            try {
                val headers = mutableMapOf<String, String>()
                if (!headersJson.isNullOrEmpty()) {
                    val json = JSONObject(headersJson)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers[key] = json.getString(key)
                    }
                }
                httpClient.getHtml(urlString, headers)
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    /**
     * ネイティブによる安全なスリープ待機
     */
    @JavascriptInterface
    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 該当話がすでにキャッシュ保存されているか判定
     */
    @JavascriptInterface
    fun isCached(episodeUrl: String): Boolean {
        return kotlinx.coroutines.runBlocking {
            val ep = repository.getEpisodeByUrl(episodeUrl)
            ep?.cachedAt != null
        }
    }

    /**
     * スクリプトからのデバッグログ
     */
    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("AppBridgeScript", message)
    }

    /**
     * リーダー画面で画面中央タップ時にメニューの表示/非表示を切り替える
     */
    @JavascriptInterface
    fun toggleReaderMenu() {
        onToggleMenu?.invoke()
    }

    /**
     * ① 目次・小説マスター保存 API
     * スクリプトが目次ページをパースした後に呼び出します。
     */
    @JavascriptInterface
    fun saveNovelIndex(jsonString: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val topUrl = json.getString("topUrl")
                val title = json.getString("title")
                val author = json.optString("author", "")
                val synopsis = json.optString("synopsis", "")
                val episodesArray = json.getJSONArray("episodes")

                // 小説の登録または更新
                var novel = repository.getNovelByUrl(topUrl)
                val novelId: Long
                val adapter = repository.findAdapterForUrl(topUrl)
                if (novel == null) {
                    novelId = repository.insertNovel(
                        Novel(
                            siteAdapterId = adapter?.id,
                            title = title,
                            author = author,
                            synopsis = synopsis,
                            topUrl = topUrl,
                            lastEpisodeIndex = episodesArray.length()
                        )
                    )
                } else {
                    novelId = novel.id
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    repository.updateNovel(
                        novel.copy(
                            siteAdapterId = adapter?.id ?: novel.siteAdapterId,
                            title = title,
                            author = author.ifEmpty { novel.author },
                            synopsis = synopsis.ifEmpty { novel.synopsis },
                            lastEpisodeIndex = episodesArray.length(),
                            lastCheckedAt = now
                        )
                    )
                }

                // 各エピソードの登録/更新
                for (i in 0 until episodesArray.length()) {
                    val epObj = episodesArray.getJSONObject(i)
                    val epUrl = epObj.getString("url")
                    val epTitle = epObj.getString("title")
                    val chapter = epObj.optString("chapter").takeIf { it.isNotEmpty() }
                    val updatedAt = epObj.optString("updatedAt").takeIf { it.isNotEmpty() }
                    val contentHtml = epObj.optString("contentHtml").takeIf { it.isNotEmpty() }

                    val existingEp = repository.getEpisodeByUrl(epUrl)
                    val epIndex = epObj.optInt("index", i + 1)

                    val episode = existingEp?.copy(
                        episodeIndex = epIndex,
                        chapterTitle = chapter ?: existingEp.chapterTitle,
                        title = epTitle,
                        updatedAtSite = updatedAt ?: existingEp.updatedAtSite
                    ) ?: Episode(
                        novelId = novelId,
                        episodeIndex = epIndex,
                        chapterTitle = chapter,
                        title = epTitle,
                        url = epUrl,
                        updatedAtSite = updatedAt
                    )

                    val insertedId = repository.insertOrUpdateEpisode(episode)
                    val epId = if (existingEp != null) existingEp.id else insertedId

                    // 「仕様の穴」：もしスクリプトが親切にも本文を入れてきていたら、受動的にキャッシュ保存してしまう
                    if (!contentHtml.isNullOrEmpty()) {
                        val path = cacheManager.saveEpisode(
                            novelId = novelId,
                            episodeIndex = epIndex,
                            episodeTitle = epTitle,
                            chapterTitle = chapter,
                            contentHtml = contentHtml,
                            sourceUrl = epUrl
                        )
                        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        repository.markEpisodeCached(epId, path, now)
                    }
                }

                onIndexSaved?.invoke(topUrl)
            } catch (e: Exception) {
                e.printStackTrace()
                onError?.invoke("saveNovelIndex error: ${e.message}")
            }
        }
    }

    /**
     * ② 1話本文キャッシュ保存 API
     * スクリプトが各エピソード本文をパースした後に呼び出します。
     */
    @JavascriptInterface
    fun saveEpisodeContent(jsonString: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val episodeUrl = json.getString("episodeUrl")
                val episodeTitle = json.getString("episodeTitle")
                val contentHtml = json.getString("contentHtml")
                val prevUrl = json.optString("prevUrl").takeIf { it.isNotEmpty() }
                val nextUrl = json.optString("nextUrl").takeIf { it.isNotEmpty() }

                val episode = repository.getEpisodeByUrl(episodeUrl)
                if (episode != null) {
                    val path = cacheManager.saveEpisode(
                        novelId = episode.novelId,
                        episodeIndex = episode.episodeIndex,
                        episodeTitle = episodeTitle,
                        chapterTitle = episode.chapterTitle,
                        contentHtml = contentHtml,
                        sourceUrl = episodeUrl,
                        prevUrl = prevUrl,
                        nextUrl = nextUrl
                    )
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    repository.markEpisodeCached(episode.id, path, now)
                    onEpisodeSaved?.invoke(episodeUrl)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onError?.invoke("saveEpisodeContent error: ${e.message}")
            }
        }
    }
}
