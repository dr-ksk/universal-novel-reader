package com.example.universalnovelreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.Novel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novelId: Long,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val novel by viewModel.selectedNovel.collectAsState()
    val episodes by viewModel.currentEpisodes.collectAsState()
    val adapter by viewModel.currentAdapter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var synopsisExpanded by remember { mutableStateOf(false) }
    var isAscending by remember { mutableStateOf(true) }

    val displayEpisodes = if (isAscending) episodes else episodes.reversed()

    // 続きを読む対象の話を特定（前回読んだ話、または最初の未読話、なければ第1話）
    val resumeEpisode: Episode? = remember(novel, episodes) {
        val lastReadId = novel?.lastReadEpisodeId
        if (lastReadId != null) {
            episodes.firstOrNull { it.id == lastReadId }
        } else {
            episodes.firstOrNull { !it.isRead } ?: episodes.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(novel?.title ?: "小説詳細", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Bookshelf) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { novel?.let { viewModel.refreshNovelIndex(it) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "目次更新")
                    }
                    IconButton(onClick = {
                        viewModel.deleteNovel(novelId)
                        viewModel.navigateTo(Screen.Bookshelf)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "小説削除")
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
            if (isLoading && statusMessage != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = statusMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            novel?.let { n ->
                // アダプター未登録時の案内カード
                if (adapter == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("サイト定義が登録されていません", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "このドメイン（${n.topUrl}）に対応するサイト定義（YAML/JS）がありません。定義を追加すると目次と本文が取得できます。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.navigateTo(Screen.AdapterStudio) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("定義を作成・追加")
                                }
                            }
                        }
                    }
                }

                // 小説ヘッダー情報
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = n.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (n.author.isNotEmpty()) {
                            Text(
                                text = "作者: ${n.author}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (n.synopsis.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = n.synopsis,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (synopsisExpanded) "▲ 閉じる" else "▼ あらすじをもっと見る",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { synopsisExpanded = !synopsisExpanded }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 「続きを読む」アクションボタン
                        resumeEpisode?.let { rep ->
                            Button(
                                onClick = {
                                    viewModel.navigateTo(Screen.Reader(novelId, rep.episodeIndex))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("続きを読む (第${rep.episodeIndex}話: ${rep.title})")
                            }
                        }
                    }
                }

                // 目次リストヘッダー
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "全 ${episodes.size} 話",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { isAscending = !isAscending }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (isAscending) "降順にする" else "昇順にする"
                        )
                    }
                }

                // エピソードリスト
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(displayEpisodes, key = { it.id }) { ep ->
                        EpisodeItem(
                            episode = ep,
                            onClick = {
                                viewModel.navigateTo(Screen.Reader(novelId, ep.episodeIndex))
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeItem(
    episode: Episode,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!episode.chapterTitle.isNullOrEmpty()) {
                Text(
                    text = episode.chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "${episode.episodeIndex}. ${episode.title}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (!episode.isRead) FontWeight.Bold else FontWeight.Normal
            )
            if (episode.updatedAtSite != null) {
                Text(
                    text = "更新: ${episode.updatedAtSite}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // キャッシュ済みバッジ
            if (episode.cachedAt != null) {
                Icon(
                    Icons.Default.DownloadDone,
                    contentDescription = "キャッシュ済み",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // 既読状態
            if (episode.isRead) {
                Text(
                    text = "既読",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
