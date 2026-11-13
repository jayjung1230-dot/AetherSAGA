package com.aether.reader.features.memo

import android.content.Context
import android.graphics.*
import com.aether.reader.data.BookEntity
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Evolution Timeline Engine
 * Assembles and renders a 9:16 Instagram Story visual report depicting
 * how a reader's perspective developed chronologically throughout a book.
 */
object EvolutionTimelineEngine {

    data class TimelineStep(
        val stepLabel: String,
        val pctLabel: String,
        val dateLabel: String,
        val quote: String,
        val memo: String,
        val colorRgb: Int
    )

    data class EvolutionTimelinePayload(
        val bookTitle: String,
        val bookAuthor: String,
        val readingPeriodStr: String,
        val isCompleted: Boolean,
        val steps: List<TimelineStep>,
        val coverBitmap: Bitmap? = null
    )

    /**
     * Extracts chronological thinking steps from the book's highlights.
     */
    fun buildPayload(
        book: BookEntity,
        highlights: List<HighlightEntity>,
        cachedCoverBitmap: Bitmap? = null
    ): EvolutionTimelinePayload? {
        val sortedHl = highlights.sortedWith(
            compareBy<HighlightEntity> { it.chapter }.thenBy { it.startChar }.thenBy { it.createdAt }
        )
        if (sortedHl.isEmpty()) return null

        val withNotes = sortedHl.filter { it.note.isNotBlank() }
        val rawSelected = if (withNotes.size >= 3) {
            pickRepresentativeSteps(withNotes, 4)
        } else {
            pickRepresentativeSteps(sortedHl, 4)
        }

        val stepColors = intArrayOf(
            0xFF2563EB.toInt(), // Blue
            0xFF059669.toInt(), // Green
            0xFFD97706.toInt(), // Amber
            0xFFE11D48.toInt()  // Rose
        )

        val dateFmt = if (L10n.isKorean) SimpleDateFormat("M월 d일", Locale.KOREAN) else SimpleDateFormat("MMM d", Locale.US)

        val steps = rawSelected.mapIndexed { i, h ->
            val pct = (h.progress * 100).toInt().coerceIn(0, 100)
            val stepName = when (i) {
                0 -> L10n.t("Step 1 · 첫 발자국 (시작의 의문)", "Step 1 · First Footstep (Opening)")
                1 -> L10n.t("Step 2 · 생각의 충돌 (탐색과 갈등)", "Step 2 · Collision of Thoughts (Exploration)")
                2 -> L10n.t("Step 3 · 전환점 (결정적 깨달음)", "Step 3 · Turning Point (Breakthrough)")
                else -> if (book.progress >= 0.95f) L10n.t("Step 4 · 완독 에필로그 (내 삶의 다짐)", "Step 4 · Epilogue (Life Resolution)")
                        else L10n.t("Step 4 · 현재의 성찰 (성장의 여정)", "Step 4 · Current Reflection (Growth)")
            }
            val pctStr = if (i == rawSelected.size - 1 && book.progress >= 0.95f) L10n.t("100% 완독 🏆", "100% Completed 🏆") else "${pct}%"
            val dStr = if (h.createdAt > 0) dateFmt.format(Date(h.createdAt)) else ""

            TimelineStep(
                stepLabel = stepName,
                pctLabel = pctStr,
                dateLabel = dStr,
                quote = h.quote.replace("\n", " ").take(130),
                memo = h.note.ifBlank { L10n.t("이 챕터의 핵심 문장을 통해 내면의 생각을 정리하다.", "Reflecting deeply on the core message of this chapter.") }.replace("\n", " ").take(65),
                colorRgb = stepColors[i % stepColors.size]
            )
        }

        val periodStr = if (book.progress >= 0.95f) {
            L10n.t("완독 달성 · 성찰의 여정", "Book Completed · Journey of Reflection")
        } else {
            L10n.t("독서 진행 중 (${(book.progress * 100).toInt()}%)", "In Progress (${(book.progress * 100).toInt()}%)")
        }

        return EvolutionTimelinePayload(
            bookTitle = book.title,
            bookAuthor = book.author.ifBlank { L10n.t("저자 미상", "Unknown Author") },
            readingPeriodStr = periodStr,
            isCompleted = book.progress >= 0.95f,
            steps = steps,
            coverBitmap = cachedCoverBitmap
        )
    }

    private fun pickRepresentativeSteps(list: List<HighlightEntity>, maxCount: Int): List<HighlightEntity> {
        if (list.size <= maxCount) return list
        val result = mutableListOf<HighlightEntity>()
        result.add(list.first())
        val step = (list.size - 1).toDouble() / (maxCount - 1)
        for (i in 1 until maxCount - 1) {
            val idx = (step * i).roundToInt().coerceIn(1, list.size - 2)
            if (!result.contains(list[idx])) result.add(list[idx])
        }
        result.add(list.last())
        return result
    }

    /**
     * Renders a 1080x1920 Instagram Story (9:16).
     */
    fun renderStoryCard(context: Context, payload: EvolutionTimelinePayload): Bitmap {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Luxury off-white clean paper background
        c.drawColor(0xFFF8FAFC.toInt())

        // Top Brand Ribbon
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFFD97706.toInt(), 0xFFE11D48.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), 12f, barPaint)

        // Header Badge
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFEF3C7.toInt() }
        val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD97706.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val badgeRect = RectF(70f, 65f, 310f, 115f)
        c.drawRoundRect(badgeRect, 25f, 25f, badgeBg)
        c.drawRoundRect(badgeRect, 25f, 25f, badgeBorder)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB45309.toInt()
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("🌱 생각의 진화 연대기", "🌱 Idea Evolution Story"), 94f, 98f, badgeTextPaint)

        val heroTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("한 권의 책이 바꾼 나의 생각들", "Thoughts Transformed by a Single Book"), 70f, 168f, heroTitlePaint)

        val heroSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 22f
        }
        c.drawText(L10n.t("처음 책장을 펼친 날부터 완독까지, 내 성찰의 변화를 기록하다", "Tracing the growth of my perspectives from opening to completion"), 70f, 206f, heroSubPaint)

        // Book Header Card
        val bookCardRect = RectF(70f, 235f, 1010f, 375f)
        val bookCardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bookCardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(bookCardRect, 18f, 18f, bookCardBg)
        c.drawRoundRect(bookCardRect, 18f, 18f, bookCardBorder)

        // Draw Cover or dummy
        if (payload.coverBitmap != null) {
            val coverRect = RectF(95f, 255f, 175f, 355f)
            c.drawBitmap(payload.coverBitmap, null, coverRect, null)
        } else {
            val dummyRect = RectF(95f, 255f, 175f, 355f)
            val dummyBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
            c.drawRoundRect(dummyRect, 8f, 8f, dummyBg)
            val dummyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText("BOOK", 135f, 312f, dummyText)
        }

        val bTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText("《${payload.bookTitle}》", 195f, 296f, bTitlePaint)

        val bAuthorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 21f
        }
        c.drawText("${payload.bookAuthor}  •  ${payload.readingPeriodStr}", 195f, 338f, bAuthorPaint)

        // Vertical Spine Line
        val spineX = 125f
        val spineTop = 415f
        val spineBottom = 1680f
        val spinePaint = Paint().apply {
            color = 0xFFCBD5E1.toInt()
            strokeWidth = 4f
        }
        c.drawLine(spineX, spineTop, spineX, spineBottom, spinePaint)

        // Render Steps
        var cardY = 415f
        val cardSpacing = 310f

        val stepTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 19f
            textAlign = Paint.Align.RIGHT
        }
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF334155.toInt()
            textSize = 21f
        }
        val memoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 20.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        for (s in payload.steps.take(4)) {
            val nodeY = cardY + 45f
            // Outer Ring
            val outerNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.colorRgb
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val whiteRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.colorRgb }

            c.drawCircle(spineX, nodeY, 14f, whiteRing)
            c.drawCircle(spineX, nodeY, 14f, outerNodePaint)
            c.drawCircle(spineX, nodeY, 6f, innerDot)

            // Step Card
            val cx0 = 175f
            val cy0 = cardY
            val cx1 = 1010f
            val cy1 = cardY + 275f
            val cRect = RectF(cx0, cy0, cx1, cy1)
            c.drawRoundRect(cRect, 18f, 18f, bookCardBg)
            c.drawRoundRect(cRect, 18f, 18f, bookCardBorder)

            // Header line
            stepTitlePaint.color = s.colorRgb
            c.drawText(s.stepLabel, cx0 + 24f, cy0 + 38f, stepTitlePaint)
            val datePct = if (s.dateLabel.isNotBlank()) "${s.dateLabel} · ${s.pctLabel}" else s.pctLabel
            c.drawText(datePct, cx1 - 24f, cy0 + 38f, pctPaint)

            val divPaint = Paint().apply { color = 0xFFF1F5F9.toInt(); strokeWidth = 1.5f }
            c.drawLine(cx0 + 24f, cy0 + 58f, cx1 - 24f, cy0 + 58f, divPaint)

            // Quote
            drawMultiline(c, "“${s.quote}”", cx0 + 24f, cy0 + 92f, cx1 - cx0 - 48f, quotePaint, maxLines = 2, lineSpacing = 7f)

            // Memo Box
            val mRect = RectF(cx0 + 24f, cy1 - 85f, cx1 - 24f, cy1 - 18f)
            val mBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt() }
            val mBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            c.drawRoundRect(mRect, 10f, 10f, mBg)
            c.drawRoundRect(mRect, 10f, 10f, mBorder)

            val memoText = "✎ " + L10n.t("나의 성찰: ", "Reflection: ") + s.memo
            c.drawText(memoText.take(48), cx0 + 40f, cy1 - 44f, memoPaint)

            cardY += cardSpacing
        }

        // Footer
        c.drawLine(70f, 1780f, 1010f, 1780f, spinePaint)
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 21f
        }
        c.drawText("Aether SAGA · " + L10n.t("생각의 진화 타임라인", "Idea Evolution Story"), 70f, 1826f, footerPaint)

        val footerMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText(L10n.t("총 ${payload.steps.size}개의 생각 궤적", "${payload.steps.size} Thinking Milestones"), 1010f, 1826f, footerMeta)

        return bmp
    }

    private fun drawMultiline(c: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint, maxLines: Int, lineSpacing: Float) {
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
