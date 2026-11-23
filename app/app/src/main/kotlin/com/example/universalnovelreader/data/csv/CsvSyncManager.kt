package com.example.universalnovelreader.data.csv

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.model.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class CsvSyncManager(
    private val context: Context,
    private val repository: NovelRepository
) {
    private val exportsDir: File
        get() = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

    /**
     * 本棚の小説一覧をCSV形式のファイルとしてエクスポート
     */
    suspend fun exportBookshelfCsv(): File = withContext(Dispatchers.IO) {
        val novels = repository.getAllNovels()
        val adapters = repository.getAllAdapters().associateBy { it.id }

        val sb = StringBuilder()
        // ヘッダー行
        sb.append("url,title,author,site_name,total_episodes,read_episodes\n")

        for (n in novels) {
            val episodes = repository.getEpisodesForNovel(n.id)
            val readCount = episodes.count { it.isRead }
            val siteName = adapters[n.siteAdapterId]?.name ?: "未登録サイト"

            sb.append(escapeCsv(n.topUrl)).append(",")
            sb.append(escapeCsv(n.title)).append(",")
            sb.append(escapeCsv(n.author)).append(",")
            sb.append(escapeCsv(siteName)).append(",")
            sb.append(episodes.size).append(",")
            sb.append(readCount)
            sb.append("\n")
        }

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val exportFile = File(exportsDir, "bookshelf_$dateStr.csv")
        exportFile.writeText(sb.toString(), Charsets.UTF_8)
        exportFile
    }

    /**
     * エクスポートしたCSVファイルを他アプリへ共有
     */
    fun createShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "万能小説 本棚バックアップ (${file.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * CSVテキストまたはファイル内容をインポート
     * 寛容なパース: URLが含まれていれば抽出して登録
     */
    suspend fun importCsvText(csvContent: String): ImportResult = withContext(Dispatchers.IO) {
        var addedCount = 0
        var skippedCount = 0
        val urlPattern = Pattern.compile("https?://[\\w!?/+\\-_~=;.,*&@#$%()[\\]]+")

        val lines = csvContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("url,") || trimmed.startsWith("\"url\"")) continue

            val matcher = urlPattern.matcher(trimmed)
            if (matcher.find()) {
                val url = matcher.group()

                // 既存チェック
                val existing = repository.getNovelByUrl(url)
                if (existing != null) {
                    skippedCount++
                    continue
                }

                val adapter = repository.findAdapterForUrl(url)
                val parts = parseCsvLine(trimmed)
                val title = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1] else "（未同期）$url"
                val author = if (parts.size > 2) parts[2] else ""

                repository.insertNovel(
                    Novel(
                        siteAdapterId = adapter?.id,
                        title = title,
                        author = author,
                        topUrl = url
                    )
                )
                addedCount++
            }
        }

        ImportResult(addedCount = addedCount, skippedCount = skippedCount)
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    sb.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    data class ImportResult(val addedCount: Int, val skippedCount: Int)
}
