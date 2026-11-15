package com.paperfeed.backup

import com.paperfeed.data.AppSettings
import com.paperfeed.data.Article
import com.paperfeed.data.ArticleList
import com.paperfeed.data.BackupData
import com.paperfeed.data.FeedStyle
import com.paperfeed.data.ListEntry
import com.paperfeed.data.Note
import com.paperfeed.data.Source
import com.paperfeed.data.SourceType
import org.json.JSONArray
import org.json.JSONObject

/** Portable JSON form of the user's data (kept articles, lists, ratings, sources, settings). */
object BackupJson {
    private const val VERSION = 1

    fun encode(d: BackupData): String = JSONObject().apply {
        put("app", "PaperFeed")
        put("version", VERSION)
        put("createdAt", System.currentTimeMillis())
        put("articles", JSONArray(d.articles.map { a ->
            JSONObject().apply {
                put("key", a.key); put("title", a.title); put("authors", a.authors); put("journal", a.journal)
                put("sourceId", a.sourceId); put("doi", a.doi); put("url", a.url); put("abstract", a.abstract)
                put("published", a.published); put("year", a.year); put("volume", a.volume); put("issue", a.issue)
                put("pages", a.pages); put("fetchedAt", a.fetchedAt); put("rating", a.rating); put("read", a.read)
            }
        }))
        put("lists", JSONArray(d.lists.map { l ->
            JSONObject().apply {
                put("id", l.id); put("name", l.name); put("position", l.position)
                put("notificationAction", l.notificationAction); put("createdAt", l.createdAt)
            }
        }))
        put("entries", JSONArray(d.entries.map { e ->
            JSONObject().apply { put("listId", e.listId); put("articleKey", e.articleKey); put("addedAt", e.addedAt) }
        }))
        put("sources", JSONArray(d.sources.map { s ->
            JSONObject().apply {
                put("id", s.id); put("name", s.name); put("type", s.type.name); put("query", s.query)
                put("enabled", s.enabled); put("notify", s.notify)
            }
        }))
        put("notes", JSONArray(d.notes.map { n ->
            JSONObject().apply {
                put("id", n.id); put("articleKey", n.articleKey); put("text", n.text)
                put("createdAt", n.createdAt); put("updatedAt", n.updatedAt)
            }
        }))
        d.settings?.let { s ->
            put("settings", JSONObject().apply {
                put("themeId", s.themeId); put("customTheme", s.customTheme); put("refreshMinutes", s.refreshMinutes)
                put("notificationsEnabled", s.notificationsEnabled); put("contactEmail", s.contactEmail)
                put("textScale", s.textScale.toDouble()); put("feedScale", s.feedScale.toDouble())
                put("readerScale", s.readerScale.toDouble()); put("feedStyle", s.feedStyle.name)
                put("previewLines", s.previewLines); put("elsevierApiKey", s.elsevierApiKey)
            })
        }
    }.toString()

    fun decode(json: String): BackupData {
        val o = JSONObject(json)
        require(o.optString("app") == "PaperFeed") { "Not a PaperFeed backup" }
        fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
        val articles = o.optJSONArray("articles").objects().map { a ->
            Article(
                key = a.getString("key"), title = a.getString("title"), authors = a.optString("authors"),
                journal = a.optString("journal"), sourceId = a.optLong("sourceId"), doi = a.str("doi"),
                url = a.str("url"), abstract = a.str("abstract"), published = a.optLong("published"),
                year = if (a.isNull("year")) null else a.optInt("year"), volume = a.str("volume"),
                issue = a.str("issue"), pages = a.str("pages"), fetchedAt = a.optLong("fetchedAt"),
                rating = a.optInt("rating"), read = a.optBoolean("read"), abstractAttempts = 99,
            )
        }
        val lists = o.optJSONArray("lists").objects().map { l ->
            ArticleList(
                id = l.getLong("id"), name = l.getString("name"), position = l.optInt("position"),
                notificationAction = l.optBoolean("notificationAction"), createdAt = l.optLong("createdAt"),
            )
        }
        val entries = o.optJSONArray("entries").objects().map { e ->
            ListEntry(e.getLong("listId"), e.getString("articleKey"), e.optLong("addedAt"))
        }
        val sources = o.optJSONArray("sources").objects().map { s ->
            Source(
                id = s.getLong("id"), name = s.getString("name"),
                type = runCatching { SourceType.valueOf(s.getString("type")) }.getOrDefault(SourceType.RSS),
                query = s.getString("query"), enabled = s.optBoolean("enabled", true), notify = s.optBoolean("notify", true),
                // Mark as fetched so restoring doesn't suppress or flood notifications.
                lastFetched = System.currentTimeMillis(),
            )
        }
        val settings = o.optJSONObject("settings")?.let { s ->
            AppSettings(
                themeId = s.optString("themeId", "light"),
                customTheme = s.optString("customTheme", AppSettings().customTheme),
                refreshMinutes = s.optInt("refreshMinutes", 60),
                notificationsEnabled = s.optBoolean("notificationsEnabled", true),
                contactEmail = s.optString("contactEmail"),
                textScale = s.optDouble("textScale", 1.0).toFloat(),
                feedScale = s.optDouble("feedScale", 1.0).toFloat(),
                readerScale = s.optDouble("readerScale", 1.0).toFloat(),
                feedStyle = runCatching { FeedStyle.valueOf(s.optString("feedStyle")) }.getOrDefault(FeedStyle.LIST),
                previewLines = s.optInt("previewLines", 0),
                elsevierApiKey = s.optString("elsevierApiKey"),
            )
        }
        val notes = o.optJSONArray("notes").objects().map { n ->
            Note(
                id = n.getLong("id"), articleKey = n.getString("articleKey"), text = n.optString("text"),
                createdAt = n.optLong("createdAt"), updatedAt = n.optLong("updatedAt"),
            )
        }
        return BackupData(articles, lists, entries, sources, settings, notes)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
