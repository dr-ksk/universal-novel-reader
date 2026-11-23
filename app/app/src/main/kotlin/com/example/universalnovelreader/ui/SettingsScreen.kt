package com.example.universalnovelreader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheSize by viewModel.cacheSizeText.collectAsState()
    val defaultSettings by viewModel.readerSettings.collectAsState()
    val autoSync by viewModel.autoSyncSettings.collectAsState()

    var showImportTextDialog by remember { mutableStateOf(false) }
    var inputCsvText by remember { mutableStateOf("") }

    // Android 13+ 通知権限リクエストランチャー
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "通知権限が拒否されました（定期更新自体は動作します）", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // CSVファイルピッカー
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        val res = viewModel.csvSyncManager.importCsvText(text)
                        Toast.makeText(context, "${res.addedCount}件追加, ${res.skippedCount}件スキップ", Toast.LENGTH_SHORT).show()
                        viewModel.loadBookshelf()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "CSVの読み込みに失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定・データ管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Bookshelf) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
                .verticalScroll(rememberScrollState())
        ) {
            // セクション: 定期更新チェック
            Text(
                text = "定期更新チェック（バックグラウンド）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )

            ListItem(
                headlineContent = { Text("自動で定期的に新着をチェック") },
                supportingContent = {
                    Text(
                        if (autoSync.enabled) {
                            "${autoSync.intervalHours}時間ごとに自動チェック（${if (autoSync.wifiOnly) "Wi-Fiのみ" else "モバイル通信も可"}）"
                        } else {
                            "オフ（手動更新のみ）"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = autoSync.enabled,
                        onCheckedChange = { checked ->
                            if (checked && autoSync.notifyOnNewEpisodes) {
                                requestNotificationPermissionIfNeeded()
                            }
                            viewModel.updateAutoSyncSettings(autoSync.copy(enabled = checked))
                            Toast.makeText(
                                context,
                                if (checked) "定期更新をオンにしました（${autoSync.intervalHours}時間ごと）" else "定期更新をオフにしました",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            )

            AnimatedVisibility(visible = autoSync.enabled) {
                Column {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            text = "チェック間隔",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val intervals = listOf(1, 3, 6, 12, 24)
                            for (hours in intervals) {
                                FilterChip(
                                    selected = autoSync.intervalHours == hours,
                                    onClick = {
                                        viewModel.updateAutoSyncSettings(autoSync.copy(intervalHours = hours))
                                    },
                                    label = { Text("${hours}時間") }
                                )
                            }
                        }
                    }

                    ListItem(
                        headlineContent = { Text("Wi-Fi接続時のみ実行") },
                        supportingContent = { Text("モバイルデータ通信量を節約します") },
                        leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = autoSync.wifiOnly,
                                onCheckedChange = { checked ->
                                    viewModel.updateAutoSyncSettings(autoSync.copy(wifiOnly = checked))
                                }
                            )
                        }
                    )

                    ListItem(
                        headlineContent = { Text("新着話が見つかったら通知") },
                        supportingContent = { Text("更新された作品名と新着話数を通知します") },
                        leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = autoSync.notifyOnNewEpisodes,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        requestNotificationPermissionIfNeeded()
                                    }
                                    viewModel.updateAutoSyncSettings(autoSync.copy(notifyOnNewEpisodes = checked))
                                }
                            )
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (autoSync.lastSyncAt != null) "最終自動チェック: ${autoSync.lastSyncAt}" else "最終自動チェック: 未実行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!autoSync.lastSyncSummary.isNullOrBlank()) {
                        Text(
                            text = autoSync.lastSyncSummary!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (autoSync.notifyOnNewEpisodes) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.runAutoSyncNow()
                        Toast.makeText(context, "バックグラウンド更新チェックを開始しました", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("今すぐ実行")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // セクション: CSVデータ管理
            Text(
                text = "CSVデータ連携",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )

            ListItem(
                headlineContent = { Text("本棚をCSVエクスポート") },
                supportingContent = { Text("登録小説一覧をCSVファイルとして出力・共有します") },
                leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                    scope.launch {
                        val file = viewModel.csvSyncManager.exportBookshelfCsv()
                        val shareIntent = viewModel.csvSyncManager.createShareIntent(file)
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "本棚CSVをエクスポート"))
                    }
                }
            )

            ListItem(
                headlineContent = { Text("CSVファイルからインポート") },
                supportingContent = { Text("端末内のCSVファイルから小説を一括登録します") },
                leadingContent = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                modifier = Modifier.clickable {
                    csvPickerLauncher.launch("text/*")
                }
            )

            ListItem(
                headlineContent = { Text("CSVテキストを直接貼り付け") },
                supportingContent = { Text("クリップボード等からテキストを貼り付けてインポートします") },
                leadingContent = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                modifier = Modifier.clickable {
                    showImportTextDialog = true
                }
            )

            HorizontalDivider()

            // セクション: 全体デフォルトリーダー設定
            Text(
                text = "デフォルトリーダー設定",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )

            val themeLabel = when (defaultSettings.theme) {
                "white" -> "白"
                "sepia" -> "セピア"
                "dark" -> "ダーク"
                "black" -> "漆黒"
                else -> defaultSettings.theme
            }
            val fontLabel = if (defaultSettings.fontFamily == "serif") "明朝体" else "ゴシック体"

            ListItem(
                headlineContent = { Text("表示設定・デフォルトCSS") },
                supportingContent = {
                    Text(
                        "テーマ: $themeLabel / $fontLabel / ${defaultSettings.fontSize.toInt()}px / 行間 ${
                            String.format(java.util.Locale.US, "%.1f", defaultSettings.lineHeight)
                        }"
                    )
                },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable {
                    viewModel.navigateTo(Screen.DefaultReaderSettings)
                }
            )

            HorizontalDivider()

            // セクション: ストレージ・キャッシュ管理
            Text(
                text = "ストレージ・キャッシュ管理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )

            ListItem(
                headlineContent = { Text("キャッシュ容量") },
                supportingContent = { Text("保存済み本文・挿絵画像: $cacheSize") },
                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) }
            )

            ListItem(
                headlineContent = { Text("全キャッシュをクリア") },
                supportingContent = { Text("保存済みの全本文・画像を削除します（本棚情報は残ります）") },
                leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                    viewModel.clearAllCache()
                    Toast.makeText(context, "キャッシュを削除しました", Toast.LENGTH_SHORT).show()
                }
            )

            HorizontalDivider()

            // セクション: サイト定義
            Text(
                text = "サイト定義（パーサー）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )

            ListItem(
                headlineContent = { Text("サイト定義管理 (Adapter Studio)") },
                supportingContent = { Text("YAML / JS によるサイト別解析ルールの追加・編集") },
                leadingContent = { Icon(Icons.Default.Extension, contentDescription = null) },
                modifier = Modifier.clickable {
                    viewModel.navigateTo(Screen.AdapterStudio)
                }
            )
        }
    }

    // CSVテキスト貼り付けダイアログ
    if (showImportTextDialog) {
        AlertDialog(
            onDismissRequest = { showImportTextDialog = false },
            title = { Text("CSVテキストの貼り付け") },
            text = {
                Column {
                    Text("URLを含むCSV形式テキストを貼り付けてください。", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputCsvText,
                        onValueChange = { inputCsvText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        placeholder = { Text("https://ncode.syosetu.com/n1234a/,\nhttps://novel18.syosetu.com/n5678b/") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportTextDialog = false
                    scope.launch {
                        val res = viewModel.csvSyncManager.importCsvText(inputCsvText)
                        Toast.makeText(context, "${res.addedCount}件追加, ${res.skippedCount}件スキップ", Toast.LENGTH_SHORT).show()
                        viewModel.loadBookshelf()
                    }
                }) {
                    Text("インポート")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportTextDialog = false }) { Text("キャンセル") }
            }
        )
    }
}
