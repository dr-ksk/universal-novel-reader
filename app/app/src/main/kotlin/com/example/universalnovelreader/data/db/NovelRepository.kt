package com.example.universalnovelreader.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.Novel
import com.example.universalnovelreader.data.model.ReaderSettings
import com.example.universalnovelreader.data.model.SiteAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class NovelRepository(context: Context) {
    private val dbHelper = DatabaseHelper(context)

    // --- Site Adapters ---

    suspend fun getAllAdapters(): List<SiteAdapter> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<SiteAdapter>()
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_SITE_ADAPTERS} ORDER BY id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToAdapter(cursor))
            }
        }
        list
    }

    suspend fun getAdapterById(id: Long): SiteAdapter? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_SITE_ADAPTERS} WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursorToAdapter(cursor) else null
        }
    }

    suspend fun findAdapterForUrl(url: String): SiteAdapter? = withContext(Dispatchers.IO) {
        val adapters = getAllAdapters()
        adapters.firstOrNull { adapter ->
            try {
                Pattern.compile(adapter.domainPattern).matcher(url).find()
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun insertOrUpdateAdapter(adapter: SiteAdapter): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("adapter_id", adapter.adapterId)
            put("name", adapter.name)
            put("domain_pattern", adapter.domainPattern)
            put("adapter_type", adapter.adapterType)
            put("content", adapter.content)
            put("custom_css", adapter.customCss)
            put("version", adapter.version)
            put("is_builtin", if (adapter.isBuiltin) 1 else 0)
        }
        // 既存レコードは UPDATE して外部キー (novels.site_adapter_id ON DELETE RESTRICT) の衝突を回避
        if (adapter.id > 0) {
            val updated = db.update(
                DatabaseHelper.TABLE_SITE_ADAPTERS,
                values,
                "id = ?",
                arrayOf(adapter.id.toString())
            )
            if (updated > 0) return@withContext adapter.id
        }
        val updatedByKey = db.update(
            DatabaseHelper.TABLE_SITE_ADAPTERS,
            values,
            "adapter_id = ?",
            arrayOf(adapter.adapterId)
        )
        if (updatedByKey > 0) {
            return@withContext adapter.id
        }
        db.insert(DatabaseHelper.TABLE_SITE_ADAPTERS, null, values)
    }

    suspend fun deleteAdapter(id: Long): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DatabaseHelper.TABLE_SITE_ADAPTERS, "id = ? AND is_builtin = 0", arrayOf(id.toString())) > 0
    }

    // --- Reader Settings (全体デフォルト ＆ ドメイン別上書き) ---

    suspend fun getEffectiveReaderSettings(siteAdapterId: Long?): ReaderSettings = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val defaultSettings = getDefaultReaderSettingsInternal(db)

        if (siteAdapterId == null) {
            return@withContext defaultSettings
        }

        // ドメイン別個別設定を検索
        db.rawQuery(
            "SELECT * FROM ${DatabaseHelper.TABLE_READER_SETTINGS} WHERE site_adapter_id = ?",
            arrayOf(siteAdapterId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val siteSettings = cursorToReaderSettings(cursor)
                // ドメイン固有の設定があれば上書き、未指定ならデフォルト値を継承
                return@withContext ReaderSettings(
                    id = siteSettings.id,
                    siteAdapterId = siteAdapterId,
                    theme = siteSettings.theme.ifEmpty { defaultSettings.theme },
                    writingMode = siteSettings.writingMode.ifEmpty { defaultSettings.writingMode },
                    fontFamily = if (siteSettings.fontFamily != "default") siteSettings.fontFamily else defaultSettings.fontFamily,
                    fontSize = if (siteSettings.fontSize > 0) siteSettings.fontSize else defaultSettings.fontSize,
                    lineHeight = if (siteSettings.lineHeight > 0) siteSettings.lineHeight else defaultSettings.lineHeight,
                    letterSpacing = siteSettings.letterSpacing,
                    marginHorizontal = siteSettings.marginHorizontal,
                    marginVertical = siteSettings.marginVertical,
                    customCss = siteSettings.customCss ?: defaultSettings.customCss
                )
            }
        }
        defaultSettings
    }

    suspend fun getDefaultReaderSettings(): ReaderSettings = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        getDefaultReaderSettingsInternal(db)
    }

    private fun getDefaultReaderSettingsInternal(db: android.database.sqlite.SQLiteDatabase): ReaderSettings {
        db.rawQuery(
            "SELECT * FROM ${DatabaseHelper.TABLE_READER_SETTINGS} WHERE site_adapter_id IS NULL",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursorToReaderSettings(cursor)
            }
        }
        return ReaderSettings()
    }

    suspend fun saveReaderSettings(settings: ReaderSettings): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (settings.siteAdapterId != null) {
                put("site_adapter_id", settings.siteAdapterId)
            } else {
                putNull("site_adapter_id")
            }
            put("theme", settings.theme)
            put("writing_mode", settings.writingMode)
            put("font_family", settings.fontFamily)
            put("font_size", settings.fontSize)
            put("line_height", settings.lineHeight)
            put("letter_spacing", settings.letterSpacing)
            put("margin_horizontal", settings.marginHorizontal)
            put("margin_vertical", settings.marginVertical)
            put("custom_css", settings.customCss)
        }

        if (settings.siteAdapterId != null) {
            db.insertWithOnConflict(
                DatabaseHelper.TABLE_READER_SETTINGS,
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        } else {
            // デフォルト設定の更新
            val updated = db.update(DatabaseHelper.TABLE_READER_SETTINGS, values, "site_adapter_id IS NULL", null)
            if (updated <= 0) {
                db.insert(DatabaseHelper.TABLE_READER_SETTINGS, null, values)
            } else {
                1L
            }
        }
    }

    // --- Novels ---

    suspend fun getAllNovels(): List<Novel> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<Novel>()
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_NOVELS} ORDER BY id DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToNovel(cursor))
            }
        }
        list
    }

    suspend fun getNovelById(id: Long): Novel? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_NOVELS} WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursorToNovel(cursor) else null
        }
    }

    suspend fun getNovelByUrl(url: String): Novel? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_NOVELS} WHERE top_url = ?", arrayOf(url)).use { cursor ->
            if (cursor.moveToFirst()) cursorToNovel(cursor) else null
        }
    }

    suspend fun insertNovel(novel: Novel): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (novel.siteAdapterId != null) put("site_adapter_id", novel.siteAdapterId)
            put("title", novel.title)
            put("author", novel.author)
            put("synopsis", novel.synopsis)
            put("top_url", novel.topUrl)
            put("last_episode_index", novel.lastEpisodeIndex)
        }
        db.insertWithOnConflict(
            DatabaseHelper.TABLE_NOVELS,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    suspend fun updateNovel(novel: Novel): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (novel.siteAdapterId != null) put("site_adapter_id", novel.siteAdapterId)
            put("title", novel.title)
            put("author", novel.author)
            put("synopsis", novel.synopsis)
            put("last_episode_index", novel.lastEpisodeIndex)
            put("last_checked_at", novel.lastCheckedAt)
        }
        db.update(DatabaseHelper.TABLE_NOVELS, values, "id = ?", arrayOf(novel.id.toString()))
    }

    suspend fun updateNovelLastRead(novelId: Long, episodeId: Long): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("last_read_episode_id", episodeId)
            put("last_read_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
        }
        db.update(DatabaseHelper.TABLE_NOVELS, values, "id = ?", arrayOf(novelId.toString()))
    }

    suspend fun deleteNovel(id: Long): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DatabaseHelper.TABLE_NOVELS, "id = ?", arrayOf(id.toString()))
    }

    // --- Episodes ---

    suspend fun getEpisodesForNovel(novelId: Long): List<Episode> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<Episode>()
        db.rawQuery(
            "SELECT * FROM ${DatabaseHelper.TABLE_EPISODES} WHERE novel_id = ? ORDER BY episode_index ASC",
            arrayOf(novelId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEpisode(cursor))
            }
        }
        list
    }

    suspend fun getEpisodeById(id: Long): Episode? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_EPISODES} WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursorToEpisode(cursor) else null
        }
    }

    suspend fun getEpisodeByUrl(url: String): Episode? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM ${DatabaseHelper.TABLE_EPISODES} WHERE url = ?", arrayOf(url)).use { cursor ->
            if (cursor.moveToFirst()) cursorToEpisode(cursor) else null
        }
    }

    suspend fun insertOrUpdateEpisode(episode: Episode): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("novel_id", episode.novelId)
            put("episode_index", episode.episodeIndex)
            put("chapter_title", episode.chapterTitle)
            put("title", episode.title)
            put("url", episode.url)
            put("is_read", if (episode.isRead) 1 else 0)
            put("scroll_position", episode.scrollPosition)
            if (episode.cachedAt != null) put("cached_at", episode.cachedAt)
            if (episode.cacheFilePath != null) put("cache_file_path", episode.cacheFilePath)
            if (episode.updatedAtSite != null) put("updated_at_site", episode.updatedAtSite)
        }
        db.insertWithOnConflict(
            DatabaseHelper.TABLE_EPISODES,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun updateEpisodeScrollAndRead(episodeId: Long, scrollPosition: Float, isRead: Boolean): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("scroll_position", scrollPosition)
            put("is_read", if (isRead) 1 else 0)
        }
        db.update(DatabaseHelper.TABLE_EPISODES, values, "id = ?", arrayOf(episodeId.toString()))
    }

    suspend fun markEpisodeCached(episodeId: Long, cachePath: String, cachedAt: String): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("cached_at", cachedAt)
            put("cache_file_path", cachePath)
        }
        db.update(DatabaseHelper.TABLE_EPISODES, values, "id = ?", arrayOf(episodeId.toString()))
    }

    // --- Cursor Mappers ---

    private fun cursorToAdapter(c: Cursor): SiteAdapter = SiteAdapter(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        adapterId = c.getString(c.getColumnIndexOrThrow("adapter_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        domainPattern = c.getString(c.getColumnIndexOrThrow("domain_pattern")),
        adapterType = c.getString(c.getColumnIndexOrThrow("adapter_type")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        customCss = if (!c.isNull(c.getColumnIndexOrThrow("custom_css"))) c.getString(c.getColumnIndexOrThrow("custom_css")) else null,
        version = c.getString(c.getColumnIndexOrThrow("version")),
        isBuiltin = c.getInt(c.getColumnIndexOrThrow("is_builtin")) == 1,
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun cursorToReaderSettings(c: Cursor): ReaderSettings = ReaderSettings(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        siteAdapterId = if (!c.isNull(c.getColumnIndexOrThrow("site_adapter_id"))) c.getLong(c.getColumnIndexOrThrow("site_adapter_id")) else null,
        theme = c.getString(c.getColumnIndexOrThrow("theme")),
        writingMode = c.getString(c.getColumnIndexOrThrow("writing_mode")),
        fontFamily = c.getString(c.getColumnIndexOrThrow("font_family")),
        fontSize = c.getFloat(c.getColumnIndexOrThrow("font_size")),
        lineHeight = c.getFloat(c.getColumnIndexOrThrow("line_height")),
        letterSpacing = c.getFloat(c.getColumnIndexOrThrow("letter_spacing")),
        marginHorizontal = c.getFloat(c.getColumnIndexOrThrow("margin_horizontal")),
        marginVertical = c.getFloat(c.getColumnIndexOrThrow("margin_vertical")),
        customCss = if (!c.isNull(c.getColumnIndexOrThrow("custom_css"))) c.getString(c.getColumnIndexOrThrow("custom_css")) else null
    )

    private fun cursorToNovel(c: Cursor): Novel = Novel(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        siteAdapterId = if (!c.isNull(c.getColumnIndexOrThrow("site_adapter_id"))) c.getLong(c.getColumnIndexOrThrow("site_adapter_id")) else null,
        title = c.getString(c.getColumnIndexOrThrow("title")),
        author = c.getString(c.getColumnIndexOrThrow("author")) ?: "",
        synopsis = c.getString(c.getColumnIndexOrThrow("synopsis")) ?: "",
        topUrl = c.getString(c.getColumnIndexOrThrow("top_url")),
        coverImagePath = c.getString(c.getColumnIndexOrThrow("cover_image_path")),
        lastEpisodeIndex = c.getInt(c.getColumnIndexOrThrow("last_episode_index")),
        lastReadEpisodeId = if (!c.isNull(c.getColumnIndexOrThrow("last_read_episode_id"))) c.getLong(c.getColumnIndexOrThrow("last_read_episode_id")) else null,
        lastReadAt = c.getString(c.getColumnIndexOrThrow("last_read_at")),
        lastCheckedAt = c.getString(c.getColumnIndexOrThrow("last_checked_at")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
    )

    private fun cursorToEpisode(c: Cursor): Episode = Episode(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        novelId = c.getLong(c.getColumnIndexOrThrow("novel_id")),
        episodeIndex = c.getInt(c.getColumnIndexOrThrow("episode_index")),
        chapterTitle = c.getString(c.getColumnIndexOrThrow("chapter_title")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        isRead = c.getInt(c.getColumnIndexOrThrow("is_read")) == 1,
        scrollPosition = c.getFloat(c.getColumnIndexOrThrow("scroll_position")),
        cachedAt = c.getString(c.getColumnIndexOrThrow("cached_at")),
        cacheFilePath = c.getString(c.getColumnIndexOrThrow("cache_file_path")),
        updatedAtSite = c.getString(c.getColumnIndexOrThrow("updated_at_site")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
    )
}
