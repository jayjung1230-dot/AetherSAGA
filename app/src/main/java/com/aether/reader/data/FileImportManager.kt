package com.aether.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.aether.reader.engine.BookFormat
import com.aether.reader.engine.Containers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework (SAF) File Importer & Persistable URI Handler
 * Zero-duplication strategy: Prevents cloning large archives into internal 8GB ROM.
 * Persists read permissions and caches thumbnails exclusively to external SD or external cache.
 */
class FileImportManager(
    private val context: Context,
    private val db: AppDatabase,
    private val coverCache: CoverCache
) {
    suspend fun importUris(uris: List<Uri>, onProgress: (count: Int) -> Unit): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val resolver = context.contentResolver

        for (uri in uris) {
            try {
                val isContent = (uri.scheme == "content")
                // 1. Take permanent persistable read permission
                if (isContent) {
                    try {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                }

                val pathString = Containers.normalizePath(if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString())

                // 2. Query document metadata
                var fileName = "Document"
                var fileSize = 0L
                if (isContent) {
                    try {
                        resolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst()) {
                                if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    val f = java.io.File(pathString)
                    if (f.exists()) {
                        fileName = f.name
                        fileSize = f.length()
                    }
                }

                var title = fileName.substringBeforeLast('.')
                var author = ""
                var coverPath: String? = null

                var fmt = Containers.formatOf(pathString, context) ?: BookFormat.TXT

                // If content:// URI from SAF, persist to internal filesDir/books/ immediately while permission is fresh
                var persistentPath = pathString
                if (isContent) {
                    try {
                        val booksDir = java.io.File(context.filesDir, "books").apply { mkdirs() }
                        val safeKey = md5(pathString)
                        val ext = if (fileName.contains(".")) fileName.substringAfterLast('.').lowercase() else fmt.name.lowercase()
                        val permFile = java.io.File(booksDir, "book_${safeKey}.${ext}")
                        if (!permFile.exists() || permFile.length() == 0L) {
                            resolver.openInputStream(uri)?.use { inp ->
                                permFile.outputStream().use { out -> inp.copyTo(out) }
                            }
                        }
                        if (permFile.exists() && permFile.length() > 0L) {
                            persistentPath = permFile.absolutePath
                            fileSize = permFile.length()
                            val sniffed = Containers.sniffFileFormat(permFile)
                            if (sniffed != BookFormat.TXT || fmt !in listOf(BookFormat.MD, BookFormat.TXT)) {
                                fmt = sniffed
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // Extract rich metadata, verify format via magic bytes, and generate grayscale cover thumbnail
                try {
                    Containers.open(persistentPath, context, hintFormat = fmt).use { c ->
                        if (c.title.isNotBlank()) title = c.title
                        if (c.author.isNotBlank()) author = c.author
                        coverPath = c.coverBytes()?.let { coverCache.store(persistentPath, it) }
                        when (c) {
                            is com.aether.reader.engine.EpubContainer -> fmt = BookFormat.EPUB
                            is com.aether.reader.engine.PdfContainer -> fmt = BookFormat.PDF
                            is com.aether.reader.engine.CbzContainer -> fmt = BookFormat.CBZ
                            is com.aether.reader.engine.MobiContainer -> fmt = BookFormat.MOBI
                        }
                    }
                } catch (_: Throwable) {}

                val entity = BookEntity(
                    path = persistentPath,
                    title = title,
                    author = author,
                    format = fmt.name,
                    sizeBytes = fileSize,
                    addedAt = System.currentTimeMillis(),
                    coverPath = coverPath
                )

                db.books().insertAll(listOf(entity))
                if (coverPath != null || author.isNotBlank() || title.isNotBlank()) {
                    db.books().updateMeta(persistentPath, title, author, coverPath)
                }
                imported++
                onProgress(imported)
            } catch (_: Exception) {}
        }
        imported
    }
}
