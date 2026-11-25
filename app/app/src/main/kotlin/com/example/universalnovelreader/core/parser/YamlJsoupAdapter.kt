package com.example.universalnovelreader.core.parser

import com.example.universalnovelreader.data.model.ParsedEpisodeSummary
import com.example.universalnovelreader.data.model.ParsedNovelIndex
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.yaml.snakeyaml.Yaml
import java.net.URI

interface ISiteAdapter {
    val adapterId: String
    val name: String
    val customHeaders: Map<String, String>
    val cacheImages: Boolean
    val crawlIntervalMs: Long

    suspend fun parseIndex(html: String, baseUri: String): ParsedNovelIndex
    suspend fun parseEpisode(html: String, baseUri: String): ParsedEpisodeContent
}

data class ParsedEpisodeContent(
    val title: String,
    val contentHtml: String,
    val imageUrls: List<String> = emptyList(),
    val prevUrl: String? = null,
    val nextUrl: String? = null
)

class YamlJsoupAdapter(
    yamlContent: String
) : ISiteAdapter {

    private val yamlConfig: Map<String, Any> = run {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        yaml.load<Map<String, Any>>(yamlContent) ?: emptyMap()
    }

    override val adapterId: String
        get() = getMetadataString("id") ?: "yaml_adapter"

    override val name: String
        get() = getMetadataString("name") ?: "YAML Adapter"

    override val customHeaders: Map<String, String>
        get() {
            @Suppress("UNCHECKED_CAST")
            val headersMap = yamlConfig["headers"] as? Map<String, Any> ?: emptyMap()
            return headersMap.mapValues { it.value.toString() }
        }

    override val cacheImages: Boolean
        get() {
            val crawl = yamlConfig["crawl"] as? Map<*, *>
            return (crawl?.get("cache_images") as? Boolean) ?: true
        }

    override val crawlIntervalMs: Long
        get() {
            val crawl = yamlConfig["crawl"] as? Map<*, *>
            val raw = (crawl?.get("interval_ms") as? Number)?.toLong() ?: 1500L
            return raw.coerceAtLeast(1000L) // サーバー保護のため最低1秒間隔を保証
        }

    private fun getMetadataString(key: String): String? {
        val metadata = yamlConfig["metadata"] as? Map<*, *>
        return metadata?.get(key)?.toString()
    }

    override suspend fun parseIndex(html: String, baseUri: String): ParsedNovelIndex {
        val doc = Jsoup.parse(html, baseUri)
        val indexConfig = yamlConfig["index"] as? Map<*, *> ?: emptyMap<String, Any>()

        // Next.js (__NEXT_DATA__ / Apollo State) 形式のサイト（カクヨム等）向け専用パーサー
        val mode = indexConfig["mode"]?.toString()?.trim()
        if (mode == "kakuyomu_next_data") {
            return parseKakuyomuNextDataIndex(doc, baseUri, indexConfig)
        }

        val title = extractText(doc, indexConfig["title"]) ?: doc.title() ?: "無題"
        val author = extractText(doc, indexConfig["author"])
        val synopsis = extractText(doc, indexConfig["synopsis"])
        val nextPageUrl = extractAttr(doc, indexConfig["next_page_url"], "abs:href")

        val episodes = mutableListOf<ParsedEpisodeSummary>()
        val episodesConfig = indexConfig["episodes"] as? Map<*, *>
        if (episodesConfig != null) {
            val containerSelector = episodesConfig["container"]?.toString() ?: ""
            val titleSelector = episodesConfig["title"]?.toString() ?: "a"
            val urlSelector = episodesConfig["url"]?.toString() ?: "a @href"
            val chapterSelector = episodesConfig["chapter"]?.toString()
            val updateSelector = episodesConfig["updated_at"]?.toString()

            val containers = if (containerSelector.isNotEmpty()) doc.select(containerSelector) else doc.select("body")
            var epIndex = 1
            var currentChapter: String? = null

            for (element in containers) {
                val epTitle = extractText(element, titleSelector) ?: "第${epIndex}話"
                val epUrl = extractAttr(element, urlSelector, "abs:href") ?: ""
                
                // 章タイトルの抽出（要素内または直前の兄弟要素から検索）
                var chapter: String? = null
                if (chapterSelector != null) {
                    val inElement = extractText(element, chapterSelector)
                    if (inElement != null) {
                        chapter = inElement
                        currentChapter = inElement
                    } else {
                        // 兄弟要素を上に辿って直近の章タイトルを取得
                        val prevChapter = element.previousElementSiblings().firstOrNull { sib ->
                            sib.selectFirst(chapterSelector) != null || sib.`is`(chapterSelector)
                        }
                        if (prevChapter != null) {
                            chapter = if (prevChapter.`is`(chapterSelector)) prevChapter.text().trim() else prevChapter.selectFirst(chapterSelector)?.text()?.trim()
                            currentChapter = chapter
                        } else {
                            chapter = currentChapter
                        }
                    }
                }
                
                val updatedAt = if (updateSelector != null) extractText(element, updateSelector) else null

                if (epUrl.isNotEmpty()) {
                    episodes.add(
                        ParsedEpisodeSummary(
                            episodeIndex = epIndex++,
                            title = epTitle,
                            url = resolveUrl(baseUri, epUrl),
                            chapter = chapter,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        }

        return ParsedNovelIndex(
            title = title,
            author = author,
            synopsis = synopsis,
            episodes = episodes,
            nextPageUrl = nextPageUrl?.let { resolveUrl(baseUri, it) }
        )
    }

    override suspend fun parseEpisode(html: String, baseUri: String): ParsedEpisodeContent {
        val doc = Jsoup.parse(html, baseUri)
        val epConfig = yamlConfig["episode"] as? Map<*, *> ?: emptyMap<String, Any>()

        val title = extractText(doc, epConfig["title"]) ?: doc.title() ?: ""

        // 不要なタグの削除
        val removeSelector = epConfig["remove"]?.toString()
        if (!removeSelector.isNullOrEmpty()) {
            doc.select(removeSelector).remove()
        }

        // 本文の抽出
        val contentSelector = epConfig["content"]?.toString() ?: "body"
        val contentElement = doc.selectFirst(contentSelector) ?: doc.body()

        // 挿絵画像URLの抽出
        val imageUrls = mutableListOf<String>()
        val imagesSelector = epConfig["images"]?.toString()
        if (!imagesSelector.isNullOrEmpty()) {
            val candidates = imagesSelector.split(",")
            for (candidate in candidates) {
                val trimmed = candidate.trim()
                if (trimmed.isEmpty()) continue
                val (sel, attr) = if (trimmed.contains("@")) {
                    val p = trimmed.split("@")
                    p[0].trim() to p[1].trim()
                } else {
                    trimmed to "src"
                }
                for (img in contentElement.select(sel)) {
                    val src = img.absUrl(attr).ifEmpty { img.attr(attr) }
                    if (src.isNotEmpty() && !imageUrls.contains(src)) {
                        imageUrls.add(src)
                    }
                }
            }
        }

        val prevUrl = epConfig["prev_page_url"]?.let { extractAttr(doc, it, "abs:href") }
        val nextUrl = epConfig["next_page_url"]?.let { extractAttr(doc, it, "abs:href") }

        return ParsedEpisodeContent(
            title = title,
            contentHtml = contentElement.html(),
            imageUrls = imageUrls,
            prevUrl = prevUrl?.let { resolveUrl(baseUri, it) },
            nextUrl = nextUrl?.let { resolveUrl(baseUri, it) }
        )
    }

    private fun extractText(context: Element, rule: Any?): String? {
        if (rule == null) return null
        if (rule is String) {
            val target = context.selectFirst(rule) ?: return null
            return target.text().trim()
        }
        if (rule is Map<*, *>) {
            val selector = rule["selector"]?.toString() ?: return null
            val target = context.selectFirst(selector) ?: return null
            var text = target.text().trim()
            val regexReplace = rule["regex_replace"] as? List<*>
            if (regexReplace != null && regexReplace.size >= 2) {
                val pattern = regexReplace[0].toString()
                val replacement = regexReplace[1].toString()
                text = text.replace(Regex(pattern), replacement).trim()
            }
            return text
        }
        return null
    }

    private fun extractAttr(context: Element, rule: Any?, defaultAttr: String): String? {
        if (rule == null) return null
        val ruleStr = rule.toString().trim()
        val candidates = ruleStr.split(",")
        for (candidate in candidates) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue
            val (selector, attr) = if (trimmed.contains("@")) {
                val parts = trimmed.split("@")
                parts[0].trim() to parts[1].trim()
            } else {
                trimmed to defaultAttr
            }
            val target = context.selectFirst(selector) ?: continue
            val value = if (attr.startsWith("abs:")) {
                target.absUrl(attr.substringAfter("abs:"))
            } else {
                target.attr(attr)
            }
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    private fun parseKakuyomuNextDataIndex(
        doc: Document,
        baseUri: String,
        indexConfig: Map<*, *>
    ): ParsedNovelIndex {
        val scriptSelector = indexConfig["script_selector"]?.toString()?.ifBlank { null } ?: "script#__NEXT_DATA__"
        val scriptEl = doc.selectFirst(scriptSelector)
            ?: throw IllegalStateException("Next.jsデータ要素($scriptSelector)が見つかりません")

        val root = JSONObject(scriptEl.data())
        val apollo = root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("__APOLLO_STATE__")
            ?: throw IllegalStateException("__APOLLO_STATE__ が見つかりません")

        // URLから Work ID を抽出、なければ Apollo State から最初の Work:* を探す
        val workIdFromUrl = Regex("""/works/(\d+)""").find(baseUri)?.groupValues?.getOrNull(1)
        var workKey: String? = workIdFromUrl?.let { "Work:$it" }?.takeIf { apollo.has(it) }
        if (workKey == null) {
            val keys = apollo.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("Work:")) {
                    workKey = k
                    break
                }
            }
        }
        val workObj = workKey?.let { apollo.optJSONObject(it) }
            ?: throw IllegalStateException("カクヨム作品情報(Work)が見つかりません")

        val workId = workObj.optString("id").ifBlank { workIdFromUrl ?: "" }
        val title = workObj.optString("title").ifBlank { doc.title().ifBlank { "無題" } }
        val authorRef = workObj.optJSONObject("author")?.optString("__ref")
        val authorObj = authorRef?.let { apollo.optJSONObject(it) }
        val author = workObj.optString("alternateAuthorName").ifBlank {
            authorObj?.optString("activityName")?.ifBlank {
                authorObj.optString("name")
            } ?: ""
        }.ifBlank { null }

        val synopsis = workObj.optString("introduction").ifBlank {
            workObj.optString("catchphrase")
        }.ifBlank { null }

        val episodes = mutableListOf<ParsedEpisodeSummary>()
        val tocRefs = mutableListOf<String>()

        // tableOfContentsV2 または tableOfContents から章リストを取得
        fun collectRefs(raw: Any?) {
            when (raw) {
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        val ref = raw.optJSONObject(i)?.optString("__ref")
                        if (!ref.isNullOrBlank()) tocRefs.add(ref)
                    }
                }
                is JSONObject -> {
                    val ref = raw.optString("__ref")
                    if (ref.isNotBlank()) tocRefs.add(ref)
                }
            }
        }
        collectRefs(workObj.opt("tableOfContentsV2"))
        if (tocRefs.isEmpty()) {
            collectRefs(workObj.opt("tableOfContents"))
        }

        var epIndex = 1
        for (tocRef in tocRefs) {
            val tocObj = apollo.optJSONObject(tocRef) ?: continue
            val chapterRef = tocObj.optJSONObject("chapter")?.optString("__ref")
            val chapterTitle = chapterRef?.let { apollo.optJSONObject(it)?.optString("title") }?.ifBlank { null }

            val epUnions = tocObj.optJSONArray("episodeUnions") ?: continue
            for (i in 0 until epUnions.length()) {
                val epRef = epUnions.optJSONObject(i)?.optString("__ref") ?: continue
                val epObj = apollo.optJSONObject(epRef) ?: continue
                if (epObj.optString("__typename") != "Episode") continue
                val epId = epObj.optString("id")
                if (epId.isBlank()) continue
                val epTitle = epObj.optString("title").ifBlank { "第${epIndex}話" }
                val rawDate = epObj.optString("publishedAt")
                val formattedDate = formatIsoDate(rawDate)
                val epUrl = "https://kakuyomu.jp/works/$workId/episodes/$epId"
                episodes.add(
                    ParsedEpisodeSummary(
                        episodeIndex = epIndex++,
                        title = epTitle,
                        url = epUrl,
                        chapter = chapterTitle,
                        updatedAt = formattedDate
                    )
                )
            }
        }

        return ParsedNovelIndex(
            title = title,
            author = author,
            synopsis = synopsis,
            episodes = episodes,
            nextPageUrl = null
        )
    }

    private fun formatIsoDate(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso)
        return if (m != null) {
            "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}"
        } else {
            iso
        }
    }

    private fun resolveUrl(baseUri: String, relativeUrl: String): String {
        return try {
            URI(baseUri).resolve(relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}

