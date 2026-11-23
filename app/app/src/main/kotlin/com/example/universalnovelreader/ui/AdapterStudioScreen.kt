package com.example.universalnovelreader.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.universalnovelreader.data.catalog.AdapterPackage
import com.example.universalnovelreader.data.catalog.AdapterPackageCodec
import com.example.universalnovelreader.data.model.SiteAdapter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterStudioScreen(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapters by viewModel.adapters.collectAsState()

    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var isFetchingUrl by remember { mutableStateOf(false) }
    var pendingPackage by remember { mutableStateOf<AdapterPackage?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val raw = stream.bufferedReader(Charsets.UTF_8).readText()
                    pendingPackage = AdapterPackageCodec.decodeFromText(raw)
                    showImportDialog = false
                }
            } catch (e: Exception) {
                Toast.makeText(context, "読み込み失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("サイト定義管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Settings) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        importText = ""
                        showImportDialog = true
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "定義を取り込む")
                    }
                    IconButton(onClick = { viewModel.navigateTo(Screen.AdapterEdit(null)) }) {
                        Icon(Icons.Default.Add, contentDescription = "定義追加")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.navigateTo(Screen.AdapterEdit(null)) }) {
                Icon(Icons.Default.Add, contentDescription = "定義追加")
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // マーケット誘導カード（公式・野良からワンタップ導入）
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable { viewModel.navigateTo(Screen.AdapterMarket) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "サイト定義マーケット",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "公式・野良（掲示板）の定義を探してワンタップで追加・更新",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "導入済みのサイト定義 (${adapters.size}件)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    importText = ""
                    showImportDialog = true
                }) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("コード/ファイル取込")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp)
            ) {
                items(adapters, key = { it.id }) { adapter ->
                    AdapterListItem(
                        adapter = adapter,
                        onEdit = { viewModel.navigateTo(Screen.AdapterEdit(adapter.id)) },
                        onDelete = {
                            viewModel.deleteAdapter(adapter.id)
                            Toast.makeText(context, "定義を削除しました", Toast.LENGTH_SHORT).show()
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // 共有コード / JSON / URL / ファイル 取り込みダイアログ
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isFetchingUrl) showImportDialog = false },
            title = { Text("サイト定義を取り込む") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "掲示板の共有コード (UNR1:...)、JSON、または .unr.json の URL を貼り付けるか、ファイルを選択してください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clip.isNotBlank()) {
                                    importText = clip
                                } else {
                                    Toast.makeText(context, "クリップボードが空です", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("貼付")
                        }
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ファイル選択")
                        }
                    }
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("UNR1:... / {...} / https://...unr.json") },
                        minLines = 5,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isFetchingUrl && importText.isNotBlank(),
                    onClick = {
                        val trimmed = importText.trim()
                        if ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && !trimmed.contains('\n')) {
                            scope.launch {
                                isFetchingUrl = true
                                val res = viewModel.catalogClient.fetchAdapterPackage(trimmed)
                                isFetchingUrl = false
                                if (res.isSuccess) {
                                    pendingPackage = res.getOrNull()
                                    showImportDialog = false
                                } else {
                                    Toast.makeText(context, "URLからの取得に失敗: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            try {
                                pendingPackage = AdapterPackageCodec.decodeFromText(trimmed)
                                showImportDialog = false
                            } catch (e: Exception) {
                                Toast.makeText(context, "解析エラー: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text(if (isFetchingUrl) "取得中..." else "内容を確認")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isFetchingUrl,
                    onClick = { showImportDialog = false }
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    pendingPackage?.let { pkg ->
        val existing = adapters.firstOrNull { it.adapterId == pkg.adapterId }
        AdapterImportConfirmDialog(
            pkg = pkg,
            existing = existing,
            onDismiss = { pendingPackage = null },
            onConfirm = {
                viewModel.importAdapterPackage(pkg) {
                    pendingPackage = null
                    Toast.makeText(
                        context,
                        if (existing != null) "「${pkg.name}」を更新しました" else "「${pkg.name}」を追加しました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@Composable
fun AdapterListItem(
    adapter: SiteAdapter,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = adapter.name, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (adapter.adapterType == "yaml") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = adapter.adapterType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (adapter.isBuiltin) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "プリセット",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "ID: ${adapter.adapterId} (v${adapter.version})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "パターン: ${adapter.domainPattern}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Row {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "編集")
            }
            if (!adapter.isBuiltin) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
