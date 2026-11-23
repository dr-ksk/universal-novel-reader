package com.example.universalnovelreader.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

data class AutoSyncSettings(
    val enabled: Boolean = false,
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = true,
    val notifyOnNewEpisodes: Boolean = true,
    val lastSyncAt: String? = null,
    val lastSyncSummary: String? = null
)

object AutoSyncManager {
    private const val PREFS_NAME = "universal_novel_reader_auto_sync_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_NOTIFY = "notify_on_new_episodes"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"

    const val UNIQUE_PERIODIC_WORK_NAME = "universal_novel_reader_periodic_sync"
    const val UNIQUE_ONETIME_WORK_NAME = "universal_novel_reader_onetime_sync"

    fun loadSettings(context: Context): AutoSyncSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AutoSyncSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            intervalHours = prefs.getInt(KEY_INTERVAL_HOURS, 6).coerceIn(1, 24),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
            notifyOnNewEpisodes = prefs.getBoolean(KEY_NOTIFY, true),
            lastSyncAt = prefs.getString(KEY_LAST_SYNC_AT, null),
            lastSyncSummary = prefs.getString(KEY_LAST_SYNC_SUMMARY, null)
        )
    }

    fun saveAndApply(context: Context, settings: AutoSyncSettings): AutoSyncSettings {
        val normalized = settings.copy(intervalHours = settings.intervalHours.coerceIn(1, 24))
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putInt(KEY_INTERVAL_HOURS, normalized.intervalHours)
            .putBoolean(KEY_WIFI_ONLY, normalized.wifiOnly)
            .putBoolean(KEY_NOTIFY, normalized.notifyOnNewEpisodes)
            .apply()

        applySchedule(context, normalized)
        return loadSettings(context)
    }

    fun recordLastSync(context: Context, timestamp: String, summary: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_SYNC_AT, timestamp)
            .putString(KEY_LAST_SYNC_SUMMARY, summary)
            .apply()
    }

    fun applySchedule(context: Context, settings: AutoSyncSettings = loadSettings(context)) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request = PeriodicWorkRequestBuilder<NovelSyncWorker>(
            settings.intervalHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<NovelSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONETIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
