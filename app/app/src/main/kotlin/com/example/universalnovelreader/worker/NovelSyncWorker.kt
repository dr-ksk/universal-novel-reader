package com.example.universalnovelreader.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.universalnovelreader.MainActivity
import com.example.universalnovelreader.R
import com.example.universalnovelreader.core.network.NativeHttpClient
import com.example.universalnovelreader.core.parser.SiteAdapterManager
import com.example.universalnovelreader.data.db.NovelRepository
import com.example.universalnovelreader.data.storage.CacheManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovelSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "universal_novel_reader_new_episodes"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_NOVEL_ID = "extra_open_novel_id"
    }

    private data class UpdatedNovelInfo(
        val novelId: Long,
        val title: String,
        val addedEpisodes: Int,
        val totalEpisodes: Int
    )

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = NovelRepository(context)
        val cacheManager = CacheManager(context)
        val httpClient = NativeHttpClient()
        val adapterManager = SiteAdapterManager(context, repository, cacheManager, httpClient)
        val settings = AutoSyncManager.loadSettings(context)

        return try {
            val novels = repository.getAllNovels()
            val updatedList = mutableListOf<UpdatedNovelInfo>()
            var errorCount = 0

            for ((index, novel) in novels.withIndex()) {
                val beforeCount = novel.lastEpisodeIndex
                val res = adapterManager.syncNovelIndex(novel.topUrl)
                if (res.isSuccess) {
                    val updated = res.getOrNull()
                    val afterCount = updated?.lastEpisodeIndex ?: beforeCount
                    val diff = afterCount - beforeCount
                    if (diff > 0) {
                        updatedList.add(
                            UpdatedNovelInfo(
                                novelId = novel.id,
                                title = updated?.title?.ifBlank { novel.title } ?: novel.title,
                                addedEpisodes = diff,
                                totalEpisodes = afterCount
                            )
                        )
                    }
                } else {
                    errorCount++
                }
                if (index < novels.lastIndex) {
                    delay(1000)
                }
            }

            val now = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
            val totalAdded = updatedList.sumOf { it.addedEpisodes }
            val summary = buildString {
                append("${novels.size}作品を確認")
                if (totalAdded > 0) {
                    append("・新着${totalAdded}話 (${updatedList.size}作品)")
                } else {
                    append("・新着なし")
                }
                if (errorCount > 0) {
                    append("・失敗${errorCount}件")
                }
            }
            AutoSyncManager.recordLastSync(context, now, summary)

            if (settings.notifyOnNewEpisodes && updatedList.isNotEmpty()) {
                sendNewEpisodeNotification(context, updatedList)
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun sendNewEpisodeNotification(context: Context, updated: List<UpdatedNovelInfo>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureNotificationChannel(context)

        val totalAdded = updated.sumOf { it.addedEpisodes }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (updated.size == 1) {
                putExtra(EXTRA_NOVEL_ID, updated.first().novelId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (updated.size == 1) {
            "新着更新: ${updated.first().title}"
        } else {
            "${updated.size}作品に新着（計${totalAdded}話）"
        }

        val contentText = if (updated.size == 1) {
            "+${updated.first().addedEpisodes}話 追加（全${updated.first().totalEpisodes}話）"
        } else {
            updated.joinToString(" / ") { "${it.title} (+${it.addedEpisodes})" }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (updated.size > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
            updated.take(6).forEach {
                inboxStyle.addLine("${it.title} (+${it.addedEpisodes}話)")
            }
            if (updated.size > 6) {
                inboxStyle.setSummaryText("ほか ${updated.size - 6} 作品")
            }
            builder.setStyle(inboxStyle)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "新着エピソード通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "定期更新チェックで新しい話が見つかったときに通知します"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
