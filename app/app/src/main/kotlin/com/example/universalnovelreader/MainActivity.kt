package com.example.universalnovelreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.universalnovelreader.ui.ReaderViewModel
import com.example.universalnovelreader.ui.Screen
import com.example.universalnovelreader.ui.theme.UniversalNovelReaderTheme
import com.example.universalnovelreader.worker.NovelSyncWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            UniversalNovelReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // バックグラウンド定期更新の結果を復帰時に反映
        viewModel.loadBookshelf()
        viewModel.reloadAutoSyncSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // 新着通知タップで作品詳細へ直接遷移
        val openNovelId = intent.getLongExtra(NovelSyncWorker.EXTRA_NOVEL_ID, -1L)
        if (openNovelId > 0L) {
            intent.removeExtra(NovelSyncWorker.EXTRA_NOVEL_ID)
            viewModel.navigateTo(Screen.NovelDetail(openNovelId))
            return
        }

        val rawText = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return

        // テキスト内から http/https の URL を正規表現で抽出
        val urlRegex = Regex("""https?://[^\s]+""")
        val match = urlRegex.find(rawText) ?: return
        val targetUrl = match.value.trim()

        viewModel.registerNovelFromUrl(targetUrl) { success ->
            if (success) {
                lifecycleScope.launch {
                    val novel = viewModel.repository.getNovelByUrl(targetUrl)
                    if (novel != null) {
                        viewModel.navigateTo(Screen.NovelDetail(novel.id))
                    }
                }
            }
        }
    }
}
