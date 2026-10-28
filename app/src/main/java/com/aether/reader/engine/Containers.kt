package com.aether.reader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import android.text.SpannableStringBuilder
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Chapter-at-a-time book source. Implementations must never hold the whole book in memory. */
interface BookContainer : Closeable {
    val title: String
    val author: String
    val translator: String get() = ""
    val publisher: String get() = ""
    val publishYear: String get() = ""
    val chapters: List<ChapterInfo>
    val isImageBased: Boolean get() = false
    val documentStyle: DocumentStyle get() = DocumentStyle()
    fun chapterText(index: Int): String
    fun chapterSpanned(index: Int): CharSequence = chapterText(index)
    fun renderPageBitmap(index: Int, targetBmp: Bitmap, bgColor: Int): Boolean = false
    fun footnotesForChapter(index: Int): Map<String, String> = emptyMap()
    fun coverBytes(): ByteArray? = null
    fun getPageBounds(index: Int, targetW: Int, targetH: Int): RectF? = null
}

object Containers {

    internal fun safeFromHtml(html: String): CharSequence {
        val sp = try { Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) } catch (_: Throwable) { null }
        if (sp != null) return sp
        return html.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|blockquote)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    /** Normalizes file:// URIs into clean local filesystem paths. */
    fun normalizePath(path: String): String {
        if (!path.startsWith("file://", ignoreCase = true)) return path
        return try {
            val u = Uri.parse(path)
            u.path ?: path.removePrefix("file://")
        } catch (_: Exception) {
            path.removePrefix("file://")
        }
    }

    /** Resolves format from path, ContentResolver display name/MIME, and file header magic bytes. */
    fun formatOf(path: String, context: Context? = null): BookFormat? {
        val cleanPath = normalizePath(path)
        var displayName = if (cleanPath.startsWith("content://")) {
            val uri = Uri.parse(cleanPath)
            var queriedName: String? = null
            if (context != null) {
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) queriedName = c.getString(0)
                    }
                } catch (_: Exception) {}
            }
            queriedName ?: try { URLDecoder.decode(uri.lastPathSegment ?: cleanPath, "UTF-8") } catch (_: Exception) { cleanPath }
        } else cleanPath

        val ext = displayName.substringAfterLast('.', "").lowercase()
        when (ext) {
            "epub" -> return BookFormat.EPUB
            "pdf" -> return BookFormat.PDF
            "cbz" -> return BookFormat.CBZ
            "mobi", "azw", "azw3", "prc" -> return BookFormat.MOBI
            "md", "markdown" -> return BookFormat.MD
            "txt", "fb2", "log", "html", "htm" -> return BookFormat.TXT
        }

        // Check MIME type from ContentResolver if extension was absent in SAF URI
        if (cleanPath.startsWith("content://") && context != null) {
            val mime = try { context.contentResolver.getType(Uri.parse(cleanPath))?.lowercase() } catch (_: Exception) { null }
            if (mime != null) {
                when {
                    mime.contains("epub") -> return BookFormat.EPUB
                    mime.contains("pdf") -> return BookFormat.PDF
                    mime.contains("cbz") || mime.contains("comicbook") -> return BookFormat.CBZ
                    mime.contains("mobi") || mime.contains("amazon.ebook") -> return BookFormat.MOBI
                    mime.contains("markdown") -> return BookFormat.MD
                    mime.startsWith("text/") -> return BookFormat.TXT
                }
            }
        }

        // Inspect local file magic bytes if path is a direct file
        if (!cleanPath.startsWith("content://")) {
            val f = File(cleanPath)
            if (f.exists() && f.isFile) return sniffFileFormat(f)
        }

        return when {
            displayName.contains(".epub", ignoreCase = true) -> BookFormat.EPUB
            displayName.contains(".pdf", ignoreCase = true) -> BookFormat.PDF
            displayName.contains(".cbz", ignoreCase = true) -> BookFormat.CBZ
            displayName.contains(".mobi", ignoreCase = true) || displayName.contains(".azw", ignoreCase = true) -> BookFormat.MOBI
            displayName.contains(".md", ignoreCase = true) -> BookFormat.MD
            cleanPath.startsWith("content://") -> BookFormat.TXT
            else -> null
        }
    }

    /** Inspects binary magic signature (%PDF, PK zip contents, PalmDOC BOOKMOBI) to guarantee stabilityeven on misnamed files. */
    fun sniffFileFormat(file: File): BookFormat {
        try {
            if (!file.exists() || file.length() < 4L) return BookFormat.TXT
            val head = ByteArray(minOf(file.length(), 128L).toInt())
            RandomAccessFile(file, "r").use { it.readFully(head) }

            // 1. PDF signature: %PDF (0x25 0x50 0x44 0x46)
            if (head.size >= 4 && head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x44.toByte() && head[3] == 0x46.toByte()) {
                return BookFormat.PDF
            }

            // 2. ZIP signature: PK\x03\x04 (0x50 0x4B 0x03 0x04) -> distinguish EPUB vs CBZ vs FB2.ZIP
            if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) {
                ZipFile(file).use { z ->
                    if (z.getEntry("META-INF/container.xml") != null) return BookFormat.EPUB
                    var hasOpfOrHtml = false
                    var hasImages = false
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name.lowercase()
                        if (name.endsWith(".opf") || name.endsWith(".xhtml") || name.endsWith(".ncx")) hasOpfOrHtml = true
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) hasImages = true
                    }
                    return when {
                        hasOpfOrHtml -> BookFormat.EPUB
                        hasImages -> BookFormat.CBZ
                        else -> BookFormat.EPUB
                    }
                }
            }

            // 3. PalmDOC / MOBI / AZW3 signature at offset 60: "BOOKMOBI" or "TEXtREAd"
            if (head.size >= 68) {
                val sig = String(head, 60, 8, Charsets.US_ASCII)
                if (sig == "BOOKMOBI" || sig == "TEXtREAd") return BookFormat.MOBI
            }
        } catch (_: Throwable) {}

        val ext = file.extension.lowercase()
        return when (ext) {
            "md", "markdown" -> BookFormat.MD
            else -> BookFormat.TXT
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun resolveContentToFile(
        cleanPath: String,
        context: Context,
        fmt: BookFormat,
        resolvedDisplayName: String = ""
    ): String {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val safeKey = md5(cleanPath)
        val ext = fmt.name.lowercase(Locale.ROOT)

        // 1. Check permanent internal storage first (guaranteed persistent across sessions)
        val targetPermanent = File(booksDir, "book_${safeKey}.${ext}")
        if (targetPermanent.exists() && targetPermanent.length() > 0L) {
            return targetPermanent.absolutePath
        }

        // 2. Check cache files from previous sessions or legacy import
        val legacyHashFile = File(cacheDir, "book_${cleanPath.hashCode()}")
        if (legacyHashFile.exists() && legacyHashFile.length() > 0L) {
            try {
                legacyHashFile.copyTo(targetPermanent, overwrite = true)
                return targetPermanent.absolutePath
            } catch (_: Throwable) {
                return legacyHashFile.absolutePath
            }
        }

        val legacyKeyFile = File(cacheDir, "book_${safeKey}.${ext}")
        if (legacyKeyFile.exists() && legacyKeyFile.length() > 0L) {
            try {
                legacyKeyFile.copyTo(targetPermanent, overwrite = true)
                return targetPermanent.absolutePath
            } catch (_: Throwable) {
                return legacyKeyFile.absolutePath
            }
        }

        if (resolvedDisplayName.isNotBlank()) {
            val sanitizedName = resolvedDisplayName.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            val dispFile = File(cacheDir, sanitizedName)
            if (dispFile.exists() && dispFile.length() > 0L) {
                try {
                    dispFile.copyTo(targetPermanent, overwrite = true)
                    return targetPermanent.absolutePath
                } catch (_: Throwable) {
                    return dispFile.absolutePath
                }
            }
        }

        // 3. Try reading directly from ContentResolver while permission is available
        val uri = Uri.parse(cleanPath)
        var streamCopied = false
        try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(targetPermanent).use { out -> inp.copyTo(out) }
                streamCopied = (targetPermanent.exists() && targetPermanent.length() > 0L)
            }
        } catch (_: Throwable) {}

        if (streamCopied) {
            return targetPermanent.absolutePath
        }

        // 4. Try extracting raw path from URI if possible (e.g. raw:/storage/emulated/0/... or primary:...)
        try {
            val decoded = URLDecoder.decode(cleanPath, "UTF-8")
            val directPath = when {
                decoded.contains("raw:/") -> "/" + decoded.substringAfter("raw:/")
                decoded.contains("primary:") -> "/storage/emulated/0/" + decoded.substringAfter("primary:").substringBefore("?")
                else -> null
            }
            if (directPath != null) {
                val directF = File(directPath)
                if (directF.exists() && directF.canRead() && directF.length() > 0L) {
                    try {
                        directF.copyTo(targetPermanent, overwrite = true)
                        return targetPermanent.absolutePath
                    } catch (_: Throwable) {
                        return directF.absolutePath
                    }
                }
            }
        } catch (_: Throwable) {}

        // 5. Exhaustive fallback: Search all files in cacheDir / externalCacheDir for a match by format signature or name
        try {
            val candidates = listOfNotNull(context.externalCacheDir, context.cacheDir)
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && it.length() > 1000L }

            if (resolvedDisplayName.isNotBlank()) {
                val base = resolvedDisplayName.substringBeforeLast('.')
                val nameMatch = candidates.firstOrNull { it.name.contains(base, ignoreCase = true) }
                if (nameMatch != null) {
                    nameMatch.copyTo(targetPermanent, overwrite = true)
                    return targetPermanent.absolutePath
                }
            }

            val sigMatch = candidates.firstOrNull { sniffFileFormat(it) == fmt }
            if (sigMatch != null) {
                sigMatch.copyTo(targetPermanent, overwrite = true)
                return targetPermanent.absolutePath
            }
        } catch (_: Throwable) {}

        if (targetPermanent.exists() && targetPermanent.length() > 0L) {
            return targetPermanent.absolutePath
        }

        throw IllegalStateException("도서 파일 스트림에 접근할 수 없습니다 (권한 만료 또는 원본 파일 이동)")
    }

    fun open(
        path: String,
        context: Context? = null,
        forcedEncoding: String? = null,
        hintFormat: BookFormat? = null,
        hintTitle: String? = null
    ): BookContainer {
        val cleanPath = normalizePath(path)
        val enc = forcedEncoding?.takeIf { it.isNotBlank() && it != "AUTO" }
        var fmt = hintFormat ?: formatOf(cleanPath, context) ?: BookFormat.TXT

        var resolvedDisplayName = ""
        if (cleanPath.startsWith("content://") && context != null) {
            try {
                context.contentResolver.query(Uri.parse(cleanPath), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) resolvedDisplayName = c.getString(0) ?: ""
                }
            } catch (_: Exception) {}
        }

        // Zero-Copy ParcelFileDescriptor path for PDFs opened via SAF content:// URI
        if (fmt == BookFormat.PDF && cleanPath.startsWith("content://") && context != null) {
            try {
                val uri = Uri.parse(cleanPath)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val rawName = hintTitle?.takeIf { it.isNotBlank() } ?: resolvedDisplayName.ifBlank {
                        try { URLDecoder.decode(uri.lastPathSegment ?: "Document.pdf", "UTF-8") } catch (_: Exception) { "Document.pdf" }
                    }.substringAfterLast('/').substringAfterLast(':').substringBeforeLast('.')
                    return PdfContainer(pfd, rawName)
                }
            } catch (_: Throwable) {
                // Fall through to cached file copy if direct descriptor fails on specific cloud/SAF provider
            }
        }

        val actualPath = if (cleanPath.startsWith("content://") && context != null) {
            val resolved = resolveContentToFile(cleanPath, context, fmt, resolvedDisplayName)
            val targetFile = File(resolved)
            val sniffed = sniffFileFormat(targetFile)
            if (sniffed != BookFormat.TXT || fmt !in listOf(BookFormat.MD, BookFormat.TXT)) {
                fmt = sniffed
            }
            resolved
        } else {
            val f = File(cleanPath)
            if (f.exists()) {
                val sniffed = sniffFileFormat(f)
                if (sniffed != BookFormat.TXT || fmt !in listOf(BookFormat.MD, BookFormat.TXT)) {
                    fmt = sniffed
                }
                cleanPath
            } else {
                if (context != null) {
                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    val copyName = "book_${md5(cleanPath)}.${fmt.name.lowercase(Locale.ROOT)}"
                    val permF = File(booksDir, copyName)
                    if (permF.exists() && permF.length() > 0L) {
                        permF.absolutePath
                    } else {
                        cleanPath
                    }
                } else cleanPath
            }
        }

        val cleanTitle = hintTitle?.takeIf { it.isNotBlank() }
            ?: resolvedDisplayName.substringBeforeLast('.').ifBlank { File(actualPath).nameWithoutExtension }

        return try {
            when (fmt) {
                BookFormat.EPUB -> EpubContainer(actualPath, cleanTitle)
                BookFormat.PDF -> {
                    val f = File(actualPath)
                    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfContainer(pfd, cleanTitle)
                }
                BookFormat.CBZ -> CbzContainer(actualPath, cleanTitle)
                BookFormat.MOBI, BookFormat.AZW3 -> MobiContainer(actualPath, cleanTitle, enc)
                BookFormat.MD -> TxtContainer(actualPath, true, enc, cleanTitle)
                else -> TxtContainer(actualPath, false, enc, cleanTitle)
            }
        } catch (e: Throwable) {
            // Ultimate resilience fallback: if an archive/container fails to parse as EPUB/MOBI, attempt safe text fallback if readable
            if (fmt != BookFormat.PDF && fmt != BookFormat.CBZ) {
                try {
                    TxtContainer(actualPath, false, enc, cleanTitle)
                } catch (_: Throwable) {
                    throw e
                }
            } else throw e
        }
    }
}

/**
 * TXT / MD / FB2 Container:
 * - Cuts large files into ~24 KB chunks on newline boundaries (including 2-byte aligned UTF-16LE/BE!).
 * - Handles 0-byte files, BOMs, FB2 XML tags, and legacy Korean/Japanese encodings without OOM.
 */
class TxtContainer(
    rawPath: String,
    private val markdown: Boolean,
    forcedEncoding: String? = null,
    customTitle: String? = null
) : BookContainer {
    private val path: String = Containers.normalizePath(rawPath)
    private val raf = RandomAccessFile(path, "r")
    private val charset: Charset
    private val starts = ArrayList<Long>()
    private val isFb2: Boolean
    override val title: String = customTitle?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
    override val author: String = ""
    override val chapters: List<ChapterInfo>
    override val documentStyle: DocumentStyle = DocumentStyle(
        fontFamily = "serif",
        lineHeight = 1.38f,
        paragraphSpacingDp = 0,
        marginHorizontalDp = 18,
        marginVerticalDp = 16
    )

    init {
        val len = raf.length()
        val head = ByteArray(minOf(len, 65536L).toInt())
        if (head.isNotEmpty()) raf.readFully(head)
        charset = if (!forcedEncoding.isNullOrBlank()) {
            try { Charset.forName(forcedEncoding) } catch (_: Exception) { detect(head) }
        } else detect(head)

        val headSample = if (head.isNotEmpty()) {
            try { String(head, 0, minOf(head.size, 2048), charset) } catch (_: Exception) { "" }
        } else ""
        isFb2 = path.endsWith(".fb2", ignoreCase = true) || headSample.contains("<FictionBook", ignoreCase = true)

        starts += 0L
        if (len > CHUNK) {
            val buf = ByteArray(65536)
            raf.seek(0)
            var pos = 0L
            var last = 0L
            val cname = charset.name().uppercase()
            val isUtf16Le = cname.contains("UTF-16LE")
            val isUtf16Be = cname.contains("UTF-16") && !isUtf16Le

            while (pos < len) {
                val n = raf.read(buf)
                if (n <= 0) break
                if (isUtf16Le) {
                    var i = 0
                    while (i + 1 < n) {
                        if (buf[i] == 10.toByte() && buf[i + 1] == 0.toByte() && pos + i + 2 - last >= CHUNK) {
                            last = pos + i + 2
                            if (last < len) starts += last
                        }
                        i += 2
                    }
                } else if (isUtf16Be) {
                    var i = 0
                    while (i + 1 < n) {
                        if (buf[i] == 0.toByte() && buf[i + 1] == 10.toByte() && pos + i + 2 - last >= CHUNK) {
                            last = pos + i + 2
                            if (last < len) starts += last
                        }
                        i += 2
                    }
                } else {
                    for (i in 0 until n) {
                        if (buf[i] == 10.toByte() && pos + i + 1 - last >= CHUNK) {
                            last = pos + i + 1
                            if (last < len) starts += last
                        }
                    }
                }
                pos += n
            }
        }
        if (starts.last() != len) starts += len
        if (starts.size < 2) starts += len

        chapters = (0 until starts.size - 1).map {
            ChapterInfo(it, "섹션 ${it + 1}", (starts[it + 1] - starts[it]).coerceAtLeast(1L))
        }
    }

    private fun detect(head: ByteArray): Charset {
        if (head.isEmpty()) return Charsets.UTF_8
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        // Heuristic for BOM-less UTF-16LE/BE (high ratio of 0x00 at odd/even indices)
        if (head.size >= 64) {
            var evenZeros = 0; var oddZeros = 0
            val checkLen = minOf(head.size, 512)
            for (i in 0 until checkLen) {
                if (head[i] == 0.toByte()) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
            }
            if (oddZeros > checkLen / 5 && evenZeros == 0) return Charsets.UTF_16LE
            if (evenZeros > checkLen / 5 && oddZeros == 0) return Charsets.UTF_16BE
        }
        for (cut in 0..3) {
            if (head.size - cut <= 0) break
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head, 0, head.size - cut))
                return Charsets.UTF_8
            } catch (_: Exception) { }
        }
        return try { Charset.forName("x-windows-949") } catch (_: Exception) {
            try { Charset.forName("EUC-KR") } catch (_: Exception) { Charsets.ISO_8859_1 }
        }
    }

    @Synchronized
    override fun chapterText(index: Int): String {
        if (index < 0 || index + 1 >= starts.size) return ""
        val s = starts[index]
        val e = starts[index + 1]
        val byteLen = (e - s).toInt().coerceAtLeast(0)
        if (byteLen == 0) return "(빈 문서입니다)"
        val bytes = ByteArray(byteLen)
        raf.seek(s)
        raf.readFully(bytes)
        var t = String(bytes, charset).replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
        if (isFb2) {
            t = t.replace(Regex("(?is)<binary[^>]*>.*?</binary>"), "")
                .replace(Regex("(?is)<description>.*?</description>"), "")
                .replace(Regex("(?i)</(p|title|subtitle|section|v)>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\n{3,}"), "\n\n")
        } else if (markdown) {
            t = t.replace(Regex("(?m)^#{1,6}\\s*"), "")
                .replace("**", "").replace("__", "").replace("`", "")
        }
        return t.ifBlank { " " }
    }

    override fun close() { try { raf.close() } catch (_: Exception) {} }

    private companion object { const val CHUNK = 24_000L }
}

/**
 * EPUB 2/3 Resilient Container:
 * - Case-insensitive & URL-decoded zip entry resolution.
 * - Supports both EPUB 2 NCX and EPUB 3 `<nav>` TOC, plus regex fallback for malformed OPF XML.
 * - Extracts publisher CSS typography defaults and inline HTML formatting spans.
 */
class EpubContainer(path: String, customTitle: String? = null) : BookContainer {
    private val normalizedPath = Containers.normalizePath(path)
    private val zip = ZipFile(normalizedPath)
    private val entryMap = HashMap<String, String>() // lowercase normalized path -> actual ZipEntry name
    private val fileNameMap = HashMap<String, String>() // lowercase file basename -> actual ZipEntry name
    private val spine = ArrayList<String>()
    private var opfBaseDir: String = ""
    private var coverEntry: String? = null
    private val footnoteCache = HashMap<Int, Map<String, String>>()
    private val imageCache = object : android.util.LruCache<String, Bitmap>(16) {}
    override var title: String = customTitle?.takeIf { it.isNotBlank() } ?: File(normalizedPath).nameWithoutExtension
        private set
    override var author: String = ""
        private set
    override var translator: String = ""
        private set
    override var publisher: String = ""
        private set
    override var publishYear: String = ""
        private set
    override val chapters: List<ChapterInfo>
    override var documentStyle: DocumentStyle = DocumentStyle()
        private set

    init {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory) {
                val norm = e.name.trimStart('/').replace('\\', '/')
                entryMap[norm.lowercase()] = e.name
                val baseName = norm.substringAfterLast('/').lowercase()
                if (baseName.isNotEmpty()) fileNameMap.putIfAbsent(baseName, e.name)
                try {
                    val decoded = URLDecoder.decode(norm, "UTF-8").lowercase()
                    entryMap.putIfAbsent(decoded, e.name)
                    val decBase = decoded.substringAfterLast('/').lowercase()
                    if (decBase.isNotEmpty()) fileNameMap.putIfAbsent(decBase, e.name)
                } catch (_: Exception) {}
            }
        }

        val opfPath = findOpf()
        val base = opfPath.substringBeforeLast('/', "")
        opfBaseDir = base
        val manifest = LinkedHashMap<String, Triple<String, String, String>>() // id -> (href, type, props)
        val spineIds = ArrayList<String>()
        var tocId: String? = null
        var coverId: String? = null

        fun resolve(href: String): String = resolveRelativeTo(base, href)

        val opfEntry = getZipEntry(opfPath)
        if (opfEntry != null) {
            val opfXml = readEntryText(opfEntry)
            try {
                val p = Xml.newPullParser()
                p.setInput(opfXml.reader())
                var gotTitle = false
                var gotAuthor = false
                var gotPublisher = false
                var gotDate = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val tag = p.name.substringAfterLast(':').lowercase()
                        when (tag) {
                            "item" -> {
                                val id = p.getAttributeValue(null, "id") ?: ""
                                val href = p.getAttributeValue(null, "href") ?: ""
                                val type = p.getAttributeValue(null, "media-type") ?: ""
                                val props = p.getAttributeValue(null, "properties") ?: ""
                                if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = Triple(href, type, props)
                            }
                            "itemref" -> p.getAttributeValue(null, "idref")?.let { spineIds += it }
                            "spine" -> tocId = p.getAttributeValue(null, "toc")
                            "meta" -> if (p.getAttributeValue(null, "name").equals("cover", true)) {
                                coverId = p.getAttributeValue(null, "content")
                            }
                            "title" -> if (!gotTitle) {
                                val t = p.nextText().trim()
                                if (t.isNotEmpty()) { title = t; gotTitle = true }
                            }
                            "creator" -> {
                                val role = (p.getAttributeValue(null, "role") ?: p.getAttributeValue("http://www.idpf.org/2007/opf", "role") ?: "").lowercase()
                                val t = p.nextText().trim()
                                if (t.isNotEmpty()) {
                                    if (role == "trl" || role.contains("translator")) {
                                        if (translator.isEmpty()) translator = t
                                    } else if (!gotAuthor) {
                                        author = t; gotAuthor = true
                                    }
                                }
                            }
                            "contributor" -> {
                                val role = (p.getAttributeValue(null, "role") ?: p.getAttributeValue("http://www.idpf.org/2007/opf", "role") ?: "").lowercase()
                                val t = p.nextText().trim()
                                if (t.isNotEmpty()) {
                                    if (role == "trl" || role.contains("translator") || t.contains("옮긴") || t.contains("역자") || t.contains("번역")) {
                                        if (translator.isEmpty()) {
                                            translator = t.replace(Regex("^(옮긴이|역자|번역)\\s*[:：]?\\s*"), "").trim()
                                        }
                                    }
                                }
                            }
                            "publisher" -> if (!gotPublisher) {
                                val t = p.nextText().trim()
                                if (t.isNotEmpty()) { publisher = t; gotPublisher = true }
                            }
                            "date" -> if (!gotDate) {
                                val t = p.nextText().trim()
                                if (t.isNotEmpty()) {
                                    val y = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(t)
                                    publishYear = y?.value ?: t.take(4)
                                    gotDate = true
                                }
                            }
                        }
                    }
                    ev = p.next()
                }
            } catch (_: Throwable) {
                // Regex fallback if OPF has malformed XML entities
                Regex("(?is)<item\\b([^>]+)>").findAll(opfXml).forEach { m ->
                    val attrs = m.groupValues[1]
                    val id = Regex("id=[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1) ?: ""
                    val href = Regex("href=[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1) ?: ""
                    val type = Regex("media-type=[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1) ?: ""
                    if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = Triple(href, type, "")
                }
                Regex("(?is)<itemref\\b[^>]*idref=[\"']([^\"']+)[\"']").findAll(opfXml).forEach { m ->
                    spineIds += m.groupValues[1]
                }
            }
            if (publisher.isEmpty()) {
                Regex("(?is)<(?:[a-zA-Z0-9]+:)?publisher[^>]*>([^<]+)").find(opfXml)?.let {
                    publisher = it.groupValues[1].trim()
                }
            }
            if (publishYear.isEmpty()) {
                Regex("(?is)<(?:[a-zA-Z0-9]+:)?date[^>]*>([^<]+)").find(opfXml)?.let {
                    val y = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(it.groupValues[1])
                    publishYear = y?.value ?: it.groupValues[1].trim().take(4)
                }
            }
            if (translator.isEmpty()) {
                Regex("(?is)<(?:[a-zA-Z0-9]+:)?contributor[^>]*role=[\"']trl[\"'][^>]*>([^<]+)").find(opfXml)?.let {
                    translator = it.groupValues[1].trim()
                } ?: Regex("(?is)<(?:[a-zA-Z0-9]+:)?contributor[^>]*>([^<]+)").findAll(opfXml).firstOrNull {
                    val text = it.groupValues[1]
                    text.contains("옮긴") || text.contains("역자") || text.contains("번역")
                }?.let {
                    translator = it.groupValues[1].trim().replace(Regex("^(옮긴이|역자|번역)\\s*[:：]?\\s*"), "").trim()
                }
            }
        }

        for (id in spineIds) {
            val m = manifest[id] ?: continue
            val ext = m.first.substringBefore('#').substringAfterLast('.', "").lowercase()
            if (m.second.contains("html", true) || m.second.contains("xml", true) || ext in listOf("xhtml", "html", "htm", "xml")) {
                val resolved = resolve(m.first)
                if (getZipEntry(resolved) != null && resolved !in spine) spine += resolved
            }
        }

        // Fallback if spine was empty or broken: collect all HTML/XHTML files in archive
        if (spine.isEmpty()) {
            entryMap.values.filter {
                val l = it.lowercase()
                (l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")) && !l.contains("toc") && !l.contains("nav")
            }.sorted().forEach { spine += it }
        }

        coverEntry = manifest.values.firstOrNull { it.third.contains("cover-image", true) }?.let { resolve(it.first) }
            ?: coverId?.let { manifest[it] }?.let { resolve(it.first) }
            ?: manifest.values.firstOrNull {
                it.second.startsWith("image/", true) && it.first.contains("cover", true)
            }?.let { resolve(it.first) }
            ?: manifest.values.firstOrNull { it.second.startsWith("image/", true) }?.let { resolve(it.first) }

        // Extract publisher CSS defaults
        val cssBuilder = StringBuilder()
        manifest.values.filter { it.second.contains("css", true) || it.first.endsWith(".css", true) }.take(4).forEach { cssItem ->
            try {
                val entry = getZipEntry(resolve(cssItem.first))
                if (entry != null && entry.size in 1..150_000) {
                    cssBuilder.append(readEntryText(entry)).append("\n")
                }
            } catch (_: Exception) {}
        }
        if (cssBuilder.isNotEmpty()) {
            documentStyle = parseCssDocumentStyle(cssBuilder.toString())
        }

        // Parse TOC titles (EPUB 2 NCX + EPUB 3 nav)
        val titles = HashMap<String, String>()
        tocId?.let { manifest[it] }?.let { ncx ->
            try {
                getZipEntry(resolve(ncx.first))?.let { entry ->
                    val p = Xml.newPullParser()
                    p.setInput(readEntryText(entry).reader())
                    var label: String? = null
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            val tag = p.name.substringAfterLast(':').lowercase()
                            if (tag == "text" && label == null) label = p.nextText().trim()
                            else if (tag == "content") {
                                val src = p.getAttributeValue(null, "src")
                                if (src != null && !label.isNullOrBlank()) {
                                    val r = resolve(src)
                                    titles.putIfAbsent(r, label)
                                    titles.putIfAbsent(r.lowercase(), label)
                                    val bName = r.substringAfterLast('/').lowercase()
                                    if (bName.isNotEmpty()) titles.putIfAbsent(bName, label)
                                    label = null
                                }
                            }
                        }
                        ev = p.next()
                    }
                }
            } catch (_: Exception) { }
        }

        // EPUB 3 nav fallback
        if (titles.isEmpty()) {
            manifest.values.firstOrNull { it.third.contains("nav", true) }?.let { navItem ->
                try {
                    getZipEntry(resolve(navItem.first))?.let { entry ->
                        val navHtml = readEntryText(entry)
                        Regex("(?is)<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>").findAll(navHtml).forEach { m ->
                            val href = resolve(m.groupValues[1])
                            val text = Containers.safeFromHtml(m.groupValues[2]).toString().trim()
                            if (text.isNotEmpty()) {
                                titles.putIfAbsent(href, text)
                                titles.putIfAbsent(href.lowercase(), text)
                                val bName = href.substringAfterLast('/').lowercase()
                                if (bName.isNotEmpty()) titles.putIfAbsent(bName, text)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        chapters = if (spine.isNotEmpty()) {
            spine.mapIndexed { i, name ->
                val titleMatch = titles[name]
                    ?: titles[name.lowercase()]
                    ?: titles[name.substringAfterLast('/').lowercase()]
                ChapterInfo(i, titleMatch ?: "챕터 ${i + 1}", (getZipEntry(name)?.size ?: 1000L).coerceAtLeast(1L))
            }
        } else {
            listOf(ChapterInfo(0, "챕터 1", 100L))
        }
    }

    private fun resolveRelativeTo(baseDir: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?').trim()
        val clean = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
        val parts = ArrayList<String>()
        val combined = if (baseDir.isEmpty() || clean.startsWith("/")) clean.trimStart('/') else "$baseDir/$clean"
        combined.replace('\\', '/').split('/').forEach {
            when (it) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += it
            }
        }
        val joined = parts.joinToString("/")
        return entryMap[joined.lowercase()]
            ?: entryMap[clean.trimStart('/').lowercase()]
            ?: entryMap[raw.trimStart('/').lowercase()]
            ?: fileNameMap[clean.substringAfterLast('/').lowercase()]
            ?: joined
    }

    private fun getZipEntry(name: String): ZipEntry? {
        val norm = name.trimStart('/').replace('\\', '/')
        return zip.getEntry(name)
            ?: entryMap[norm.lowercase()]?.let { zip.getEntry(it) }
            ?: fileNameMap[norm.substringAfterLast('/').lowercase()]?.let { zip.getEntry(it) }
    }

    @Synchronized
    private fun decodeEpubBitmap(chapterEntryName: String, rawSrc: String): Bitmap? {
        if (rawSrc.isBlank()) return null
        val chapterDir = chapterEntryName.replace('\\', '/').substringBeforeLast('/', "")
        val resolvedName = resolveRelativeTo(chapterDir, rawSrc).let { candidate ->
            if (getZipEntry(candidate) != null) candidate else resolveRelativeTo(opfBaseDir, rawSrc)
        }
        imageCache.get(resolvedName)?.let { if (!it.isRecycled) return it }

        val entry = getZipEntry(resolvedName) ?: return null
        if (entry.size <= 0L || entry.size > 25_000_000L) return null
        return try {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val boundsOpt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpt)
            if (boundsOpt.outWidth <= 0 || boundsOpt.outHeight <= 0) return null

            var sample = 1
            val maxDim = 1080
            while (boundsOpt.outWidth / sample > maxDim || boundsOpt.outHeight / sample > maxDim) {
                sample *= 2
            }
            val decodeOpt = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpt)
            if (bmp != null) {
                imageCache.put(resolvedName, bmp)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun readEntryText(entry: ZipEntry): String {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        val cs = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> {
                val headStr = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1).lowercase()
                if (headStr.contains("euc-kr") || headStr.contains("ks_c_5601") || headStr.contains("cp949")) {
                    try { Charset.forName("x-windows-949") } catch (_: Exception) { Charsets.UTF_8 }
                } else Charsets.UTF_8
            }
        }
        return String(bytes, cs).replace("\uFEFF", "")
    }

    private fun parseCssDocumentStyle(css: String): DocumentStyle {
        var fontFamily = "serif"
        var lineHeight = 1.42f
        var paraGapDp = 6
        var marginHDp = 18
        val marginVDp = 16

        val ffMatch = Regex("(?i)font-family\\s*:\\s*([^;}{]+)").find(css)
        if (ffMatch != null) {
            val ff = ffMatch.groupValues[1].lowercase()
            fontFamily = when {
                ff.contains("sans") || ff.contains("gothic") || ff.contains("dotum") ||
                    ff.contains("gulim") || ff.contains("pretendard") || ff.contains("arial") ||
                    ff.contains("고딕") || ff.contains("돋움") -> "sans-serif"
                ff.contains("mono") || ff.contains("courier") -> "monospace"
                else -> "serif"
            }
        }

        val lhMatch = Regex("(?i)line-height\\s*:\\s*([0-9.]+)(%|em|rem)?").find(css)
        if (lhMatch != null) {
            val rawVal = lhMatch.groupValues[1].toFloatOrNull()
            val unit = lhMatch.groupValues[2].lowercase()
            if (rawVal != null) {
                val computed = if (unit == "%") rawVal / 100f else rawVal
                if (computed in 1.1f..2.4f) lineHeight = computed
            }
        }

        val pBlockMatch = Regex("(?is)\\bp\\s*\\{([^}]*)\\}").find(css)
        if (pBlockMatch != null) {
            val pBody = pBlockMatch.groupValues[1]
            val mbMatch = Regex("(?i)margin-bottom\\s*:\\s*([0-9.]+)(em|rem|px|pt)?").find(pBody)
            if (mbMatch != null) {
                val v = mbMatch.groupValues[1].toFloatOrNull() ?: 0f
                val u = mbMatch.groupValues[2].lowercase()
                paraGapDp = when (u) {
                    "em", "rem" -> (v * 14f).toInt().coerceIn(0, 24)
                    "px", "pt" -> v.toInt().coerceIn(0, 24)
                    else -> if (v == 0f) 0 else 6
                }
            }
        }

        val bodyMatch = Regex("(?is)\\bbody\\s*\\{([^}]*)\\}").find(css)
        if (bodyMatch != null) {
            val bBody = bodyMatch.groupValues[1]
            val mlMatch = Regex("(?i)(?:margin-left|padding-left)\\s*:\\s*([0-9.]+)(%|em|rem|px)?").find(bBody)
            if (mlMatch != null) {
                val v = mlMatch.groupValues[1].toFloatOrNull() ?: 0f
                val u = mlMatch.groupValues[2].lowercase()
                marginHDp = when (u) {
                    "%" -> (v * 3.2f).toInt().coerceIn(10, 42)
                    "em", "rem" -> (v * 14f).toInt().coerceIn(10, 42)
                    "px" -> v.toInt().coerceIn(10, 42)
                    else -> 18
                }
            }
        }

        return DocumentStyle(fontFamily, lineHeight, paraGapDp, marginHDp, marginVDp)
    }

    private fun findOpf(): String {
        getZipEntry("META-INF/container.xml")?.let { containerEntry ->
            try {
                val xml = readEntryText(containerEntry)
                val m = Regex("full-path=[\"']([^\"']+)[\"']").find(xml)
                if (m != null) return m.groupValues[1]
            } catch (_: Exception) {}
        }
        // Fallback: search for any .opf file in the ZIP
        entryMap.values.firstOrNull { it.endsWith(".opf", ignoreCase = true) }?.let { return it }
        return ""
    }

    private fun parseHtmlChapter(index: Int): CharSequence {
        if (index < 0 || index >= spine.size) return ""
        val chapterEntryName = spine[index]
        val entry = getZipEntry(chapterEntryName) ?: return ""
        var html = try { readEntryText(entry) } catch (_: Throwable) { return "" }

        // 1. Preserve SVG-wrapped images (<svg>...<image xlink:href="..."/>...</svg>) and standalone <image> tags as <img>
        html = html
            .replace(
                Regex("(?is)<svg\\b[^>]*>.*?<image\\b[^>]*?(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>.*?</svg>"),
                "<p><img src=\"$1\"/></p>"
            )
            .replace(
                Regex("(?is)<image\\b[^>]*?(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>"),
                "<img src=\"$1\"/>"
            )
            .replace(Regex("(?is)<head.*?</head>"), "")
            .replace(Regex("(?is)<(script|style|svg).*?</\\1>"), "")
            // 2. Clean ruby annotations and normalize self-closing non-void XHTML tags so TagSoup never leaves open spans
            .replace(Regex("(?is)<rp>.*?</rp>"), "")
            .replace(Regex("(?is)<rt[^>]*>(.*?)</rt>"), "($1)")
            .replace(
                Regex("(?i)<(span|a|sub|sup|small|big|font|div|p|em|i|b|strong|u|s|del|strike|code|tt)\\b([^>]*?)/>"),
                "<$1$2></$1>"
            )

        val notes = LinkedHashMap<String, String>()
        val asideRegex = Regex("(?is)<(aside|li|div|p)[^>]*\\bid=[\"']([^\"']*(?:fn|note)[^\"']*)[\"'][^>]*>(.*?)</\\1>")
        asideRegex.findAll(html).forEach { m ->
            val id = m.groupValues[2]
            val cleanNote = Containers.safeFromHtml(m.groupValues[3]).toString().trim()
            if (cleanNote.isNotEmpty()) {
                notes[id] = cleanNote
            }
        }
        footnoteCache[index] = notes

        val decodedBitmaps = HashMap<String, Bitmap>()
        val spanned: CharSequence = try {
            val getter = Html.ImageGetter { src ->
                if (src.isNullOrBlank()) return@ImageGetter null
                val bmp = decodeEpubBitmap(chapterEntryName, src) ?: return@ImageGetter null
                decodedBitmaps[src] = bmp
                @Suppress("DEPRECATION")
                android.graphics.drawable.BitmapDrawable(bmp).apply {
                    setBounds(0, 0, bmp.width.coerceAtLeast(1), bmp.height.coerceAtLeast(1))
                }
            }
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, getter, null)
        } catch (_: Throwable) {
            null
        } ?: Containers.safeFromHtml(html)

        val ssb = try { SpannableStringBuilder(spanned) } catch (_: Throwable) { null }
        if (ssb != null && ssb.isNotEmpty()) {
            // Upgrade ImageSpans from Html.fromHtml into viewport-aware BookImageSpans
            val imgSpans = ssb.getSpans(0, ssb.length, android.text.style.ImageSpan::class.java)
            for (imgSpan in imgSpans) {
                val s = ssb.getSpanStart(imgSpan)
                val e = ssb.getSpanEnd(imgSpan)
                val src = imgSpan.source ?: ""
                val bmp = decodedBitmaps[src]
                    ?: (imgSpan.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: decodeEpubBitmap(chapterEntryName, src)
                ssb.removeSpan(imgSpan)
                if (bmp != null && !bmp.isRecycled && s >= 0 && e > s && e <= ssb.length) {
                    ssb.setSpan(BookImageSpan(bmp), s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // Strip all spans that shift vertical baseline or change font family/size mid-line
            // (SubscriptSpan, SuperscriptSpan, TypefaceSpan, RelativeSizeSpan, AbsoluteSizeSpan, ParagraphStyles, etc.)
            val allSpans = ssb.getSpans(0, ssb.length, Any::class.java)
            for (span in allSpans) {
                when (span) {
                    is BookImageSpan,
                    is android.text.style.StyleSpan,
                    is android.text.style.UnderlineSpan,
                    is android.text.style.StrikethroughSpan -> { /* keep safe inline styles & images */ }
                    else -> ssb.removeSpan(span)
                }
            }

            // Ensure block-level BookImageSpans sit on their own dedicated line (\n\uFFFC\n)
            val bookImages = ssb.getSpans(0, ssb.length, BookImageSpan::class.java)
                .sortedByDescending { ssb.getSpanStart(it) }
            for (bSpan in bookImages) {
                if (!bSpan.isInline) {
                    val e = ssb.getSpanEnd(bSpan)
                    if (e in 1 until ssb.length && ssb[e] != '\n') {
                        ssb.insert(e, "\n")
                    }
                    val s = ssb.getSpanStart(bSpan)
                    if (s > 0 && ssb[s - 1] != '\n') {
                        ssb.insert(s, "\n")
                    }
                }
            }

            // Remove any unattached \uFFFC placeholders (failed/missing images) so no blank boxes appear
            var i = ssb.length - 1
            while (i >= 0) {
                if (ssb[i] == '\uFFFC') {
                    val hasBookImg = ssb.getSpans(i, i + 1, BookImageSpan::class.java).isNotEmpty()
                    if (!hasBookImg) {
                        ssb.delete(i, i + 1)
                    }
                }
                i--
            }

            // Trim leading/trailing whitespace without removing valid \uFFFC image characters
            while (ssb.isNotEmpty() && ssb[0] != '\uFFFC' && ssb[0].isWhitespace()) {
                ssb.delete(0, 1)
            }
            while (ssb.isNotEmpty() && ssb[ssb.length - 1] != '\uFFFC' && ssb[ssb.length - 1].isWhitespace()) {
                ssb.delete(ssb.length - 1, ssb.length)
            }
            return ssb
        }
        return spanned.toString().replace('\uFFFC', ' ').trim()
    }

    override fun chapterSpanned(index: Int): CharSequence = parseHtmlChapter(index)

    override fun chapterText(index: Int): String {
        return parseHtmlChapter(index).toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    override fun footnotesForChapter(index: Int): Map<String, String> {
        if (!footnoteCache.containsKey(index)) parseHtmlChapter(index)
        return footnoteCache[index] ?: emptyMap()
    }

    override fun coverBytes(): ByteArray? = coverEntry?.let { n ->
        getZipEntry(n)?.let { e -> try { zip.getInputStream(e).use { it.readBytes() } } catch (_: Throwable) { null } }
    }

    override fun close() { try { zip.close() } catch (_: Exception) { } }
}

/**
 * Unencrypted MOBI / AZW / PalmDOC Container:
 * - Decodes PalmDOC uncompressed (type 1) and LZ77 compressed (type 2) text records.
 * - Detects DRM-encrypted records gracefully without crashing.
 */
class MobiContainer(
    rawPath: String,
    customTitle: String? = null,
    forcedEncoding: String? = null
) : BookContainer {
    private val path: String = Containers.normalizePath(rawPath)
    override var title: String = customTitle?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
        private set
    override var author: String = ""
        private set
    override val chapters: List<ChapterInfo>
    private val sectionTexts = ArrayList<String>()

    init {
        RandomAccessFile(path, "r").use { raf ->
            val fileLen = raf.length()
            if (fileLen < 78) throw IllegalStateException("유효하지 않은 MOBI 파일입니다.")

            val pdbName = ByteArray(32)
            raf.readFully(pdbName)
            val rawPdbTitle = String(pdbName, Charsets.UTF_8).substringBefore('\u0000').trim()
            if (rawPdbTitle.isNotBlank() && customTitle.isNullOrBlank()) title = rawPdbTitle

            raf.seek(76)
            val numRecords = raf.readUnsignedShort()
            val recordOffsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                recordOffsets[i] = raf.readInt().toLong() and 0xFFFFFFFFL
                raf.readInt() // attributes + uniqueID
            }

            if (numRecords < 2) throw IllegalStateException("MOBI 레코드가 비어 있습니다.")

            val rec0Pos = recordOffsets[0]
            raf.seek(rec0Pos)
            val compression = raf.readUnsignedShort()
            raf.readUnsignedShort() // unused
            raf.readInt()           // text length
            val textRecordCount = raf.readUnsignedShort()
            raf.readUnsignedShort() // record size
            val encryptionType = raf.readUnsignedShort()

            if (encryptionType != 0) {
                sectionTexts += "이 전자책(MOBI/AZW)에는 DRM 암호화가 적용되어 있어 열람할 수 없습니다."
            } else {
                // Check MOBI header for UTF-8 vs CP1252/CP949 encoding (offset rec0Pos + 28)
                var charset: Charset = Charsets.UTF_8
                if (recordOffsets[1] - rec0Pos >= 36) {
                    raf.seek(rec0Pos + 16)
                    val mobiMagic = ByteArray(4)
                    raf.readFully(mobiMagic)
                    if (String(mobiMagic, Charsets.US_ASCII) == "MOBI") {
                        raf.seek(rec0Pos + 28)
                        val codepage = raf.readInt()
                        charset = when (codepage) {
                            65001 -> Charsets.UTF_8
                            949 -> try { Charset.forName("x-windows-949") } catch (_: Exception) { Charsets.UTF_8 }
                            else -> Charsets.UTF_8
                        }
                        // Read full title from MOBI header if available (offset rec0Pos + 84 & 88)
                        if (recordOffsets[1] - rec0Pos >= 92) {
                            raf.seek(rec0Pos + 84)
                            val fullNameOffset = raf.readInt()
                            val fullNameLen = raf.readInt()
                            if (fullNameOffset > 0 && fullNameLen in 1..256 && rec0Pos + fullNameOffset + fullNameLen <= recordOffsets[1]) {
                                raf.seek(rec0Pos + fullNameOffset)
                                val fnBytes = ByteArray(fullNameLen)
                                raf.readFully(fnBytes)
                                val fn = String(fnBytes, charset).trim()
                                if (fn.isNotBlank()) title = fn
                            }
                        }
                    }
                }
                if (!forcedEncoding.isNullOrBlank()) {
                    try { charset = Charset.forName(forcedEncoding) } catch (_: Exception) {}
                }

                val outStream = ByteArrayOutputStream()
                val maxRec = minOf(numRecords - 1, textRecordCount)
                for (r in 1..maxRec) {
                    val start = recordOffsets[r]
                    val end = if (r + 1 < numRecords) recordOffsets[r + 1] else fileLen
                    val size = (end - start).toInt().coerceIn(0, 131072)
                    if (size <= 0) continue
                    val raw = ByteArray(size)
                    raf.seek(start)
                    raf.readFully(raw)

                    val decoded = when (compression) {
                        1 -> raw
                        2 -> decompressPalmDocLz77(raw)
                        else -> raw
                    }
                    outStream.write(decoded)
                    if (outStream.size() >= 36_000 || r == maxRec) {
                        val htmlChunk = String(outStream.toByteArray(), charset)
                        outStream.reset()
                        val cleaned = Containers.safeFromHtml(
                            htmlChunk.replace(Regex("(?is)<head.*?</head>"), "")
                                .replace(Regex("(?is)<(script|style).*?</\\1>"), "")
                        ).toString().replace('\uFFFC', ' ').trim()
                        if (cleaned.isNotEmpty()) sectionTexts += cleaned
                    }
                }
            }
        }
        if (sectionTexts.isEmpty()) sectionTexts += "(내용을 추출할 수 없는 문서입니다)"
        chapters = sectionTexts.mapIndexed { i, s -> ChapterInfo(i, "섹션 ${i + 1}", s.length.toLong().coerceAtLeast(1L)) }
    }

    private fun decompressPalmDocLz77(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        val n = data.size
        while (i < n) {
            val b = data[i++].toInt() and 0xFF
            when (b) {
                0x00 -> out.write(0)
                in 0x01..0x08 -> {
                    val count = minOf(b, n - i)
                    out.write(data, i, count)
                    i += count
                }
                in 0x09..0x7F -> out.write(b)
                in 0x80..0xBF -> {
                    if (i >= n) break
                    val next = data[i++].toInt() and 0xFF
                    val pair = ((b shl 8) or next) and 0x3FFF
                    val dist = pair ushr 3
                    val len = (pair and 0x07) + 3
                    if (dist > 0 && dist <= out.size()) {
                        val curBytes = out.toByteArray()
                        var srcIdx = curBytes.size - dist
                        for (k in 0 until len) {
                            val v = if (srcIdx < curBytes.size) curBytes[srcIdx] else 32.toByte()
                            out.write(v.toInt())
                            srcIdx++
                        }
                    }
                }
                in 0xC0..0xFF -> {
                    out.write(32)
                    out.write(b xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }

    override fun chapterText(index: Int): String = sectionTexts.getOrElse(index) { "" }

    override fun close() {}
}

/**
 * Native Android PdfRenderer container for zero-dependency, low-memory PDF viewing.
 */
class PdfContainer(
    private val pfd: ParcelFileDescriptor,
    override val title: String
) : BookContainer {
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: SecurityException) {
        try { pfd.close() } catch (_: Exception) {}
        throw IllegalStateException("암호가 설정된 PDF 파일입니다.")
    } catch (e: Exception) {
        try { pfd.close() } catch (_: Exception) {}
        throw IllegalStateException("손상되었거나 읽을 수 없는 PDF 파일입니다.")
    }

    override val author: String = "PDF Document"
    override val isImageBased: Boolean = true
    override val chapters: List<ChapterInfo> = if (renderer.pageCount > 0) {
        (0 until renderer.pageCount).map { ChapterInfo(it, "페이지 ${it + 1}", 1000L) }
    } else {
        listOf(ChapterInfo(0, "빈 PDF", 1L))
    }

    override fun chapterText(index: Int): String = "[PDF Page ${index + 1} / ${renderer.pageCount}]"

    @Synchronized
    override fun renderPageBitmap(index: Int, targetBmp: Bitmap, bgColor: Int): Boolean {
        if (index < 0 || index >= renderer.pageCount) {
            targetBmp.eraseColor(bgColor)
            return false
        }
        return try {
            targetBmp.eraseColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                val vw = targetBmp.width.toFloat()
                val vh = targetBmp.height.toFloat()
                val pw = page.width.toFloat().coerceAtLeast(1f)
                val ph = page.height.toFloat().coerceAtLeast(1f)
                val scale = minOf(vw / pw, vh / ph)
                val dw = pw * scale
                val dh = ph * scale
                val dx = (vw - dw) / 2f
                val dy = (vh - dh) / 2f
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }
                page.render(targetBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            if (bgColor == Color.BLACK || bgColor == 0xFF121212.toInt() || bgColor == 0xFF141416.toInt()) {
                val c = Canvas(targetBmp)
                val invertMatrix = ColorMatrix(floatArrayOf(
                    -1f,  0f,  0f, 0f, 255f,
                     0f, -1f,  0f, 0f, 255f,
                     0f,  0f, -1f, 0f, 255f,
                     0f,  0f,  0f, 1f,   0f
                ))
                val p = Paint().apply { colorFilter = ColorMatrixColorFilter(invertMatrix) }
                val copy = targetBmp.copy(targetBmp.config, false)
                if (copy != null) {
                    c.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    override fun getPageBounds(index: Int, targetW: Int, targetH: Int): RectF? {
        if (index < 0 || index >= renderer.pageCount) return null
        return try {
            renderer.openPage(index).use { page ->
                val vw = targetW.toFloat()
                val vh = targetH.toFloat()
                val pw = page.width.toFloat().coerceAtLeast(1f)
                val ph = page.height.toFloat().coerceAtLeast(1f)
                val scale = minOf(vw / pw, vh / ph)
                val dw = pw * scale
                val dh = ph * scale
                val dx = (vw - dw) / 2f
                val dy = (vh - dh) / 2f
                RectF(dx, dy, dx + dw, dy + dh)
            }
        } catch (_: Throwable) {
            RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        }
    }

    @Synchronized
    override fun coverBytes(): ByteArray? = try {
        if (renderer.pageCount <= 0) null else {
            val bmp = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            renderer.openPage(0).use { page ->
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 80, bos)
            bmp.recycle()
            bos.toByteArray()
        }
    } catch (_: Throwable) { null }

    @Synchronized
    override fun close() {
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Streaming CBZ Comic / Image Archive Container.
 */
/**
 * Streaming CBZ / Comic / Image Archive Container powered by SubsampledBitmapDecoder & ArchiveStreamManager.
 */
class CbzContainer(path: String, customTitle: String? = null) : BookContainer {
    private val streamer = com.aether.reader.comic.ArchiveStreamManager(null, path)
    override val title: String = customTitle?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
    override val author: String = "Comic Archive"
    override val isImageBased: Boolean = true
    override val chapters: List<ChapterInfo> = if (streamer.pages.isNotEmpty()) {
        streamer.pages.mapIndexed { i, _ -> ChapterInfo(i, "페이지 ${i + 1}", 1000L) }
    } else {
        listOf(ChapterInfo(0, "이미지 없음", 1L))
    }

    override fun chapterText(index: Int): String = "[Comic Page ${index + 1} / ${streamer.pages.size}]"

    @Synchronized
    override fun renderPageBitmap(index: Int, targetBmp: Bitmap, bgColor: Int): Boolean {
        targetBmp.eraseColor(bgColor)
        return streamer.renderPage(
            pageIndex = index,
            targetBmp = targetBmp,
            reqW = targetBmp.width,
            reqH = targetBmp.height,
            enhanceEink = (bgColor == Color.WHITE || bgColor == 0xFFFBFBF8.toInt())
        )
    }

    override fun getPageBounds(index: Int, targetW: Int, targetH: Int): RectF? =
        RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())

    override fun coverBytes(): ByteArray? = streamer.extractFastCoverBytes(true)

    override fun close() {
        streamer.close()
    }
}
