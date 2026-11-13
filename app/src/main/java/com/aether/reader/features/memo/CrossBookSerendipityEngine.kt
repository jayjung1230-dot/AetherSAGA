package com.aether.reader.features.memo

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aether.reader.data.BookEntity
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Cross-Book Serendipity Engine
 * Detects intellectual connections and shared themes between notes across different books
 * and renders high-resolution 1:1 SNS share cards.
 */
object CrossBookSerendipityEngine {

    data class CrossBookItem(
        val bookTitle: String,
        val bookAuthor: String,
        val readDateStr: String,
        val quote: String,
        val note: String,
        val isCurrentBook: Boolean = false
    )

    data class CrossBookPayload(
        val pastBook: CrossBookItem,
        val currentBook: CrossBookItem,
        val connectionKeyword: String,
        val totalLibraryNotes: Int
    )

    private val STOPWORDS = setOf(
        "것", "수", "등", "이", "그", "저", "에", "를", "을", "은", "는", "가", "의", "로", "으로",
        "과", "와", "도", "하다", "있다", "되다", "않다", "하지만", "그리고", "때문", "대한", "통해",
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "is", "are"
    )

    /**
     * Finds the most inspiring connection between the current book and past books in the library.
     */
    fun findBestMatch(
        currentBookPath: String,
        currentBookTitle: String,
        currentBookAuthor: String,
        currentHighlights: List<HighlightEntity>,
        allHighlights: List<HighlightEntity>,
        allBooks: List<BookEntity>
    ): CrossBookPayload? {
        val otherHighlights = allHighlights.filter {
            it.bookPath != currentBookPath && "file://${it.bookPath}" != currentBookPath
        }
        if (otherHighlights.isEmpty()) return null

        val currentCandidate = currentHighlights.firstOrNull { it.note.isNotBlank() }
            ?: currentHighlights.firstOrNull()
            ?: return null

        val currentKeywords = extractKeywords(currentCandidate.quote + " " + currentCandidate.note)

        // Score other highlights by keyword overlap
        var bestOther: HighlightEntity? = null
        var bestScore = -1
        var matchedKeyword = ""

        for (other in otherHighlights) {
            val otherKeywords = extractKeywords(other.quote + " " + other.note)
            val overlap = currentKeywords.intersect(otherKeywords)
            val score = overlap.size * 3 + (if (other.note.isNotBlank()) 2 else 0)
            if (score > bestScore) {
                bestScore = score
                bestOther = other
                matchedKeyword = overlap.firstOrNull() ?: ""
            }
        }

        val targetOther = bestOther ?: otherHighlights.firstOrNull { it.note.isNotBlank() } ?: otherHighlights.first()
        val otherBook = allBooks.find { it.path == targetOther.bookPath || "file://${it.path}" == targetOther.bookPath }
        val otherTitle = otherBook?.title ?: targetOther.bookTitle.ifBlank { L10n.t("이전 완독 도서", "Past Read Book") }
        val otherAuthor = otherBook?.author ?: targetOther.bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }

        val keyword = if (matchedKeyword.isNotBlank()) {
            matchedKeyword
        } else {
            L10n.t("인간 본성과 통찰", "Human Nature & Insight")
        }

        val dateFmt = if (L10n.isKorean) SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN) else SimpleDateFormat("MMM d, yyyy", Locale.US)
        val pastDateStr = dateFmt.format(Date(targetOther.createdAt))
        val curDateStr = dateFmt.format(Date(currentCandidate.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()))

        return CrossBookPayload(
            pastBook = CrossBookItem(
                bookTitle = otherTitle,
                bookAuthor = otherAuthor,
                readDateStr = pastDateStr,
                quote = targetOther.quote.take(160),
                note = targetOther.note.ifBlank { L10n.t("책을 읽으며 깊은 울림을 남겼던 구절", "A passage that left a deep resonance.") },
                isCurrentBook = false
            ),
            currentBook = CrossBookItem(
                bookTitle = currentBookTitle,
                bookAuthor = currentBookAuthor,
                readDateStr = curDateStr,
                quote = currentCandidate.quote.take(160),
                note = currentCandidate.note.ifBlank { L10n.t("오늘 읽으며 새롭게 발견한 지적 연결점", "A newly discovered insight today.") },
                isCurrentBook = true
            ),
            connectionKeyword = keyword,
            totalLibraryNotes = allHighlights.size
        )
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 2 && !STOPWORDS.contains(it) }
            .toSet()
    }

    /**
     * Renders a 1080x1080 aesthetic Square Feed Card for Instagram/Threads/X.
     */
    fun renderCrossBookCard(payload: CrossBookPayload, aiInsight: String? = null): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Deep midnight obsidian background
        c.drawColor(0xFF111827.toInt())

        // Top Accent Gradient Bar
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), 10f, barPaint)

        // Header Badge
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E3A8A.toInt()
            style = Paint.Style.FILL
        }
        val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF60A5FA.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val badgeRect = RectF(60f, 48f, 290f, 94f)
        c.drawRoundRect(badgeRect, 23f, 23f, badgeBgPaint)
        c.drawRoundRect(badgeRect, 23f, 23f, badgeStrokePaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("✦ 독서 세렌디피티", "✦ Book Serendipity"), 82f, 79f, badgeTextPaint)

        // Header Title & Subtitle
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1F5F9.toInt()
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("두 권의 책이 하나의 생각으로 만나는 순간", "When Two Books Meet in a Single Thought"), 60f, 138f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 20f
        }
        c.drawText(L10n.t("시간을 건너뛰어 내 서재의 서로 다른 두 책이 나눈 지적인 대화", "An intellectual dialogue between two different books in my library"), 60f, 172f, subPaint)

        // --- Box 1: Past Book Card ---
        drawBookCard(c, 60f, 205f, 1020f, 495f, payload.pastBook, isPast = true)

        // --- Center Connection Badge (AI Insight or Local Keyword) ---
        if (!aiInsight.isNullOrBlank()) {
            val aiBadgeW = 960f
            val aiBadgeH = 80f
            val aiBx = 60f
            val aiBy = 502f
            val aiRect = RectF(aiBx, aiBy, aiBx + aiBadgeW, aiBy + aiBadgeH)
            val aiBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4338CA.toInt() }
            val aiBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF818CF8.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
            c.drawRoundRect(aiRect, 16f, 16f, aiBgPaint)
            c.drawRoundRect(aiRect, 16f, 16f, aiBorderPaint)

            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC7D2FE.toInt(); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
            c.drawText("✨ GEMINI AI " + L10n.t("지적 연결 통찰", "Intellectual Synergy"), 82f, aiBy + 27f, tagPaint)

            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 19f; typeface = Typeface.DEFAULT_BOLD }
            val cleanAi = "“" + aiInsight.trim().take(150) + "”"
            val aiLayout = StaticLayout.Builder.obtain(cleanAi, 0, cleanAi.length, bodyPaint, (aiBadgeW - 44).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .build()
            c.save()
            c.translate(82f, aiBy + 33f)
            aiLayout.draw(c)
            c.restore()
        } else {
            val kwBadgeW = 460f
            val kwBadgeH = 58f
            val kwBx = (w - kwBadgeW) / 2f
            val kwBy = 512f
            val kwRect = RectF(kwBx, kwBy, kwBx + kwBadgeW, kwBy + kwBadgeH)
            val kwBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD97706.toInt()
            }
            val kwStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFBBF24.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            c.drawRoundRect(kwRect, 29f, 29f, kwBgPaint)
            c.drawRoundRect(kwRect, 29f, 29f, kwStrokePaint)

            val kwTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val kwLabel = L10n.t("⚡ 핵심 연결 고리: ", "⚡ Core Connection: ") + payload.connectionKeyword
            c.drawText(kwLabel, w / 2f, kwBy + 37f, kwTextPaint)
        }

        // --- Box 2: Current Book Card ---
        drawBookCard(c, 60f, 588f, 1020f, 878f, payload.currentBook, isPast = false)

        // Footer Divider & Text
        val footerLinePaint = Paint().apply { color = 0xFF334155.toInt(); strokeWidth = 1.5f }
        c.drawLine(60f, 920f, 1020f, 920f, footerLinePaint)

        val footerBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 21f
        }
        c.drawText("Aether SAGA · " + L10n.t("독서 세렌디피티 엔진", "Cross-Book Serendipity Engine"), 60f, 960f, footerBrandPaint)

        val footerMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText(L10n.t("서재 독서 기록 ${payload.totalLibraryNotes}건 매칭", "${payload.totalLibraryNotes} library notes matched"), 1020f, 960f, footerMetaPaint)

        return bmp
    }

    private fun drawBookCard(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, item: CrossBookItem, isPast: Boolean) {
        val cardRect = RectF(x0, y0, x1, y1)
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(cardRect, 18f, 18f, cardBgPaint)
        c.drawRoundRect(cardRect, 18f, 18f, cardBorderPaint)

        // Meta Header
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 18f
        }
        val prefix = if (isPast) L10n.t("📖 과거의 독서 기록 (${item.readDateStr})", "📖 Past Reading Record (${item.readDateStr})")
                     else L10n.t("📖 오늘의 독서 기록 (${item.readDateStr})", "📖 Today's Reading Record (${item.readDateStr})")
        c.drawText(prefix, x0 + 26f, y0 + 36f, metaPaint)

        val bookTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isPast) 0xFF60A5FA.toInt() else 0xFFF87171.toInt()
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
        }
        val authPart = if (item.bookAuthor.isNotBlank()) " — ${item.bookAuthor}" else ""
        c.drawText("《${item.bookTitle}》$authPart", x0 + 26f, y0 + 68f, bookTitlePaint)

        // Inner Divider
        val divPaint = Paint().apply { color = 0xFF334155.toInt(); strokeWidth = 1f }
        c.drawLine(x0 + 26f, y0 + 82f, x1 - 26f, y0 + 82f, divPaint)

        // Quote
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 22f
        }
        drawMultilineText(c, "“${item.quote}”", x0 + 26f, y0 + 115f, x1 - x0 - 52f, quotePaint, maxLines = 3, lineSpacing = 8f)

        // User Memo Box
        val memoBoxRect = RectF(x0 + 26f, y1 - 74f, x1 - 26f, y1 - 20f)
        val memoBoxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        c.drawRoundRect(memoBoxRect, 10f, 10f, memoBoxBg)

        val memoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFDE047.toInt()
            textSize = 20.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val memoText = "✎ " + L10n.t("나의 생각: ", "Note: ") + item.note.replace("\n", " ").take(48)
        c.drawText(memoText, x0 + 44f, y1 - 42f, memoPaint)
    }

    private fun drawMultilineText(c: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint, maxLines: Int, lineSpacing: Float) {
        var curY = y
        var lineCount = 0
        val words = text.split(" ")
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxWidth) {
                c.drawText(currentLine, x, curY, paint)
                curY += paint.textSize + lineSpacing
                lineCount++
                if (lineCount >= maxLines - 1) {
                    val remaining = text.substring(min(text.length, currentLine.length + 1)).take(40) + "..."
                    c.drawText(remaining, x, curY, paint)
                    return
                }
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty() && lineCount < maxLines) {
            c.drawText(currentLine, x, curY, paint)
        }
    }
}
