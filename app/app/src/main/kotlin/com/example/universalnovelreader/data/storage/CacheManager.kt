package com.example.universalnovelreader.data.storage

import android.content.Context
import com.example.universalnovelreader.data.model.EpisodeCacheData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class CacheManager(private val context: Context) {

    private val baseDir: File
        get() = File(context.filesDir, "novels").apply { if (!exists()) mkdirs() }

    suspend fun saveEpisode(
        novelId: Long,
        episodeIndex: Int,
        episodeTitle: String,
        chapterTitle: String?,
        contentHtml: String,
        sourceUrl: String,
        prevUrl: String? = null,
        nextUrl: String? = null,
        images: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val novelDir = File(baseDir, novelId.toString()).apply { if (!exists()) mkdirs() }
        val episodesDir = File(novelDir, "episodes").apply { if (!exists()) mkdirs() }
        val file = File(episodesDir, "$episodeIndex.json")

        val json = JSONObject().apply {
            put("novelId", novelId)
            put("episodeIndex", episodeIndex)
            put("episodeTitle", episodeTitle)
            put("chapterTitle", chapterTitle ?: "")
            put("contentHtml", contentHtml)
            put("cachedAt", System.currentTimeMillis().toString())
            put("sourceUrl", sourceUrl)
            put("prevUrl", prevUrl ?: "")
            put("nextUrl", nextUrl ?: "")
            put("images", JSONArray(images))
        }

        file.writeText(json.toString(), Charsets.UTF_8)
        "novels/$novelId/episodes/$episodeIndex.json"
    }

    suspend fun getEpisode(novelId: Long, episodeIndex: Int): EpisodeCacheData? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$novelId/episodes/$episodeIndex.json")
        if (!file.exists()) return@withContext null

        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val imagesList = mutableListOf<String>()
            val imagesArray = json.optJSONArray("images")
            if (imagesArray != null) {
                for (i in 0 until imagesArray.length()) {
                    imagesList.add(imagesArray.getString(i))
                }
            }

            EpisodeCacheData(
                novelId = json.getLong("novelId"),
                episodeIndex = json.getInt("episodeIndex"),
                episodeTitle = json.getString("episodeTitle"),
                chapterTitle = json.optString("chapterTitle").takeIf { it.isNotEmpty() },
                contentHtml = json.getString("contentHtml"),
                cachedAt = json.optString("cachedAt"),
                sourceUrl = json.getString("sourceUrl"),
                prevUrl = json.optString("prevUrl").takeIf { it.isNotEmpty() },
                nextUrl = json.optString("nextUrl").takeIf { it.isNotEmpty() },
                images = imagesList
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveImage(novelId: Long, imageUrl: String, imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val novelDir = File(baseDir, novelId.toString()).apply { if (!exists()) mkdirs() }
        val imagesDir = File(novelDir, "episodes/images").apply { if (!exists()) mkdirs() }
        
        val ext = when {
            imageUrl.endsWith(".png", ignoreCase = true) -> "png"
            imageUrl.endsWith(".gif", ignoreCase = true) -> "gif"
            imageUrl.endsWith(".webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val hash = md5(imageUrl)
        val file = File(imagesDir, "$hash.$ext")
        file.writeBytes(imageBytes)
        file.absolutePath
    }

    suspend fun deleteEpisodeCache(novelId: Long, episodeIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$novelId/episodes/$episodeIndex.json")
        if (file.exists()) file.delete() else true
    }

    suspend fun clearNovelCache(novelId: Long): Boolean = withContext(Dispatchers.IO) {
        val novelDir = File(baseDir, novelId.toString())
        if (novelDir.exists()) novelDir.deleteRecursively() else true
    }

    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        if (baseDir.exists()) baseDir.deleteRecursively() else true
    }

    suspend fun getCacheSizeFormatted(): String = withContext(Dispatchers.IO) {
        val totalBytes = calculateDirSize(baseDir)
        formatSize(totalBytes)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
