package com.aether.reader.features.share

import android.content.Context
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.ShareFileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dedicated feature module for packaging book highlights & reading notes into
 * a ZIP archive containing both Plain Text (.txt) and Markdown (.md) documents,
 * and dispatching them via email.
 */
object HighlightsZipExporter {

    fun createHighlightsZip(
        ctx: Context,
        bookTitle: String,
        bookAuthor: String,
        highlights: List<HighlightEntity>,
        includeBookInfo: Boolean = true
    ): File? = try {
        val dir = ShareFileProvider.shareDir(ctx)
        val distinctBooks = highlights.map { (it.bookTitle.ifBlank { bookTitle }).trim() to (it.bookAuthor.ifBlank { bookAuthor }).trim() }.distinct()
        val safeTitle = if (distinctBooks.size == 1) {
            distinctBooks[0].first.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(24).ifBlank { "highlights" }
        } else {
            "AetherSAGA_${distinctBooks.size}Books_${highlights.size}Items"
        }
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
            if (distinctBooks.size > 1) {
                sb.appendLine("• 포함 도서 (${distinctBooks.size}권):")
                distinctBooks.forEachIndexed { idx, b ->
                    val bCount = highlights.count { it.bookTitle == b.first || (it.bookTitle.isBlank() && b.first == bookTitle) }
                    val auth = if (b.second.isNotBlank()) " (${b.second})" else ""
                    sb.appendLine("  ${idx + 1}. 《${b.first}》$auth - ${bCount}건")
                }
            } else {
                val singleTitle = distinctBooks.firstOrNull()?.first?.ifBlank { bookTitle } ?: bookTitle
                val singleAuthor = distinctBooks.firstOrNull()?.second?.ifBlank { bookAuthor } ?: bookAuthor
                sb.appendLine("• 도서명: 《$singleTitle》")
                sb.appendLine("• 저자: ${if (singleAuthor.isNotBlank()) singleAuthor else "저자 정보 없음"}")
            }
            sb.appendLine("• 기록 내역: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            sb.appendLine("• 추출 일시: $dateStr")
            sb.appendLine()
            sb.appendLine("본 문서는 Aether SAGA 전자책 뷰어에서 독자가 직접 남긴")
            sb.appendLine("소중한 문장과 독서 메모를 영구 보관하기 위해 정리된 문서입니다.")
            sb.appendLine("==================================================")
            sb.appendLine()

            highlights.forEachIndexed { i, h ->
                val itemTitle = h.bookTitle.ifBlank { bookTitle }
                val itemAuthor = h.bookAuthor.ifBlank { bookAuthor }
                val authorSuffix = if (itemAuthor.isNotBlank()) " ($itemAuthor)" else ""
                val bookHeader = if (includeBookInfo) "《$itemTitle》$authorSuffix | " else ""

                val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(h.createdAt))
                val chTitle = h.chapterTitle.ifBlank { "챕터/페이지 ${h.chapter + 1}" }
                val typeName = if (h.kind == 1) "북마크" else "하이라이트"
                sb.appendLine("[#${i + 1}] [$typeName] $bookHeader$chTitle ($itemDate)")
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
            val mdMainTitle = if (distinctBooks.size == 1) "《${distinctBooks[0].first}》" else "${distinctBooks.size}권 도서"
            md.appendLine("# 📚 $mdMainTitle 독서 하이라이트 및 메모 기록")
            md.appendLine()
            md.appendLine("> 본 문서는 **Aether SAGA** 전자책 뷰어에서 기록한 독서 하이라이트와 사유 메모를 정리한 문서입니다.")
            md.appendLine()
            md.appendLine("## 📖 도서 정보 요약")
            if (distinctBooks.size > 1) {
                md.appendLine("- **포함 도서 (${distinctBooks.size}권)**:")
                distinctBooks.forEachIndexed { idx, b ->
                    val bCount = highlights.count { it.bookTitle == b.first || (it.bookTitle.isBlank() && b.first == bookTitle) }
                    val auth = if (b.second.isNotBlank()) " (${b.second})" else ""
                    md.appendLine("  ${idx + 1}. 《${b.first}》$auth - ${bCount}건")
                }
            } else {
                val singleTitle = distinctBooks.firstOrNull()?.first?.ifBlank { bookTitle } ?: bookTitle
                val singleAuthor = distinctBooks.firstOrNull()?.second?.ifBlank { bookAuthor } ?: bookAuthor
                md.appendLine("- **도서명**: 《$singleTitle》")
                md.appendLine("- **저자**: ${if (singleAuthor.isNotBlank()) singleAuthor else "저자 정보 없음"}")
            }
            md.appendLine("- **기록 내역**: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            md.appendLine("- **추출 일시**: $dateStr")
            md.appendLine()
            md.appendLine("---")

            if (distinctBooks.size > 1 && includeBookInfo) {
                val grouped = highlights.groupBy { (it.bookTitle.ifBlank { bookTitle }).trim() }
                for ((groupTitle, groupItems) in grouped) {
                    val groupAuthor = groupItems.firstOrNull()?.bookAuthor?.ifBlank { bookAuthor } ?: bookAuthor
                    val authorPart = if (groupAuthor.isNotBlank()) " - $groupAuthor" else ""
                    md.appendLine("\n## 📖 《$groupTitle》$authorPart")
                    groupItems.forEachIndexed { i, h ->
                        val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(h.createdAt))
                        val chTitle = h.chapterTitle.ifBlank { "챕터/페이지 ${h.chapter + 1}" }
                        val typeName = if (h.kind == 1) "🔖 북마크" else "🖍 하이라이트"
                        md.appendLine("\n### ${i + 1}. $typeName - $chTitle (`$itemDate`)")
                        md.appendLine("> ${h.quote.trim()}\n")
                        if (h.note.isNotBlank()) {
                            md.appendLine("✍ **독서 메모**: ${h.note.trim()}\n")
                        }
                    }
                }
            } else {
                highlights.forEachIndexed { i, h ->
                    val itemTitle = h.bookTitle.ifBlank { bookTitle }
                    val itemAuthor = h.bookAuthor.ifBlank { bookAuthor }
                    val authorSuffix = if (itemAuthor.isNotBlank()) " ($itemAuthor)" else ""
                    val bookHeader = if (includeBookInfo && distinctBooks.size > 1) "《$itemTitle》$authorSuffix | " else ""

                    val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(h.createdAt))
                    val chTitle = h.chapterTitle.ifBlank { "챕터/페이지 ${h.chapter + 1}" }
                    val typeName = if (h.kind == 1) "🔖 북마크" else "🖍 하이라이트"
                    md.appendLine("\n### ${i + 1}. $typeName - $bookHeader$chTitle (`$itemDate`)")
                    md.appendLine("> ${h.quote.trim()}\n")
                    if (h.note.isNotBlank()) {
                        md.appendLine("✍ **독서 메모**: ${h.note.trim()}\n")
                    }
                }
            }
            zos.write(md.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        if (zipFile.exists() && zipFile.length() > 0L) zipFile else null
    } catch (_: Throwable) {
        null
    }

    fun sendHighlightsZipEmail(
        ctx: Context,
        toEmail: String,
        bookTitle: String,
        bookAuthor: String,
        highlights: List<HighlightEntity>,
        includeBookInfo: Boolean = true
    ): Boolean {
        val zipFile = createHighlightsZip(ctx, bookTitle, bookAuthor, highlights, includeBookInfo) ?: return false
        val zipUri = ShareFileProvider.uriForFile(ctx, zipFile)
        val distinctBooks = highlights.map { (it.bookTitle.ifBlank { bookTitle }).trim() to (it.bookAuthor.ifBlank { bookAuthor }).trim() }.distinct()

        val subject = if (distinctBooks.size > 1) {
            "[Aether SAGA] 《${distinctBooks[0].first}》 외 ${distinctBooks.size - 1}권 - 독서 하이라이트 및 메모 (${highlights.size}건)"
        } else {
            val singleTitle = distinctBooks.firstOrNull()?.first?.ifBlank { bookTitle } ?: bookTitle
            val singleAuthor = distinctBooks.firstOrNull()?.second?.ifBlank { bookAuthor } ?: bookAuthor
            val authorPart = if (singleAuthor.isNotBlank()) " ($singleAuthor)" else ""
            "[Aether SAGA] 《$singleTitle》$authorPart - 독서 하이라이트 및 메모 (${highlights.size}건)"
        }

        val hlCount = highlights.count { it.kind == 0 }
        val bmCount = highlights.count { it.kind == 1 }
        val memoCount = highlights.count { it.note.isNotBlank() }
        val dateStr = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN).format(Date())

        val body = buildString {
            appendLine("안녕하세요.")
            appendLine("Aether SAGA 전자책 뷰어에서 발송된 독서 기록 및 하이라이트 모음입니다.")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📖 [선택 도서 및 기록 요약]")
            if (distinctBooks.size > 1) {
                appendLine("• 포함 도서 (${distinctBooks.size}권):")
                distinctBooks.forEachIndexed { idx, b ->
                    val bCount = highlights.count { it.bookTitle == b.first || (it.bookTitle.isBlank() && b.first == bookTitle) }
                    val auth = if (b.second.isNotBlank()) " (${b.second})" else ""
                    appendLine("  ${idx + 1}. 《${b.first}》$auth - ${bCount}건")
                }
            } else {
                val singleTitle = distinctBooks.firstOrNull()?.first?.ifBlank { bookTitle } ?: bookTitle
                val singleAuthor = distinctBooks.firstOrNull()?.second?.ifBlank { bookAuthor } ?: bookAuthor
                appendLine("• 도서명: 《$singleTitle》")
                if (singleAuthor.isNotBlank()) {
                    appendLine("• 저자: $singleAuthor")
                }
            }
            appendLine("• 총 전송 기록: 하이라이트 ${hlCount}건, 북마크 ${bmCount}건" + (if (memoCount > 0) ", 작성 메모 ${memoCount}건" else "") + " (총 ${highlights.size}건)")
            appendLine("• 전송 일시: $dateStr")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("📝 [주요 하이라이트 및 메모 미리보기]")
            val previewItems = highlights.take(5)
            previewItems.forEachIndexed { idx, h ->
                val chName = h.chapterTitle.ifBlank { "본문" }
                val prefix = if (h.kind == 1) "🔖 [북마크]" else "🖍 [하이라이트]"
                val itemTitle = h.bookTitle.ifBlank { bookTitle }
                val itemAuthor = h.bookAuthor.ifBlank { bookAuthor }
                val bookHeader = if (includeBookInfo) {
                    val auth = if (itemAuthor.isNotBlank()) "($itemAuthor)" else ""
                    "📖 《$itemTitle》$auth | "
                } else ""

                appendLine("${idx + 1}. $bookHeader$prefix $chName")
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
        return AndroidShareHelper.openEmailAppWithFiles(ctx, toEmail, subject, body, listOf(zipUri), "application/zip")
    }
}
