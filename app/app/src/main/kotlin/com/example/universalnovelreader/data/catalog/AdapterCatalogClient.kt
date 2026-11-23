package com.example.universalnovelreader.data.catalog

import android.content.Context
import com.example.universalnovelreader.core.network.NativeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    val discussionUrl: String?
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

class AdapterCatalogClient(
    private val context: Context,
    private val httpClient: NativeHttpClient
) {
    companion object {
        const val WEB_HUB_URL = "https://dr-ksk.github.io/universal-novel-reader-catalog/"
        const val OFFICIAL_CATALOG_URL = "https://dr-ksk.github.io/universal-novel-reader-catalog/official/index.json"
        const val COMMUNITY_CATALOG_URL = "https://dr-ksk.github.io/universal-novel-reader-catalog/community/index.json"
        const val COMMUNITY_ISSUES_URL = "https://github.com/dr-ksk/universal-novel-reader-catalog/issues"

        private const val PREFS_NAME = "universal_novel_reader_catalog_prefs"
        private const val KEY_CUSTOM_SOURCES = "custom_catalog_sources"
    }

    suspend fun fetchCatalog(catalogUrl: String): Result<AdapterCatalog> = withContext(Dispatchers.IO) {
        try {
            val sep = if (catalogUrl.contains("?")) "&" else "?"
            val raw = httpClient.getHtml("${catalogUrl}${sep}t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val arr = root.optJSONArray("adapters") ?: JSONArray()
            val list = mutableListOf<CatalogEntry>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("adapter_id").trim()
                val name = item.optString("name").trim()
                val dl = item.optString("download_url").trim()
                if (id.isBlank() || name.isBlank() || dl.isBlank()) continue
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
                        downloadUrl = dl,
                        discussionUrl = if (item.isNull("discussion_url")) null else item.optString("discussion_url").takeIf { it.isNotBlank() }
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
