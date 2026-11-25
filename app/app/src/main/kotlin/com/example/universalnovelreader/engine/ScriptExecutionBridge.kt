package com.example.universalnovelreader.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.universalnovelreader.core.network.NativeHttpClient
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.storage.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class ScriptExecutionBridge(
    private val context: Context,
    private val repository: NovelRepository,
    private val cacheManager: CacheManager,
    private val httpClient: NativeHttpClient
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * 目次（小説トップ）ページをロードし、スクリプトの parseIndex を実行する
     */
    suspend fun executeIndexScript(url: String, scriptContent: String): Result<Unit> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            fun cleanup() {
                Handler(Looper.getMainLooper()).post {
                    webView?.destroy()
                    webView = null
                }
            }

            createHeadlessWebView(
                onIndexSaved = { savedUrl ->
                    if (continuation.isActive) {
                        continuation.resume(Result.success(Unit))
                        cleanup()
                    }
                },
                onError = { err ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(Exception(err)))
                        cleanup()
                    }
                }
            ) { wv ->
                webView = wv
                var executed = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        if (executed) return
                        executed = true
                        val targetUrlJs = JSONObject.quote(url)
                        val jsCode = """
                            (async function() {
                                try {
                                    $scriptContent
                                    if (typeof SiteAdapter !== 'undefined' && SiteAdapter.parseIndex) {
                                        await SiteAdapter.parseIndex(document.documentElement.outerHTML, $targetUrlJs);
                                    }
                                } catch(e) {
                                    console.error("parseIndex error: " + e.message);
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsCode, null)
                    }
                }
                wv.loadUrl(url)
            }
        }
    }

    /**
     * 各話本文ページをロードし、スクリプトの parseEpisode を実行してキャッシュ保存させる
     */
    suspend fun executeEpisodeScript(episodeUrl: String, scriptContent: String): Result<Unit> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            fun cleanup() {
                Handler(Looper.getMainLooper()).post {
                    webView?.destroy()
                    webView = null
                }
            }

            createHeadlessWebView(
                onEpisodeSaved = { savedEpisodeUrl ->
                    if (continuation.isActive) {
                        continuation.resume(Result.success(Unit))
                        cleanup()
                    }
                },
                onError = { err ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(Exception(err)))
                        cleanup()
                    }
                }
            ) { wv ->
                webView = wv
                var executed = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        if (executed) return
                        executed = true
                        val targetEpUrlJs = JSONObject.quote(episodeUrl)
                        val jsCode = """
                            (async function() {
                                try {
                                    $scriptContent
                                    if (typeof SiteAdapter !== 'undefined' && SiteAdapter.parseEpisode) {
                                        await SiteAdapter.parseEpisode(document.documentElement.outerHTML, $targetEpUrlJs);
                                    }
                                } catch(e) {
                                    console.error("parseEpisode error: " + e.message);
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsCode, null)
                    }
                }
                wv.loadUrl(episodeUrl)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createHeadlessWebView(
        onIndexSaved: ((String) -> Unit)? = null,
        onEpisodeSaved: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onReady: (WebView) -> Unit
    ) {
        val webView = WebView(context)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val bridge = AppBridge(
            repository = repository,
            cacheManager = cacheManager,
            httpClient = httpClient,
            scope = scope,
            onIndexSaved = onIndexSaved,
            onEpisodeSaved = onEpisodeSaved,
            onError = onError
        )
        webView.addJavascriptInterface(bridge, "AppBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("HeadlessJS", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        onReady(webView)
    }
}
