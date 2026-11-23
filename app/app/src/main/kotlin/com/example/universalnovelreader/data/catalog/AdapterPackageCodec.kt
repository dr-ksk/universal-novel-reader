package com.example.universalnovelreader.data.catalog

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.universalnovelreader.data.model.SiteAdapter
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class AdapterPackage(
    val schemaVersion: Int = 1,
    val adapterId: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val domainPattern: String,
    val adapterType: String = "yaml",
    val content: String,
    val customCss: String? = null,
    val discussionUrl: String? = null
) {
    fun toSiteAdapter(existing: SiteAdapter? = null): SiteAdapter {
        return SiteAdapter(
            id = existing?.id ?: 0L,
            adapterId = adapterId.trim(),
            name = name.trim(),
            domainPattern = domainPattern.trim(),
            adapterType = adapterType.lowercase(Locale.US).ifBlank { "yaml" },
            content = content,
            customCss = customCss?.trim()?.ifEmpty { null },
            version = version.trim().ifBlank { "1.0.0" },
            isBuiltin = existing?.isBuiltin ?: false
        )
    }

    fun hasBroadDomainPattern(): Boolean {
        val t = domainPattern.trim()
        return t == ".*" || t == "^https?://.*" || t == "^https?://.*$"
    }
}

object AdapterPackageCodec {
    private val unr1Regex = Regex("""UNR1:[A-Za-z0-9\-_+/=]+""")

    fun fromSiteAdapter(
        adapter: SiteAdapter,
        author: String = "",
        description: String = ""
    ): AdapterPackage {
        return AdapterPackage(
            schemaVersion = 1,
            adapterId = adapter.adapterId,
            name = adapter.name,
            version = adapter.version,
            author = author,
            description = description,
            domainPattern = adapter.domainPattern,
            adapterType = adapter.adapterType,
            content = adapter.content,
            customCss = adapter.customCss
        )
    }

    fun encodeToJson(pkg: AdapterPackage, pretty: Boolean = true): String {
        val obj = JSONObject().apply {
            put("schema_version", pkg.schemaVersion)
            put("adapter_id", pkg.adapterId)
            put("name", pkg.name)
            put("version", pkg.version)
            if (pkg.author.isNotBlank()) put("author", pkg.author)
            if (pkg.description.isNotBlank()) put("description", pkg.description)
            put("domain_pattern", pkg.domainPattern)
            put("adapter_type", pkg.adapterType)
            put("content", pkg.content)
            if (!pkg.customCss.isNullOrBlank()) {
                put("custom_css", pkg.customCss)
            } else {
                put("custom_css", JSONObject.NULL)
            }
            if (!pkg.discussionUrl.isNullOrBlank()) {
                put("discussion_url", pkg.discussionUrl)
            }
        }
        return if (pretty) obj.toString(2) else obj.toString()
    }

    fun encodeToUnr1(pkg: AdapterPackage): String {
        val compactJson = encodeToJson(pkg, pretty = false)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            gz.write(compactJson.toByteArray(Charsets.UTF_8))
        }
        val b64 = Base64.encodeToString(
            baos.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "UNR1:$b64"
    }

    /**
     * 掲示板の文章が前後に混ざっていても UNR1:... または JSON を自動抽出してデコードする
     */
    fun decodeFromText(rawText: String): AdapterPackage {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("入力が空です")
        }

        val unrMatch = unr1Regex.find(trimmed)
        val jsonStr = if (unrMatch != null) {
            decodeUnr1Token(unrMatch.value)
        } else {
            val first = trimmed.indexOf('{')
            val last = trimmed.lastIndexOf('}')
            if (first < 0 || last <= first) {
                throw IllegalArgumentException("有効な JSON または UNR1:... 共有コードが見つかりません")
            }
            trimmed.substring(first, last + 1)
        }

        val obj = JSONObject(jsonStr)
        val adapterId = obj.optString("adapter_id").trim()
        val name = obj.optString("name").trim()
        val version = obj.optString("version", "1.0.0").trim().ifBlank { "1.0.0" }
        val domainPattern = obj.optString("domain_pattern").trim()
        val adapterType = obj.optString("adapter_type", "yaml").trim().lowercase(Locale.US)
        val content = obj.optString("content")

        if (adapterId.isBlank()) throw IllegalArgumentException("adapter_id が含まれていません")
        if (name.isBlank()) throw IllegalArgumentException("name（サイト名）が含まれていません")
        if (domainPattern.isBlank()) throw IllegalArgumentException("domain_pattern が含まれていません")
        if (content.isBlank()) throw IllegalArgumentException("content（定義本体）が空です")
        if (adapterType != "yaml" && adapterType != "javascript") {
            throw IllegalArgumentException("未対応の形式です: $adapterType")
        }

        // 正規表現の妥当性チェック
        Regex(domainPattern)

        val customCss = if (obj.isNull("custom_css")) null else obj.optString("custom_css").takeIf { it.isNotBlank() }
        val discussionUrl = if (obj.isNull("discussion_url")) null else obj.optString("discussion_url").takeIf { it.isNotBlank() }

        return AdapterPackage(
            schemaVersion = obj.optInt("schema_version", 1),
            adapterId = adapterId,
            name = name,
            version = version,
            author = obj.optString("author", "").trim(),
            description = obj.optString("description", "").trim(),
            domainPattern = domainPattern,
            adapterType = adapterType,
            content = content,
            customCss = customCss,
            discussionUrl = discussionUrl
        )
    }

    private fun decodeUnr1Token(token: String): String {
        val payload = token.removePrefix("UNR1:")
        val compressed = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun hasCookieHeader(content: String): Boolean {
        return Regex("""(?im)^\s*Cookie\s*:""").containsMatchIn(content)
    }

    fun exportPackageFile(context: Context, pkg: AdapterPackage): File {
        val dir = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
        val safeId = pkg.adapterId.replace(Regex("""[^a-zA-Z0-9_-]"""), "_").ifBlank {
            "adapter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }
        val file = File(dir, "$safeId.unr.json")
        file.writeText(encodeToJson(pkg, pretty = true) + "\n", Charsets.UTF_8)
        return file
    }

    fun createShareIntent(context: Context, file: File, adapterName: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "万能小説 サイト定義: $adapterName (${file.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
