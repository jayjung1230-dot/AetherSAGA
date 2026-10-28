package com.aether.reader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val path: String,
    val title: String,
    val author: String,
    val format: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val lastRead: Long = 0,
    val chapter: Int = 0,
    val charOffset: Long = 0,
    val progress: Float = 0f,
    val coverPath: String? = null
)

/** kind: 0 = highlight, 1 = bookmark. style: 0 solid, 1 dotted, 2 wavy. colorTag: 0..3. */
@Entity(tableName = "highlights", indices = [Index("bookPath")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookPath: String,
    val kind: Int,
    val chapter: Int,
    val startChar: Long,
    val endChar: Long,
    val quote: String,
    val note: String,
    val style: Int,
    val colorTag: Int,
    val progress: Float,
    val chapterTitle: String,
    val createdAt: Long,
    val bookTitle: String = "",
    val bookAuthor: String = ""
)

/** Pending uploads (WebDAV). remotePath is unique so newer content replaces queued older content. */
@Entity(tableName = "export_queue", indices = [Index(value = ["remotePath"], unique = true)])
data class ExportQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remotePath: String,
    val content: String,
    val mime: String,
    val createdAt: Long
)

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<BookEntity>): List<Long>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT path FROM books")
    suspend fun allPaths(): List<String>

    @Query("DELETE FROM books WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM books WHERE lastRead = 0")
    suspend fun deleteUnopenedAutoScannedBooks()

    @Query("SELECT * FROM books WHERE path = :path")
    suspend fun get(path: String): BookEntity?

    @Query("UPDATE books SET path = :newPath WHERE path = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String)

    @Query("UPDATE books SET title=:title, author=:author, coverPath=:cover WHERE path=:path")
    suspend fun updateMeta(path: String, title: String, author: String, cover: String?)

    @Query("UPDATE books SET lastRead=:t, chapter=:ch, charOffset=:off, progress=:p WHERE path=:path")
    suspend fun updateProgress(path: String, t: Long, ch: Int, off: Long, p: Float)

    @Query("UPDATE books SET path = substr(path, 8) WHERE path LIKE 'file://%'")
    suspend fun normalizeFilePaths()
}

@Dao
interface HighlightDao {
    @Insert suspend fun insert(h: HighlightEntity): Long
    @Query("SELECT * FROM highlights WHERE bookPath=:path ORDER BY chapter, startChar")
    suspend fun forBook(path: String): List<HighlightEntity>
    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    suspend fun all(): List<HighlightEntity>
    @Query("DELETE FROM highlights WHERE id=:id") suspend fun delete(id: Long)
    @Query("UPDATE highlights SET note = :note WHERE id = :id") suspend fun updateNote(id: Long, note: String)
    @Query("DELETE FROM highlights WHERE bookPath=:path") suspend fun deleteForBook(path: String)
    @Query("UPDATE highlights SET bookPath = :newPath WHERE bookPath = :oldPath")
    suspend fun updateBookPath(oldPath: String, newPath: String)
    @Query("UPDATE highlights SET bookTitle = :title, bookAuthor = :author WHERE bookPath = :path")
    suspend fun updateBookMeta(path: String, title: String, author: String)

    @Query("UPDATE highlights SET bookPath = substr(bookPath, 8) WHERE bookPath LIKE 'file://%'")
    suspend fun normalizeFilePaths()
}

@Dao
interface ExportQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ExportQueueEntity)
    @Query("SELECT * FROM export_queue ORDER BY id") suspend fun all(): List<ExportQueueEntity>
    @Query("DELETE FROM export_queue WHERE id=:id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM export_queue") suspend fun count(): Int
}

@Entity(tableName = "daily_reading_stats")
data class DailyReadingStatsEntity(
    @PrimaryKey val date: String, // "yyyy-MM-dd"
    val minutes: Int,
    val pages: Int
)

@Dao
interface DailyReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DailyReadingStatsEntity)
    @Query("SELECT * FROM daily_reading_stats WHERE date = :date") suspend fun getForDate(date: String): DailyReadingStatsEntity?
    @Query("SELECT * FROM daily_reading_stats WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC") suspend fun getRange(startDate: String, endDate: String): List<DailyReadingStatsEntity>
    @Query("SELECT * FROM daily_reading_stats ORDER BY date ASC") suspend fun all(): List<DailyReadingStatsEntity>
}

@Entity(tableName = "completed_books", indices = [Index("completedDate"), Index("title")])
data class CompletedBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookPath: String,
    val title: String,
    val author: String = "",
    val completedAt: Long,
    val completedDate: String // "yyyy-MM-dd"
)

@Dao
interface CompletedBookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CompletedBookEntity): Long

    @Query("SELECT * FROM completed_books WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): CompletedBookEntity?

    @Query("SELECT * FROM completed_books WHERE bookPath = :path LIMIT 1")
    suspend fun findByPath(path: String): CompletedBookEntity?

    @Query("SELECT * FROM completed_books WHERE completedDate LIKE :yearMonthPrefix || '%' ORDER BY completedAt DESC")
    suspend fun forMonth(yearMonthPrefix: String): List<CompletedBookEntity>

    @Query("SELECT * FROM completed_books WHERE completedDate LIKE :yearPrefix || '%' ORDER BY completedAt DESC")
    suspend fun forYear(yearPrefix: String): List<CompletedBookEntity>

    @Query("SELECT * FROM completed_books ORDER BY completedAt DESC")
    suspend fun all(): List<CompletedBookEntity>

    @Query("DELETE FROM completed_books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [BookEntity::class, HighlightEntity::class, ExportQueueEntity::class, DailyReadingStatsEntity::class, CompletedBookEntity::class],
    version = 4, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun highlights(): HighlightDao
    abstract fun exportQueue(): ExportQueueDao
    abstract fun stats(): DailyReadingStatsDao
    abstract fun completedBooks(): CompletedBookDao
}
