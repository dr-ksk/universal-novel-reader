package com.example.universalnovelreader.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.Novel
import com.example.universalnovelreader.data.model.ReaderSettings
import com.example.universalnovelreader.data.model.SiteAdapter

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "universal_novel_reader.db"
        const val DATABASE_VERSION = 9

        const val TABLE_SITE_ADAPTERS = "site_adapters"
        const val TABLE_READER_SETTINGS = "reader_settings"
        const val TABLE_NOVELS = "novels"
        const val TABLE_EPISODES = "episodes"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SITE_ADAPTERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                adapter_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                domain_pattern TEXT NOT NULL,
                adapter_type TEXT NOT NULL DEFAULT 'yaml',
                content TEXT NOT NULL,
                custom_css TEXT,
                version TEXT NOT NULL,
                is_builtin INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_READER_SETTINGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site_adapter_id INTEGER UNIQUE,
                theme TEXT NOT NULL DEFAULT 'sepia',
                writing_mode TEXT NOT NULL DEFAULT 'horizontal-tb',
                font_family TEXT NOT NULL DEFAULT 'default',
                font_size REAL NOT NULL DEFAULT 18.0,
                line_height REAL NOT NULL DEFAULT 1.85,
                letter_spacing REAL NOT NULL DEFAULT 0.05,
                margin_horizontal REAL NOT NULL DEFAULT 20.0,
                margin_vertical REAL NOT NULL DEFAULT 24.0,
                custom_css TEXT,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(site_adapter_id) REFERENCES $TABLE_SITE_ADAPTERS(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_NOVELS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site_adapter_id INTEGER,
                title TEXT NOT NULL,
                author TEXT,
                synopsis TEXT,
                top_url TEXT NOT NULL UNIQUE,
                cover_image_path TEXT,
                last_episode_index INTEGER DEFAULT 0,
                last_read_episode_id INTEGER,
                last_read_at DATETIME,
                last_checked_at DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(site_adapter_id) REFERENCES $TABLE_SITE_ADAPTERS(id) ON DELETE RESTRICT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_EPISODES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                novel_id INTEGER NOT NULL,
                episode_index INTEGER NOT NULL,
                chapter_title TEXT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                is_read INTEGER DEFAULT 0,
                scroll_position REAL DEFAULT 0.0,
                cached_at DATETIME,
                cache_file_path TEXT,
                updated_at_site TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(novel_id) REFERENCES $TABLE_NOVELS(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_novels_top_url ON $TABLE_NOVELS(top_url)")
        db.execSQL("CREATE INDEX idx_episodes_novel_index ON $TABLE_EPISODES(novel_id, episode_index)")
        db.execSQL("CREATE INDEX idx_episodes_cached ON $TABLE_EPISODES(novel_id, cached_at)")

        // 組み込みアダプターおよび全体デフォルト設定の初期投入
        insertBuiltinAdapters(db)
        insertDefaultReaderSettings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            // リーダー表示はWeb小説向けに「横書き」を強制標準化
            val settingValues = ContentValues().apply {
                put("writing_mode", "horizontal-tb")
            }
            db.update(TABLE_READER_SETTINGS, settingValues, null, null)
        }
        if (oldVersion < 7) {
            // 組み込み「小説家になろう」アダプター定義を最新版へ更新
            val adapterValues = ContentValues().apply {
                put("domain_pattern", "^https?://(ncode|novel18)\\.syosetu\\.com/[a-z0-9]+(/.*)?$")
                put("content", getBuiltinYamlContent())
                put("version", "1.0.4")
            }
            db.update(TABLE_SITE_ADAPTERS, adapterValues, "adapter_id = ? AND is_builtin = 1", arrayOf("syosetu_standard"))
        }
        if (oldVersion < 9) {
            // 組み込みプリセットの定義を最新仕様へ同期（既存の本棚データは保持）
            val syosetuValues = ContentValues().apply {
                put("domain_pattern", "^https?://(ncode|novel18)\\.syosetu\\.com/[a-z0-9]+(/.*)?$")
                put("content", getBuiltinYamlContent())
                put("version", "1.0.5")
            }
            db.update(TABLE_SITE_ADAPTERS, syosetuValues, "adapter_id = ? AND is_builtin = 1", arrayOf("syosetu_standard"))
            upsertBuiltinKakuyomuAdapter(db)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun insertBuiltinAdapters(db: SQLiteDatabase) {
        val yamlContent = getBuiltinYamlContent()

        val values = ContentValues().apply {
            put("adapter_id", "syosetu_standard")
            put("name", "小説家になろう")
            put("domain_pattern", "^https?://(ncode|novel18)\\.syosetu\\.com/[a-z0-9]+(/.*)?$")
            put("adapter_type", "yaml")
            put("content", yamlContent)
            put("custom_css", "ruby rt { font-size: 0.52em; }")
            put("version", "1.0.5")
            put("is_builtin", 1)
        }
        db.insert(TABLE_SITE_ADAPTERS, null, values)

        upsertBuiltinKakuyomuAdapter(db)
    }

    private fun upsertBuiltinKakuyomuAdapter(db: SQLiteDatabase) {
        val values = ContentValues().apply {
            put("adapter_id", "kakuyomu_standard")
            put("name", "カクヨム")
            put("domain_pattern", "^https?://kakuyomu\\.jp/works/\\d+(/.*)?$")
            put("adapter_type", "yaml")
            put("content", getBuiltinKakuyomuYamlContent())
            put("custom_css", "ruby rt { font-size: 0.52em; }\n.blank { height: 1em; }")
            put("version", "1.0.1")
            put("is_builtin", 1)
        }
        val updated = db.update(TABLE_SITE_ADAPTERS, values, "adapter_id = ?", arrayOf("kakuyomu_standard"))
        if (updated == 0) {
            db.insert(TABLE_SITE_ADAPTERS, null, values)
        }
    }

    private fun insertDefaultReaderSettings(db: SQLiteDatabase) {
        val values = ContentValues().apply {
            putNull("site_adapter_id") // 全体デフォルト
            put("theme", "sepia")
            put("writing_mode", "horizontal-tb")
            put("font_family", "sans-serif")
            put("font_size", 18.0f)
            put("line_height", 1.85f)
            put("letter_spacing", 0.05f)
            put("margin_horizontal", 16.0f)
            put("margin_vertical", 24.0f)
            putNull("custom_css")
        }
        db.insert(TABLE_READER_SETTINGS, null, values)
    }

    private fun getBuiltinYamlContent(): String = """
metadata:
  id: "syosetu_standard"
  name: "小説家になろう"
  version: "1.0.5"
  domain_pattern: "^https?://(ncode|novel18)\\.syosetu\\.com/[a-z0-9]+(/.*)?$"

headers:
  User-Agent: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
  Cookie: "over18=yes"

index:
  title: ".novel_title, p.novel_title, h1.p-novel__title, h1"
  author:
    selector: ".novel_writername, .p-novel__author"
    regex_replace: ["^作者：\\s*", ""]
  synopsis: "#novel_ex, .p-novel__summary"

  episodes:
    container: ".p-eplist__sublist, .novel_sublist2"
    title: ".p-eplist__subtitle, dd.subtitle a, a"
    url: ".p-eplist__subtitle @href, dd.subtitle a @href, a @href"
    updated_at: ".p-eplist__update, dt.long_update"
    chapter: ".p-eplist__chapter-title, .chapter_title"

  next_page_url: ".c-pager__item--next, .novelview_pager-next a @href"

episode:
  title: ".p-novel__title, .novel_subtitle"
  content: ".js-novel-text, .p-novel__text, #novel_honbun, .p-novel__body"
  remove: "script, style, iframe, .ads, .novel_attention, .c-announce"
  images: ".js-novel-text img @src, .p-novel__body img @src, #novel_honbun img @src"

crawl:
  interval_ms: 1500
  cache_images: true

custom_css: |
  /* なろう専用ルビ余白調整 */
  ruby rt {
    font-size: 0.52em;
  }
""".trimIndent()

    private fun getBuiltinKakuyomuYamlContent(): String = """
metadata:
  id: "kakuyomu_standard"
  name: "カクヨム"
  version: "1.0.1"
  domain_pattern: "^https?://kakuyomu\\.jp/works/\\d+(/.*)?$"

headers:
  User-Agent: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

index:
  # カクヨムの目次ページは Next.js (__NEXT_DATA__) の JSON に全章・全話一覧が格納されています
  mode: "kakuyomu_next_data"
  script_selector: "script#__NEXT_DATA__"

episode:
  title: ".widget-episodeTitle, h1"
  content: ".widget-episodeBody"
  remove: "script, style, iframe, .ads"
  images: ".widget-episodeBody img @src"
  prev_page_url: "#contentMain-previousEpisode @href"
  next_page_url: "#contentMain-nextEpisode @href"

crawl:
  interval_ms: 1500
  cache_images: true
""".trimIndent()
}
