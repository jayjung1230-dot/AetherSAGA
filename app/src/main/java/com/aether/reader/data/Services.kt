package com.aether.reader.data

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.aether.reader.app.ReaderTheme
import com.aether.reader.app.cardDrawable
import com.aether.reader.app.dp
import com.aether.reader.app.enableVisibleScrollbar
import com.aether.reader.app.setPtSize
import com.aether.reader.app.showWithOptimalWidth
import android.widget.Toast
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Telephony
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.aether.reader.engine.BookFormat
import com.aether.reader.engine.Containers
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Grayscale WebP thumbnails, stored on the external SD card cache dir (falls back to internal). */
class CoverCache(private val ctx: Context) {
    private val dir: File by lazy {
        val d = ctx.externalCacheDirs.filterNotNull().lastOrNull() ?: ctx.cacheDir
        File(d, "covers").apply { mkdirs() }
    }

    fun store(bookPath: String, bytes: ByteArray): String? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > 480) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (src == null) null else {
            val w = 240; val h = (src.height * (w / src.width.toFloat())).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            Canvas(out).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, p)
            val f = File(dir, md5(bookPath) + ".webp")
            FileOutputStream(f).use {
                @Suppress("DEPRECATION")
                out.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 70, it)
            }
            src.recycle(); out.recycle()
            f.path
        }
    } catch (_: Throwable) { null }
}

/** Minimal WebDAV client (PUT/GET with Basic auth). Remote files are written flat into the configured folder URL. */
class WebDav(private val base: String, private val user: String, private val pass: String) {
    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(base.trimEnd('/') + "/" + path.trimStart('/')).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 15000
        if (user.isNotBlank()) c.setRequestProperty("Authorization",
            "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
        return c
    }

    fun put(path: String, content: String, mime: String): Boolean = try {
        val c = open(path, "PUT"); c.doOutput = true
        c.setRequestProperty("Content-Type", "$mime; charset=utf-8")
        c.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        (c.responseCode in 200..299).also { c.disconnect() }
    } catch (_: Exception) { false }

    fun get(path: String): String? = try {
        val c = open(path, "GET")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) { null }
}

/** Offline export queue: entries are persisted in Room and flushed whenever a network becomes available. */
class ExportSync(private val ctx: Context, private val prefs: Prefs, private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun client(): WebDav? = prefs.webdavUrl.takeIf { it.isNotBlank() }?.let { WebDav(it, prefs.webdavUser, prefs.webdavPass) }

    fun enqueue(remotePath: String, content: String, mime: String) {
        scope.launch {
            db.exportQueue().upsert(ExportQueueEntity(remotePath = remotePath, content = content, mime = mime, createdAt = System.currentTimeMillis()))
            flush()
        }
    }

    fun flush() {
        scope.launch {
            mutex.withLock {
                val c = client() ?: return@launch
                for (e in db.exportQueue().all()) {
                    if (c.put(e.remotePath, e.content, e.mime)) db.exportQueue().delete(e.id) else break
                }
            }
        }
    }

    suspend fun pending(): Int = db.exportQueue().count()

    fun registerNetworkTrigger() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { flush() }
            })
        } catch (_: Exception) { }
    }
}

object MarkdownFormatter {
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun pct(p: Float) = "${(p * 100).toInt()}%"

    /**
     * Formats email share body starting with detailed book metadata (Title, Author, Format, Progress, Cover note)
     * followed by the user's selected sentences / highlights / bookmarks.
     */
    fun formatEmailBody(
        book: BookEntity,
        resolvedTitle: String,
        resolvedAuthor: String,
        totalChapters: Int,
        items: List<HighlightEntity>,
        hasCoverAttachment: Boolean
    ): String {
        val sb = StringBuilder()
        val cleanTitle = resolvedTitle.ifBlank { book.title }.ifBlank { L10n.t("제목 미상", "Unknown Title") }
        val cleanAuthor = resolvedAuthor.ifBlank { book.author }.ifBlank { L10n.t("저자 미상", "Unknown Author") }
        val cleanFormat = book.format.ifBlank { "E-BOOK" }.uppercase(Locale.ROOT)
        val curPct = pct(book.progress.coerceIn(0f, 1f))

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append(L10n.t("📚 도서 정보 (Book Information)\n", "📚 Book Information\n"))
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append(L10n.t("• 도서 제목 : ", "• Title: ")).append(cleanTitle).append("\n")
        sb.append(L10n.t("• 저자 이름 : ", "• Author: ")).append(cleanAuthor).append("\n")
        sb.append(L10n.t("• 문서 형식 : ", "• Format: ")).append(cleanFormat)
        if (totalChapters > 0) sb.append(L10n.t(" (총 ${totalChapters}개 챕터/섹션)", " ($totalChapters chapters/sections)"))
        sb.append("\n")
        sb.append(L10n.t("• 현재 진도 : ", "• Progress: ")).append(curPct).append("\n")
        sb.append(L10n.t("• 공유 일시 : ", "• Shared: ")).append(ts.format(Date())).append("\n")
        if (hasCoverAttachment) {
            sb.append(L10n.t("• 도서 표지 : 첨부된 표지 이미지 파일을 확인해 주세요.\n", "• Book Cover: Please see attached cover image.\n"))
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━\n")
        sb.append(L10n.t("📝 선택한 문장 및 독서 노트 (총 ${items.size}건)\n", "📝 Selected Quotes & Reading Notes (${items.size} items)\n"))
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")

        var lastCh = Int.MIN_VALUE
        for ((idx, h) in items.withIndex()) {
            if (h.chapter != lastCh) {
                val chTitle = h.chapterTitle.ifBlank { L10n.t("챕터 ${h.chapter + 1}", "Chapter ${h.chapter + 1}") }
                sb.append("\n■ [").append(chTitle).append("]\n")
                lastCh = h.chapter
            }
            val kindTag = when {
                h.id == 0L && idx == 0 -> L10n.t("[선택 문장]", "[Selected Quote]")
                h.kind == 1 -> L10n.t("[북마크]", "[Bookmark]")
                else -> L10n.t("[하이라이트]", "[Highlight]")
            }
            val locLabel = L10n.t("위치: ", "Loc: ")
            sb.append("\n").append(idx + 1).append(". ").append(kindTag).append(" ($locLabel").append(pct(h.progress)).append(")\n")
            sb.append("“").append(h.quote.trim()).append("”\n")
            if (h.note.isNotBlank()) {
                val memoLabel = L10n.t("   └ 메모: ", "   └ Note: ")
                sb.append(memoLabel).append(h.note.trim()).append("\n")
            }
        }
        sb.append(L10n.t("\n— Aether SAGA 전자책 뷰어에서 공유됨\n", "\n— Shared from Aether SAGA E-Book Viewer\n"))
        return sb.toString()
    }

    fun format(book: BookEntity, items: List<HighlightEntity>, markdown: Boolean = true): String {
        val sb = StringBuilder()
        if (markdown) {
            sb.append("# ").append(book.title).append("\n")
            if (book.author.isNotBlank()) sb.append("*").append(book.author).append("*\n")
            sb.append("\nExported: ").append(ts.format(Date())).append("\n")
        } else {
            sb.append(book.title).append("\n")
            if (book.author.isNotBlank()) sb.append(book.author).append("\n")
            sb.append("Exported: ").append(ts.format(Date())).append("\n")
        }
        var lastCh = Int.MIN_VALUE
        for (h in items) {
            if (h.chapter != lastCh) {
                sb.append("\n").append(if (markdown) "## " else "== ").append(h.chapterTitle).append(if (markdown) "\n" else " ==\n")
                lastCh = h.chapter
            }
            sb.append("\n").append(entry(h, markdown))
        }
        return sb.toString()
    }

    fun single(book: BookEntity, h: HighlightEntity, markdown: Boolean = true): String =
        (if (markdown) "**${book.title}**" else book.title) +
            (if (book.author.isNotBlank()) " - ${book.author}" else "") + "\n\n" + entry(h, markdown)

    private fun entry(h: HighlightEntity, markdown: Boolean): String {
        val sb = StringBuilder()
        val kindTag = if (h.kind == 1) "[북마크] " else "[하이라이트] "
        sb.append(if (markdown) "> $kindTag" else "\"$kindTag").append(h.quote.replace("\n", if (markdown) "\n> " else " "))
        if (!markdown) sb.append("\"")
        sb.append("\n")
        sb.append(if (markdown) "- " else "  ").append(h.chapterTitle).append(" | ").append(pct(h.progress))
            .append(" | ").append(ts.format(Date(h.createdAt))).append("\n")
        if (h.note.isNotBlank()) sb.append(if (markdown) "\n**Note:** " else "  Note: ").append(h.note).append("\n")
        return sb.toString()
    }
}

/**
 * Lightweight ContentProvider that exposes cached book cover images in `cacheDir/share_covers`
 * as `content://com.aether.reader.shareprovider/<filename>` URIs for email attachments.
 */
class ShareFileProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.aether.reader.shareprovider"

        fun shareDir(ctx: Context): File = File(ctx.cacheDir, "share_covers").apply { mkdirs() }

        fun uriForFile(ctx: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.name)
                .build()
    }

    override fun onCreate(): Boolean = true

    private fun resolveFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val name = uri.lastPathSegment?.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            ?: throw FileNotFoundException("Invalid URI")
        val f = File(shareDir(ctx), name)
        if (!f.exists()) throw FileNotFoundException("File not found: ${f.name}")
        return f
    }

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase(Locale.ROOT) ?: ""
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".epub") -> "application/epub+zip"
            name.endsWith(".pdf") -> "application/pdf"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".md") -> "text/markdown"
            name.endsWith(".cbz") -> "application/x-cbz"
            name.endsWith(".cbr") -> "application/x-cbr"
            name.endsWith(".mobi") -> "application/x-mobipocket-ebook"
            name.endsWith(".azw3") -> "application/vnd.amazon.ebook"
            else -> "application/octet-stream"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val f = resolveFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = Array<Any?>(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(values) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolveFile(uri)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object AndroidShareHelper {

    data class SnsQuoteItem(
        val quote: String,
        val memo: String? = null,
        val chapterTitle: String? = null,
        val isBookmark: Boolean = false
    )

    /**
     * Decodes the book's full-color cover bitmap (from container coverBytes or cached coverPath),
     * or renders a clean typographic book cover card if the book has no embedded cover image.
     */
    fun prepareCoverBitmap(
        bookTitle: String,
        bookAuthor: String,
        bookFormat: String,
        rawCoverBytes: ByteArray?,
        cachedCoverPath: String?
    ): Bitmap {
        // 1. Try full-color cover bytes directly from BookContainer
        if (rawCoverBytes != null && rawCoverBytes.isNotEmpty()) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawCoverBytes, 0, rawCoverBytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    var sample = 1
                    while (opts.outWidth / sample > 720 || opts.outHeight / sample > 1024) sample *= 2
                    val decoded = BitmapFactory.decodeByteArray(
                        rawCoverBytes, 0, rawCoverBytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    )
                    if (decoded != null) return decoded
                }
            } catch (_: Throwable) {}
        }

        // 2. Fallback to cached cover thumbnail file if present
        if (!cachedCoverPath.isNullOrBlank()) {
            try {
                val f = File(cachedCoverPath)
                if (f.exists() && f.length() > 0L) {
                    val decoded = BitmapFactory.decodeFile(f.absolutePath)
                    if (decoded != null) return decoded
                }
            } catch (_: Throwable) {}
        }

        // 3. Render a clean typographic book cover card when no embedded cover exists (e.g. TXT / MD)
        return renderTypographicCover(bookTitle, bookAuthor, bookFormat)
    }

    private fun renderTypographicCover(rawTitle: String, rawAuthor: String, rawFormat: String): Bitmap {
        val w = 480
        val h = 680
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Warm paper background + double frame border
        c.drawColor(0xFFF6F4EE.toInt())
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF222222.toInt()
            strokeWidth = 4f
        }
        c.drawRect(24f, 24f, w - 24f, h - 24f, borderPaint)
        borderPaint.strokeWidth = 1.5f
        borderPaint.color = 0xFF666666.toInt()
        c.drawRect(34f, 34f, w - 34f, h - 34f, borderPaint)

        // Top format badge
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            style = Paint.Style.FILL
        }
        c.drawRoundRect(RectF(60f, 64f, 168f, 102f), 8f, 8f, badgePaint)
        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText(rawFormat.ifBlank { "BOOK" }.uppercase(Locale.ROOT).take(6), 114f, 90f, badgeTextPaint)

        // Book Title (multiline wrapped)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            textSize = 36f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val titleText = rawTitle.ifBlank { "제목 미상" }.take(80)
        val sl = StaticLayout.Builder.obtain(titleText, 0, titleText.length, titlePaint, w - 120)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.15f)
            .setMaxLines(6)
            .build()
        c.save()
        c.translate(60f, 160f)
        sl.draw(c)
        c.restore()

        // Divider rule
        val ruleY = (180f + sl.height + 24f).coerceAtMost(h - 160f)
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            strokeWidth = 3f
        }
        c.drawLine(60f, ruleY, 160f, ruleY, rulePaint)

        // Author
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt()
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val authorText = rawAuthor.ifBlank { "Aether SAGA Edition" }.take(40)
        c.drawText(authorText, 60f, ruleY + 44f, authorPaint)

        // Footer brand
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText("Aether SAGA Library", 60f, h - 60f, footerPaint)
        return bmp
    }

    /**
     * Saves the cover Bitmap to the share cache and returns its content:// URI
     * for attaching to outgoing emails.
     */
    fun prepareCoverShareUri(ctx: Context, bookTitle: String, coverBitmap: Bitmap): Uri? = try {
        val dir = ShareFileProvider.shareDir(ctx)
        val safe = bookTitle.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(28).ifBlank { "book" }
        val file = File(dir, "${safe}_cover.jpg")
        val out = FileOutputStream(file)
        val bgBmp = Bitmap.createBitmap(
            coverBitmap.width.coerceAtLeast(1),
            coverBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bgBmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(coverBitmap, 0f, 0f, null)
        bgBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.flush()
        out.close()
        bgBmp.recycle()
        ShareFileProvider.uriForFile(ctx, file)
    } catch (_: Throwable) {
        null
    }

    /**
     * [Option B] Directly opens the device's default email composer (Gmail, Samsung Email, Outlook, etc.)
     * with recipient, subject, complete book info, and selected sentences pre-filled,
     * without showing any general Android share sheet (Bluetooth, Quick Share, Messages, etc.).
     */
    fun openEmailAppDirectly(
        ctx: Context,
        toEmail: String,
        subject: String,
        body: String,
        coverUri: Uri?
    ): Boolean = openEmailAppWithFiles(ctx, toEmail, subject, body, listOfNotNull(coverUri), "image/jpeg")

    /**
     * Directly opens the email composer with one or multiple file attachments
     * (such as full .epub / .pdf / .txt / .cbz book files).
     */
    fun openEmailAppWithFiles(
        ctx: Context,
        toEmail: String,
        subject: String,
        body: String,
        attachmentUris: List<Uri>,
        mimeType: String = "*/*"
    ): Boolean {
        val cleanTo = toEmail.trim()
        val pm = ctx.packageManager

        // 1. Check for dedicated email apps to bypass any chooser completely
        val knownMailPackages = listOf(
            "com.google.android.gm",                 // Gmail
            "com.samsung.android.email.provider",    // Samsung Email
            "com.microsoft.office.outlook",          // Outlook
            "com.android.email",                     // AOSP / E-ink Email
            "com.fsck.k9",                           // K-9 Mail
            "org.kman.AquaMail"                      // AquaMail
        )

        var directPackage: String? = null
        for (pkg in knownMailPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                directPackage = pkg
                break
            } catch (_: Throwable) {}
        }

        // If no known package found, query all apps handling mailto:
        if (directPackage == null) {
            val mailtoQuery = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            val resolveList = pm.queryIntentActivities(mailtoQuery, 0)
            if (resolveList.isNotEmpty()) {
                directPackage = resolveList[0].activityInfo?.packageName
            }
        }

        // Try launching directly with the targeted email package
        if (directPackage != null) {
            try {
                for (uri in attachmentUris) {
                    ctx.grantUriPermission(directPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val directIntent = when {
                    attachmentUris.size > 1 -> {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            setPackage(directPackage)
                            type = "*/*"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    attachmentUris.size == 1 -> {
                        Intent(Intent.ACTION_SEND).apply {
                            setPackage(directPackage)
                            type = mimeType
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                            putExtra(Intent.EXTRA_STREAM, attachmentUris[0])
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    else -> {
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(cleanTo)}")).apply {
                            setPackage(directPackage)
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }
                ctx.startActivity(directIntent)
                return true
            } catch (_: Throwable) {}
        }

        // Fallback 1: standard ACTION_SENDTO with mailto:
        if (attachmentUris.size <= 1) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(cleanTo)}")).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    if (attachmentUris.isNotEmpty()) {
                        putExtra(Intent.EXTRA_STREAM, attachmentUris[0])
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallbackIntent.resolveActivity(pm) != null) {
                    ctx.startActivity(fallbackIntent)
                    return true
                }
            } catch (_: Throwable) {}
        }

        // Fallback 2: ACTION_SEND_MULTIPLE or ACTION_SEND
        try {
            val sendIntent = if (attachmentUris.size > 1) {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = if (attachmentUris.isNotEmpty()) mimeType else "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    if (attachmentUris.isNotEmpty()) {
                        putExtra(Intent.EXTRA_STREAM, attachmentUris[0])
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val chooser = Intent.createChooser(sendIntent, "이메일 전송").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * Packages a collection of highlights into a ZIP file containing text (.txt) and markdown (.md) documents
     * enriched with detailed book information and friendly reading notes.
     */
    fun createHighlightsZip(
        ctx: Context,
        bookTitle: String,
        bookAuthor: String,
        highlights: List<HighlightEntity>
    ): File? = try {
        val dir = ShareFileProvider.shareDir(ctx)
        val safeTitle = bookTitle.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(24).ifBlank { "highlights" }
        val zipFile = File(dir, "AetherSAGA_Highlights_${safeTitle}.zip")

        val hlCount = highlights.count { it.kind == 0 }
        val bmCount = highlights.count { it.kind == 1 }
        val memoCount = highlights.count { it.note.isNotBlank() }
        val dateStr = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN).format(Date())

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. Text format
            val textEntry = ZipEntry("${safeTitle}_하이라이트.txt")
            zos.putNextEntry(textEntry)
            val sb = StringBuilder()
            sb.appendLine("==================================================")
            sb.appendLine("📚 [Aether SAGA] 독서 하이라이트 및 메모 기록")
            sb.appendLine("==================================================")
            sb.appendLine("• 도서명: 《$bookTitle》")
            sb.appendLine("• 저자: ${if (bookAuthor.isNotBlank()) bookAuthor else "저자 정보 없음"}")
            sb.appendLine("• 기록 내역: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            sb.appendLine("• 추출 일시: $dateStr")
            sb.appendLine()
            sb.appendLine("본 문서는 Aether SAGA 전자책 뷰어에서 독자가 직접 남긴")
            sb.appendLine("소중한 문장과 독서 메모를 영구 보관하기 위해 정리된 문서입니다.")
            sb.appendLine("==================================================")
            sb.appendLine()
            highlights.forEachIndexed { i, h ->
                val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(h.createdAt))
                val chTitle = h.chapterTitle.ifBlank { "챕터/페이지 ${h.chapter + 1}" }
                val typeName = if (h.kind == 1) "북마크" else "하이라이트"
                sb.appendLine("[#${i + 1}] [$typeName] $chTitle ($itemDate)")
                sb.appendLine("• 본문 인용: \"${h.quote.trim()}\"")
                if (h.note.isNotBlank()) {
                    sb.appendLine("  ✍ 독서 메모: ${h.note.trim()}")
                }
                sb.appendLine("--------------------------------------------------")
            }
            zos.write(sb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Markdown format
            val mdEntry = ZipEntry("${safeTitle}_하이라이트.md")
            zos.putNextEntry(mdEntry)
            val md = StringBuilder()
            md.appendLine("# 📚 《$bookTitle》 독서 하이라이트 및 메모")
            md.appendLine()
            md.appendLine("> 본 문서는 **Aether SAGA** 전자책 뷰어에서 기록한 독서 하이라이트와 사유 메모를 정리한 문서입니다.")
            md.appendLine()
            md.appendLine("## 📖 도서 정보")
            md.appendLine("- **도서명**: 《$bookTitle》")
            md.appendLine("- **저자**: ${if (bookAuthor.isNotBlank()) bookAuthor else "저자 정보 없음"}")
            md.appendLine("- **기록 내역**: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            md.appendLine("- **추출 일시**: $dateStr")
            md.appendLine()
            md.appendLine("---")
            highlights.forEachIndexed { i, h ->
                val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(h.createdAt))
                val chTitle = h.chapterTitle.ifBlank { "챕터/페이지 ${h.chapter + 1}" }
                val typeName = if (h.kind == 1) "🔖 북마크" else "🖍 하이라이트"
                md.appendLine("\n### ${i + 1}. $typeName - $chTitle (`$itemDate`)")
                md.appendLine("> ${h.quote.trim()}\n")
                if (h.note.isNotBlank()) {
                    md.appendLine("✍ **독서 메모**: ${h.note.trim()}\n")
                }
            }
            zos.write(md.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        if (zipFile.exists() && zipFile.length() > 0L) zipFile else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Zips the highlights and directly opens the email app with the ZIP attachment,
     * including polite, courteous greetings, rich book details, and highlight previews in the email body.
     */
    fun sendHighlightsZipEmail(
        ctx: Context,
        toEmail: String,
        bookTitle: String,
        bookAuthor: String,
        highlights: List<HighlightEntity>
    ): Boolean {
        val zipFile = createHighlightsZip(ctx, bookTitle, bookAuthor, highlights) ?: return false
        val zipUri = ShareFileProvider.uriForFile(ctx, zipFile) ?: return false
        val authorPart = if (bookAuthor.isNotBlank()) " ($bookAuthor)" else ""
        val subject = "[Aether SAGA] 《$bookTitle》$authorPart - 독서 하이라이트 및 메모 (${highlights.size}건)"
        val hlCount = highlights.count { it.kind == 0 }
        val bmCount = highlights.count { it.kind == 1 }
        val memoCount = highlights.count { it.note.isNotBlank() }
        val dateStr = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN).format(Date())

        val body = buildString {
            appendLine("안녕하세요.")
            appendLine("Aether SAGA 전자책 뷰어에서 발송된 《$bookTitle》 독서 기록 및 하이라이트 모음입니다.")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📖 [도서 상세 정보]")
            appendLine("• 도서명: 《$bookTitle》")
            if (bookAuthor.isNotBlank()) {
                appendLine("• 저자: $bookAuthor")
            }
            appendLine("• 기록 내역: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            appendLine("• 전송 일시: $dateStr")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("📝 [주요 하이라이트 및 메모 미리보기]")
            val previewItems = highlights.take(5)
            previewItems.forEachIndexed { idx, h ->
                val chName = h.chapterTitle.ifBlank { "본문" }
                val prefix = if (h.kind == 1) "🔖 [북마크]" else "🖍 [하이라이트]"
                appendLine("${idx + 1}. $prefix $chName")
                appendLine("   \"${h.quote.trim()}\"")
                if (h.note.isNotBlank()) {
                    appendLine("   ✍ 메모: ${h.note.trim()}")
                }
                appendLine()
            }
            if (highlights.size > 5) {
                appendLine("... 외 ${highlights.size - 5}건의 기록이 첨부파일에 모두 수록되어 있습니다.")
                appendLine()
            }
            appendLine("📦 [첨부파일 안내]")
            appendLine("첨부된 압축파일(${zipFile.name}) 내에 아래 2가지 포맷의 전체 기록 문서가 포함되어 있습니다:")
            appendLine("1. 텍스트 문서 (.txt): 메모장 등 모든 기기에서 바로 열 수 있는 일반 텍스트 파일")
            appendLine("2. 마크다운 문서 (.md): 노션(Notion), 옵시디언(Obsidian) 등에서 서식이 유지되는 마크다운 파일")
            appendLine()
            appendLine("소중한 독서 기록을 영구 보관하고 다양하게 활용해 보세요.")
            appendLine("감사합니다.")
            appendLine()
            appendLine("— Aether SAGA (journeys through endless stories)")
        }
        return openEmailAppWithFiles(ctx, toEmail, subject, body, listOf(zipUri), "application/zip")
    }

    enum class SnsTarget(
        val title: String,
        val androidPackage: String,
        val playStoreUrl: String,
        val appStoreUrl: String
    ) {
        SAVE_IMAGE(
            "이미지 저장", "", "", ""
        ),
        KAKAO(
            "카카오톡", "com.kakao.talk",
            "https://play.google.com/store/apps/details?id=com.kakao.talk",
            "https://apps.apple.com/app/id362057947"
        ),
        SMS(
            "문자 메시지", "", "", ""
        ),
        INSTAGRAM(
            "Instagram", "com.instagram.android",
            "https://play.google.com/store/apps/details?id=com.instagram.android",
            "https://apps.apple.com/app/id389801252"
        ),
        THREADS(
            "Threads", "com.instagram.barcelona",
            "https://play.google.com/store/apps/details?id=com.instagram.barcelona",
            "https://apps.apple.com/app/id6446901499"
        ),
        X(
            "X (Twitter)", "com.twitter.android",
            "https://play.google.com/store/apps/details?id=com.twitter.android",
            "https://apps.apple.com/app/id333903271"
        ),
        FACEBOOK(
            "Facebook", "com.facebook.katana",
            "https://play.google.com/store/apps/details?id=com.facebook.katana",
            "https://apps.apple.com/app/id284882215"
        );

        val localizedTitle: String
            get() = when (this) {
                SAVE_IMAGE -> L10n.t("💾 독서 카드 이미지 저장", "💾 Save Card Image to Gallery")
                KAKAO -> L10n.t("💬 카카오톡 (KakaoTalk)", "💬 KakaoTalk")
                SMS -> L10n.t("✉️ 문자 메시지 (SMS / MMS)", "✉️ Text Message (SMS / MMS)")
                INSTAGRAM -> "Instagram"
                THREADS -> "Threads"
                X -> "X (Twitter)"
                FACEBOOK -> "Facebook"
            }

        fun isInstalled(context: Context): Boolean {
            if (this == SAVE_IMAGE) return true
            if (this == SMS) return canSendSms(context)
            val pm = context.packageManager
            return try {
                pm.getPackageInfo(androidPackage, 0)
                true
            } catch (_: Throwable) {
                if (this == X) {
                    try {
                        pm.getPackageInfo("com.twitter.android.lite", 0)
                        true
                    } catch (_: Throwable) { false }
                } else false
            }
        }

        fun openStore(context: Context) {
            val isIos = false // Android runtime
            val intent = if (!isIos) {
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$androidPackage")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(appStoreUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playStoreUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Throwable) {
                    Toast.makeText(context, "스토어를 열 수 없습니다: $playStoreUrl", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Renders an elegant typographic Quote Card bitmap (1080x1080) for SNS feed sharing.
     */
    fun renderQuoteCard(
        bookTitle: String,
        bookAuthor: String,
        quote: String,
        memo: String? = null,
        coverBitmap: Bitmap? = null
    ): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Warm elegant paper tone
        c.drawColor(0xFFF9F7F1.toInt())

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFD8D2C2.toInt()
            strokeWidth = 3f
        }
        c.drawRect(48f, 48f, w - 48f, h - 48f, borderPaint)

        borderPaint.strokeWidth = 1.2f
        borderPaint.color = 0xFFB8B09E.toInt()
        c.drawRect(60f, 60f, w - 60f, h - 60f, borderPaint)

        // Book Cover placement in Top Right ([시안 A-2]: 대형 강조형 175x248 px)
        val isLocalCover = (coverBitmap == null)
        val coverToDraw: Bitmap = coverBitmap ?: renderTypographicCover(bookTitle, bookAuthor, "")
        try {
            val coverW = 175f
            val coverH = 248f
            val coverX = w - 100f - coverW
            val coverY = 75f

            // 1. Soft drop shadow behind cover
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33000000.toInt()
            }
            c.drawRoundRect(RectF(coverX + 4f, coverY + 6f, coverX + coverW + 4f, coverY + coverH + 6f), 8f, 8f, shadowPaint)

            // 2. Rounded cover clipping & draw
            val coverRect = RectF(coverX, coverY, coverX + coverW, coverY + coverH)
            val clipPath = Path().apply {
                addRoundRect(coverRect, 8f, 8f, Path.Direction.CW)
            }
            c.save()
            c.clipPath(clipPath)
            c.drawBitmap(coverToDraw, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()

            // 3. Subtle 1.5px border over cover
            val coverBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x55888888.toInt()
                strokeWidth = 1.5f
            }
            c.drawRoundRect(coverRect, 8f, 8f, coverBorderPaint)
        } finally {
            if (isLocalCover && !coverToDraw.isRecycled) {
                coverToDraw.recycle()
            }
        }

        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 32f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("Aether SAGA", 100f, 130f, brandPaint)

        val bookInfoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val unknownAuthor = L10n.t("저자 미상", "Unknown Author")
        val metaText = "${bookTitle.take(24)} · ${bookAuthor.ifBlank { unknownAuthor }.take(16)}"
        c.drawText(metaText, 100f, 175f, bookInfoPaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0DDD5.toInt()
            strokeWidth = 2f
        }
        val coverStartX = w - 100f - 175f
        c.drawLine(100f, 210f, coverStartX - 35f, 210f, rulePaint)

        val quoteMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD4CCBC.toInt()
            textSize = 140f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText("“", 95f, 320f, quoteMarkPaint)

        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            textSize = if (quote.length > 150) 36f else 42f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val contentW = w - 200
        val quoteText = quote.trim().take(300)
        val quoteLayout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, quotePaint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(12f, 1.25f)
            .setMaxLines(9)
            .build()

        c.save()
        c.translate(100f, 370f)
        quoteLayout.draw(c)
        c.restore()

        val nextY = 370f + quoteLayout.height + 40f
        if (!memo.isNullOrBlank()) {
            val memoRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F5E9C.toInt()
                strokeWidth = 4f
            }
            val memoY = nextY.coerceAtMost(h - 220f)
            c.drawLine(100f, memoY, 100f, memoY + 50f, memoRulePaint)

            val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF4A5568.toInt()
                textSize = 28f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            }
            val memoPrefix = L10n.t("독서 메모: ", "Note: ")
            val memoFull = "$memoPrefix${memo.trim().take(120)}"
            val memoLayout = StaticLayout.Builder.obtain(memoFull, 0, memoFull.length, memoPaint, contentW - 30)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(6f, 1.15f)
                .setMaxLines(2)
                .build()
            c.save()
            c.translate(120f, memoY + 4f)
            memoLayout.draw(c)
            c.restore()
        }

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("journeys through endless stories", w - 100f, h - 90f, footerPaint)

        return bmp
    }

    /**
     * Renders a vertical 9:16 (1080x1920) Story Card bundling up to 4 quotes/bookmarks/notes
     * for Instagram/Facebook Stories.
     */
    fun renderStoryBundleCard(
        bookTitle: String,
        bookAuthor: String,
        items: List<SnsQuoteItem>,
        coverBitmap: Bitmap? = null
    ): Bitmap {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Warm elegant paper tone
        c.drawColor(0xFFF9F7F1.toInt())

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFD8D2C2.toInt()
            strokeWidth = 3f
        }
        c.drawRect(48f, 48f, w - 48f, h - 48f, borderPaint)

        borderPaint.strokeWidth = 1.2f
        borderPaint.color = 0xFFB8B09E.toInt()
        c.drawRect(60f, 60f, w - 60f, h - 60f, borderPaint)

        // Book Cover placement in Top Right ([시안 A-2]: 175x248 px)
        val isLocalCover = (coverBitmap == null)
        val coverToDraw: Bitmap = coverBitmap ?: renderTypographicCover(bookTitle, bookAuthor, "")
        val coverW = 175f
        val coverH = 248f
        val coverX = w - 100f - coverW
        val coverY = 80f
        try {
            // 1. Soft drop shadow behind cover
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33000000.toInt()
            }
            c.drawRoundRect(RectF(coverX + 4f, coverY + 6f, coverX + coverW + 4f, coverY + coverH + 6f), 10f, 10f, shadowPaint)

            // 2. Rounded cover clipping & draw
            val coverRect = RectF(coverX, coverY, coverX + coverW, coverY + coverH)
            val clipPath = Path().apply {
                addRoundRect(coverRect, 10f, 10f, Path.Direction.CW)
            }
            c.save()
            c.clipPath(clipPath)
            c.drawBitmap(coverToDraw, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()

            // 3. Subtle 1.5px border over cover
            val coverBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x55888888.toInt()
                strokeWidth = 1.5f
            }
            c.drawRoundRect(coverRect, 10f, 10f, coverBorderPaint)
        } finally {
            if (isLocalCover && !coverToDraw.isRecycled) {
                coverToDraw.recycle()
            }
        }

        // Brand & Subtitle
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("Aether SAGA", 100f, 115f, brandPaint)

        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888275.toInt()
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.04f
        }
        c.drawText(L10n.t("독서 노트 스토리 컬렉션", "Reading Notes Story Collection"), 100f, 162f, badgePaint)

        val bookTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2825.toInt()
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText(bookTitle.take(22), 100f, 210f, bookTitlePaint)

        val unknownAuthor = L10n.t("저자 미상", "Unknown Author")
        val bookAuthorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A7468.toInt()
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(bookAuthor.ifBlank { unknownAuthor }.take(22), 100f, 256f, bookAuthorPaint)

        // Header Divider (stops before cover)
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0DDD5.toInt()
            strokeWidth = 2f
        }
        c.drawLine(100f, 305f, coverX - 30f, 305f, rulePaint)

        // Content Area: from y = 350f to y = 1810f (~1460px)
        val contentW = (w - 200).toInt()
        val bundleItems = items.take(4)
        val n = bundleItems.size.coerceAtLeast(1)
        val slotH = 1460f / n

        val quoteMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD4CCBC.toInt()
            textSize = if (n <= 2) 54f else 46f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val quoteTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2825.toInt()
            textSize = if (n <= 2) 32f else (if (n == 3) 28f else 25f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

        val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = if (n <= 2) 26f else 23f
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        }

        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        val slotDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2DDD2.toInt()
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        for ((idx, item) in bundleItems.withIndex()) {
            val slotTop = 350f + (idx * slotH)
            var curY = slotTop + 20f

            // Tag or Chapter info
            val tagText = buildString {
                append(if (item.isBookmark) "🔖 " else "🖍 ")
                if (!item.chapterTitle.isNullOrBlank()) {
                    append(item.chapterTitle.take(30))
                } else {
                    append(L10n.t("인용구", "Quote"))
                    append(" #${idx + 1}")
                }
            }
            c.drawText(tagText, 100f, curY, tagPaint)
            curY += 34f

            // Quote Mark
            c.drawText("“", 100f, curY + 6f, quoteMarkPaint)
            curY += 38f

            // Quote Text Layout
            val maxLinesForQuote = if (n <= 2) 8 else (if (n == 3) 5 else 4)
            val quoteLayout = StaticLayout.Builder.obtain(item.quote.trim(), 0, item.quote.trim().length, quoteTextPaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(if (n <= 2) 6f else 4f, 1.18f)
                .setMaxLines(maxLinesForQuote)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()

            c.save()
            c.translate(100f, curY)
            quoteLayout.draw(c)
            c.restore()

            curY += quoteLayout.height + 15f

            // Memo / Note
            if (!item.memo.isNullOrBlank()) {
                val memoRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF1F5E9C.toInt()
                    strokeWidth = 3f
                }
                c.drawLine(100f, curY, 100f, curY + 36f, memoRulePaint)

                val memoFull = "  ${L10n.t("메모: ", "Note: ")}${item.memo.trim().take(80)}"
                val memoLayout = StaticLayout.Builder.obtain(memoFull, 0, memoFull.length, memoPaint, contentW - 20)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()

                c.save()
                c.translate(110f, curY + 2f)
                memoLayout.draw(c)
                c.restore()
            }

            // Divider between items (if not last)
            if (idx < bundleItems.size - 1) {
                val divY = slotTop + slotH - 8f
                c.drawLine(120f, divY, w - 120f, divY, slotDividerPaint)
            }
        }

        // Footer
        c.drawLine(100f, 1830f, w - 100f, 1830f, rulePaint)

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8274.toInt()
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("journeys through endless stories", w - 100f, 1860f, footerPaint)

        val footerCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8274.toInt()
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val countText = L10n.t("선택된 독서 기록 ${bundleItems.size}건", "${bundleItems.size} Selected Notes")
        c.drawText(countText, 100f, 1860f, footerCountPaint)

        return bmp
    }


    /**
     * Renders a stylish Reading Stats Card bitmap (1080x1080) for SNS feed sharing.
     */
    fun renderStatsCard(
        periodName: String,
        summary: String,
        insight: String,
        completedBooksCount: Int
    ): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawColor(0xFFF8FAFC.toInt())

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFCBD5E1.toInt()
            strokeWidth = 3f
        }
        c.drawRect(48f, 48f, w - 48f, h - 48f, borderPaint)

        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("Aether SAGA · $periodName", 100f, 130f, brandPaint)

        val headerRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 2f
        }
        c.drawLine(100f, 170f, w - 100f, 170f, headerRulePaint)

        val summaryTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 44f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        c.drawText(L10n.t("📊 나의 독서 활동 요약", "📊 My Reading Summary"), 100f, 250f, summaryTitlePaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        c.drawRoundRect(RectF(100f, 290f, w - 100f, 490f), 24f, 24f, cardPaint)
        val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(RectF(100f, 290f, w - 100f, 490f), 24f, 24f, cardBorder)

        val statsTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        c.drawText(summary.take(45), 140f, 370f, statsTextPaint)

        val bookCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2563EB.toInt()
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(L10n.t("📚 누적 완독 도서: ${completedBooksCount}권", "📚 Completed Books: $completedBooksCount"), 140f, 440f, bookCountPaint)

        val insightTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt()
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        c.drawText(L10n.t("💡 독서 총평 및 인사이트", "💡 Reading Review & Insights"), 100f, 560f, insightTitlePaint)

        val insightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF334155.toInt()
            textSize = 32f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val contentW = w - 200
        val defaultInsight = L10n.t("꾸준한 독서 습관이 이어지고 있습니다.", "Building a steady reading habit.")
        val insightClean = insight.ifBlank { defaultInsight }.take(200)
        val insightLayout = StaticLayout.Builder.obtain(insightClean, 0, insightClean.length, insightPaint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(8f, 1.2f)
            .setMaxLines(6)
            .build()
        c.save()
        c.translate(100f, 600f)
        insightLayout.draw(c)
        c.restore()

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("Aether SAGA · journeys through endless stories", w - 100f, h - 90f, footerPaint)

        return bmp
    }

    fun prepareSnsCardUri(ctx: Context, bitmap: Bitmap, fileName: String = "AetherSAGA_SNS_Card.png"): Uri? = try {
        val dir = ShareFileProvider.shareDir(ctx)
        val file = File(dir, fileName)
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.flush()
        out.close()
        ShareFileProvider.uriForFile(ctx, file)
    } catch (_: Throwable) {
        null
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    fun canSendSms(context: Context): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return try {
            val defaultSms = Telephony.Sms.getDefaultSmsPackage(context)
            if (!defaultSms.isNullOrBlank()) return true
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            pm.queryIntentActivities(smsIntent, 0).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    fun saveCardToGallery(context: Context, bitmap: Bitmap, prefix: String = "AetherSAGA"): Boolean {
        return try {
            val fileName = "${prefix}_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AetherSAGA")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    true
                } else false
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = java.io.File(picturesDir, "AetherSAGA")
                if (!appDir.exists()) appDir.mkdirs()
                val file = java.io.File(appDir, fileName)
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                true
            }
        } catch (e: Exception) {
            false
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun showSnsAppNotInstalledDialog(activity: Activity, target: SnsTarget) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = ReaderTheme.EINK_BORDER_CONTRAST
        val strokeW = activity.dp(2)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
        }

        // Inner Card Box with 2px High Contrast Border
        val cardBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol, strokeW)
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Official App Icon
        val iconIv = ImageView(activity).apply {
            setImageDrawable(getSnsAppIcon(activity, target))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        cardBox.addView(iconIv, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
            bottomMargin = activity.dp(10)
        })

        // Title with App Name
        val titleTv = TextView(activity).apply {
            text = L10n.t("${target.localizedTitle} 앱 미설치", "${target.title} Not Installed")
            setPtSize(10f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, activity.dp(6))
        }
        cardBox.addView(titleTv)

        // Description
        val descTv = TextView(activity).apply {
            text = L10n.t(
                "${target.localizedTitle} 앱이 기기에 설치되어 있지 않습니다.\n\n구글 플레이스토어로 이동하여 앱을 다운로드하시겠습니까?",
                "${target.title} is not installed on this device.\n\nWould you like to open Google Play Store to download it?"
            )
            setPtSize(8.5f)
            setTextColor(inkCol)
            gravity = Gravity.CENTER
            setLineSpacing(activity.dp(3).toFloat(), 1.15f)
            setPadding(0, 0, 0, activity.dp(4))
        }
        cardBox.addView(descTv)
        root.addView(cardBox)

        // Action Buttons Row
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(12), 0, 0)
        }

        lateinit var dlg: AlertDialog
        val cancelBtn = TextView(activity).apply {
            text = L10n.t("취소", "Cancel")
            setPtSize(8.8f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(ReaderTheme.CORNER_PILL_DP).toFloat()
                setStroke(strokeW, borderCol)
            }
            setPadding(activity.dp(14), activity.dp(9), activity.dp(14), activity.dp(9))
            setOnClickListener { dlg.dismiss() }
        }

        val storeBtn = TextView(activity).apply {
            text = L10n.t("스토어로 이동 ↗", "Open Store ↗")
            setPtSize(8.8f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ReaderTheme.ACCENT_RIDI_BLUE)
                cornerRadius = activity.dp(ReaderTheme.CORNER_PILL_DP).toFloat()
            }
            setPadding(activity.dp(14), activity.dp(9), activity.dp(14), activity.dp(9))
            setOnClickListener {
                dlg.dismiss()
                target.openStore(activity)
            }
        }

        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = activity.dp(6) })
        btnRow.addView(storeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        root.addView(btnRow)

        dlg = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(activity)
    }

    fun shareQuoteToSns(
        activity: Activity,
        target: SnsTarget,
        bookTitle: String,
        bookAuthor: String,
        quote: String,
        memo: String? = null,
        coverBitmap: Bitmap? = null
    ) {
        shareQuotesToSns(
            activity = activity,
            target = target,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            items = listOf(SnsQuoteItem(quote = quote, memo = memo)),
            coverBitmap = coverBitmap
        )
    }

    fun shareQuotesToSns(
        activity: Activity,
        target: SnsTarget,
        bookTitle: String,
        bookAuthor: String,
        items: List<SnsQuoteItem>,
        coverBitmap: Bitmap? = null,
        isEink: Boolean = false
    ) {
        if (items.isEmpty()) return

        // 1. Gallery Save
        if (target == SnsTarget.SAVE_IMAGE) {
            val saveLimit = 10
            val sendItems = items.take(saveLimit)
            var savedCount = 0
            for ((idx, item) in sendItems.withIndex()) {
                val cardBmp = renderQuoteCard(bookTitle, bookAuthor, item.quote, item.memo, coverBitmap)
                val suffix = if (sendItems.size > 1) String.format("_%02d", idx + 1) else ""
                val ok = saveCardToGallery(activity, cardBmp, "AetherSAGA_Quote$suffix")
                if (ok) savedCount++
                cardBmp.recycle()
            }
            if (savedCount > 0) {
                val msg = if (savedCount > 1) {
                    L10n.t("독서 카드 ${savedCount}장이 갤러리(Pictures/AetherSAGA)에 저장되었습니다. 💾", "Saved $savedCount reading cards to Gallery (Pictures/AetherSAGA). 💾")
                } else {
                    L10n.t("독서 카드가 갤러리(Pictures/AetherSAGA)에 저장되었습니다. 💾", "Reading card saved to Gallery (Pictures/AetherSAGA). 💾")
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, L10n.t("이미지 저장에 실패했습니다.", "Failed to save image."), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // 2. SMS / MMS
        if (target == SnsTarget.SMS) {
            val firstItem = items.first()
            val cardBmp = renderQuoteCard(bookTitle, bookAuthor, firstItem.quote, firstItem.memo, coverBitmap)
            val cardUri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Quote_Card.png")
            val authorPart = bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }
            val shareText = "📖 $bookTitle ($authorPart)\n\n\"${firstItem.quote.take(180)}\"\n\n- Aether SAGA"
            dispatchSmsIntent(activity, shareText, cardUri)
            cardBmp.recycle()
            return
        }

        if (!target.isInstalled(activity)) {
            showSnsAppNotInstalledDialog(activity, target)
            return
        }

        // 3. Instagram: Offer Story (max 4 bundled into 1) vs Feed (multi-card slides up to 10)
        if (target == SnsTarget.INSTAGRAM) {
            showInstagramModeChooser(
                activity = activity,
                itemsCount = items.size,
                isEink = isEink,
                onStoryChosen = {
                    val storyItems = items.take(4)
                    val storyBmp = renderStoryBundleCard(bookTitle, bookAuthor, storyItems, coverBitmap)
                    val storyUri = prepareSnsCardUri(activity, storyBmp, "AetherSAGA_Story.png")
                    if (storyUri != null) {
                        dispatchInstagramStoryIntent(activity, storyUri)
                    }
                    storyBmp.recycle()
                },
                onFeedChosen = {
                    val feedLimit = 10
                    val feedItems = items.take(feedLimit)
                    val uris = ArrayList<Uri>()
                    for ((idx, item) in feedItems.withIndex()) {
                        val cardBmp = renderQuoteCard(bookTitle, bookAuthor, item.quote, item.memo, coverBitmap)
                        val uri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Quote_${idx + 1}.png")
                        if (uri != null) uris.add(uri)
                        cardBmp.recycle()
                    }
                    val authorPart = bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }
                    val hashPart = L10n.t("#독서 #책스타그램", "#Reading #Bookworm")
                    val shareText = "📖 $bookTitle ($authorPart)\n\n\"${feedItems.first().quote.take(180)}\"\n\n#AetherSAGA $hashPart"
                    dispatchSnsMultipleIntent(activity, SnsTarget.INSTAGRAM, shareText, uris)
                }
            )
            return
        }

        // 4. Other Social Targets (X, Facebook, Threads, KakaoTalk)
        val platformLimit = when (target) {
            SnsTarget.X -> 4
            else -> 10
        }
        val sendItems = items.take(platformLimit)
        val uris = ArrayList<Uri>()
        for ((idx, item) in sendItems.withIndex()) {
            val cardBmp = renderQuoteCard(bookTitle, bookAuthor, item.quote, item.memo, coverBitmap)
            val uri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Quote_${idx + 1}.png")
            if (uri != null) uris.add(uri)
            cardBmp.recycle()
        }

        val authorPart = bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }
        val hashPart = L10n.t("#독서 #책스타그램", "#Reading #Bookworm")
        val shareText = "📖 $bookTitle ($authorPart)\n\n\"${sendItems.first().quote.take(180)}\"\n\n#AetherSAGA $hashPart"

        if (uris.size == 1) {
            dispatchSnsIntent(activity, target, shareText, uris[0])
        } else {
            dispatchSnsMultipleIntent(activity, target, shareText, uris)
        }
    }

    fun shareStatsToSns(
        activity: Activity,
        target: SnsTarget,
        periodName: String,
        summary: String,
        insight: String,
        completedBooksCount: Int
    ) {
        if (target == SnsTarget.SAVE_IMAGE) {
            val cardBmp = renderStatsCard(periodName, summary, insight, completedBooksCount)
            val ok = saveCardToGallery(activity, cardBmp, "AetherSAGA_Stats")
            if (ok) {
                Toast.makeText(activity, L10n.t("독서 통계 카드가 갤러리(Pictures/AetherSAGA)에 저장되었습니다. 💾", "Stats card saved to Gallery (Pictures/AetherSAGA). 💾"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, L10n.t("이미지 저장에 실패했습니다.", "Failed to save image."), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (target == SnsTarget.SMS) {
            val cardBmp = renderStatsCard(periodName, summary, insight, completedBooksCount)
            val cardUri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Stats_Card.png")
            val statsHeader = L10n.t("나의 독서 기록", "My Reading Stats")
            val shareText = "📊 [AetherSAGA] $statsHeader ($periodName)\n$summary\n\n- Aether SAGA"
            dispatchSmsIntent(activity, shareText, cardUri)
            return
        }

        if (!target.isInstalled(activity)) {
            showSnsAppNotInstalledDialog(activity, target)
            return
        }

        val cardBmp = renderStatsCard(periodName, summary, insight, completedBooksCount)
        val cardUri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Stats_Card.png")
        val statsHeader = L10n.t("나의 독서 기록", "My Reading Stats")
        val hashPart = L10n.t("#독서기록 #독서통계", "#ReadingLog #BookStats")
        val shareText = "📊 [AetherSAGA] $statsHeader ($periodName)\n$summary\n\n#AetherSAGA $hashPart"
        dispatchSnsIntent(activity, target, shareText, cardUri)
    }


    private fun showInstagramModeChooser(
        activity: Activity,
        itemsCount: Int,
        isEink: Boolean,
        onStoryChosen: () -> Unit,
        onFeedChosen: () -> Unit
    ) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = if (isEink) ReaderTheme.EINK_BORDER_CONTRAST else ReaderTheme.EINK_BORDER
        val strokeW = if (isEink) activity.dp(2) else activity.dp(1)
        val mutedCol = ReaderTheme.EINK_MUTED

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(16), activity.dp(18), activity.dp(16))
        }

        val subTv = TextView(activity).apply {
            text = L10n.t(
                "인스타그램에서 공유할 방식을 선택하세요.",
                "Choose how to share on Instagram."
            )
            setPtSize(8.4f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, activity.dp(12))
        }
        root.addView(subTv)

        val listCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol, strokeW)
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
        }

        lateinit var dlg: AlertDialog

        // 1. Stories option (max 4 items bundled)
        val storyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = activity.dp(6).toFloat()
            }
            setPadding(activity.dp(10), activity.dp(12), activity.dp(10), activity.dp(12))
            setOnClickListener {
                dlg.dismiss()
                onStoryChosen()
            }
        }
        val storyIcon = ImageView(activity).apply {
            setImageDrawable(getSnsAppIcon(activity, SnsTarget.INSTAGRAM))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        storyRow.addView(storyIcon, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))

        val storyCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, 0, 0)
        }
        val storyTitleTv = TextView(activity).apply {
            text = L10n.t("✨ 스토리 (Story) - 1장 종합 카드", "✨ Stories (1 Bundled Card)")
            setPtSize(9.2f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
        }
        val storySubTv = TextView(activity).apply {
            val countNotice = if (itemsCount > 1) {
                L10n.t("선택한 문장 최대 4건을 9:16 세로형 1장 카드로 묶어 업로드", "Bundle up to 4 quotes into 1 vertical story card")
            } else {
                L10n.t("9:16 세로형 감성 독서 카드로 스토리에 업로드", "Upload as 9:16 vertical card to Stories")
            }
            text = countNotice
            setPtSize(7.6f)
            setTextColor(0xFF1F5E9C.toInt())
        }
        storyCol.addView(storyTitleTv)
        storyCol.addView(storySubTv)
        storyRow.addView(storyCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val storyChevron = TextView(activity).apply {
            text = "›"
            setPtSize(13f)
            setTextColor(mutedCol)
        }
        storyRow.addView(storyChevron)
        listCard.addView(storyRow)

        // Divider
        listCard.addView(View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
                setMargins(activity.dp(8), 0, activity.dp(8), 0)
            }
        })

        // 2. Feed option (multi-card carousel)
        val feedRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = activity.dp(6).toFloat()
            }
            setPadding(activity.dp(10), activity.dp(12), activity.dp(10), activity.dp(12))
            setOnClickListener {
                dlg.dismiss()
                onFeedChosen()
            }
        }
        val feedIcon = ImageView(activity).apply {
            setImageDrawable(getSnsAppIcon(activity, SnsTarget.INSTAGRAM))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        feedRow.addView(feedIcon, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))

        val feedCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, 0, 0)
        }
        val feedTitleTv = TextView(activity).apply {
            val titleStr = if (itemsCount > 1) {
                L10n.t("📸 피드 (Feed) - 여러 장 슬라이드", "📸 Feed (Multi-Card Carousel)")
            } else {
                L10n.t("📸 피드 (Feed) - 1:1 정사각형 카드", "📸 Feed (1:1 Square Card)")
            }
            text = titleStr
            setPtSize(9.2f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
        }
        val feedSubTv = TextView(activity).apply {
            val countNotice = if (itemsCount > 1) {
                L10n.t("선택한 문장 ${minOf(itemsCount, 10)}건을 개별 카드로 슬라이드 등록", "Upload up to ${minOf(itemsCount, 10)} cards as feed carousel")
            } else {
                L10n.t("1080x1080 고해상도 정사각형 카드로 피드에 게시", "Post 1080x1080 square card to feed")
            }
            text = countNotice
            setPtSize(7.6f)
            setTextColor(0xFF1F5E9C.toInt())
        }
        feedCol.addView(feedTitleTv)
        feedCol.addView(feedSubTv)
        feedRow.addView(feedCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val feedChevron = TextView(activity).apply {
            text = "›"
            setPtSize(13f)
            setTextColor(mutedCol)
        }
        feedRow.addView(feedChevron)
        listCard.addView(feedRow)

        root.addView(listCard)

        dlg = AlertDialog.Builder(activity)
            .setTitle(L10n.t("Instagram 공유 방식 선택", "Select Instagram Share Mode"))
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(activity)
    }

    private fun dispatchInstagramStoryIntent(
        activity: Activity,
        storyUri: Uri
    ) {
        val igPackage = "com.instagram.android"
        activity.grantUriPermission(igPackage, storyUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // 1. Try official Instagram Story Intent ("com.instagram.share.ADD_TO_STORY")
        // Use clean background asset ONLY (omit interactive_asset_uri to prevent duplicate overlapping sticker)
        val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(storyUri, "image/png")
            putExtra("source_application", activity.packageName)
            putExtra("top_background_color", "#F9F7F1")
            putExtra("bottom_background_color", "#F9F7F1")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = activity.packageManager
        if (pm.resolveActivity(storyIntent, 0) != null) {
            activity.startActivity(storyIntent)
            return
        }

        // 2. Fallback: Query ACTION_SEND activities in Instagram looking for Story handler
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            setPackage(igPackage)
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, storyUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolveInfos = pm.queryIntentActivities(sendIntent, 0)
        val storyRi = resolveInfos.find {
            it.activityInfo.name.contains("Story", ignoreCase = true) ||
            it.loadLabel(pm).toString().contains("Story", ignoreCase = true) ||
            it.loadLabel(pm).toString().contains("스토리", ignoreCase = true)
        }

        if (storyRi != null) {
            sendIntent.setClassName(storyRi.activityInfo.packageName, storyRi.activityInfo.name)
            activity.startActivity(sendIntent)
        } else if (resolveInfos.isNotEmpty()) {
            showSnsHandlerChooserDialog(activity, SnsTarget.INSTAGRAM, resolveInfos, sendIntent)
        } else {
            activity.startActivity(sendIntent)
        }
    }

    private fun dispatchSnsMultipleIntent(
        activity: Activity,
        target: SnsTarget,
        shareText: String,
        cardUris: ArrayList<Uri>
    ) {
        if (cardUris.isEmpty()) return
        if (cardUris.size == 1) {
            dispatchSnsIntent(activity, target, shareText, cardUris[0])
            return
        }

        val targetPackage = if (target == SnsTarget.X) {
            val pm = activity.packageManager
            try {
                pm.getPackageInfo("com.twitter.android", 0)
                "com.twitter.android"
            } catch (_: Throwable) {
                "com.twitter.android.lite"
            }
        } else {
            target.androidPackage
        }

        try {
            for (uri in cardUris) {
                activity.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage(targetPackage)
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, cardUris)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolveInfos = activity.packageManager.queryIntentActivities(intent, 0)
            if (target == SnsTarget.INSTAGRAM && resolveInfos.isNotEmpty()) {
                val feedRi = resolveInfos.find { ri ->
                    val label = ri.loadLabel(activity.packageManager)?.toString() ?: ""
                    val name = ri.activityInfo.name
                    (label.contains("Feed", ignoreCase = true) || label.contains("피드", ignoreCase = true) ||
                     name.contains("Feed", ignoreCase = true) || name.contains("ShareHandlerActivity", ignoreCase = true) ||
                     name.contains("PostShare", ignoreCase = true)) &&
                    !label.contains("Story", ignoreCase = true) && !label.contains("스토리", ignoreCase = true) && !name.contains("Story", ignoreCase = true) &&
                    !label.contains("Reel", ignoreCase = true) && !label.contains("릴스", ignoreCase = true) && !name.contains("Reel", ignoreCase = true) &&
                    !label.contains("Direct", ignoreCase = true) && !name.contains("Direct", ignoreCase = true)
                } ?: resolveInfos.find { ri ->
                    val name = ri.activityInfo.name
                    !name.contains("Story", ignoreCase = true) && !name.contains("Reel", ignoreCase = true) && !name.contains("Direct", ignoreCase = true)
                } ?: resolveInfos[0]

                intent.setClassName(feedRi.activityInfo.packageName, feedRi.activityInfo.name)
                activity.startActivity(intent)
            } else if (resolveInfos.size > 1) {
                showSnsHandlerChooserDialog(activity, target, resolveInfos, intent)
            } else if (resolveInfos.size == 1) {
                intent.setClassName(resolveInfos[0].activityInfo.packageName, resolveInfos[0].activityInfo.name)
                activity.startActivity(intent)
            } else {
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                dispatchSnsIntent(activity, target, shareText, cardUris[0])
            } catch (_: Throwable) {
                Toast.makeText(activity, "${target.title} 공유 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dispatchSmsIntent(activity: Activity, shareText: String, cardUri: Uri?) {
        try {
            val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(activity)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (cardUri != null) "image/png" else "text/plain"
                if (cardUri != null) {
                    putExtra(Intent.EXTRA_STREAM, cardUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra("sms_body", shareText)
                if (!defaultSmsPkg.isNullOrBlank()) {
                    setPackage(defaultSmsPkg)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (cardUri != null && !defaultSmsPkg.isNullOrBlank()) {
                activity.grantUriPermission(defaultSmsPkg, cardUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                    putExtra("sms_body", shareText)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    if (cardUri != null) {
                        putExtra(Intent.EXTRA_STREAM, cardUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(activity, L10n.t("문자 메시지 앱을 열 수 없습니다.", "Cannot open messaging app."), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSnsHandlerChooserDialog(
        activity: Activity,
        target: SnsTarget,
        resolveInfos: List<android.content.pm.ResolveInfo>,
        intent: Intent
    ) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = ReaderTheme.EINK_BORDER_CONTRAST
        val strokeW = activity.dp(2)
        val mutedCol = ReaderTheme.EINK_MUTED

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(16), activity.dp(18), activity.dp(16))
        }

        val subTv = TextView(activity).apply {
            text = L10n.t(
                "${target.localizedTitle}에서 공유할 위치(스토리, 피드 등)를 선택하세요.",
                "Choose where to share in ${target.title} (Stories, Feed, etc.)."
            )
            setPtSize(8.2f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, activity.dp(12))
        }
        root.addView(subTv)

        val listCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol, strokeW)
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        }

        lateinit var dlg: AlertDialog
        for ((idx, ri) in resolveInfos.withIndex()) {
            val label = ri.loadLabel(activity.packageManager)?.toString() ?: target.localizedTitle
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = activity.dp(6).toFloat()
                }
                setPadding(activity.dp(10), activity.dp(12), activity.dp(10), activity.dp(12))
                setOnClickListener {
                    dlg.dismiss()
                    intent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    activity.startActivity(intent)
                }
            }

            val iv = ImageView(activity).apply {
                val appIcon = try {
                    ri.loadIcon(activity.packageManager)
                } catch (_: Throwable) {
                    getSnsAppIcon(activity, target)
                }
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(iv, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))

            val titleTv = TextView(activity).apply {
                text = label
                setPtSize(9.2f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(inkCol)
                setPadding(activity.dp(12), 0, 0, 0)
            }
            row.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val chevronTv = TextView(activity).apply {
                text = "›"
                setPtSize(13f)
                setTextColor(mutedCol)
            }
            row.addView(chevronTv)

            listCard.addView(row)
            if (idx < resolveInfos.size - 1) {
                listCard.addView(View(activity).apply {
                    setBackgroundColor(borderCol)
                    layoutParams = LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
                        setMargins(activity.dp(8), 0, activity.dp(8), 0)
                    }
                })
            }
        }
        root.addView(listCard)

        dlg = AlertDialog.Builder(activity)
            .setTitle(L10n.t("${target.localizedTitle} 공유 방식 선택", "Select ${target.title} Share Mode"))
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(activity)
    }

    private fun dispatchSnsIntent(
        activity: Activity,
        target: SnsTarget,
        shareText: String,
        cardUri: Uri?
    ) {
        val targetPackage = if (target == SnsTarget.X) {
            val pm = activity.packageManager
            try {
                pm.getPackageInfo("com.twitter.android", 0)
                "com.twitter.android"
            } catch (_: Throwable) {
                "com.twitter.android.lite"
            }
        } else {
            target.androidPackage
        }

        try {
            if (cardUri != null) {
                activity.grantUriPermission(targetPackage, cardUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                setPackage(targetPackage)
                type = if (cardUri != null) "image/png" else "text/plain"
                if (cardUri != null) putExtra(Intent.EXTRA_STREAM, cardUri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolveInfos = activity.packageManager.queryIntentActivities(intent, 0)
            if (target == SnsTarget.INSTAGRAM && resolveInfos.isNotEmpty()) {
                val feedRi = resolveInfos.find { ri ->
                    val label = ri.loadLabel(activity.packageManager)?.toString() ?: ""
                    val name = ri.activityInfo.name
                    (label.contains("Feed", ignoreCase = true) || label.contains("피드", ignoreCase = true) ||
                     name.contains("Feed", ignoreCase = true) || name.contains("ShareHandlerActivity", ignoreCase = true) ||
                     name.contains("PostShare", ignoreCase = true)) &&
                    !label.contains("Story", ignoreCase = true) && !label.contains("스토리", ignoreCase = true) && !name.contains("Story", ignoreCase = true) &&
                    !label.contains("Reel", ignoreCase = true) && !label.contains("릴스", ignoreCase = true) && !name.contains("Reel", ignoreCase = true) &&
                    !label.contains("Direct", ignoreCase = true) && !name.contains("Direct", ignoreCase = true)
                } ?: resolveInfos.find { ri ->
                    val name = ri.activityInfo.name
                    !name.contains("Story", ignoreCase = true) && !name.contains("Reel", ignoreCase = true) && !name.contains("Direct", ignoreCase = true)
                } ?: resolveInfos[0]

                intent.setClassName(feedRi.activityInfo.packageName, feedRi.activityInfo.name)
                activity.startActivity(intent)
            } else if (resolveInfos.size > 1) {
                showSnsHandlerChooserDialog(activity, target, resolveInfos, intent)
            } else if (resolveInfos.size == 1) {
                intent.setClassName(resolveInfos[0].activityInfo.packageName, resolveInfos[0].activityInfo.name)
                activity.startActivity(intent)
            } else {
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "${target.title} 공유 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Resolves the official application icon for an SNS target.
     * If the app is installed, the OS PackageManager icon is directly fetched and returned.
     * If not installed, a high-fidelity authentic brand icon is generated via pure Canvas.
     */
    fun getSnsAppIcon(context: Context, target: SnsTarget): Drawable {
        val targetPkg = if (target == SnsTarget.X) {
            val pm = context.packageManager
            try {
                pm.getPackageInfo("com.twitter.android", 0)
                "com.twitter.android"
            } catch (_: Throwable) {
                "com.twitter.android.lite"
            }
        } else {
            target.androidPackage
        }

        try {
            return context.packageManager.getApplicationIcon(targetPkg)
        } catch (_: Throwable) {
            // App is not installed: render high-fidelity brand icon
        }

        val size = 120
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        when (target) {
            SnsTarget.SAVE_IMAGE -> {
                p.color = 0xFF1E293B.toInt()
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 62f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("💾", 60f, 82f, textPaint)
            }
            SnsTarget.KAKAO -> {
                p.color = 0xFFFEE500.toInt()
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                p.color = 0xFF3C1E1E.toInt()
                c.drawOval(RectF(24f, 28f, 96f, 84f), p)
                val tailPath = Path().apply {
                    moveTo(36f, 74f)
                    lineTo(24f, 94f)
                    lineTo(50f, 82f)
                    close()
                }
                c.drawPath(tailPath, p)

                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFEE500.toInt()
                    textSize = 22f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("TALK", 60f, 64f, textPaint)
            }
            SnsTarget.SMS -> {
                p.color = 0xFF10B981.toInt()
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 62f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("✉️", 60f, 82f, textPaint)
            }
            SnsTarget.INSTAGRAM -> {
                // Signature Instagram gradient
                val shader = android.graphics.LinearGradient(
                    0f, size.toFloat(), size.toFloat(), 0f,
                    intArrayOf(
                        0xFF833AB4.toInt(),
                        0xFFFD1D1D.toInt(),
                        0xFFF56040.toInt(),
                        0xFFFCAF45.toInt()
                    ),
                    floatArrayOf(0f, 0.45f, 0.75f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                p.shader = shader
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)
                p.shader = null

                // White Camera glyph
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = 7f
                c.drawRoundRect(RectF(28f, 28f, 92f, 92f), 18f, 18f, p)
                c.drawCircle(60f, 60f, 16f, p)

                // Flash dot
                p.style = Paint.Style.FILL
                c.drawCircle(79f, 41f, 4.5f, p)
            }
            SnsTarget.THREADS -> {
                // Threads black background
                p.color = 0xFF101010.toInt()
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                // White @ loop glyph
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 76f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("@", 60f, 85f, textPaint)
            }
            SnsTarget.X -> {
                // Solid black background
                p.color = Color.BLACK
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                // White 𝕏 symbol
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 72f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("𝕏", 60f, 86f, textPaint)
            }
            SnsTarget.FACEBOOK -> {
                // Signature Facebook blue
                p.color = 0xFF1877F2.toInt()
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 32f, 32f, p)

                // White 'f'
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 88f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("f", 66f, 96f, textPaint)
            }
        }
        return BitmapDrawable(context.resources, bmp)
    }

    private fun showSnsTargetPicker(
        activity: Activity,
        dialogTitle: String,
        subtitle: String,
        isEink: Boolean = false,
        onBack: (() -> Unit)? = null,
        onTargetSelected: (SnsTarget) -> Unit
    ) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = if (isEink) ReaderTheme.EINK_BORDER_CONTRAST else ReaderTheme.EINK_BORDER
        val strokeW = if (isEink) activity.dp(2) else activity.dp(1)
        val mutedCol = ReaderTheme.EINK_MUTED

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
        }

        val subTv = TextView(activity).apply {
            text = subtitle
            setPtSize(8.2f)
            setTextColor(mutedCol)
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, activity.dp(12))
        }
        root.addView(subTv)

        val listCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol, strokeW)
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        }

        lateinit var dlg: AlertDialog
        val targets = mutableListOf<SnsTarget>()
        // 1. 이미지 저장 (e-ink와 LCD/OLED 기기 모두 최상단 지원)
        targets.add(SnsTarget.SAVE_IMAGE)

        // 2. 카카오톡 (LCD/OLED 전용, e-ink 기기에서는 은닉)
        if (!isEink) {
            targets.add(SnsTarget.KAKAO)
        }

        // 3. 문자 메시지 (기기에 문자 전송 기능이 있는 경우에만 추가, e-ink 기기 미지원)
        if (!isEink && canSendSms(activity)) {
            targets.add(SnsTarget.SMS)
        }

        // 4. 주요 글로벌 SNS (Instagram, Threads, X, Facebook - e-ink 및 LCD/OLED 전 기기 지원)
        targets.add(SnsTarget.INSTAGRAM)
        targets.add(SnsTarget.THREADS)
        targets.add(SnsTarget.X)
        targets.add(SnsTarget.FACEBOOK)

        for ((idx, target) in targets.withIndex()) {
            val isInstalled = target.isInstalled(activity)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = activity.dp(6).toFloat()
                }
                setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10))
                setOnClickListener {
                    dlg.dismiss()
                    onTargetSelected(target)
                }
            }

            // Real App Icon ImageView
            val iv = ImageView(activity).apply {
                setImageDrawable(getSnsAppIcon(activity, target))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(iv, LinearLayout.LayoutParams(activity.dp(42), activity.dp(42)))

            // Title & Status Column
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(12), 0, activity.dp(8), 0)
            }
            val titleTv = TextView(activity).apply {
                text = target.localizedTitle
                setPtSize(9.2f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(inkCol)
            }
            val statusTv = TextView(activity).apply {
                text = when (target) {
                    SnsTarget.SAVE_IMAGE -> L10n.t("갤러리(Pictures/AetherSAGA)에 고해상도 PNG 즉시 저장", "Save high-res PNG to Gallery (Pictures)")
                    SnsTarget.SMS -> L10n.t("기본 문자 앱으로 카드와 문장 첨부", "Attach card & text via default Messages app")
                    else -> if (isInstalled) L10n.t("✓ 앱 설치됨 · 카드 즉시 공유", "✓ Installed · Share Card Now") else L10n.t("앱 미설치 · 터치 시 스토어로 연결", "Not Installed · Tap to open Store")
                }
                setPtSize(7.6f)
                setTextColor(if (isInstalled) 0xFF1F5E9C.toInt() else mutedCol)
            }
            col.addView(titleTv)
            col.addView(statusTv)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // Right Chevron
            val chevronTv = TextView(activity).apply {
                text = "›"
                setPtSize(13f)
                setTextColor(mutedCol)
                gravity = Gravity.CENTER
            }
            row.addView(chevronTv)

            listCard.addView(row)
            if (idx < targets.size - 1) {
                listCard.addView(View(activity).apply {
                    setBackgroundColor(borderCol)
                    layoutParams = LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
                        setMargins(activity.dp(8), 0, activity.dp(8), 0)
                    }
                })
            }
        }
        root.addView(listCard)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        scroll.addView(root)

        dlg = AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(activity, onBack = onBack)
    }

    fun showSnsShareDialog(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        quote: String,
        memo: String? = null,
        isEink: Boolean = false,
        onBack: (() -> Unit)? = null,
        coverBitmap: Bitmap? = null
    ) {
        showSnsShareDialog(
            activity = activity,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            items = listOf(SnsQuoteItem(quote = quote, memo = memo)),
            isEink = isEink,
            onBack = onBack,
            coverBitmap = coverBitmap
        )
    }

    fun showSnsShareDialog(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        items: List<SnsQuoteItem>,
        isEink: Boolean = false,
        onBack: (() -> Unit)? = null,
        coverBitmap: Bitmap? = null
    ) {
        if (items.isEmpty()) return
        val countMsg = if (items.size > 1) {
            L10n.t(" (선택 ${items.size}건)", " (${items.size} items)")
        } else ""

        val subtitle = if (items.size > 1) {
            L10n.t(
                "선택한 문장 ${items.size}건을 공유합니다. 스토리는 최대 4건을 1장으로 묶고, 피드/갤러리는 여러 장 슬라이드로 공유합니다.",
                "Sharing ${items.size} selected quotes. Stories bundles up to 4 items in 1 card; Feed & Gallery share multi-card slides."
            )
        } else {
            L10n.t(
                "원하는 저장 또는 공유 방식을 선택하세요. 본문 인용구와 도서 정보가 담긴 고해상도(1080x1080) 독서 카드가 생성됩니다.",
                "Select a save or share method. A high-resolution (1080x1080) reading card with your quote and book info will be created."
            )
        }

        showSnsTargetPicker(
            activity = activity,
            dialogTitle = L10n.t("📱 독서 카드 저장 및 SNS 공유$countMsg", "📱 Save Card & Share to SNS$countMsg"),
            subtitle = subtitle,
            isEink = isEink,
            onBack = onBack
        ) { target ->
            shareQuotesToSns(activity, target, bookTitle, bookAuthor, items, coverBitmap, isEink)
        }
    }

    fun showStatsSnsShareDialog(
        activity: Activity,
        periodName: String,
        summary: String,
        insight: String,
        completedBooksCount: Int,
        isEink: Boolean = false,
        onBack: (() -> Unit)? = null
    ) {
        showSnsTargetPicker(
            activity = activity,
            dialogTitle = L10n.t("📊 독서 통계 저장 및 SNS 공유", "📊 Save Stats & Share to SNS"),
            subtitle = L10n.t(
                "원하는 저장 또는 공유 방식을 선택하세요. $periodName 독서 활동 통계 인포그래픽 카드가 생성됩니다.",
                "Select a save or share method. An infographic card for $periodName reading activity will be created."
            ),
            isEink = isEink,
            onBack = onBack
        ) { target ->
            shareStatsToSns(activity, target, periodName, summary, insight, completedBooksCount)
        }
    }

    /**
     * Converts cover Bitmap into JPEG bytes for direct MIME embedding inside background emails.
     */
    fun encodeCoverJpegBytes(coverBitmap: Bitmap): ByteArray? = try {
        val bgBmp = Bitmap.createBitmap(
            coverBitmap.width.coerceAtLeast(1),
            coverBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bgBmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(coverBitmap, 0f, 0f, null)
        val bos = ByteArrayOutputStream()
        bgBmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        bgBmp.recycle()
        bos.toByteArray()
    } catch (_: Throwable) {
        null
    }

    data class DirectEmailResult(val success: Boolean, val message: String)

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "<br/>")

    private fun buildHtmlBody(
        resolvedTitle: String,
        resolvedAuthor: String,
        resolvedFormat: String,
        progressPct: Int,
        totalChapters: Int,
        items: List<HighlightEntity>,
        imgSrc: String?
    ): String {
        val sb = StringBuilder()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>")
        sb.append("<body style=\"font-family:sans-serif; color:#111; line-height:1.6; max-width:640px; margin:0 auto; padding:16px;\">")
        sb.append("<div style=\"background:#F6F5F0; border:1px solid #D8D8D2; border-radius:10px; padding:16px; margin-bottom:20px;\">")
        sb.append("<h2 style=\"margin:0 0 12px 0; font-size:18px; color:#1F5E9C;\">📚 도서 정보 (Book Information)</h2>")
        if (imgSrc != null) {
            sb.append("<div style=\"margin-bottom:14px; text-align:center;\">")
            sb.append("<img src=\"").append(imgSrc).append("\" alt=\"Book Cover\" style=\"max-width:200px; height:auto; border-radius:6px; border:1px solid #CCC;\"/>")
            sb.append("</div>")
        }
        sb.append("<p style=\"margin:4px 0;\"><b>• 도서 제목:</b> ").append(escapeHtml(resolvedTitle)).append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 저자 이름:</b> ").append(escapeHtml(resolvedAuthor)).append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 문서 형식:</b> ").append(escapeHtml(resolvedFormat))
        if (totalChapters > 0) sb.append(" (총 ").append(totalChapters).append("개 챕터/섹션)")
        sb.append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 현재 진도:</b> ").append(progressPct).append("%</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 공유 일시:</b> ").append(nowStr).append("</p>")
        sb.append("</div>")

        sb.append("<h3 style=\"font-size:16px; border-bottom:2px solid #222; padding-bottom:6px;\">📝 선택한 문장 및 독서 노트 (총 ")
            .append(items.size).append("건)</h3>")

        var lastCh = Int.MIN_VALUE
        for ((idx, h) in items.withIndex()) {
            if (h.chapter != lastCh) {
                val chTitle = h.chapterTitle.ifBlank { "챕터 ${h.chapter + 1}" }
                sb.append("<h4 style=\"margin:16px 0 6px 0; color:#333;\">■ [").append(escapeHtml(chTitle)).append("]</h4>")
                lastCh = h.chapter
            }
            val kindTag = when {
                h.id == 0L && idx == 0 -> "선택 문장"
                h.kind == 1 -> "북마크"
                else -> "하이라이트"
            }
            val pct = (h.progress * 100).toInt().coerceIn(0, 100)
            sb.append("<div style=\"background:#FAFAFA; border-left:4px solid #222; padding:10px 14px; margin:8px 0; border-radius:4px;\">")
            sb.append("<div style=\"font-size:12px; color:#666; margin-bottom:4px;\">")
                .append(idx + 1).append(". [").append(kindTag).append("] • 위치: ").append(pct).append("%</div>")
            sb.append("<div style=\"font-size:15px; color:#111;\">“").append(escapeHtml(h.quote.trim())).append("”</div>")
            if (h.note.isNotBlank()) {
                sb.append("<div style=\"margin-top:6px; font-size:13px; color:#1F5E9C;\">└ <b>메모:</b> ")
                    .append(escapeHtml(h.note.trim())).append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("<p style=\"margin-top:24px; font-size:12px; color:#888;\">— Aether SAGA E-Book Viewer에서 자동 발송됨</p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun mimeEncodeHeader(text: String): String =
        "=?UTF-8?B?" + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

    private fun buildMimeMessage(
        fromEmail: String,
        toEmail: String,
        subject: String,
        plainText: String,
        htmlText: String,
        coverJpegBytes: ByteArray?,
        safeCoverFileName: String
    ): String {
        val boundaryRel = "====AetherRel_${System.currentTimeMillis()}===="
        val boundaryAlt = "====AetherAlt_${System.currentTimeMillis()}===="
        val sb = StringBuilder()
        sb.append("From: Aether SAGA <").append(fromEmail).append(">\r\n")
        sb.append("To: <").append(toEmail).append(">\r\n")
        sb.append("Subject: ").append(mimeEncodeHeader(subject)).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundaryRel).append("\"\r\n\r\n")

        // Part 1: multipart/alternative (Plain + HTML)
        sb.append("--").append(boundaryRel).append("\r\n")
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundaryAlt).append("\"\r\n\r\n")

        // 1a. Plain text
        sb.append("--").append(boundaryAlt).append("\r\n")
        sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.DEFAULT)).append("\r\n")

        // 1b. HTML text
        sb.append("--").append(boundaryAlt).append("\r\n")
        sb.append("Content-Type: text/html; charset=\"UTF-8\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(Base64.encodeToString(htmlText.toByteArray(Charsets.UTF_8), Base64.DEFAULT)).append("\r\n")
        sb.append("--").append(boundaryAlt).append("--\r\n")

        // Part 2: Inline + Attached Cover Image
        if (coverJpegBytes != null && coverJpegBytes.isNotEmpty()) {
            sb.append("--").append(boundaryRel).append("\r\n")
            sb.append("Content-Type: image/jpeg; name=\"").append(safeCoverFileName).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-ID: <book_cover>\r\n")
            sb.append("Content-Disposition: inline; filename=\"").append(safeCoverFileName).append("\"\r\n\r\n")
            sb.append(Base64.encodeToString(coverJpegBytes, Base64.DEFAULT)).append("\r\n")
        }

        sb.append("--").append(boundaryRel).append("--\r\n")
        return sb.toString()
    }

    private fun resolveSmtpConfig(userEmail: String, customHost: String, customPort: Int): Pair<String, Int> {
        if (customHost.isNotBlank()) return customHost.trim() to (if (customPort > 0) customPort else 465)
        val domain = userEmail.substringAfter('@', "").lowercase(Locale.ROOT).trim()
        return when {
            domain == "naver.com" -> "smtp.naver.com" to 465
            domain == "gmail.com" || domain == "googlemail.com" -> "smtp.gmail.com" to 465
            domain == "daum.net" || domain == "hanmail.net" -> "smtp.daum.net" to 465
            domain == "kakao.com" -> "smtp.kakao.com" to 465
            domain == "nate.com" -> "smtp.mail.nate.com" to 465
            domain.startsWith("outlook.") || domain.startsWith("hotmail.") || domain.startsWith("live.") -> "smtp.office365.com" to 587
            domain == "icloud.com" || domain == "me.com" -> "smtp.mail.me.com" to 587
            domain.startsWith("yahoo.") -> "smtp.mail.yahoo.com" to 465
            else -> "smtp.$domain" to 465
        }
    }

    private fun lookupMxHosts(domain: String): List<String> {
        val hosts = ArrayList<String>()
        // 1. DNS-over-HTTPS MX lookup
        try {
            val url = URL("https://dns.google/resolve?name=${Uri.encode(domain)}&type=MX")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val ans = json.optJSONArray("Answer")
                if (ans != null) {
                    val pairs = ArrayList<Pair<Int, String>>()
                    for (i in 0 until ans.length()) {
                        val obj = ans.getJSONObject(i)
                        val data = obj.optString("data", "").trim()
                        val parts = data.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val prio = parts[0].toIntOrNull() ?: 50
                            val mx = parts[1].trimEnd('.')
                            if (mx.isNotBlank()) pairs += prio to mx
                        }
                    }
                    pairs.sortedBy { it.first }.forEach { hosts += it.second }
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}

        // 2. Known provider MX fallbacks
        when (domain.lowercase(Locale.ROOT)) {
            "naver.com" -> hosts.addAll(listOf("mx1.naver.com", "mx2.naver.com", "mx3.naver.com"))
            "gmail.com", "googlemail.com" -> hosts.addAll(listOf("gmail-smtp-in.l.google.com", "alt1.gmail-smtp-in.l.google.com"))
            "daum.net", "hanmail.net" -> hosts.addAll(listOf("mx1.daum.net", "mx2.daum.net"))
            "kakao.com" -> hosts.addAll(listOf("mx1.kakao.com", "mx2.kakao.com"))
            "nate.com" -> hosts.addAll(listOf("mx1.nate.com"))
        }
        return hosts.distinct()
    }

    private fun readSmtpResponse(reader: BufferedReader): Pair<Int, String> {
        val sb = StringBuilder()
        var code = -1
        while (true) {
            val line = reader.readLine() ?: break
            sb.append(line).append("\n")
            if (line.length >= 3) {
                code = line.substring(0, 3).toIntOrNull() ?: -1
                if (line.length == 3 || line[3] == ' ') break
            } else break
        }
        return code to sb.toString().trim()
    }

    private fun sendCmd(writer: PrintWriter, reader: BufferedReader, cmd: String?): Pair<Int, String> {
        if (cmd != null) {
            writer.print("$cmd\r\n")
            writer.flush()
        }
        return readSmtpResponse(reader)
    }

    private fun transmitSmtpSession(
        host: String,
        port: Int,
        useImplicitTls: Boolean,
        authUser: String?,
        authPass: String?,
        fromEmail: String,
        toEmail: String,
        mimeMessage: String
    ): Pair<Boolean, String> {
        var rawSocket: Socket? = null
        try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val baseSocket = Socket()
            baseSocket.connect(InetSocketAddress(host, port), 8000)
            baseSocket.soTimeout = 12000

            val socket: Socket = if (useImplicitTls) {
                val sslSock = sslFactory.createSocket(baseSocket, host, port, true) as SSLSocket
                sslSock.startHandshake()
                sslSock
            } else {
                baseSocket
            }
            rawSocket = socket

            var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false)

            val (greetCode, greetMsg) = readSmtpResponse(reader)
            if (greetCode !in 200..299) return false to "서버 연결 응답 오류 ($greetCode: $greetMsg)"

            val heloHost = if (!authUser.isNullOrBlank() && authUser.contains("@")) {
                authUser.substringAfter('@').trim().ifBlank { "gmail.com" }
            } else {
                host.substringAfter("smtp.", "gmail.com")
            }

            var (ehloCode, ehloMsg) = sendCmd(writer, reader, "EHLO $heloHost")
            if (ehloCode !in 200..299) {
                val (heloCode, heloMsg) = sendCmd(writer, reader, "HELO $heloHost")
                if (heloCode !in 200..299) return false to "HELO 오류 ($heloMsg)"
                ehloMsg = heloMsg
            }

            // Upgrade to STARTTLS if not using implicit SSL and server supports STARTTLS (or port 587)
            if (!useImplicitTls && (ehloMsg.contains("STARTTLS", ignoreCase = true) || port == 587)) {
                val (tlsCode, tlsMsg) = sendCmd(writer, reader, "STARTTLS")
                if (tlsCode in 200..299) {
                    val sslSock = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                    sslSock.startHandshake()
                    rawSocket = sslSock
                    reader = BufferedReader(InputStreamReader(sslSock.getInputStream(), Charsets.UTF_8))
                    writer = PrintWriter(OutputStreamWriter(sslSock.getOutputStream(), Charsets.UTF_8), false)
                    val (ehlo2Code, ehlo2Msg) = sendCmd(writer, reader, "EHLO $heloHost")
                    if (ehlo2Code in 200..299) {
                        ehloMsg = ehlo2Msg
                    }
                } else if (port == 587) {
                    return false to "STARTTLS 보안 연결 거부 ($tlsCode: $tlsMsg)"
                }
            }

            // Authenticate if credentials are provided
            if (!authUser.isNullOrBlank() && !authPass.isNullOrBlank()) {
                val cleanUser = authUser.trim()
                // Strip spaces if user copied Google App Password which has spaces: 'xxxx xxxx xxxx xxxx'
                val cleanPass = if (cleanUser.endsWith("@gmail.com", ignoreCase = true) || cleanUser.endsWith("@googlemail.com", ignoreCase = true)) {
                    authPass.replace(" ", "").trim()
                } else {
                    authPass.trim()
                }

                var authSuccess = false
                var authFailReason = ""

                val candidates = ArrayList<String>()
                candidates.add(cleanUser)
                if (cleanUser.contains("@")) {
                    candidates.add(cleanUser.substringBefore('@'))
                } else {
                    val fallbackDomain = host.substringAfter("smtp.", "").trim()
                    if (fallbackDomain.isNotBlank()) candidates.add("$cleanUser@$fallbackDomain")
                }

                for (userAttempt in candidates) {
                    val (authCode, _) = sendCmd(writer, reader, "AUTH LOGIN")
                    if (authCode == 334) {
                        val u64 = Base64.encodeToString(userAttempt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val (uCode, uMsg) = sendCmd(writer, reader, u64)
                        if (uCode == 334) {
                            val p64 = Base64.encodeToString(cleanPass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            val (pCode, pMsg) = sendCmd(writer, reader, p64)
                            if (pCode in 200..299) {
                                authSuccess = true
                                break
                            } else {
                                authFailReason = "비밀번호 인증 실패 (응답 $pCode: $pMsg)"
                            }
                        } else {
                            authFailReason = "아이디 인증 실패 (응답 $uCode: $uMsg)"
                        }
                    } else {
                        val plainToken = "\u0000$userAttempt\u0000$cleanPass"
                        val plain64 = Base64.encodeToString(plainToken.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val (pCode, pMsg) = sendCmd(writer, reader, "AUTH PLAIN $plain64")
                        if (pCode in 200..299) {
                            authSuccess = true
                            break
                        } else {
                            authFailReason = "인증 실패 (응답 $pCode: $pMsg)"
                        }
                    }
                }

                if (!authSuccess) {
                    val isGmail = host.contains("gmail", ignoreCase = true) || cleanUser.contains("gmail", ignoreCase = true)
                    val isNaver = host.contains("naver", ignoreCase = true) || cleanUser.contains("naver", ignoreCase = true)
                    val guide = when {
                        isGmail -> "\n\n💡 [Gmail 발송 해결 방법]\nGmail은 일반 계정 비밀번호 사용이 차단되어 있습니다. Google 계정 관리 → 보안 → 2단계 인증 → [앱 비밀번호(16자리)]를 생성하여 비밀번호 칸에 입력하셔야 합니다."
                        isNaver -> "\n\n💡 [네이버 메일 해결 방법]\n네이버 웹메일 환경설정 → POP3/IMAP 설정에서 [POP3/SMTP 사용] 또는 [IMAP/SMTP 사용]을 '사용함'으로 설정해야 발송할 수 있습니다."
                        else -> ""
                    }
                    return false to "SMTP 계정 인증 실패: $authFailReason$guide"
                }
            }

            val (mailCode, mailMsg) = sendCmd(writer, reader, "MAIL FROM:<$fromEmail>")
            if (mailCode !in 200..299) return false to "발신자 주소 거부 ($mailCode: $mailMsg)"

            val (rcptCode, rcptMsg) = sendCmd(writer, reader, "RCPT TO:<$toEmail>")
            if (rcptCode !in 200..299) return false to "수신자 주소 거부 ($rcptCode: $rcptMsg)"

            val (dataCode, dataMsg) = sendCmd(writer, reader, "DATA")
            if (dataCode != 354) return false to "DATA 전송 거부 ($dataCode: $dataMsg)"

            // Dot-stuffing per RFC 5321
            val safeMime = mimeMessage.replace("\r\n.", "\r\n..")
            writer.print(safeMime)
            if (!safeMime.endsWith("\r\n")) writer.print("\r\n")
            val (endCode, endMsg) = sendCmd(writer, reader, ".")
            sendCmd(writer, reader, "QUIT")

            return if (endCode in 200..299) {
                true to "이메일이 성공적으로 발송되었습니다.\n• 수신자: $toEmail\n• 발신 서버: $host:$port"
            } else {
                false to "메일 서버 발송 거부 ($endCode: $endMsg)"
            }
        } catch (e: Throwable) {
            return false to (e.message ?: "네트워크 연결 실패")
        } finally {
            try { rawSocket?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Automatically sends the email (Book Metadata + Book Cover Image + Selected Quotes)
     * directly in the background without opening any Android Share Chooser or external app.
     */
    suspend fun sendEmailDirectly(
        prefs: Prefs,
        sync: ExportSync,
        toEmail: String,
        subject: String,
        plainText: String,
        bookTitle: String,
        bookAuthor: String,
        bookFormat: String,
        progressPct: Int,
        totalChapters: Int,
        items: List<HighlightEntity>,
        coverBitmap: Bitmap?
    ): DirectEmailResult = withContext(Dispatchers.IO) {
        val cleanTo = toEmail.trim()
        if (cleanTo.isBlank() || !cleanTo.contains("@")) {
            return@withContext DirectEmailResult(false, "올바른 이메일 주소를 입력해주세요.")
        }

        val coverBytes = if (coverBitmap != null) encodeCoverJpegBytes(coverBitmap) else null
        val safeTitle = bookTitle.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(36).ifBlank { "book" }
        val coverFileName = "${safeTitle}_cover.jpg"

        val htmlCid = buildHtmlBody(
            resolvedTitle = bookTitle,
            resolvedAuthor = bookAuthor,
            resolvedFormat = bookFormat,
            progressPct = progressPct,
            totalChapters = totalChapters,
            items = items,
            imgSrc = if (coverBytes != null) "cid:book_cover" else null
        )

        // 1. If SMTP sender account is configured, send via authenticated SMTPS / STARTTLS
        val smtpUser = prefs.smtpUser.trim()
        val smtpPass = prefs.smtpPass.trim()
        var lastError = ""

        if (smtpUser.isNotBlank() && smtpPass.isNotBlank()) {
            val (host, port) = resolveSmtpConfig(smtpUser, prefs.smtpHost, prefs.smtpPort)
            val fromAddr = if (smtpUser.contains("@")) smtpUser else "$smtpUser@${host.substringAfter("smtp.")}"
            val mime = buildMimeMessage(fromAddr, cleanTo, subject, plainText, htmlCid, coverBytes, coverFileName)
            val (ok, msg) = transmitSmtpSession(
                host = host,
                port = port,
                useImplicitTls = (port == 465),
                authUser = smtpUser,
                authPass = smtpPass,
                fromEmail = fromAddr,
                toEmail = cleanTo,
                mimeMessage = mime
            )
            if (ok) return@withContext DirectEmailResult(true, msg)
            // Try port 587 STARTTLS fallback if 465 failed
            if (port == 465) {
                val (ok2, msg2) = transmitSmtpSession(
                    host = host,
                    port = 587,
                    useImplicitTls = false,
                    authUser = smtpUser,
                    authPass = smtpPass,
                    fromEmail = fromAddr,
                    toEmail = cleanTo,
                    mimeMessage = mime
                )
                if (ok2) return@withContext DirectEmailResult(true, msg2)
                lastError = msg2
            } else {
                lastError = msg
            }

            // 사용자가 SMTP 계정을 설정한 경우, 인증 없는 수신 MX 서버(포트 25)로 폴백하지 않고 명확한 원인을 반환합니다.
            return@withContext DirectEmailResult(false, lastError)
        }

        // 2. Attempt Direct-to-MX delivery to recipient's mail server
        val domain = cleanTo.substringAfter('@', "").trim()
        if (domain.isNotBlank()) {
            val mxHosts = lookupMxHosts(domain)
            val fromAddr = if (smtpUser.contains("@")) smtpUser else "noreply@$domain"
            val mime = buildMimeMessage(fromAddr, cleanTo, subject, plainText, htmlCid, coverBytes, coverFileName)
            for (mx in mxHosts.take(2)) {
                val (ok, msg) = transmitSmtpSession(
                    host = mx,
                    port = 25,
                    useImplicitTls = false,
                    authUser = null,
                    authPass = null,
                    fromEmail = fromAddr,
                    toEmail = cleanTo,
                    mimeMessage = mime
                )
                if (ok) return@withContext DirectEmailResult(true, msg)
                lastError = msg
            }
        }

        // 3. Also archive the complete self-contained HTML email (with embedded Base64 cover image) locally & to WebDAV
        val dataUriImg = if (coverBytes != null) {
            "data:image/jpeg;base64," + Base64.encodeToString(coverBytes, Base64.NO_WRAP)
        } else null
        val standaloneHtml = buildHtmlBody(
            resolvedTitle = bookTitle,
            resolvedAuthor = bookAuthor,
            resolvedFormat = bookFormat,
            progressPct = progressPct,
            totalChapters = totalChapters,
            items = items,
            imgSrc = dataUriImg
        )
        try {
            saveToDocuments("${safeTitle}_${System.currentTimeMillis()}.html", standaloneHtml)
            sync.enqueue("email_outbox/${safeTitle}_${System.currentTimeMillis()}.html", standaloneHtml, "text/html")
        } catch (_: Throwable) {}

        val hint = if (smtpUser.isBlank() || smtpPass.isBlank()) {
            "수신 메일 서버($domain)가 발신 인증을 요구합니다. 창 하단의 [발신 메일(SMTP) 계정 설정]에 이메일과 앱 비밀번호를 1회 입력하시면 즉시 자동 발송됩니다."
        } else {
            "발송 실패: $lastError"
        }
        return@withContext DirectEmailResult(false, hint)
    }

    fun saveToDocuments(name: String, text: String): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AetherSAGA")
        dir.mkdirs()
        return File(dir, name).also { it.writeText(text) }
    }
}
