package com.example.universalnovelreader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.universalnovelreader.core.parser.AdapterTestResult
import com.example.universalnovelreader.data.catalog.AdapterPackageCodec
import com.example.universalnovelreader.data.model.SiteAdapter
import kotlinx.coroutines.launch

/**
 * 1つのYAML定義を「一覧（目次）取得」「1話ごとの本文取得」「通信ヘッダー・同期設定」へ分割・再結合するヘルパー
 */
internal data class YamlDefinitionParts(
    val indexYaml: String,
    val episodeYaml: String,
    val commonYaml: String
)

internal object YamlDefinitionSplitter {
    private val topLevelKeyRegex = Regex("""^([A-Za-z0-9_]+)\s*:\s*(.*)$""")

    fun split(fullYaml: String): YamlDefinitionParts {
        val sections = linkedMapOf<String, MutableList<String>>()
        var currentKey: String? = null

        for (line in fullYaml.lines()) {
            val isTopLevel = line.isNotEmpty() && !line[0].isWhitespace() && !line.trimStart().startsWith("#")
            if (isTopLevel) {
                val match = topLevelKeyRegex.find(line)
                if (match != null) {
                    currentKey = match.groupValues[1]
                    sections.getOrPut(currentKey) { mutableListOf() }.add(line)
                    continue
                }
            }
            if (currentKey != null) {
                sections.getOrPut(currentKey) { mutableListOf() }.add(line)
            }
        }

        val indexBody = extractSectionBody(sections["index"])
        val episodeBody = extractSectionBody(sections["episode"])

        val commonBlocks = mutableListOf<String>()
        sections["headers"]?.let { commonBlocks.add(it.joinToString("\n").trimEnd()) }
        sections["crawl"]?.let { commonBlocks.add(it.joinToString("\n").trimEnd()) }

        return YamlDefinitionParts(
            indexYaml = indexBody.ifBlank { defaultIndexYaml() },
            episodeYaml = episodeBody.ifBlank { defaultEpisodeYaml() },
            commonYaml = commonBlocks.joinToString("\n\n").ifBlank { defaultCommonYaml() }
        )
    }

    fun combine(
        adapterId: String,
        name: String,
        version: String,
        domainPattern: String,
        indexYaml: String,
        episodeYaml: String,
        commonYaml: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("metadata:")
        sb.appendLine("  id: \"${escapeYamlDoubleQuoted(adapterId.ifBlank { "custom_site" })}\"")
        sb.appendLine("  name: \"${escapeYamlDoubleQuoted(name.ifBlank { "カスタムサイト" })}\"")
        sb.appendLine("  version: \"${escapeYamlDoubleQuoted(version.ifBlank { "1.0.0" })}\"")
        sb.appendLine("  domain_pattern: \"${escapeYamlDoubleQuoted(domainPattern)}\"")
        sb.appendLine()

        val trimmedCommon = commonYaml.trim()
        if (trimmedCommon.isNotEmpty()) {
            sb.appendLine(trimmedCommon)
            sb.appendLine()
        }

        sb.appendLine("index:")
        sb.appendLine(indentSectionBody(stripLeadingHeader(indexYaml, "index")))
        sb.appendLine()

        sb.appendLine("episode:")
        sb.appendLine(indentSectionBody(stripLeadingHeader(episodeYaml, "episode")))

        return sb.toString().trimEnd()
    }

    private fun extractSectionBody(lines: List<String>?): String {
        if (lines.isNullOrEmpty()) return ""
        val bodyLines = lines.drop(1).dropLastWhile { it.isBlank() }
        if (bodyLines.isEmpty()) return ""
        val minIndent = bodyLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.indexOfFirst { ch -> !ch.isWhitespace() }.let { idx -> if (idx < 0) 0 else idx } }
            ?: 0
        return bodyLines.joinToString("\n") { line ->
            if (line.length >= minIndent) line.substring(minIndent) else line.trimStart()
        }.trimEnd()
    }

    private fun stripLeadingHeader(raw: String, expectedKey: String): String {
        val trimmed = raw.trim()
        val lines = trimmed.lines()
        if (lines.isNotEmpty() && lines.first().trim() == "$expectedKey:") {
            return extractSectionBody(lines)
        }
        return trimmed
    }

    private fun indentSectionBody(body: String): String {
        if (body.isBlank()) return "  # 未設定"
        return body.lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else "  $line"
        }
    }

    private fun escapeYamlDoubleQuoted(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun defaultIndexYaml(): String = """
title: "h1.title"
author: ".author"
synopsis: ".synopsis"
episodes:
  container: ".episode-list li"
  title: "a"
  url: "a @href"
  chapter: ".chapter"
  updated_at: ".date"
next_page_url: ".pager-next a @href"
""".trimIndent()

    fun defaultEpisodeYaml(): String = """
title: "h1.episode-title"
content: "#novel-content"
remove: "script, style, iframe, .ads"
images: "#novel-content img @src"
""".trimIndent()

    fun defaultCommonYaml(): String = """
headers:
  User-Agent: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

crawl:
  interval_ms: 1500
  cache_images: true
""".trimIndent()

    fun defaultJsTemplate(): String = """
const SiteAdapter = {
  metadata: {
    id: "custom_js_site",
    name: "カスタムJSサイト",
    version: "1.0.0",
    domain_pattern: "^https?://example\\.com/.*"
  },
  parseIndex: async (html, currentUrl) => {
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, "text/html");
    const title = doc.querySelector('h1')?.textContent.trim() || "";
    const episodes = [];
    doc.querySelectorAll('.ep-link').forEach((a, i) => {
      episodes.push({
        index: i + 1,
        title: a.textContent.trim(),
        url: a.href
      });
    });
    window.AppBridge.saveNovelIndex(JSON.stringify({
      topUrl: currentUrl,
      title: title,
      episodes: episodes
    }));
  },
  parseEpisode: async (html, currentUrl) => {
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, "text/html");
    const title = doc.querySelector('.title')?.textContent.trim() || "";
    const content = doc.querySelector('.content')?.innerHTML || "";
    window.AppBridge.saveEpisodeContent(JSON.stringify({
      episodeUrl: currentUrl,
      episodeTitle: title,
      contentHtml: content
    }));
  }
};
""".trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterEditScreen(
    adapterIdToEdit: Long?,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapters by viewModel.adapters.collectAsState()
    val existingAdapter = remember(adapters, adapterIdToEdit) {
        if (adapterIdToEdit == null) null else adapters.firstOrNull { it.id == adapterIdToEdit }
    }

    val initialParts = remember(existingAdapter) {
        if (existingAdapter != null && existingAdapter.adapterType == "yaml") {
            YamlDefinitionSplitter.split(existingAdapter.content)
        } else {
            YamlDefinitionParts(
                indexYaml = YamlDefinitionSplitter.defaultIndexYaml(),
                episodeYaml = YamlDefinitionSplitter.defaultEpisodeYaml(),
                commonYaml = YamlDefinitionSplitter.defaultCommonYaml()
            )
        }
    }

    var adapterKey by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.adapterId ?: "custom_site_${System.currentTimeMillis() % 10000}") }
    var name by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.name ?: "新規サイト定義") }
    var version by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.version ?: "1.0.0") }
    var domainPattern by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.domainPattern ?: "^https?://example\\.com/.*") }
    var adapterType by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.adapterType ?: "yaml") }

    // YAML 2分割（一覧定義 + 1話本文定義）＋共通設定
    var indexYaml by remember(existingAdapter?.id) { mutableStateOf(initialParts.indexYaml) }
    var episodeYaml by remember(existingAdapter?.id) { mutableStateOf(initialParts.episodeYaml) }
    var commonYaml by remember(existingAdapter?.id) { mutableStateOf(initialParts.commonYaml) }
    var showCommonYaml by remember { mutableStateOf(false) }

    // JS 用コード
    var jsContent by remember(existingAdapter?.id) {
        mutableStateOf(
            if (existingAdapter != null && existingAdapter.adapterType == "javascript") {
                existingAdapter.content
            } else {
                YamlDefinitionSplitter.defaultJsTemplate()
            }
        )
    }

    // サイト専用カスタムCSS
    var customCss by remember(existingAdapter?.id) { mutableStateOf(existingAdapter?.customCss ?: "") }

    // パース即時テスト用状態
    val defaultTestUrl = remember(existingAdapter?.adapterId) {
        when (existingAdapter?.adapterId) {
            "syosetu_standard" -> "https://ncode.syosetu.com/n9669bk/"
            "kakuyomu_standard" -> "https://kakuyomu.jp/works/11054822665990631527"
            else -> ""
        }
    }
    var testUrl by remember(existingAdapter?.id) { mutableStateOf(defaultTestUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<AdapterTestResult?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    fun buildCandidateAdapter(): SiteAdapter {
        val mergedContent = if (adapterType == "yaml") {
            YamlDefinitionSplitter.combine(
                adapterId = adapterKey.trim(),
                name = name.trim(),
                version = version.trim(),
                domainPattern = domainPattern.trim(),
                indexYaml = indexYaml,
                episodeYaml = episodeYaml,
                commonYaml = commonYaml
            )
        } else {
            jsContent
        }
        return SiteAdapter(
            id = existingAdapter?.id ?: 0,
            adapterId = adapterKey.trim().ifBlank { "custom_site" },
            name = name.trim().ifBlank { "カスタムサイト" },
            domainPattern = domainPattern.trim(),
            adapterType = adapterType,
            content = mergedContent,
            customCss = customCss.trim().ifEmpty { null },
            version = version.trim().ifBlank { "1.0.0" },
            isBuiltin = existingAdapter?.isBuiltin ?: false
        )
    }

    fun readClipboard(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (existingAdapter == null) "サイト定義の新規作成" else "サイト定義の編集",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.AdapterStudio) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "書き出し・共有")
                    }
                    TextButton(
                        onClick = {
                            val candidate = buildCandidateAdapter()
                            viewModel.saveAdapter(candidate) {
                                Toast.makeText(context, "サイト定義を保存しました", Toast.LENGTH_SHORT).show()
                                viewModel.navigateTo(Screen.AdapterStudio)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 1. 基本情報 ---
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("基本情報", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = adapterType == "yaml",
                                onClick = { adapterType = "yaml" },
                                label = { Text("YAML") }
                            )
                            FilterChip(
                                selected = adapterType == "javascript",
                                onClick = { adapterType = "javascript" },
                                label = { Text("JavaScript") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("サイト名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = adapterKey,
                            onValueChange = { adapterKey = it },
                            label = { Text("定義ID") },
                            singleLine = true,
                            enabled = !(existingAdapter?.isBuiltin ?: false),
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = version,
                            onValueChange = { version = it },
                            label = { Text("Ver") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = domainPattern,
                        onValueChange = { domainPattern = it },
                        label = { Text("マッチ用ドメイン正規表現") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (adapterType == "yaml") {
                // --- 2A. 一覧（目次）取得の定義 (YAML) ---
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "① 一覧（目次）取得の定義",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "作品タイトル・作者・あらすじ・各話リンク一覧の抽出ルール",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                val clip = readClipboard()
                                if (clip.isNotBlank()) {
                                    indexYaml = clip
                                    Toast.makeText(context, "一覧定義へ貼り付けました", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("貼付")
                            }
                        }

                        OutlinedTextField(
                            value = indexYaml,
                            onValueChange = { indexYaml = it },
                            minLines = 10,
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // --- 2B. 1話ごとの本文取得の定義 (YAML) ---
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "② 1話ごとの本文取得の定義",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "各話ページのタイトル・本文エリア・除外タグ・挿絵の抽出ルール",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                val clip = readClipboard()
                                if (clip.isNotBlank()) {
                                    episodeYaml = clip
                                    Toast.makeText(context, "本文定義へ貼り付けました", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("貼付")
                            }
                        }

                        OutlinedTextField(
                            value = episodeYaml,
                            onValueChange = { episodeYaml = it },
                            minLines = 7,
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // --- 2C. 通信ヘッダー・同期設定（折りたたみ） ---
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCommonYaml = !showCommonYaml },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "通信ヘッダー・同期設定（任意）",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "User-Agent / Cookie / 取得間隔などの共通設定",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (showCommonYaml) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showCommonYaml) "閉じる" else "開く"
                            )
                        }

                        AnimatedVisibility(visible = showCommonYaml) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                OutlinedTextField(
                                    value = commonYaml,
                                    onValueChange = { commonYaml = it },
                                    minLines = 6,
                                    textStyle = LocalTextStyle.current.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            } else {
                // --- JavaScript 定義 ---
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "JavaScript パーサー定義",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = {
                                val clip = readClipboard()
                                if (clip.isNotBlank()) {
                                    jsContent = clip
                                    Toast.makeText(context, "JSコードへ貼り付けました", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("貼付")
                            }
                        }

                        OutlinedTextField(
                            value = jsContent,
                            onValueChange = { jsContent = it },
                            minLines = 14,
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // --- 3. サイト専用カスタムCSS ---
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "サイト専用カスタムCSS（任意）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "このサイトの小説を読むときだけ追加適用するCSS（ルビ調整・余白調整など）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customCss,
                        onValueChange = { customCss = it },
                        minLines = 6,
                        placeholder = { Text("/* 例:\nruby rt { font-size: 0.52em; }\n.blank { height: 1em; }\n*/") },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // --- 4. パース即時テスト（画面下部） ---
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "パース即時テスト",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "現在の編集内容（保存前でもOK）を使って、一覧取得と第1話本文取得をテストします。本棚には登録されません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        label = { Text("テスト対象小説URL") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isTesting = true
                                    testResult = null
                                    val candidate = buildCandidateAdapter()
                                    testResult = viewModel.testAdapterParse(candidate, testUrl.trim())
                                    isTesting = false
                                }
                            },
                            enabled = !isTesting && testUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("テスト実行中...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("一覧＋第1話本文をテスト")
                            }
                        }

                        OutlinedButton(onClick = {
                            val clip = readClipboard()
                            if (clip.startsWith("http://") || clip.startsWith("https://")) {
                                testUrl = clip.trim()
                            } else {
                                Toast.makeText(context, "クリップボードにURLがありません", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("URL貼付")
                        }
                    }

                    testResult?.let { res ->
                        Surface(
                            color = if (res.success) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (res.success) "✓ ${res.message}" else "✗ ${res.message}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (res.success) {
                                    Text("作品名: ${res.novelTitle ?: "（未取得）"}", style = MaterialTheme.typography.bodySmall)
                                    if (!res.author.isNullOrBlank()) {
                                        Text("作者: ${res.author}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("取得話数: ${res.episodeCount} 話", style = MaterialTheme.typography.bodySmall)
                                    if (res.sampleEpisodes.isNotEmpty()) {
                                        Text(
                                            text = "先頭エピソード:\n" + res.sampleEpisodes.joinToString("\n") { "  $it" },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (res.firstEpisodeTitle != null) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            text = "第1話本文テスト: ${res.firstEpisodeTitle} (${res.firstEpisodeLength}文字)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (!res.firstEpisodePreview.isNullOrBlank()) {
                                            Text(
                                                text = res.firstEpisodePreview,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 下部保存ボタン（スクロール末尾からも押しやすいように）
            Button(
                onClick = {
                    val candidate = buildCandidateAdapter()
                    viewModel.saveAdapter(candidate) {
                        Toast.makeText(context, "サイト定義を保存しました", Toast.LENGTH_SHORT).show()
                        viewModel.navigateTo(Screen.AdapterStudio)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("このサイト定義を保存")
            }
        }
    }

    // サイト定義の書き出し・共有ダイアログ
    if (showExportDialog) {
        val candidate = buildCandidateAdapter()
        val pkg = AdapterPackageCodec.fromSiteAdapter(candidate)
        val hasCookie = AdapterPackageCodec.hasCookieHeader(candidate.content)

        fun copyToClipboard(label: String, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "${label}をコピーしました", Toast.LENGTH_SHORT).show()
        }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("サイト定義を書き出し・共有") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "「${candidate.name}」(v${candidate.version}) を掲示板投稿や他端末への共有用に書き出します。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (hasCookie) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠ Cookie ヘッダーが含まれています。年齢確認用（over18=yes等）以外の個人ログイン情報が入っていないか確認してください。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Button(
                        onClick = {
                            copyToClipboard("共有コード (UNR1)", AdapterPackageCodec.encodeToUnr1(pkg))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1行共有コード (UNR1:...) をコピー")
                    }
                    OutlinedButton(
                        onClick = {
                            copyToClipboard("定義 JSON", AdapterPackageCodec.encodeToJson(pkg, pretty = true))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("JSON 全文をコピー")
                    }
                    OutlinedButton(
                        onClick = {
                            val file = AdapterPackageCodec.exportPackageFile(context, pkg)
                            val shareIntent = AdapterPackageCodec.createShareIntent(context, file, candidate.name)
                            context.startActivity(Intent.createChooser(shareIntent, "サイト定義を共有"))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(".unr.json ファイルとして共有")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("閉じる") }
            }
        )
    }
}
