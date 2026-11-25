package com.example.universalnovelreader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.universalnovelreader.data.model.Episode
import com.example.universalnovelreader.data.model.EpisodeCacheData
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    novelId: Long,
    initialEpisodeIndex: Int,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentEpIndex by remember { mutableStateOf(initialEpisodeIndex) }
    var currentEpisode by remember { mutableStateOf<Episode?>(null) }
    var currentData by remember { mutableStateOf<EpisodeCacheData?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var isForceReloading by remember { mutableStateOf(false) }
    var reloadCounter by remember { mutableStateOf(0) }

    val readerSettings by viewModel.readerSettings.collectAsState()
    val novel by viewModel.selectedNovel.collectAsState()
    val episodes by viewModel.currentEpisodes.collectAsState()
    val currentAdapter by viewModel.currentAdapter.collectAsState()

    val latestEpisodes by rememberUpdatedState(episodes)
    val latestCurrentEpisode by rememberUpdatedState(currentEpisode)
    var lastSlideDir by remember { mutableStateOf(0) } // 1: 次話(左スワイプ), -1: 前話(右スワイプ), 0: なし

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 現在のエピソードモデルを特定
    LaunchedEffect(currentEpIndex, episodes) {
        currentEpisode = episodes.firstOrNull { it.episodeIndex == currentEpIndex }
        android.util.Log.d("ReaderScreen", "currentEpisode resolved: index=$currentEpIndex, ep=$currentEpisode")
    }

    // エピソードのロード
    LaunchedEffect(novelId, currentEpIndex, currentEpisode) {
        val ep = currentEpisode
        if (ep == null) {
            android.util.Log.d("ReaderScreen", "currentEpisode is null, cannot load content")
            return@LaunchedEffect
        }
        android.util.Log.d("ReaderScreen", "Loading episode content for ep: ${ep.title}, url: ${ep.url}")
        val data = viewModel.getEpisodeContent(novelId, ep)
        android.util.Log.d("ReaderScreen", "Loaded episode data: title=${data?.episodeTitle}, contentLen=${data?.contentHtml?.length}")
        currentData = data
    }

    // データまたはWebViewの準備完了時に確実に本文を反映
    LaunchedEffect(currentData, webViewRef, episodes.size, reloadCounter) {
        val data = currentData
        val wv = webViewRef
        android.util.Log.d("ReaderScreen", "Check reflect: data=${data != null}, wv=${wv != null}")
        if (data != null && wv != null) {
            val ep = currentEpisode
            val hasPrev = currentEpIndex > 1
            val hasNext = currentEpIndex < episodes.size
            loadContentIntoWebView(
                webView = wv,
                data = data,
                initialScrollProgress = ep?.scrollPosition ?: 0f,
                hasPrev = hasPrev,
                hasNext = hasNext,
                slideDir = lastSlideDir
            )
            lastSlideDir = 0
        }
    }

    // リーダー設定が更新された時のスタイル動的適用
    LaunchedEffect(readerSettings, webViewRef) {
        webViewRef?.let { wv ->
            applyStylesToWebView(wv, readerSettings)
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showMenu,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentData?.episodeTitle ?: currentEpisode?.title ?: "読書中",
                                maxLines = 1,
                                fontSize = 16.sp
                            )
                            Text(
                                text = novel?.title ?: "",
                                maxLines = 1,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.navigateTo(Screen.NovelDetail(novelId)) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !isForceReloading && currentEpisode != null,
                            onClick = {
                                val ep = currentEpisode ?: return@IconButton
                                scope.launch {
                                    isForceReloading = true
                                    val refreshed = viewModel.getEpisodeContent(novelId, ep, forceRefresh = true)
                                    isForceReloading = false
                                    if (refreshed != null) {
                                        currentData = refreshed
                                        reloadCounter++
                                        Toast.makeText(context, "この話を強制再キャッシュしました", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "再キャッシュに失敗しました", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            if (isForceReloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "この話を強制再キャッシュ")
                            }
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.FormatSize, contentDescription = "表示設定")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showMenu,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (currentEpIndex > 1) {
                                    lastSlideDir = -1
                                    currentEpIndex--
                                }
                            },
                            enabled = currentEpIndex > 1
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null)
                            Text("前の話")
                        }

                        Text(
                            text = "$currentEpIndex / ${episodes.size}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = {
                                if (currentEpIndex < episodes.size) {
                                    lastSlideDir = 1
                                    currentEpIndex++
                                }
                            },
                            enabled = currentEpIndex < episodes.size
                        ) {
                            Text("次の話")
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }

                        // ネイティブ連携インターフェース
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun toggleMenu() {
                                scope.launch { showMenu = !showMenu }
                            }

                            @JavascriptInterface
                            fun onProgress(progress: Float) {
                                latestCurrentEpisode?.let { ep ->
                                    viewModel.updateReadingProgress(novelId, ep.id, progress)
                                }
                            }

                            @JavascriptInterface
                            fun navigateNext() {
                                scope.launch {
                                    if (currentEpIndex < latestEpisodes.size) {
                                        showMenu = false
                                        lastSlideDir = 1
                                        currentEpIndex++
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun navigatePrev() {
                                scope.launch {
                                    if (currentEpIndex > 1) {
                                        showMenu = false
                                        lastSlideDir = -1
                                        currentEpIndex--
                                    }
                                }
                            }
                        }, "ReaderBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentData?.let { d ->
                                    latestCurrentEpisode?.let { ep ->
                                        loadContentIntoWebView(
                                            webView = this@apply,
                                            data = d,
                                            initialScrollProgress = ep.scrollPosition,
                                            hasPrev = currentEpIndex > 1,
                                            hasNext = currentEpIndex < latestEpisodes.size,
                                            slideDir = 0
                                        )
                                    }
                                }
                            }
                        }

                        webViewRef = this
                        loadHtmlTemplate(this, readerSettings, currentAdapter?.customCss)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // 表示設定ボトムシート
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "リーダー表示設定",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 1. カラーテーマ選択
                Text("テーマ", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = listOf(
                        "white" to ("白" to Color.White),
                        "sepia" to ("セピア" to Color(0xFFF7F6F2)),
                        "dark" to ("ダーク" to Color(0xFF232323)),
                        "black" to ("漆黒" to Color.Black)
                    )
                    for ((themeKey, labelAndColor) in themes) {
                        val isSelected = readerSettings.theme == themeKey
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(
                                    labelAndColor.second,
                                    shape = MaterialTheme.shapes.small
                                )
                                .clickable {
                                    viewModel.saveReaderSettings(readerSettings.copy(theme = themeKey))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = labelAndColor.first,
                                color = if (themeKey == "dark" || themeKey == "black") Color.White else Color.Black,
                                style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. フォント種別（ゴシック / 明朝）
                Text("フォント", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val fontOptions = listOf(
                        "sans-serif" to "ゴシック体 (標準)",
                        "serif" to "明朝体"
                    )
                    for ((fontKey, fontLabel) in fontOptions) {
                        val isSelected = (readerSettings.fontFamily == fontKey) || (fontKey == "sans-serif" && readerSettings.fontFamily == "default")
                        OutlinedButton(
                            onClick = {
                                viewModel.saveReaderSettings(readerSettings.copy(fontFamily = fontKey))
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = fontLabel,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. 文字サイズスライダー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("文字サイズ (${readerSettings.fontSize.toInt()}px)", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = readerSettings.fontSize,
                        onValueChange = { newSize ->
                            viewModel.saveReaderSettings(readerSettings.copy(fontSize = newSize))
                        },
                        valueRange = 12f..32f,
                        steps = 20,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4. 行間スライダー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("行間 (${String.format(java.util.Locale.US, "%.1f", readerSettings.lineHeight)})", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = readerSettings.lineHeight,
                        onValueChange = { newLineHeight ->
                            viewModel.saveReaderSettings(readerSettings.copy(lineHeight = newLineHeight))
                        },
                        valueRange = 1.2f..2.5f,
                        steps = 13,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. 左右余白スライダー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("左右余白 (${readerSettings.marginHorizontal.toInt()}px)", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = readerSettings.marginHorizontal,
                        onValueChange = { newMargin ->
                            viewModel.saveReaderSettings(readerSettings.copy(marginHorizontal = newMargin))
                        },
                        valueRange = 8f..36f,
                        steps = 14,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

private fun loadHtmlTemplate(
    webView: WebView,
    settings: com.example.universalnovelreader.data.model.ReaderSettings,
    adapterCustomCss: String? = null
) {
    val html = """
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style id="reader-styles">
:root {
  --reader-font-family: ${getFontFamilyCss(settings.fontFamily)};
  --reader-font-size: ${settings.fontSize}px;
  --reader-line-height: ${settings.lineHeight};
  --reader-letter-spacing: ${settings.letterSpacing}em;
  --reader-side-margin: ${settings.marginHorizontal}px;
  --reader-top-margin: ${settings.marginVertical}px;
  --reader-bg-color: ${getBgColorForTheme(settings.theme)};
  --reader-text-color: ${getTextColorForTheme(settings.theme)};
  --reader-ruby-color: #777;
}

html, body {
  margin: 0;
  padding: 0;
  background-color: var(--reader-bg-color);
  color: var(--reader-text-color);
  font-family: var(--reader-font-family);
  font-size: var(--reader-font-size);
  line-height: var(--reader-line-height);
  letter-spacing: var(--reader-letter-spacing);
  writing-mode: horizontal-tb;
  text-align: justify;
  word-break: break-all;
  overflow-x: hidden;
  overflow-y: scroll;
  -webkit-overflow-scrolling: touch;
}

#content-container {
  padding: var(--reader-top-margin) var(--reader-side-margin) 120px var(--reader-side-margin);
  box-sizing: border-box;
  max-width: 800px;
  margin: 0 auto;
}

.chapter-title {
  font-size: 0.88em;
  color: #888;
  margin-bottom: 8px;
  font-weight: normal;
}

.episode-title {
  font-size: 1.32em;
  font-weight: bold;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.4);
  line-height: 1.4;
}

#body-text {
  font-size: 1.0em;
}

#body-text p {
  margin: 0 0 0.85em 0;
}

ruby rt {
  font-size: 0.52em;
  color: var(--reader-ruby-color);
}

img {
  max-width: 100%;
  height: auto;
  display: block;
  margin: 16px auto;
}

.footer-nav {
  margin-top: 48px;
  padding-top: 24px;
  border-top: 1px solid rgba(128, 128, 128, 0.3);
  display: flex;
  justify-content: space-between;
  writing-mode: horizontal-tb;
}

.footer-nav button {
  padding: 10px 18px;
  font-size: 14px;
  border: 1px solid #aaa;
  background: transparent;
  color: inherit;
  border-radius: 4px;
}

.footer-nav button:disabled {
  opacity: 0.35;
}

/* スワイプ遷移インジケーター */
.swipe-indicator {
  position: fixed;
  top: 50%;
  transform: translateY(-50%) scale(0.92);
  padding: 10px 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: rgba(20, 32, 42, 0.78);
  color: #ffffff;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.14s ease, transform 0.14s ease, background-color 0.14s ease;
  z-index: 999;
  white-space: nowrap;
}

#swipe-indicator-prev {
  left: 14px;
}

#swipe-indicator-next {
  right: 14px;
}

.swipe-indicator.ready {
  background: rgba(0, 80, 110, 0.94);
  transform: translateY(-50%) scale(1.0);
}

.swipe-indicator.disabled {
  background: rgba(80, 80, 80, 0.65);
}

#content-container {
  transition: transform 0.16s ease-out, opacity 0.16s ease-out;
  will-change: transform, opacity;
}

/* ユーザー/ドメインカスタムCSS */
${settings.customCss ?: ""}
${adapterCustomCss ?: ""}
</style>
</head>
<body onclick="handleClick(event)">
  <div id="swipe-indicator-prev" class="swipe-indicator">← 前の話</div>
  <div id="swipe-indicator-next" class="swipe-indicator">次の話 →</div>
  <div id="content-container">
    <div id="chapter-title" class="chapter-title"></div>
    <div id="episode-title" class="episode-title"></div>
    <div id="body-text"></div>
    <div class="footer-nav">
      <button id="btn-prev" onclick="event.stopPropagation(); window.ReaderBridge.navigatePrev();">← 前の話</button>
      <button id="btn-next" onclick="event.stopPropagation(); window.ReaderBridge.navigateNext();">次の話 →</button>
    </div>
  </div>

<script>
var navState = { hasPrev: true, hasNext: true };
var scrollTimer = null;
var justSwiped = false;

window.updateContent = function(chapter, title, html, scrollProgress, hasPrev, hasNext, slideDir) {
  clearTimeout(scrollTimer);
  navState.hasPrev = !!hasPrev;
  navState.hasNext = !!hasNext;

  var chapEl = document.getElementById('chapter-title');
  var titleEl = document.getElementById('episode-title');
  var bodyEl = document.getElementById('body-text');
  var container = document.getElementById('content-container');
  var btnPrev = document.getElementById('btn-prev');
  var btnNext = document.getElementById('btn-next');

  if (chapEl) chapEl.textContent = chapter || '';
  if (titleEl) titleEl.textContent = title || '';
  if (bodyEl) bodyEl.innerHTML = html || '';
  if (btnPrev) btnPrev.disabled = !navState.hasPrev;
  if (btnNext) btnNext.disabled = !navState.hasNext;

  if (container && slideDir) {
    container.style.transition = 'none';
    container.style.opacity = '0.25';
    container.style.transform = 'translateX(' + (slideDir > 0 ? '36px' : '-36px') + ')';
    void container.offsetWidth;
    container.style.transition = 'transform 0.18s ease-out, opacity 0.18s ease-out';
    container.style.opacity = '1';
    container.style.transform = 'translateX(0)';
  } else if (container) {
    container.style.opacity = '1';
    container.style.transform = 'translateX(0)';
  }

  if (scrollProgress > 0) {
    setTimeout(function() {
      var maxScroll = document.documentElement.scrollHeight - window.innerHeight;
      if (maxScroll > 0) {
        window.scrollTo(0, maxScroll * scrollProgress);
      } else {
        window.scrollTo(0, 0);
      }
    }, 80);
  } else {
    window.scrollTo(0, 0);
  }
};

function handleClick(e) {
  if (justSwiped) {
    justSwiped = false;
    return;
  }
  // 画面中央付近のタップでメニューを表示
  var h = window.innerHeight;
  var y = e.clientY;
  if (y > h * 0.25 && y < h * 0.75) {
    window.ReaderBridge.toggleMenu();
  }
}

// 左右スワイプで前後の話へ移動（縦スクロール・エッジ戻るジェスチャーとの競合を回避）
(function setupSwipeNavigation() {
  var startX = 0;
  var startY = 0;
  var mode = null; // null | 'horizontal' | 'vertical'
  var currentDx = 0;
  var edgeMargin = 28;
  var lockDistance = 14;
  var prevInd = document.getElementById('swipe-indicator-prev');
  var nextInd = document.getElementById('swipe-indicator-next');
  var container = document.getElementById('content-container');

  function getThreshold() {
    return Math.max(72, Math.min(120, window.innerWidth * 0.22));
  }

  function resetVisuals() {
    if (prevInd) {
      prevInd.style.opacity = '0';
      prevInd.classList.remove('ready', 'disabled');
    }
    if (nextInd) {
      nextInd.style.opacity = '0';
      nextInd.classList.remove('ready', 'disabled');
    }
    if (container) {
      container.style.transition = 'transform 0.16s ease-out, opacity 0.16s ease-out';
      container.style.transform = 'translateX(0)';
    }
  }

  document.addEventListener('touchstart', function(e) {
    if (e.touches.length !== 1) {
      mode = 'vertical';
      return;
    }
    var sel = window.getSelection ? window.getSelection().toString() : '';
    if (sel && sel.length > 0) {
      mode = 'vertical';
      return;
    }
    var t = e.touches[0];
    startX = t.clientX;
    startY = t.clientY;
    currentDx = 0;
    // システムの画面端「戻る」ジェスチャー領域は除外
    if (startX < edgeMargin || startX > window.innerWidth - edgeMargin) {
      mode = 'vertical';
    } else {
      mode = null;
    }
  }, { passive: true });

  document.addEventListener('touchmove', function(e) {
    if (mode === 'vertical' || e.touches.length !== 1) return;
    var t = e.touches[0];
    var dx = t.clientX - startX;
    var dy = t.clientY - startY;

    if (mode === null) {
      if (Math.abs(dx) < lockDistance && Math.abs(dy) < lockDistance) return;
      if (Math.abs(dx) > Math.abs(dy) * 1.6) {
        mode = 'horizontal';
      } else {
        mode = 'vertical';
        return;
      }
    }

    if (mode === 'horizontal') {
      e.preventDefault();
      currentDx = dx;
      var threshold = getThreshold();
      var progress = Math.min(1, Math.abs(dx) / threshold);
      var ready = Math.abs(dx) >= threshold;

      // 本文を軽く追従させる（抵抗感あり）
      if (container) {
        var shift = Math.max(-44, Math.min(44, dx * 0.22));
        container.style.transition = 'none';
        container.style.transform = 'translateX(' + shift + 'px)';
      }

      if (dx > 0) {
        // 右スワイプ → 前の話
        if (nextInd) nextInd.style.opacity = '0';
        if (prevInd) {
          prevInd.textContent = navState.hasPrev ? '← 前の話' : '最初の話です';
          prevInd.style.opacity = String(0.25 + progress * 0.75);
          prevInd.classList.toggle('ready', ready && navState.hasPrev);
          prevInd.classList.toggle('disabled', !navState.hasPrev);
        }
      } else {
        // 左スワイプ → 次の話
        if (prevInd) prevInd.style.opacity = '0';
        if (nextInd) {
          nextInd.textContent = navState.hasNext ? '次の話 →' : '最新の話です';
          nextInd.style.opacity = String(0.25 + progress * 0.75);
          nextInd.classList.toggle('ready', ready && navState.hasNext);
          nextInd.classList.toggle('disabled', !navState.hasNext);
        }
      }
    }
  }, { passive: false });

  document.addEventListener('touchend', function() {
    if (mode !== 'horizontal') {
      mode = null;
      return;
    }
    var threshold = getThreshold();
    var dx = currentDx;
    mode = null;
    currentDx = 0;
    justSwiped = true;
    setTimeout(function() { justSwiped = false; }, 250);

    resetVisuals();

    if (dx <= -threshold && navState.hasNext) {
      if (container) container.style.opacity = '0.45';
      window.ReaderBridge.navigateNext();
    } else if (dx >= threshold && navState.hasPrev) {
      if (container) container.style.opacity = '0.45';
      window.ReaderBridge.navigatePrev();
    }
  }, { passive: true });

  document.addEventListener('touchcancel', function() {
    if (mode === 'horizontal') {
      resetVisuals();
    }
    mode = null;
    currentDx = 0;
  }, { passive: true });
})();

// スクロール進捗監視（デバウンス）
window.addEventListener('scroll', function() {
  clearTimeout(scrollTimer);
  scrollTimer = setTimeout(function() {
    var maxScroll = document.documentElement.scrollHeight - window.innerHeight;
    if (maxScroll > 0) {
      var progress = window.scrollY / maxScroll;
      window.ReaderBridge.onProgress(progress);
    }
  }, 300);
});
</script>
</body>
</html>
""".trimIndent()
    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
}

private fun loadContentIntoWebView(
    webView: WebView,
    data: EpisodeCacheData,
    initialScrollProgress: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    slideDir: Int
) {
    val chapter = JSONObject.quote(data.chapterTitle ?: "")
    val title = JSONObject.quote(data.episodeTitle)
    val content = JSONObject.quote(data.contentHtml)
    val js = """
        (function() {
            if (typeof window.updateContent === 'function') {
                window.updateContent($chapter, $title, $content, $initialScrollProgress, $hasPrev, $hasNext, $slideDir);
            }
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun applyStylesToWebView(webView: WebView, settings: com.example.universalnovelreader.data.model.ReaderSettings) {
    val fontFamilyCss = JSONObject.quote(getFontFamilyCss(settings.fontFamily))
    val js = """
        (function() {
            var root = document.documentElement;
            if (root && root.style) {
                root.style.setProperty('--reader-font-family', $fontFamilyCss);
                root.style.setProperty('--reader-font-size', '${settings.fontSize}px');
                root.style.setProperty('--reader-line-height', '${settings.lineHeight}');
                root.style.setProperty('--reader-side-margin', '${settings.marginHorizontal}px');
                root.style.setProperty('--reader-writing-mode', 'horizontal-tb');
                root.style.setProperty('--reader-bg-color', '${getBgColorForTheme(settings.theme)}');
                root.style.setProperty('--reader-text-color', '${getTextColorForTheme(settings.theme)}');
            }
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun getFontFamilyCss(fontFamily: String): String = when (fontFamily) {
    "serif" -> "\"Hiragino Mincho ProN\", \"Yu Mincho\", \"Noto Serif CJK JP\", serif"
    else -> "system-ui, -apple-system, \"Noto Sans CJK JP\", \"Hiragino Sans\", sans-serif"
}

private fun getBgColorForTheme(theme: String): String = when (theme) {
    "white" -> "#ffffff"
    "sepia" -> "#f7f6f2"
    "dark" -> "#232323"
    "black" -> "#000000"
    else -> "#f7f6f2"
}

private fun getTextColorForTheme(theme: String): String = when (theme) {
    "white" -> "#222222"
    "sepia" -> "#2c2a29"
    "dark" -> "#dddddd"
    "black" -> "#cccccc"
    else -> "#2c2a29"
}
