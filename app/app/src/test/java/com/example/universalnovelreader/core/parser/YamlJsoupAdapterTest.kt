package com.example.universalnovelreader.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YamlJsoupAdapterTest {

    private val syosetuYaml = """
metadata:
  id: "syosetu_standard"
  name: "小説家になろう"
  version: "1.0.0"
  domain_pattern: "^https?://(ncode|novel18)\\.syosetu\\.com/[a-z0-9]+/?$"

headers:
  User-Agent: "TestAgent/1.0"
  Cookie: "over18=yes"

index:
  title: ".novel_title, p.novel_title, h1"
  author:
    selector: ".novel_writername"
    regex_replace: ["^作者：\\s*", ""]
  synopsis: "#novel_ex"
  episodes:
    container: ".novel_sublist2"
    title: "dd.subtitle a, a"
    url: "dd.subtitle a @href"
    updated_at: "dt.long_update"
    chapter: ".chapter_title"

episode:
  title: ".novel_subtitle, .p-novel__title"
  content: "#novel_honbun, .p-novel__body"
  remove: "script, style, iframe, .ads, .novel_attention"
  images: "#novel_honbun img @src, .p-novel__body img @src"

crawl:
  interval_ms: 2500
  auto_cache_on_sync: true
  cache_images: true
""".trimIndent()

    @Test
    fun testParseIndex() = runBlocking {
        val adapter = YamlJsoupAdapter(syosetuYaml)

        assertEquals("syosetu_standard", adapter.adapterId)
        assertEquals("小説家になろう", adapter.name)
        assertTrue(adapter.autoCacheOnSync)
        assertTrue(adapter.cacheImages)
        assertEquals("over18=yes", adapter.customHeaders["Cookie"])

        val mockIndexHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>なろう小説テスト</title></head>
            <body>
                <p class="novel_title">異世界転生テスト物語</p>
                <div class="novel_writername">作者： なろう太郎</div>
                <div id="novel_ex">これは異世界に転生したプログラマーの物語です。</div>
                <div class="novel_sublist2">
                    <dt class="long_update">2026/01/01 12:00</dt>
                    <dd class="subtitle"><a href="/n1234a/1/">第1話 プロローグ</a></dd>
                </div>
                <div class="novel_sublist2">
                    <dt class="long_update">2026/01/02 18:00</dt>
                    <dd class="subtitle"><a href="/n1234a/2/">第2話 覚醒の刻</a></dd>
                </div>
            </body>
            </html>
        """.trimIndent()

        val parsed = adapter.parseIndex(mockIndexHtml, "https://ncode.syosetu.com/n1234a/")

        assertEquals("異世界転生テスト物語", parsed.title)
        assertEquals("なろう太郎", parsed.author)
        assertEquals("これは異世界に転生したプログラマーの物語です。", parsed.synopsis)
        assertEquals(2, parsed.episodes.size)
        assertEquals("第1話 プロローグ", parsed.episodes[0].title)
        assertEquals("https://ncode.syosetu.com/n1234a/1/", parsed.episodes[0].url)
        assertEquals("第2話 覚醒の刻", parsed.episodes[1].title)
        assertEquals("https://ncode.syosetu.com/n1234a/2/", parsed.episodes[1].url)
    }

    @Test
    fun testParseEpisode() = runBlocking {
        val adapter = YamlJsoupAdapter(syosetuYaml)

        val mockEpisodeHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <p class="novel_subtitle">第1話 プロローグ</p>
                <div id="novel_honbun">
                    <p>目が覚めると、見知らぬ天井だった。</p>
                    <div class="ads">スポンサー広告</div>
                    <p>枕元には剣が置かれていた。<img src="https://static.syosetu.com/images/sword.png" alt="聖剣" /></p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val parsed = adapter.parseEpisode(mockEpisodeHtml, "https://ncode.syosetu.com/n1234a/1/")

        assertEquals("第1話 プロローグ", parsed.title)
        // 広告タグが除去されていること
        assertFalse(parsed.contentHtml.contains("スポンサー広告"))
        // 本文が含まれていること
        assertTrue(parsed.contentHtml.contains("目が覚めると、見知らぬ天井だった。"))
        // 挿絵画像が抽出されていること
        assertEquals(1, parsed.imageUrls.size)
        assertEquals("https://static.syosetu.com/images/sword.png", parsed.imageUrls[0])
    }
}

