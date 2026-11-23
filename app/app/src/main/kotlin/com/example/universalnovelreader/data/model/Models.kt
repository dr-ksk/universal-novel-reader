package com.example.universalnovelreader.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SiteAdapter(
    val id: Long = 0,
    val adapterId: String,
    val name: String,
    val domainPattern: String,
    val adapterType: String = "yaml", // "yaml" または "javascript"
    val content: String,             // YAML定義 または JSスクリプト
    val customCss: String? = null,   // ドメイン専用カスタムCSS
    val version: String = "1.0.0",
    val isBuiltin: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class ReaderSettings(
    val id: Long = 0,
    val siteAdapterId: Long? = null, // null: 全体デフォルト設定, 非null: ドメイン別上書き設定
    val theme: String = "sepia",      // "white", "sepia", "dark", "black"
    val writingMode: String = "horizontal-tb", // 横書き標準
    val fontFamily: String = "sans-serif",    // "sans-serif" (ゴシック) または "serif" (明朝)
    val fontSize: Float = 18.0f,     // 文字サイズ(px)
    val lineHeight: Float = 1.85f,   // 行間比率
    val letterSpacing: Float = 0.05f,// 字間(em)
    val marginHorizontal: Float = 16.0f, // 左右余白(px)
    val marginVertical: Float = 24.0f,   // 上下余白(px)
    val customCss: String? = null    // カスタムCSS
)

@Serializable
data class Novel(
    val id: Long = 0,
    val siteAdapterId: Long?,
    val title: String,
    val author: String = "",
    val synopsis: String = "",
    val topUrl: String,
    val coverImagePath: String? = null,
    val lastEpisodeIndex: Int = 0,
    val lastReadEpisodeId: Long? = null,
    val lastReadAt: String? = null,
    val lastCheckedAt: String? = null,
    val createdAt: String = ""
)

@Serializable
data class Episode(
    val id: Long = 0,
    val novelId: Long,
    val episodeIndex: Int,
    val chapterTitle: String? = null,
    val title: String,
    val url: String,
    val isRead: Boolean = false,
    val scrollPosition: Float = 0.0f, // 読書進行率 (0.0f ~ 1.0f)
    val cachedAt: String? = null,
    val cacheFilePath: String? = null,
    val updatedAtSite: String? = null,
    val createdAt: String = ""
)

@Serializable
data class EpisodeCacheData(
    val novelId: Long,
    val episodeIndex: Int,
    val episodeTitle: String,
    val chapterTitle: String? = null,
    val contentHtml: String,
    val cachedAt: String,
    val sourceUrl: String,
    val prevUrl: String? = null,
    val nextUrl: String? = null,
    val images: List<String> = emptyList() // キャッシュされた画像ファイルパスのリスト
)

@Serializable
data class ParsedEpisodeSummary(
    val episodeIndex: Int,
    val title: String,
    val url: String,
    val chapter: String? = null,
    val updatedAt: String? = null,
    val contentHtml: String? = null // 同期時キャッシュ（仕様の穴）用
)

@Serializable
data class ParsedNovelIndex(
    val title: String,
    val author: String? = null,
    val synopsis: String? = null,
    val episodes: List<ParsedEpisodeSummary> = emptyList(),
    val nextPageUrl: String? = null
)
