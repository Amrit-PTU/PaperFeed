package com.paperfeed.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.paperfeed.data.Article
import com.paperfeed.data.Note

object Zotero {
    const val PACKAGE = "org.zotero.android"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /**
     * Hands the article's DOI/URL to the Zotero app's "Save to Zotero" share target, which
     * resolves the page with Zotero's translators and stores full metadata (and PDF if available).
     */
    fun send(context: Context, a: Article): Boolean {
        val link = a.link ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, a.title)
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openStorePage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            openUrl(context, "https://play.google.com/store/apps/details?id=$PACKAGE")
        }
    }
}

fun shareText(context: Context, subject: String, text: String, title: String = "Share") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

/** Plain link share: what most apps (browsers, reference managers, chat) expect. */
fun shareLink(context: Context, a: Article) =
    shareText(context, a.title, listOfNotNull(a.title, a.link).joinToString("\n"), "Share link")

fun shareArticle(context: Context, a: Article) =
    shareText(context, a.title, a.citation() + (if (a.doi == null) a.url?.let { "\n$it" } ?: "" else ""), "Share citation")

fun shareWithAbstract(context: Context, a: Article) =
    shareText(context, a.title, a.citation() + (a.abstract?.let { "\n\n$it" } ?: ""), "Share")

fun noteSource(a: Article?): String =
    if (a == null) "(paper no longer in the database)"
    else buildString {
        append(a.title)
        val names = a.authorList
        if (names.isNotEmpty()) append("\n").append(if (names.size > 3) "${names.take(3).joinToString(", ")} et al." else names.joinToString(", "))
        a.year?.let { append(" (").append(it).append(")") }
        a.doi?.let { append("\nhttps://doi.org/").append(it) }
    }

fun shareNote(context: Context, note: Note, a: Article?) =
    shareText(context, "Note: ${a?.title ?: ""}", note.text + "\n\n— " + noteSource(a), "Share note")
