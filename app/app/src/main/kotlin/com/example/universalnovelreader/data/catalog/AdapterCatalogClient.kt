package com.example.universalnovelreader.data.catalog

import android.content.Context
import com.example.universalnovelreader.core.network.NativeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

data class CatalogEntry(
    val adapterId: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val domainPattern: String,
    val adapterType: String,
    val updatedAt: String?,
    val downloadUrl: String,
    val discussionUrl: String?,
    val likes: Int = 0,
    val reports: Int = 0,
    val comments: Int = 0
)

data class AdapterCatalog(
    val name: String,
    val channel: String,
    val updatedAt: String?,
    val adapters: List<CatalogEntry>
)

data class CustomCatalogSource(
    val name: String,
    val url: String
)

data class VoteResult(
    val adapterId: String,
    val voted: Boolean,
    val likes: Int,
    val reports: Int
)

class AdapterCatalogClient(
    private val context: Context,
    private val httpClient: NativeHttpClient
) {
    companion object {
        const val WEB_HUB_URL = "https://universal-novel-reader-catalog.pages.dev/"
        const val OFFICIAL_CATALOG_URL = "https://universal-novel-reader-catalog.pages.dev/official/index.json"
        const val COMMUNITY_CATALOG_URL = "https://universal-novel-reader-catalog.pages.dev/community/index.json"
        const val COMMUNITY_VOTE_API_URL = "https://universal-novel-reader-catalog.pages.dev/api/community/vote"

        private const val PREFS_NAME = "universal_novel_reader_catalog_prefs"
        private const val KEY_CUSTOM_SOURCES = "custom_catalog_sources"
        private const val KEY_CLIENT_ID = "catalog_client_id"
        private const val KEY_LIKED_ADAPTER_IDS = "liked_adapter_ids"
    }

    suspend fun fetchCatalog(catalogUrl: String): Result<AdapterCatalog> = withContext(Dispatchers.IO) {
        try {
            val sep = if (catalogUrl.contains("?")) "&" else "?"
            val raw = httpClient.getHtml("${catalogUrl}${sep}t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val arr = root.optJSONArray("adapters") ?: JSONArray()
            val list = mutableListOf<CatalogEntry>()
            val baseUri = runCatching { URI(catalogUrl) }.getOrNull()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("adapter_id").trim()
                val name = item.optString("name").trim()
                val rawDl = item.optString("download_url").trim()
                if (id.isBlank() || name.isBlank() || rawDl.isBlank()) continue
                val resolvedDl = if (rawDl.startsWith("http://") || rawDl.startsWith("https://")) {
                    rawDl
                } else {
                    runCatching { baseUri?.resolve(rawDl)?.toString() }.getOrNull() ?: rawDl
                }
                list.add(
                    CatalogEntry(
                        adapterId = id,
                        name = name,
                        version = item.optString("version", "1.0.0").trim(),
                        author = item.optString("author", "").trim(),
                        description = item.optString("description", "").trim(),
                        domainPattern = item.optString("domain_pattern", "").trim(),
                        adapterType = item.optString("adapter_type", "yaml").trim().lowercase(),
                        updatedAt = if (item.isNull("updated_at")) null else item.optString("updated_at").takeIf { it.isNotBlank() },
                        downloadUrl = resolvedDl,
                        discussionUrl = if (item.isNull("discussion_url")) null else item.optString("discussion_url").takeIf { it.isNotBlank() },
                        likes = item.optInt("likes", 0),
                        reports = item.optInt("reports", 0),
                        comments = item.optInt("comments", 0)
                    )
                )
            }
            Result.success(
                AdapterCatalog(
                    name = root.optString("name", "カタログ"),
                    channel = root.optString("channel", "custom"),
                    updatedAt = if (root.isNull("updated_at")) null else root.optString("updated_at").takeIf { it.isNotBlank() },
                    adapters = list
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun fetchAdapterPackage(downloadUrl: String): Result<AdapterPackage> = withContext(Dispatchers.IO) {
        try {
            val sep = if (downloadUrl.contains("?")) "&" else "?"
            val raw = httpClient.getHtml("${downloadUrl}${sep}t=${System.currentTimeMillis()}")
            val pkg = AdapterPackageCodec.decodeFromText(raw)
            Result.success(pkg)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun toggleCommunityLike(adapterId: String): Result<VoteResult> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val payload = JSONObject().apply {
                put("adapter_id", adapterId)
                put("vote_type", "like")
                put("client_id", getOrCreateClientId())
            }.toString()

            conn = (URL(COMMUNITY_VOTE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(body) }.getOrNull()

            if (code !in 200..299 || json == null || !json.optBoolean("ok", false)) {
                val msg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val voted = json.optBoolean("voted", false)
            updateLocalLikedState(adapterId, voted)

            Result.success(
                VoteResult(
                    adapterId = adapterId,
                    voted = voted,
                    likes = json.optInt("likes", 0),
                    reports = json.optInt("reports", 0)
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun loadLikedAdapterIds(): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LIKED_ADAPTER_IDS, emptySet())?.toSet() ?: emptySet()
    }

    private fun updateLocalLikedState(adapterId: String, liked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = loadLikedAdapterIds().toMutableSet()
        if (liked) updated.add(adapterId) else updated.remove(adapterId)
        prefs.edit().putStringSet(KEY_LIKED_ADAPTER_IDS, updated).apply()
    }

    private fun getOrCreateClientId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_CLIENT_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_CLIENT_ID, created).apply()
        return created
    }

    fun loadCustomSources(): List<CustomCatalogSource> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_SOURCES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<CustomCatalogSource>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val name = obj.optString("name").trim().ifBlank { url }
                if (url.isNotBlank()) {
                    result.add(CustomCatalogSource(name = name, url = url))
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addCustomSource(name: String, url: String): List<CustomCatalogSource> {
        val current = loadCustomSources().filterNot { it.url == url.trim() }
        val updated = current + CustomCatalogSource(name = name.trim().ifBlank { url.trim() }, url = url.trim())
        saveCustomSources(updated)
        return updated
    }

    fun removeCustomSource(url: String): List<CustomCatalogSource> {
        val updated = loadCustomSources().filterNot { it.url == url }
        saveCustomSources(updated)
        return updated
    }

    private fun saveCustomSources(list: List<CustomCatalogSource>) {
        val arr = JSONArray()
        for (src in list) {
            arr.put(JSONObject().apply {
                put("name", src.name)
                put("url", src.url)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_SOURCES, arr.toString())
            .apply()
    }
}
