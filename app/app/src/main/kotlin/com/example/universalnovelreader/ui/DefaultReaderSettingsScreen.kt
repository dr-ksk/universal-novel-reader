package com.example.universalnovelreader.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultReaderSettingsScreen(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.readerSettings.collectAsState()
    var customCss by remember(settings.customCss) { mutableStateOf(settings.customCss ?: "") }

    val previewBg = when (settings.theme) {
        "white" -> Color(0xFFFFFFFF)
        "sepia" -> Color(0xFFF7F6F2)
        "dark" -> Color(0xFF232323)
        "black" -> Color(0xFF000000)
        else -> Color(0xFFF7F6F2)
    }
    val previewText = when (settings.theme) {
        "white" -> Color(0xFF222222)
        "sepia" -> Color(0xFF2C2A29)
        "dark" -> Color(0xFFDDDDDD)
        "black" -> Color(0xFFCCCCCC)
        else -> Color(0xFF2C2A29)
    }
    val previewFont = if (settings.fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("デフォルトリーダー設定", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Settings) }) {
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // リアルタイムプレビューカード
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(previewBg)
                        .padding(
                            horizontal = settings.marginHorizontal.dp,
                            vertical = 18.dp
                        )
                ) {
                    Text(
                        text = "表示プレビュー",
                        fontSize = 11.sp,
                        color = previewText.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "「これが新しい読書画面の見た目です。」\n文字サイズや行間、ダークモードの配色をリアルタイムで確認しながら調整できます。",
                        color = previewText,
                        fontFamily = previewFont,
                        fontSize = settings.fontSize.sp,
                        lineHeight = (settings.fontSize * settings.lineHeight).sp
                    )
                }
            }

            // 1. カラーテーマ（ライト / セピア / ダーク / 漆黒）
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("カラーテーマ（ダークモード等）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val themes = listOf(
                            "white" to ("白" to Color.White),
                            "sepia" to ("セピア" to Color(0xFFF7F6F2)),
                            "dark" to ("ダーク" to Color(0xFF232323)),
                            "black" to ("漆黒" to Color.Black)
                        )
                        for ((themeKey, labelAndColor) in themes) {
                            val isSelected = settings.theme == themeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(labelAndColor.second, shape = MaterialTheme.shapes.small)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .clickable {
                                        viewModel.saveReaderSettings(settings.copy(theme = themeKey))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = labelAndColor.first,
                                    color = if (themeKey == "dark" || themeKey == "black") Color.White else Color.Black,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 2. フォント種別
                    Text("フォント", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fontOptions = listOf(
                            "sans-serif" to "ゴシック体 (標準)",
                            "serif" to "明朝体"
                        )
                        for ((fontKey, fontLabel) in fontOptions) {
                            val isSelected = (settings.fontFamily == fontKey) || (fontKey == "sans-serif" && settings.fontFamily == "default")
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveReaderSettings(settings.copy(fontFamily = fontKey))
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

                    HorizontalDivider()

                    // 3. 文字サイズ
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("文字サイズ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${settings.fontSize.toInt()} px", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = settings.fontSize,
                        onValueChange = { viewModel.saveReaderSettings(settings.copy(fontSize = it)) },
                        valueRange = 12f..32f,
                        steps = 19
                    )

                    // 4. 行間
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("行間", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.US, "%.1f", settings.lineHeight), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = settings.lineHeight,
                        onValueChange = { viewModel.saveReaderSettings(settings.copy(lineHeight = it)) },
                        valueRange = 1.2f..2.5f,
                        steps = 12
                    )

                    // 5. 左右余白
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("左右余白", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${settings.marginHorizontal.toInt()} px", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = settings.marginHorizontal,
                        onValueChange = { viewModel.saveReaderSettings(settings.copy(marginHorizontal = it)) },
                        valueRange = 8f..36f,
                        steps = 13
                    )
                }
            }

            // 6. 全体デフォルト カスタムCSS
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "全体デフォルト カスタムCSS（上級者向け）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "全作品のリーダー画面に共通で適用されるCSSです。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customCss,
                        onValueChange = { customCss = it },
                        minLines = 6,
                        placeholder = { Text("/* 例:\nruby rt { color: #888; }\n#body-text p { text-indent: 1em; }\n*/") },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            viewModel.saveReaderSettings(settings.copy(customCss = customCss.trim().ifEmpty { null }))
                            Toast.makeText(context, "デフォルトCSSを保存しました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("カスタムCSSを保存")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
