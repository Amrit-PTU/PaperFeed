package com.paperfeed.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromType(t: SourceType): String = t.name
    @TypeConverter fun toType(s: String): SourceType = runCatching { SourceType.valueOf(s) }.getOrDefault(SourceType.RSS)
}

@Database(
    entities = [Article::class, Source::class, ArticleList::class, ListEntry::class, Note::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
    abstract fun sources(): SourceDao
    abstract fun lists(): ListDao
    abstract fun notes(): NoteDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paperfeed.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        seed(db)
                    }
                })
                .build()

        private fun seed(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO lists (id, name, position, notificationAction, createdAt) VALUES (?, ?, 0, 0, ?)",
                arrayOf<Any>(ArticleList.READ_LATER_ID, "Read later", now),
            )
            db.execSQL(
                "INSERT INTO lists (name, position, notificationAction, createdAt) VALUES (?, 1, 1, ?)",
                arrayOf<Any>("Important", now),
            )
            DefaultSources.all.forEach { s ->
                db.execSQL(
                    "INSERT INTO sources (name, type, query, enabled, notify, lastFetched) VALUES (?, ?, ?, 1, 1, 0)",
                    arrayOf<Any>(s.name, s.type.name, s.query),
                )
            }
        }
    }
}

object DefaultSources {
    val all = listOf(
        // APS and IOP feeds carry abstracts straight away, so RSS is used for them.
        Source(name = "Physical Review C", type = SourceType.RSS, query = "https://feeds.aps.org/rss/recent/prc.xml"),
        Source(name = "Nuclear Physics A", type = SourceType.CROSSREF, query = "0375-9474"),
        Source(name = "Physics Letters B", type = SourceType.CROSSREF, query = "0370-2693"),
        Source(name = "Chinese Physics C", type = SourceType.RSS, query = "https://iopscience.iop.org/journal/rss/1674-1137"),
        Source(name = "Progress in Particle and Nuclear Physics", type = SourceType.CROSSREF, query = "0146-6410"),
        Source(name = "Annual Review of Nuclear and Particle Science", type = SourceType.CROSSREF, query = "0163-8998"),
        Source(name = "Reviews of Modern Physics", type = SourceType.RSS, query = "https://feeds.aps.org/rss/recent/rmp.xml"),
        Source(name = "Nuclear Science and Engineering", type = SourceType.CROSSREF, query = "0029-5639"),
        Source(name = "Annals of Nuclear Energy", type = SourceType.CROSSREF, query = "0306-4549"),
        Source(name = "Journal of Nuclear Science and Technology", type = SourceType.CROSSREF, query = "0022-3131"),
        Source(name = "Journal of Physics G", type = SourceType.RSS, query = "https://iopscience.iop.org/journal/rss/0954-3899"),
        Source(name = "arXiv nucl-th / nucl-ex", type = SourceType.ARXIV, query = "cat:nucl-th OR cat:nucl-ex"),
    )
}
