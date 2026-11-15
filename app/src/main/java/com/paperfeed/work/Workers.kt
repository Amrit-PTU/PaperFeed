package com.paperfeed.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paperfeed.PaperFeedApp
import com.paperfeed.data.ArticleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    private const val PERIODIC = "periodic_refresh"
    private const val ONE_SHOT = "refresh_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, minutes: Int, replace: Boolean = true) {
        val wm = WorkManager.getInstance(context)
        if (minutes <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInitialDelay(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(constraints).build(),
        )
    }
}

/** Fetches every source, notifies about new papers, fills abstracts and runs the daily auto-backup. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PaperFeedApp
        val repo = app.repo
        val result = repo.refresh()
        val settings = repo.settings.current()

        if (settings.notificationsEnabled && result.newForNotification.isNotEmpty()) {
            val toNotify = result.newForNotification.take(40)
            // Abstracts make the expanded notification useful; spend a short budget filling them first.
            val missing = toNotify.filter { it.abstract.isNullOrBlank() || it.abstract.endsWith("…") }.take(15)
            repo.enrichAbstracts(missing, budgetMs = 45_000)
            val fresh = toNotify.mapNotNull { repo.getArticle(it.key) }
            val lists = repo.allLists()
            Notifier.showNewArticles(
                applicationContext, fresh,
                readLater = lists.firstOrNull { it.id == ArticleList.READ_LATER_ID },
                quickList = repo.notificationList(),
            )
        }
        repo.enrichPending(limit = 20)

        val lastBackup = maxOf(settings.lastDriveBackup, settings.lastSafBackup)
        if ((settings.driveAutoBackup || settings.safBackupUri.isNotBlank()) &&
            System.currentTimeMillis() - lastBackup > 20 * 3_600_000L
        ) {
            runCatching { app.backup.autoBackup() }
        }
        return Result.success()
    }
}

/** Handles the "save to list" buttons on article notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SAVE = "com.paperfeed.SAVE_TO_LIST"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAVE) return
        val key = intent.getStringExtra(Notifier.EXTRA_ARTICLE_KEY) ?: return
        val listId = intent.getLongExtra(Notifier.EXTRA_LIST_ID, ArticleList.READ_LATER_ID)
        val pending = goAsync()
        val app = context.applicationContext as PaperFeedApp
        app.appScope.launch {
            try {
                saveAndRefreshNotification(context, key, listOf(listId))
            } finally {
                pending.finish()
            }
        }
    }
}

/** Adds [key] to [listIds] and re-posts its notification showing where it was saved. */
suspend fun saveAndRefreshNotification(context: Context, key: String, listIds: List<Long>) {
    val repo = (context.applicationContext as PaperFeedApp).repo
    listIds.forEach { repo.addToList(it, key) }
    val article = repo.getArticle(key) ?: return
    val lists = repo.allLists()
    val savedIds = repo.listIdsFor(key).first()
    val savedNames = lists.filter { it.id in savedIds }.map { it.name }
    val builder = Notifier.buildArticle(
        context, article,
        readLater = lists.firstOrNull { it.id == ArticleList.READ_LATER_ID },
        quickList = repo.notificationList(),
        savedTo = savedNames,
    ).setSilent(true)
    Notifier.update(context, builder, key)
    if (listIds.isNotEmpty()) withContext(Dispatchers.Main) {
        Toast.makeText(context, "Saved to ${lists.filter { it.id in listIds }.joinToString { it.name }}", Toast.LENGTH_SHORT).show()
    }
}
