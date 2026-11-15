package com.paperfeed.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paperfeed.MainActivity
import com.paperfeed.R
import com.paperfeed.data.Article
import com.paperfeed.data.ArticleList

object Notifier {
    const val CHANNEL_NEW = "new_articles"
    const val CHANNEL_STATUS = "status"
    private const val GROUP = "com.paperfeed.NEW"
    private const val SUMMARY_ID = 1
    private const val MAX_INDIVIDUAL = 25

    const val EXTRA_ARTICLE_KEY = "article_key"
    const val EXTRA_LIST_ID = "list_id"
    const val EXTRA_SAVED_TO = "saved_to"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEW, "New articles", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A notification for every new publication in your sources"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Backup & sync status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun notificationId(key: String) = key.hashCode().let { if (it == SUMMARY_ID) 2 else it }

    private fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33

    fun openArticleIntent(context: Context, key: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "open_article:$key"
            putExtra(EXTRA_ARTICLE_KEY, key)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun saveAction(context: Context, key: String, list: ArticleList, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SAVE
            putExtra(EXTRA_ARTICLE_KEY, key)
            putExtra(EXTRA_LIST_ID, list.id)
        }
        val pi = PendingIntent.getBroadcast(
            context, (key + list.id).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_bookmark, label, pi).build()
    }

    private fun pickListAction(context: Context, key: String): NotificationCompat.Action {
        val intent = Intent(context, ListPickerActivity::class.java).apply {
            action = "pick:$key"
            putExtra(EXTRA_ARTICLE_KEY, key)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        val pi = PendingIntent.getActivity(
            context, (key + "pick").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_list, "Lists…", pi).build()
    }

    private fun shortAuthors(a: Article): String {
        val list = a.authorList
        return when {
            list.isEmpty() -> ""
            list.size <= 2 -> list.joinToString(", ")
            else -> "${list[0]}, ${list[1]} et al."
        }
    }

    fun buildArticle(
        context: Context,
        article: Article,
        readLater: ArticleList?,
        quickList: ArticleList?,
        savedTo: List<String> = emptyList(),
    ): NotificationCompat.Builder {
        val authors = shortAuthors(article)
        val big = buildString {
            if (authors.isNotEmpty()) append(authors).append("\n\n")
            append(article.abstract ?: "No abstract available yet — tap to open.")
        }
        return NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(article.title)
            .setContentText(listOf(article.journal, authors).filter { it.isNotBlank() }.joinToString(" · "))
            .setSubText(if (savedTo.isEmpty()) article.journal else "Saved to ${savedTo.joinToString(", ")}")
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(article.title).bigText(big))
            .setContentIntent(openArticleIntent(context, article.key))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .setWhen(article.published)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .apply {
                if (readLater != null && readLater.name !in savedTo) addAction(saveAction(context, article.key, readLater, readLater.name))
                if (quickList != null && quickList.name !in savedTo) addAction(saveAction(context, article.key, quickList, quickList.name))
                addAction(pickListAction(context, article.key))
            }
    }

    fun showNewArticles(context: Context, articles: List<Article>, readLater: ArticleList?, quickList: ArticleList?) {
        if (articles.isEmpty() || !canPost(context)) return
        val nm = NotificationManagerCompat.from(context)
        articles.take(MAX_INDIVIDUAL).forEach { a ->
            try {
                nm.notify(notificationId(a.key), buildArticle(context, a, readLater, quickList).build())
            } catch (_: SecurityException) {
            }
        }
        val inbox = NotificationCompat.InboxStyle().also { s -> articles.take(6).forEach { s.addLine(it.title) } }
        if (articles.size > MAX_INDIVIDUAL) inbox.setSummaryText("+${articles.size - MAX_INDIVIDUAL} more in the app")
        val summary = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${articles.size} new article${if (articles.size == 1) "" else "s"}")
            .setStyle(inbox)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        try {
            nm.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
        }
    }

    fun update(context: Context, builder: NotificationCompat.Builder, key: String) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(notificationId(key), builder.build())
        } catch (_: SecurityException) {
        }
    }
}
