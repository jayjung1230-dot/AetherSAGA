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
import android.graphics.PointF
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
        val src = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
        if (src == null) null else {
            val w = 240
            val h = (src.height * (w / src.width.toFloat())).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            Canvas(out).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
            val f = File(dir, md5(bookPath) + ".webp")
            FileOutputStream(f).use {
                @Suppress("DEPRECATION")
                out.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 70, it)
            }
            if (!src.isRecycled) src.recycle()
            if (!out.isRecycled) out.recycle()
            f.path
        }
    } catch (_: Throwable) { null }
}

/** Modularized WebDAV client & offline sync manager in `features/sync/WebDavSyncManager.kt`. */
typealias WebDav = com.aether.reader.features.sync.WebDavClient
typealias ExportSync = com.aether.reader.features.sync.WebDavSyncManager

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

    data class StatsChartPoint(
        val label: String,
        val value: Int,
        val displayValue: String = ""
    )

    data class ReadingBookShareItem(
        val title: String,
        val author: String,
        val format: String,
        val progress: Float,
        val subInfo: String = "",
        val coverPath: String? = null
    )

    data class StatsSharePayload(
        val periodName: String,
        val periodSubLabel: String,
        val totalLabel: String,
        val totalValue: String,
        val streakText: String,
        val dailyAvgText: String,
        val metricUnit: String,
        val points: List<StatsChartPoint>,
        val yMax: Int,
        val insightText: String,
        val completedBooksSummary: List<String> = emptyList(),
        val highlightQuote: String? = null,
        val currentlyReadingBooks: List<ReadingBookShareItem> = emptyList()
    )

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

    private fun renderTypographicCover(rawTitle: String, rawAuthor: String, rawFormat: String): Bitmap =
        com.aether.reader.features.share.QuoteCardRenderer.renderTypographicCover(rawTitle, rawAuthor, rawFormat)

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
     * [Option B] Directly opens the device's default email composer
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
        highlights: List<HighlightEntity>,
        includeBookInfo: Boolean = true
    ): File? = com.aether.reader.features.share.HighlightsZipExporter.createHighlightsZip(
        ctx, bookTitle, bookAuthor, highlights, includeBookInfo
    )

    /**
     * Zips the highlights and directly opens the email app with the ZIP attachment,
     * including polite, courteous greetings, rich book details, and highlight previews in the email body.
     */
    fun sendHighlightsZipEmail(
        ctx: Context,
        toEmail: String,
        bookTitle: String,
        bookAuthor: String,
        highlights: List<HighlightEntity>,
        includeBookInfo: Boolean = true
    ): Boolean = com.aether.reader.features.share.HighlightsZipExporter.sendHighlightsZipEmail(
        ctx, toEmail, bookTitle, bookAuthor, highlights, includeBookInfo
    )

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
    ): Bitmap = com.aether.reader.features.share.QuoteCardRenderer.renderQuoteCard(
        bookTitle, bookAuthor, quote, memo, coverBitmap
    )

    /**
     * Renders a vertical 9:16 (1080x1920) Story Card bundling up to 4 quotes/bookmarks/notes
     * for Instagram/Facebook Stories.
     */
    fun renderStoryBundleCard(
        bookTitle: String,
        bookAuthor: String,
        items: List<SnsQuoteItem>,
        coverBitmap: Bitmap? = null
    ): Bitmap = com.aether.reader.features.share.QuoteCardRenderer.renderStoryBundleCard(
        bookTitle, bookAuthor, items, coverBitmap
    )

    /**
     * Renders a 1:1 square (1080x1080) Reading Stats Card including the active chart/graph and currently reading books.
     */
    fun renderStatsCard(payload: StatsSharePayload): Bitmap =
        com.aether.reader.features.stats.StatsCardRenderer.renderStatsCard(payload)

    /**
     * Renders a 9:16 vertical (1080x1920) Reading Stats Infographic Card for Stories.
     */
    fun renderStatsStoryCard(payload: StatsSharePayload): Bitmap =
        com.aether.reader.features.stats.StatsCardRenderer.renderStatsStoryCard(payload)

    /**
     * Renders a stylish Reading Stats Card bitmap (1080x1080) for SNS feed sharing.
     */
    fun renderStatsCard(
        periodName: String,
        summary: String,
        insight: String,
        completedBooksCount: Int
    ): Bitmap = com.aether.reader.features.stats.StatsCardRenderer.renderStatsCard(
        StatsSharePayload(
            periodName = periodName,
            periodSubLabel = L10n.t("독서 활동 종합 분석", "Reading Activity Analysis"),
            totalLabel = L10n.t("총 독서량", "Total Reading"),
            totalValue = summary,
            streakText = L10n.t("연속 독서 🔥", "Active Streak 🔥"),
            dailyAvgText = "",
            metricUnit = "분",
            points = emptyList(),
            yMax = 10,
            insightText = insight,
            completedBooksSummary = List(completedBooksCount.coerceAtLeast(0)) { "완독 도서 #${it + 1}" }
        )
    )

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
                setColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
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
                    val storyItems = ArrayList<SnsQuoteItem>()
                    var accumulatedChars = 0
                    val maxStoryChars = 600
                    for (item in items) {
                        val len = item.quote.trim().length
                        if (storyItems.isEmpty() || accumulatedChars + len <= maxStoryChars) {
                            storyItems.add(item)
                            accumulatedChars += len
                        } else {
                            break
                        }
                    }
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


    private fun showInstagramStatsModeChooser(
        activity: Activity,
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
                "인스타그램에서 공유할 독서 통계 방식을 선택하세요.",
                "Choose how to share reading stats on Instagram."
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

        // 1. Stories option (9:16 full infographic)
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
            text = L10n.t("✨ 스토리 (Story) - 9:16 풀 인포그래픽", "✨ Stories (9:16 Full Infographic)")
            setPtSize(9.2f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
        }
        val storySubTv = TextView(activity).apply {
            text = L10n.t("전체 화면을 꽉 채우는 세로형 독서 통계 리포트 업로드", "Upload 9:16 full-screen reading stats infographic to Stories")
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

        // 2. Feed option (1:1 square chart report)
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
            text = L10n.t("📸 피드 (Feed) - 1:1 정사각형 리포트", "📸 Feed (1:1 Square Report)")
            setPtSize(9.2f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
        }
        val feedSubTv = TextView(activity).apply {
            text = L10n.t("1080x1080 고해상도 차트 리포트로 피드에 즉시 게시", "Post 1080x1080 square chart report directly to Feed")
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
            .setTitle(L10n.t("Instagram 통계 공유 방식 선택", "Select Instagram Stats Share Mode"))
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(activity)
    }

    fun showStatsSnsShareDialog(
        activity: Activity,
        payload: StatsSharePayload,
        isEink: Boolean = false,
        onBack: (() -> Unit)? = null
    ) {
        showSnsTargetPicker(
            activity = activity,
            dialogTitle = L10n.t("📊 독서 통계 저장 및 SNS 공유", "📊 Save Stats & Share to SNS"),
            subtitle = L10n.t(
                "원하는 저장 또는 공유 방식을 선택하세요. ${payload.periodName} 인포그래픽 리포트 카드가 생성됩니다.",
                "Select a save or share method. An infographic card for ${payload.periodName} will be created."
            ),
            isEink = isEink,
            onBack = onBack
        ) { target ->
            shareStatsToSns(activity, target, payload, isEink)
        }
    }

    fun shareStatsToSns(
        activity: Activity,
        target: SnsTarget,
        payload: StatsSharePayload,
        isEink: Boolean = false
    ) {
        val readingLine = payload.currentlyReadingBooks.firstOrNull()?.let { rb ->
            val pct = (rb.progress.coerceIn(0f, 1f) * 100).toInt()
            val extra = if (payload.currentlyReadingBooks.size > 1) {
                L10n.t(" 외 ${payload.currentlyReadingBooks.size - 1}권", " +${payload.currentlyReadingBooks.size - 1} more")
            } else ""
            "\n📖 " + L10n.t("읽고 있는 책: ", "Currently Reading: ") + "《${rb.title}》 (${pct}%)$extra"
        } ?: ""

        if (target == SnsTarget.SAVE_IMAGE) {
            val cardBmp = renderStatsCard(payload)
            val ok = saveCardToGallery(activity, cardBmp, "AetherSAGA_Stats")
            if (ok) {
                Toast.makeText(activity, L10n.t("독서 통계 리포트가 갤러리(Pictures/AetherSAGA)에 저장되었습니다. 💾", "Reading stats report saved to Gallery (Pictures/AetherSAGA). 💾"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, L10n.t("이미지 저장에 실패했습니다.", "Failed to save image."), Toast.LENGTH_SHORT).show()
            }
            cardBmp.recycle()
            return
        }

        if (target == SnsTarget.SMS) {
            val cardBmp = renderStatsCard(payload)
            val cardUri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Stats_Card.png")
            val shareText = "📊 [AetherSAGA] ${payload.periodName}\n${payload.totalLabel}: ${payload.totalValue} · ${payload.streakText}$readingLine\n\n- Aether SAGA"
            dispatchSmsIntent(activity, shareText, cardUri)
            cardBmp.recycle()
            return
        }

        if (!target.isInstalled(activity)) {
            showSnsAppNotInstalledDialog(activity, target)
            return
        }

        if (target == SnsTarget.INSTAGRAM) {
            showInstagramStatsModeChooser(
                activity = activity,
                isEink = isEink,
                onStoryChosen = {
                    val storyBmp = renderStatsStoryCard(payload)
                    val storyUri = prepareSnsCardUri(activity, storyBmp, "AetherSAGA_Stats_Story.png")
                    if (storyUri != null) {
                        dispatchInstagramStoryIntent(activity, storyUri)
                    }
                    storyBmp.recycle()
                },
                onFeedChosen = {
                    val feedBmp = renderStatsCard(payload)
                    val feedUri = prepareSnsCardUri(activity, feedBmp, "AetherSAGA_Stats_Feed.png")
                    val shareText = "📊 [AetherSAGA] ${payload.periodName}\n${payload.totalLabel}: ${payload.totalValue} · ${payload.streakText}$readingLine\n\n#AetherSAGA #독서기록 #독서통계"
                    dispatchSnsIntent(activity, SnsTarget.INSTAGRAM, shareText, feedUri)
                    feedBmp.recycle()
                }
            )
            return
        }

        val cardBmp = renderStatsCard(payload)
        val cardUri = prepareSnsCardUri(activity, cardBmp, "AetherSAGA_Stats_Card.png")
        val shareText = "📊 [AetherSAGA] ${payload.periodName}\n${payload.totalLabel}: ${payload.totalValue} · ${payload.streakText}$readingLine\n\n#AetherSAGA #독서기록 #독서통계"
        dispatchSnsIntent(activity, target, shareText, cardUri)
        cardBmp.recycle()
    }

    fun shareStatsToSns(
        activity: Activity,
        target: SnsTarget,
        periodName: String,
        summary: String,
        insight: String,
        completedBooksCount: Int
    ) {
        val payload = StatsSharePayload(
            periodName = periodName,
            periodSubLabel = L10n.t("독서 활동 분석", "Reading Analysis"),
            totalLabel = L10n.t("총 독서량", "Total Reading"),
            totalValue = summary,
            streakText = L10n.t("연속 독서 중 🔥", "Active Streak 🔥"),
            dailyAvgText = "",
            metricUnit = "분",
            points = emptyList(),
            yMax = 10,
            insightText = insight,
            completedBooksSummary = emptyList()
        )
        shareStatsToSns(activity, target, payload)
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
                L10n.t("선택 문장 유동 조합(최대 600자)을 9:16 세로형 1장 카드로 제작 (전체 내용 수용)", "Bundle selected quotes (up to 600 chars) into 1 vertical story card (Full content)")
            } else {
                L10n.t("9:16 세로형 감성 독서 카드로 스토리에 업로드 (전체 내용 수용)", "Upload as 9:16 vertical card to Stories (Full content)")
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

    fun dispatchCustomCardSns(
        activity: Activity,
        target: SnsTarget,
        cardBitmap: Bitmap,
        shareText: String,
        isStory: Boolean = false,
        filePrefix: String = "AetherSAGA_Card"
    ) {
        if (target == SnsTarget.SAVE_IMAGE) {
            val ok = saveCardToGallery(activity, cardBitmap, filePrefix)
            if (ok) {
                Toast.makeText(activity, L10n.t("카드가 갤러리(Pictures/AetherSAGA)에 저장되었습니다. 💾", "Card saved to Gallery (Pictures/AetherSAGA). 💾"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, L10n.t("이미지 저장에 실패했습니다.", "Failed to save image."), Toast.LENGTH_SHORT).show()
            }
            cardBitmap.recycle()
            return
        }

        if (target == SnsTarget.SMS) {
            val cardUri = prepareSnsCardUri(activity, cardBitmap, "${filePrefix}.png")
            dispatchSmsIntent(activity, shareText, cardUri)
            cardBitmap.recycle()
            return
        }

        if (!target.isInstalled(activity)) {
            showSnsAppNotInstalledDialog(activity, target)
            cardBitmap.recycle()
            return
        }

        if (target == SnsTarget.INSTAGRAM && isStory) {
            val cardUri = prepareSnsCardUri(activity, cardBitmap, "${filePrefix}_Story.png")
            if (cardUri != null) {
                dispatchInstagramStoryIntent(activity, cardUri)
            }
            cardBitmap.recycle()
            return
        }

        val cardUri = prepareSnsCardUri(activity, cardBitmap, "${filePrefix}.png")
        if (cardUri != null) {
            dispatchSnsIntent(activity, target, shareText, cardUri)
        }
        cardBitmap.recycle()
    }

    fun dispatchSnsTarget(
        activity: Activity,
        target: SnsTarget,
        cardBitmap: Bitmap,
        textToShare: String,
        isStory: Boolean = false
    ) {
        dispatchCustomCardSns(activity, target, cardBitmap, textToShare, isStory)
    }

    fun showSnsTargetPicker(
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

        // 2. 카카오톡 (전 기기 및 시뮬레이터 상시 표시)
        targets.add(SnsTarget.KAKAO)

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
        val payload = StatsSharePayload(
            periodName = periodName,
            periodSubLabel = L10n.t("독서 활동 종합 분석", "Reading Activity Analysis"),
            totalLabel = L10n.t("총 독서량", "Total Reading"),
            totalValue = summary,
            streakText = L10n.t("연속 독서 유지 중 🔥", "Active Streak 🔥"),
            dailyAvgText = "",
            metricUnit = "분",
            points = emptyList(),
            yMax = 10,
            insightText = insight,
            completedBooksSummary = emptyList()
        )
        showStatsSnsShareDialog(activity, payload, isEink, onBack)
    }

    /**
     * Converts cover Bitmap into JPEG bytes for direct MIME embedding inside background emails.
     */
    fun encodeCoverJpegBytes(coverBitmap: Bitmap): ByteArray? =
        com.aether.reader.features.share.DirectEmailSender.encodeCoverJpegBytes(coverBitmap)

    data class DirectEmailResult(val success: Boolean, val message: String)

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
    ): DirectEmailResult = com.aether.reader.features.share.DirectEmailSender.sendEmailDirectly(
        prefs, sync, toEmail, subject, plainText, bookTitle, bookAuthor, bookFormat, progressPct, totalChapters, items, coverBitmap
    )

    fun saveToDocuments(name: String, text: String): File =
        com.aether.reader.features.share.DirectEmailSender.saveToDocuments(name, text)
}
