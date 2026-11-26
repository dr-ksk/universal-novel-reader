package com.example.universalnovelreader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.universalnovelreader.data.catalog.AdapterCatalogClient
import com.example.universalnovelreader.data.catalog.AdapterPackage
import com.example.universalnovelreader.data.catalog.CatalogEntry
import com.example.universalnovelreader.data.catalog.CustomCatalogSource
import com.example.universalnovelreader.data.model.SiteAdapter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterMarketScreen(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedAdapters by viewModel.adapters.collectAsState()
    val customSources by viewModel.customCatalogSources.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: 公式, 1: 野良, 2: カスタム
    var selectedCustomSource by remember { mutableStateOf<CustomCatalogSource?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortByLikes by remember { mutableStateOf(true) }

    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pendingInstallPackage by remember { mutableStateOf<AdapterPackage?>(null) }
    var pendingInstallEntry by remember { mutableStateOf<CatalogEntry?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var votingId by remember { mutableStateOf<String?>(null) }
    var likedIds by remember { mutableStateOf(viewModel.catalogClient.loadLikedAdapterIds()) }
    var showAddSourceDialog by remember { mutableStateOf(false) }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "ブラウザを開けませんでした: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadCurrentCatalog() {
        val targetUrl = when (selectedTab) {
            0 -> AdapterCatalogClient.OFFICIAL_CATALOG_URL
            1 -> AdapterCatalogClient.COMMUNITY_CATALOG_URL
            else -> selectedCustomSource?.url
        }
        if (targetUrl.isNullOrBlank()) {
            entries = emptyList()
            errorMessage = null
            return
        }
        scope.launch {
            isLoading = true
            errorMessage = null
            val res = viewModel.catalogClient.fetchCatalog(targetUrl)
            isLoading = false
            if (res.isSuccess) {
                entries = res.getOrNull()?.adapters ?: emptyList()
            } else {
                entries = emptyList()
                errorMessage = "カタログの取得に失敗しました。\n通信環境を確認して再試行してください。\n(${res.exceptionOrNull()?.message ?: "不明なエラー"})"
            }
        }
    }

    LaunchedEffect(selectedTab, selectedCustomSource) {
        if (selectedTab == 2 && selectedCustomSource == null && customSources.isNotEmpty()) {
            selectedCustomSource = customSources.first()
        } else {
            loadCurrentCatalog()
        }
    }

    val filteredEntries = remember(entries, searchQuery, selectedTab, sortByLikes) {
        val q = searchQuery.trim().lowercase()
        val matched = if (q.isEmpty()) entries else entries.filter { e ->
            e.name.lowercase().contains(q) ||
                e.adapterId.lowercase().contains(q) ||
                e.description.lowercase().contains(q) ||
                e.author.lowercase().contains(q) ||
                e.domainPattern.lowercase().contains(q)
        }
        if (selectedTab == 1) {
            if (sortByLikes) {
                matched.sortedWith(compareByDescending<CatalogEntry> { it.likes }.thenByDescending { it.updatedAt ?: "" })
            } else {
                matched.sortedByDescending { it.updatedAt ?: "" }
            }
        } else {
            matched
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("サイト定義マーケット", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.AdapterStudio) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { loadCurrentCatalog() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
                    }
                    IconButton(onClick = { openExternalUrl(AdapterCatalogClient.WEB_HUB_URL) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Webビルダーを開く")
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
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("公式") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("野良・掲示板") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("カスタム") })
            }

            // カスタムカタログ選択バー
            if (selectedTab == 2) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("外部カタログURL (${customSources.size}件)", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { showAddSourceDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("URL追加")
                            }
                        }
                        if (customSources.isEmpty()) {
                            Text(
                                text = "有志が公開している GitHub Pages 等の index.json URL を追加して閲覧できます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            customSources.forEach { src ->
                                val isSelected = selectedCustomSource?.url == src.url
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedCustomSource = src }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedCustomSource = src }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(src.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(src.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = {
                                        viewModel.removeCustomCatalogSource(src.url)
                                        if (selectedCustomSource?.url == src.url) {
                                            selectedCustomSource = null
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "削除", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 検索バー
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("サイト名・ドメイン・作者で検索") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "クリア")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            // 野良タブの案内＆いいね順ソート＆Webビルダー誘導
            if (selectedTab == 1) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "動いた定義には「👍 いいね」を押すと、安全・動作確認の目安になります。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { openExternalUrl(AdapterCatalogClient.WEB_HUB_URL) }) {
                                Text("ビルダー")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = sortByLikes,
                                onClick = { sortByLikes = true },
                                label = { Text("👍 いいね順") }
                            )
                            FilterChip(
                                selected = !sortByLikes,
                                onClick = { sortByLikes = false },
                                label = { Text("新着順") }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { loadCurrentCatalog() }) {
                                Text("再試行")
                            }
                        }
                    }
                }

                !isLoading && filteredEntries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when {
                                    selectedTab == 1 && entries.isEmpty() -> "まだ野良サイト定義の投稿がありません。\nWebビルダーから最初の定義を投稿できます。"
                                    selectedTab == 2 && selectedCustomSource == null -> "上の「URL追加」から外部カタログを登録してください。"
                                    else -> "該当するサイト定義がありません。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selectedTab == 1) {
                                OutlinedButton(onClick = { openExternalUrl(AdapterCatalogClient.WEB_HUB_URL) }) {
                                    Text("Webビルダーで投稿する")
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredEntries, key = { "${it.adapterId}:${it.downloadUrl}" }) { entry ->
                            val existing = installedAdapters.firstOrNull { it.adapterId == entry.adapterId }
                            MarketEntryCard(
                                entry = entry,
                                existing = existing,
                                showCommunityTrust = selectedTab == 1,
                                isDownloading = downloadingId == entry.adapterId,
                                isLiked = likedIds.contains(entry.adapterId),
                                isVoting = votingId == entry.adapterId,
                                onInstallClick = {
                                    scope.launch {
                                        downloadingId = entry.adapterId
                                        val res = viewModel.catalogClient.fetchAdapterPackage(entry.downloadUrl)
                                        downloadingId = null
                                        if (res.isSuccess) {
                                            pendingInstallEntry = entry
                                            pendingInstallPackage = res.getOrNull()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "定義のダウンロードに失敗しました: ${res.exceptionOrNull()?.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                onLikeClick = if (selectedTab == 1) {
                                    {
                                        scope.launch {
                                            votingId = entry.adapterId
                                            val res = viewModel.catalogClient.toggleCommunityLike(entry.adapterId)
                                            votingId = null
                                            val vote = res.getOrNull()
                                            if (vote != null) {
                                                likedIds = viewModel.catalogClient.loadLikedAdapterIds()
                                                entries = entries.map { item ->
                                                    if (item.adapterId == vote.adapterId) {
                                                        item.copy(likes = vote.likes, reports = vote.reports)
                                                    } else item
                                                }
                                                Toast.makeText(
                                                    context,
                                                    if (vote.voted) "「${entry.name}」に 👍 いいねしました" else "「${entry.name}」の 👍 いいねを取り消しました",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "いいねの送信に失敗しました: ${res.exceptionOrNull()?.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } else null,
                                onOpenDiscussion = entry.discussionUrl?.let { url ->
                                    { openExternalUrl(url) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 取り込み前確認ダイアログ（安全性チェック＆差分表示）
    pendingInstallPackage?.let { pkg ->
        val existing = installedAdapters.firstOrNull { it.adapterId == pkg.adapterId }
        AdapterImportConfirmDialog(
            pkg = pkg,
            existing = existing,
            likesCount = pendingInstallEntry?.likes,
            reportsCount = pendingInstallEntry?.reports,
            onDismiss = {
                pendingInstallPackage = null
                pendingInstallEntry = null
            },
            onConfirm = {
                viewModel.importAdapterPackage(pkg) {
                    pendingInstallPackage = null
                    pendingInstallEntry = null
                    Toast.makeText(
                        context,
                        if (existing != null) "「${pkg.name}」を v${pkg.version} へ更新しました" else "「${pkg.name}」を追加しました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (showAddSourceDialog) {
        AddCustomCatalogDialog(
            onDismiss = { showAddSourceDialog = false },
            onAdd = { name, url ->
                viewModel.addCustomCatalogSource(name, url)
                selectedCustomSource = CustomCatalogSource(name.ifBlank { url }, url)
                showAddSourceDialog = false
            }
        )
    }
}

@Composable
private fun MarketEntryCard(
    entry: CatalogEntry,
    existing: SiteAdapter?,
    showCommunityTrust: Boolean,
    isDownloading: Boolean,
    isLiked: Boolean = false,
    isVoting: Boolean = false,
    onInstallClick: () -> Unit,
    onLikeClick: (() -> Unit)? = null,
    onOpenDiscussion: (() -> Unit)? = null
) {
    val isInstalled = existing != null
    val hasUpdate = existing != null && existing.version != entry.version
    val isTrustedCommunity = showCommunityTrust && entry.likes >= 3 && entry.reports == 0

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        color = if (entry.adapterType == "yaml") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = entry.adapterType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "v${entry.version}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (showCommunityTrust) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (isTrustedCommunity) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = if (isTrustedCommunity) {
                                "✓ 動作確認済み 👍 ${entry.likes}"
                            } else {
                                "👍 いいね ${entry.likes}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isTrustedCommunity) FontWeight.Bold else FontWeight.Normal,
                            color = if (isTrustedCommunity) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (entry.reports > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "👎 報告 ${entry.reports}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = entry.domainPattern,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${entry.adapterId}" + if (entry.author.isNotBlank()) " / 作者: ${entry.author}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onLikeClick != null) {
                        if (isLiked) {
                            FilledTonalButton(
                                onClick = onLikeClick,
                                enabled = !isVoting,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(if (isVoting) "..." else "👍 済み", fontSize = 12.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onLikeClick,
                                enabled = !isVoting,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(if (isVoting) "..." else "👍 いいね", fontSize = 12.sp)
                            }
                        }
                    } else if (onOpenDiscussion != null) {
                        OutlinedButton(
                            onClick = onOpenDiscussion,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("👍 いいね", fontSize = 12.sp)
                        }
                    }

                    Button(
                        onClick = onInstallClick,
                        enabled = !isDownloading,
                        colors = when {
                            hasUpdate -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            isInstalled -> ButtonDefaults.filledTonalButtonColors()
                            else -> ButtonDefaults.buttonColors()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = when {
                                    hasUpdate -> "更新 (${existing.version}→${entry.version})"
                                    isInstalled -> "導入済み"
                                    else -> "追加"
                                },
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdapterImportConfirmDialog(
    pkg: AdapterPackage,
    existing: SiteAdapter?,
    likesCount: Int? = null,
    reportsCount: Int? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var showCode by remember { mutableStateOf(pkg.adapterType == "javascript") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existing != null) "サイト定義を上書き更新" else "サイト定義を取り込む",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("サイト名: ${pkg.name}", fontWeight = FontWeight.Bold)
                Text(
                    text = if (existing != null) {
                        "ID: ${pkg.adapterId} (v${existing.version} → v${pkg.version})"
                    } else {
                        "ID: ${pkg.adapterId} (v${pkg.version})"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (likesCount != null) {
                    val reportNote = if ((reportsCount ?: 0) > 0) " / 👎 報告 ${reportsCount}件" else ""
                    val trustLabel = when {
                        likesCount >= 3 && (reportsCount ?: 0) == 0 -> "✓ コミュニティ動作確認済み（👍 ${likesCount}件）"
                        likesCount > 0 -> "👍 いいね ${likesCount}件$reportNote"
                        else -> "👍 いいね 0件（まだ動作確認評価がありません）$reportNote"
                    }
                    Surface(
                        color = if (likesCount >= 3 && (reportsCount ?: 0) == 0) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = trustLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (pkg.author.isNotBlank()) {
                    Text("作成者: ${pkg.author}", style = MaterialTheme.typography.bodySmall)
                }
                if (pkg.description.isNotBlank()) {
                    Text(pkg.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "対象ドメイン:\n${pkg.domainPattern}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                )

                if (pkg.adapterType == "javascript") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠ JavaScript形式の定義です。WebView上でコードが実行されます。信頼できる提供元の定義か下のコードを確認した上で取り込んでください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (pkg.hasBroadDomainPattern()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠ 対象ドメインの正規表現が非常に広い設定です。意図しないサイトにも適用される可能性があります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                TextButton(onClick = { showCode = !showCode }) {
                    Text(if (showCode) "▲ 定義の中身を閉じる" else "▼ 定義の中身を確認する (${pkg.adapterType.uppercase()})")
                }

                if (showCode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = pkg.content,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (existing != null) "上書き更新する" else "取り込む")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun AddCustomCatalogDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カスタムカタログURLを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "GitHub Pages 等で公開されている index.json の URL を入力してください。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("表示名（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("カタログ index.json の URL") },
                    placeholder = { Text("https://.../index.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name.trim(), url.trim()) },
                enabled = url.trim().startsWith("http://") || url.trim().startsWith("https://")
            ) {
                Text("追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
