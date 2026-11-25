package com.example.universalnovelreader.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpStatusException(
    val statusCode: Int,
    val url: String
) : IOException("HTTP error code: $statusCode for URL: $url") {
    fun isRateLimitOrOverloaded(): Boolean {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504
    }
}

class NativeHttpClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun getHtml(url: String, customHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)

        // デフォルトヘッダー
        val defaultHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ja,en-US;q=0.9,en;q=0.8"
        )
        // カスタムヘッダー（Cookieやサイト別UA）を上書き・合成
        defaultHeaders.putAll(customHeaders)

        val headersBuilder = Headers.Builder()
        for ((k, v) in defaultHeaders) {
            headersBuilder.add(k, v)
        }
        requestBuilder.headers(headersBuilder.build())

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, url)
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun getBytes(url: String, customHeaders: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        for ((k, v) in customHeaders) {
            requestBuilder.addHeader(k, v)
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, url)
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }
}

