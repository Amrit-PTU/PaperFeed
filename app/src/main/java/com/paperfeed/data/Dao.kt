package com.paperfeed.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query(
        """SELECT * FROM articles
           WHERE (:sourceId IS NULL OR sourceId = :sourceId)
             AND sourceId IN (SELECT id FROM sources WHERE enabled = 1)
           ORDER BY published DESC, fetchedAt DESC LIMIT :limit"""
    )
    fun feed(sourceId: Long?, limit: Int): Flow<List<Article>>

    /** Feed limited to articles newer than [since] (the date down to which every source is loaded). */
    @Query(
        """SELECT * FROM articles
           WHERE (:sourceId IS NULL OR sourceId = :sourceId)
             AND sourceId IN (SELECT id FROM sources WHERE enabled = 1)
             AND published >= :since
           ORDER BY published DESC, fetchedAt DESC LIMIT 5000"""
    )
    fun feedSince(sourceId: Long?, since: Long): Flow<List<Article>>

    /** Oldest loaded article per source. */
    @Query("SELECT sourceId, MIN(published) AS oldest FROM articles GROUP BY sourceId")
    fun observeCoverage(): Flow<List<SourceCoverage>>

    @Query("SELECT MIN(published) FROM articles WHERE sourceId = :sourceId")
    suspend fun oldestFor(sourceId: Long): Long?

    @Query("SELECT * FROM articles WHERE `key` = :key")
    fun observe(key: String): Flow<Article?>

    @Query("SELECT * FROM articles WHERE `key` = :key")
    suspend fun get(key: String): Article?

    @Query("SELECT `key` FROM articles WHERE `key` IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    /** Authors are newline separated; match whole lines only. */
    @Query(
        """SELECT * FROM articles
           WHERE (char(10) || authors || char(10)) LIKE '%' || char(10) || :author || char(10) || '%'
           ORDER BY published DESC"""
    )
    suspend fun byAuthorExact(author: String): List<Article>

    @Query("SELECT * FROM articles WHERE authors LIKE '%' || :family || '%' ORDER BY published DESC")
    suspend fun byAuthorFamily(family: String): List<Article>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(articles: List<Article>): List<Long>

    @Upsert
    suspend fun upsert(article: Article)

    @Upsert
    suspend fun upsertAll(articles: List<Article>)

    @Query("UPDATE articles SET abstract = :abstract, abstractCheckedAt = :now, abstractAttempts = abstractAttempts + 1 WHERE `key` = :key")
    suspend fun setAbstract(key: String, abstract: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE articles SET abstractCheckedAt = :now, abstractAttempts = abstractAttempts + 1 WHERE `key` = :key")
    suspend fun markAbstractAttempt(key: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE articles SET rating = :rating WHERE `key` = :key")
    suspend fun setRating(key: String, rating: Int)

    @Query("UPDATE articles SET read = 1 WHERE `key` = :key")
    suspend fun markRead(key: String)

    @Query(
        """SELECT * FROM articles WHERE (abstract IS NULL OR abstract = '' OR abstract LIKE '%…')
           AND abstractAttempts < 5
           AND abstractCheckedAt + (10800000 << abstractAttempts) < :now
           ORDER BY abstractAttempts, published DESC LIMIT :limit"""
    )
    suspend fun missingAbstracts(limit: Int, now: Long = System.currentTimeMillis()): List<Article>

    /** Articles worth backing up: in a list or rated. */
    @Query(
        """SELECT * FROM articles WHERE rating > 0
           OR `key` IN (SELECT articleKey FROM list_entries)
           OR `key` IN (SELECT articleKey FROM notes)"""
    )
    suspend fun kept(): List<Article>

    /** Drop old feed items that the user never kept. */
    @Query(
        """DELETE FROM articles WHERE fetchedAt < :before AND rating = 0
           AND `key` NOT IN (SELECT articleKey FROM list_entries)
           AND `key` NOT IN (SELECT articleKey FROM notes)"""
    )
    suspend fun prune(before: Long)

    @Query(
        """DELETE FROM articles WHERE sourceId = :sourceId AND rating = 0
           AND `key` NOT IN (SELECT articleKey FROM list_entries)
           AND `key` NOT IN (SELECT articleKey FROM notes)"""
    )
    suspend fun deleteUnkeptFromSource(sourceId: Long)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun all(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): Source?

    @Insert
    suspend fun insert(source: Source): Long

    @Insert
    suspend fun insertAll(sources: List<Source>)

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastFetched = :time, lastError = :error WHERE id = :id")
    suspend fun setStatus(id: Long, time: Long, error: String?)

    @Query("DELETE FROM sources")
    suspend fun clear()
}

@Dao
interface NoteDao {
    @androidx.room.Transaction
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteWithArticle>>

    @Query("SELECT * FROM notes WHERE articleKey = :key ORDER BY createdAt")
    fun observeFor(key: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM notes")
    suspend fun all(): List<Note>

    @Query("SELECT COUNT(*) FROM notes")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(note: Note): Long

    @Upsert
    suspend fun upsertAll(notes: List<Note>)

    @Query("UPDATE notes SET text = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateText(id: Long, text: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ListDao {
    @Query(
        """SELECT l.*, (SELECT COUNT(*) FROM list_entries e WHERE e.listId = l.id) AS count
           FROM lists l ORDER BY l.position, l.id"""
    )
    fun observeWithCounts(): Flow<List<ListWithCount>>

    @Query("SELECT * FROM lists ORDER BY position, id")
    fun observeAll(): Flow<List<ArticleList>>

    @Query("SELECT * FROM lists ORDER BY position, id")
    suspend fun all(): List<ArticleList>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observe(id: Long): Flow<ArticleList?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun get(id: Long): ArticleList?

    @Insert
    suspend fun insert(list: ArticleList): Long

    @Upsert
    suspend fun upsertAll(lists: List<ArticleList>)

    @Update
    suspend fun update(list: ArticleList)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM lists")
    suspend fun nextPosition(): Int

    @Query("SELECT * FROM lists WHERE notificationAction = 1 AND id != 1 ORDER BY position LIMIT 1")
    suspend fun notificationList(): ArticleList?

    @Query("UPDATE lists SET notificationAction = (id = :id)")
    suspend fun setNotificationList(id: Long)

    // --- entries ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEntry(entry: ListEntry)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEntries(entries: List<ListEntry>)

    @Query("DELETE FROM list_entries WHERE listId = :listId AND articleKey = :key")
    suspend fun removeEntry(listId: Long, key: String)

    @Query("DELETE FROM list_entries WHERE listId = :listId")
    suspend fun clearEntries(listId: Long)

    @Query("SELECT listId FROM list_entries WHERE articleKey = :key")
    fun observeListIdsFor(key: String): Flow<List<Long>>

    @Query("SELECT listId FROM list_entries WHERE articleKey = :key")
    suspend fun listIdsFor(key: String): List<Long>

    @Query("SELECT * FROM list_entries")
    suspend fun allEntries(): List<ListEntry>

    @Query(
        """SELECT a.* FROM articles a JOIN list_entries e ON e.articleKey = a.`key`
           WHERE e.listId = :listId
           ORDER BY CASE WHEN :byRating THEN a.rating ELSE 0 END DESC, a.published DESC"""
    )
    fun articlesIn(listId: Long, byRating: Boolean): Flow<List<Article>>

    @Query("DELETE FROM list_entries")
    suspend fun clearAllEntries()

    @Query("DELETE FROM lists WHERE id != 1")
    suspend fun clearUserLists()

    @Transaction
    suspend fun deleteList(id: Long) {
        clearEntries(id)
        delete(id)
    }
}
