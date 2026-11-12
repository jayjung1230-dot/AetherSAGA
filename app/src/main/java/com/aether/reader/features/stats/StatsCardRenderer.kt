package com.aether.reader.features.stats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.L10n
import java.io.File
import java.util.Locale

/**
 * Dedicated renderer module for 1:1 Square (1080x1080) and 9:16 Story (1080x1920)
 * Reading Stats Report Cards, including Currently Reading Books ("Reading Now") progress & covers.
 */
object StatsCardRenderer {

    private fun drawSectionBadge(
        c: Canvas,
        x: Float,
        y: Float,
        label: String,
        bgColor: Int,
        fgColor: Int,
        size: Float = 30f
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val rect = RectF(x, y, x + size * 1.45f, y + size)
        c.drawRoundRect(rect, 7f, 7f, bgPaint)

        val txtPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            textSize = size * 0.52f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = rect.centerY() - (txtPaint.descent() + txtPaint.ascent()) / 2f
        c.drawText(label, rect.centerX(), textY, txtPaint)
    }

    private fun decodeSampledCover(coverPath: String?, reqW: Int, reqH: Int): Bitmap? {
        if (coverPath.isNullOrBlank()) return null
        return try {
            val f = File(coverPath)
            if (!f.exists() || f.length() <= 0L) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var inSampleSize = 1
            while ((opts.outHeight / inSampleSize) > reqH * 2 && (opts.outWidth / inSampleSize) > reqW * 2) {
                inSampleSize *= 2
            }
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
                this.inPreferredConfig = Bitmap.Config.RGB_565
            })
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawMiniBookCover(
        c: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        title: String,
        format: String,
        coverPath: String?,
        fallbackBgColor: Int = 0xFF1E3A8A.toInt()
    ) {
        // 1. Soft drop shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            style = Paint.Style.FILL
        }
        c.drawRoundRect(RectF(x + 3f, y + 4f, x + w + 3f, y + h + 4f), 6f, 6f, shadowPaint)

        val rect = RectF(x, y, x + w, y + h)
        val bmp = decodeSampledCover(coverPath, w.toInt(), h.toInt())
        if (bmp != null) {
            try {
                val clip = Path().apply { addRoundRect(rect, 6f, 6f, Path.Direction.CW) }
                c.save()
                c.clipPath(clip)
                c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } else {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fallbackBgColor
                style = Paint.Style.FILL
            }
            c.drawRoundRect(rect, 6f, 6f, bgPaint)

            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFBFDBFE.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            c.drawRect(x + 6f, y + 6f, x + w - 6f, y + h - 6f, framePaint)

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (w * 0.18f).coerceAtLeast(11f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val shortTitle = title.trim().take(5)
            c.drawText(shortTitle, x + w / 2f, y + h * 0.38f, titlePaint)

            c.drawLine(x + 12f, y + h * 0.50f, x + w - 12f, y + h * 0.50f, framePaint)

            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFDBEAFE.toInt()
                textSize = (w * 0.13f).coerceAtLeast(9f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(format.ifBlank { "BOOK" }.uppercase(Locale.ROOT).take(5), x + w / 2f, y + h * 0.68f, subPaint)
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4464748B
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        c.drawRoundRect(rect, 6f, 6f, borderPaint)
    }

    /**
     * Renders a 1:1 square (1080x1080) Reading Stats Card including:
     * - 4 Metric Summary Cards (Total Time/Pages, Reading Now, Completed, Streak)
     * - Reading Trend Chart
     * - Currently Reading Book Card (with Cover, Progress Bar, and Companion Book)
     * - Reading Habit Insight & Completed Books Summary
     */
    fun renderStatsCard(payload: AndroidShareHelper.StatsSharePayload): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawColor(0xFFF8FAFC.toInt())

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 2f
        }
        c.drawRect(40f, 40f, w - 40f, h - 40f, borderPaint)

        borderPaint.color = 0xFFCBD5E1.toInt()
        borderPaint.strokeWidth = 1f
        c.drawRect(50f, 50f, w - 50f, h - 50f, borderPaint)

        // 1. Header
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
        }
        c.drawText("Aether SAGA", 80f, 92f, brandPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 32f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(payload.periodName, 80f, 134f, titlePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(payload.periodSubLabel.take(48), 80f, 166f, subPaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 2f
        }
        c.drawLine(80f, 182f, w - 80f, 182f, rulePaint)

        // 2. Metrics Row (4 cards: Total, Reading Now, Completed, Streak)
        val cardGap = 15f
        val cardW = (w - 160f - cardGap * 3f) / 4f
        val cardH = 92f
        val cy = 196f

        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val cardLblPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val cardValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        val readingCount = payload.currentlyReadingBooks.size
        val readingValStr = if (L10n.isKorean) "${readingCount}권 진행 중" else "$readingCount active"
        val compCount = payload.completedBooksSummary.size
        val compValStr = if (L10n.isKorean) "${compCount}권 달성" else "$compCount done"
        val cleanStreak = payload.streakText.replace("🔥", "").trim().let {
            if (L10n.isKorean && !it.endsWith("달성")) "$it 달성" else it
        }

        val metrics = listOf(
            Triple(payload.totalLabel.take(10), payload.totalValue.take(11), 0xFF1F5E9C.toInt()),
            Triple(L10n.t("읽고 있는 책", "Reading Now"), readingValStr, 0xFF2563EB.toInt()),
            Triple(L10n.t("완독 도서", "Completed"), compValStr, 0xFF059669.toInt()),
            Triple(L10n.t("연속 독서", "Streak"), cleanStreak.take(11), 0xFFD97706.toInt())
        )

        for ((idx, m) in metrics.withIndex()) {
            val cx = 80f + idx * (cardW + cardGap)
            c.drawRoundRect(RectF(cx, cy, cx + cardW, cy + cardH), 10f, 10f, cardBgPaint)
            c.drawRoundRect(RectF(cx, cy, cx + cardW, cy + cardH), 10f, 10f, cardBorderPaint)
            c.drawText(m.first, cx + 16f, cy + 32f, cardLblPaint)
            cardValPaint.color = m.third
            c.drawText(m.second, cx + 16f, cy + 70f, cardValPaint)
        }

        // 3. Chart Container Card (y: 304, h: 300)
        val chartY = 304f
        val chartH = 300f
        c.drawRoundRect(RectF(80f, chartY, w - 80f, chartY + chartH), 12f, 12f, cardBgPaint)
        c.drawRoundRect(RectF(80f, chartY, w - 80f, chartY + chartH), 12f, 12f, cardBorderPaint)

        drawSectionBadge(c, 105f, chartY + 14f, L10n.t("추이", "TREND"), 0xFFEFF6FF.toInt(), 0xFF1F5E9C.toInt(), 28f)

        val chartHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(L10n.t("독서 추이 (${payload.metricUnit})", "Reading Trend (${payload.metricUnit})"), 154f, chartY + 35f, chartHeaderPaint)

        if (payload.dailyAvgText.isNotBlank()) {
            val avgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 18f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(payload.dailyAvgText, w - 105f, chartY + 35f, avgPaint)
        }

        val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1F5F9.toInt()
            strokeWidth = 1.5f
        }
        c.drawLine(105f, chartY + 52f, w - 105f, chartY + 52f, sepLinePaint)

        val pts = payload.points
        if (pts.isNotEmpty()) {
            val plotX = 135f
            val plotY = chartY + 84f
            val plotW = w - 270f
            val plotH = 155f
            val maxV = if (payload.yMax > 0) payload.yMax.toFloat() else 10f
            val stepX = if (pts.size > 1) plotW / (pts.size - 1) else plotW
            val barW = (plotW / (pts.size * 2.1f)).coerceIn(14f, 38f)

            for (gy in floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                val glY = plotY + plotH - (plotH * gy)
                c.drawLine(plotX, glY, plotX + plotW, glY, sepLinePaint)
            }

            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val valTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E293B.toInt()
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val lblTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF475569.toInt()
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }

            val trendPoints = ArrayList<PointF>()
            for ((i, p) in pts.withIndex()) {
                val bx = plotX + (i * stepX)
                val bh = (plotH * (p.value.toFloat() / maxV)).coerceIn(0f, plotH)
                val by = plotY + plotH - bh

                barPaint.color = if (i >= pts.size - 2) 0xFFBFDBFE.toInt() else 0xFFE2E8F0.toInt()
                c.drawRoundRect(RectF(bx - barW / 2f, by, bx + barW / 2f, plotY + plotH), 6f, 6f, barPaint)

                if (p.value > 0) {
                    val displayStr = if (p.displayValue.isNotBlank()) p.displayValue else "${p.value}"
                    c.drawText(displayStr, bx, by - 12f, valTextPaint)
                }

                c.drawText(p.label, bx, plotY + plotH + 26f, lblTextPaint)
                trendPoints.add(PointF(bx, by))
            }

            val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F5E9C.toInt()
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F5E9C.toInt()
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            for (i in 0 until trendPoints.size - 1) {
                c.drawLine(trendPoints[i].x, trendPoints[i].y, trendPoints[i + 1].x, trendPoints[i + 1].y, trendPaint)
                c.drawCircle(trendPoints[i].x, trendPoints[i].y, 4f, dotPaint)
                c.drawCircle(trendPoints[i].x, trendPoints[i].y, 4f, dotBorderPaint)
            }
            if (trendPoints.isNotEmpty()) {
                val lastPt = trendPoints.last()
                c.drawCircle(lastPt.x, lastPt.y, 4f, dotPaint)
                c.drawCircle(lastPt.x, lastPt.y, 4f, dotBorderPaint)
            }
        }

        // 4. Currently Reading Book Card (y: 620, h: 195)
        val rbY = 620f
        val rbH = 195f
        val rbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF93C5FD.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(RectF(80f, rbY, w - 80f, rbY + rbH), 12f, 12f, cardBgPaint)
        c.drawRoundRect(RectF(80f, rbY, w - 80f, rbY + rbH), 12f, 12f, rbBorderPaint)

        drawSectionBadge(c, 105f, rbY + 13f, L10n.t("독서", "READ"), 0xFF2563EB.toInt(), Color.WHITE, 28f)
        val rbHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(L10n.t("현재 읽고 있는 도서 (Reading Now)", "Currently Reading (Reading Now)"), 154f, rbY + 34f, rbHeaderPaint)

        if (payload.currentlyReadingBooks.size > 1) {
            val extraCount = payload.currentlyReadingBooks.size - 1
            val extraPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(L10n.t("외 ${extraCount}권 함께 읽는 중", "+$extraCount more reading"), w - 105f, rbY + 34f, extraPaint)
        }
        c.drawLine(105f, rbY + 48f, w - 105f, rbY + 48f, sepLinePaint)

        if (payload.currentlyReadingBooks.isNotEmpty()) {
            val primary = payload.currentlyReadingBooks.first()
            drawMiniBookCover(c, 108f, rbY + 60f, 82f, 116f, primary.title, primary.format, primary.coverPath, 0xFF1E3A8A.toInt())

            // Format Pill
            val fmtBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEFF6FF.toInt(); style = Paint.Style.FILL }
            val fmtBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBFDBFE.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
            val fmtRect = RectF(210f, rbY + 62f, 286f, rbY + 86f)
            c.drawRoundRect(fmtRect, 6f, 6f, fmtBgPaint)
            c.drawRoundRect(fmtRect, 6f, 6f, fmtBorderPaint)
            val fmtTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2563EB.toInt()
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText(primary.format.ifBlank { "EPUB" }.uppercase(Locale.ROOT).take(6), fmtRect.centerX(), fmtRect.centerY() + 5f, fmtTextPaint)

            // Book Title
            val bTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0F172A.toInt()
                textSize = 23f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            c.drawText(primary.title.take(26), 298f, rbY + 82f, bTitlePaint)

            // Author & SubInfo
            val authorStr = primary.author.ifBlank { L10n.t("저자 미상", "Unknown Author") }
            val metaLine = if (primary.subInfo.isNotBlank()) "$authorStr  ·  ${primary.subInfo}" else authorStr
            val bMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 17f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            c.drawText(metaLine.take(44), 210f, rbY + 114f, bMetaPaint)

            // Progress Bar
            val barX = 210f
            val barY = rbY + 132f
            val barW = w - 395f
            val barH = 16f
            val pctInt = (primary.progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E8F0.toInt(); style = Paint.Style.FILL }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt(); style = Paint.Style.FILL }
            c.drawRoundRect(RectF(barX, barY, barX + barW, barY + barH), 8f, 8f, trackPaint)
            val fillW = (barW * (pctInt / 100f)).coerceAtLeast(barH)
            c.drawRoundRect(RectF(barX, barY, barX + fillW, barY + barH), 8f, 8f, fillPaint)

            val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2563EB.toInt()
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            }
            c.drawText("${pctInt}%", barX + barW + 14f, barY + 15f, pctPaint)

            // Companion 2nd book line (if present)
            val companionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF475569.toInt()
                textSize = 15.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            if (payload.currentlyReadingBooks.size >= 2) {
                val sec = payload.currentlyReadingBooks[1]
                val secPct = (sec.progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
                val secAuth = if (sec.author.isNotBlank()) " (${sec.author.take(12)})" else ""
                val secText = L10n.t(
                    "+ 함께 읽는 책: 《${sec.title.take(18)}》$secAuth — 진행률 ${secPct}%",
                    "+ Also reading: 《${sec.title.take(18)}》$secAuth — ${secPct}%"
                )
                c.drawText(secText, 210f, rbY + 174f, companionPaint)
            } else {
                val steadyText = L10n.t(
                    "꾸준한 독서 페이스로 완독을 향해 순항하고 있습니다.",
                    "Making steady progress toward finishing this book."
                )
                c.drawText(steadyText, 210f, rbY + 174f, companionPaint)
            }
        } else {
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 19f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            c.drawText(
                L10n.t("현재 읽고 있는 도서가 없습니다. 서재에서 새로운 책을 펼쳐보세요!", "No active book in progress. Open a new book from your shelf!"),
                108f, rbY + 120f, emptyPaint
            )
        }

        // 5. Insight & Completed Compact Box (y: 830, h: 155)
        val insY = 830f
        val insH = 155f
        val insBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEFF6FF.toInt()
            style = Paint.Style.FILL
        }
        val insBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBFDBFE.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        c.drawRoundRect(RectF(80f, insY, w - 80f, insY + insH), 12f, 12f, insBgPaint)
        c.drawRoundRect(RectF(80f, insY, w - 80f, insY + insH), 12f, 12f, insBorderPaint)

        drawSectionBadge(c, 105f, insY + 13f, L10n.t("분석", "INFO"), 0xFF1F5E9C.toInt(), Color.WHITE, 28f)
        val insHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("독서 습관 인사이트 & 완독 기록", "Reading Habit Insights & Completed Books"), 154f, insY + 34f, insHeaderPaint)

        val insTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val insClean = "• " + payload.insightText.ifBlank { L10n.t("꾸준한 독서 습관이 이어지고 있습니다.", "Building a steady reading habit.") }.replace("\n", " ").take(62)
        c.drawText(insClean, 105f, insY + 70f, insTextPaint)

        val insDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDBEAFE.toInt()
            strokeWidth = 1.2f
        }
        c.drawLine(105f, insY + 92f, w - 105f, insY + 92f, insDivPaint)

        val bListPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val booksText = if (payload.completedBooksSummary.isNotEmpty()) {
            L10n.t("최근 완독 도서:  ", "Completed:  ") + payload.completedBooksSummary.take(3).joinToString("  •  ").take(46)
        } else {
            L10n.t("최근 완독 도서:  현재 읽고 있는 도서의 첫 완독을 응원합니다!", "Completed:  Cheering for your next finished book!")
        }
        c.drawText(booksText, 105f, insY + 128f, bListPaint)

        // 6. Footer (y: 1005)
        c.drawLine(80f, 1005f, w - 80f, 1005f, rulePaint)

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText("Aether SAGA · " + L10n.t("독서 습관 트래커", "Reading Tracker"), 80f, 1034f, footerPaint)

        footerPaint.textAlign = Paint.Align.RIGHT
        c.drawText("journeys through endless stories", w - 80f, 1034f, footerPaint)

        return bmp
    }

    /**
     * Renders a 9:16 vertical (1080x1920) Reading Stats Infographic Card for Stories.
     */
    fun renderStatsStoryCard(payload: AndroidShareHelper.StatsSharePayload): Bitmap {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawColor(0xFFF8FAFC.toInt())

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 2f
        }
        c.drawRect(40f, 40f, w - 40f, h - 40f, borderPaint)

        borderPaint.color = 0xFFCBD5E1.toInt()
        borderPaint.strokeWidth = 1f
        c.drawRect(50f, 50f, w - 50f, h - 50f, borderPaint)

        // 1. Header
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("Aether SAGA", 90f, 98f, brandPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 38f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(payload.periodName, 90f, 148f, titlePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(payload.periodSubLabel.take(48), 90f, 190f, subPaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 2f
        }
        c.drawLine(90f, 212f, w - 90f, 212f, rulePaint)

        // 2. Metrics Row (4 cards)
        val cardGap = 15f
        val cardW = (w - 180f - cardGap * 3f) / 4f
        val cardH = 115f
        val cy = 232f

        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val cardLblPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val cardValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        val readingCount = payload.currentlyReadingBooks.size
        val readingValStr = if (L10n.isKorean) "${readingCount}권 진행 중" else "$readingCount active"
        val compCount = payload.completedBooksSummary.size
        val compValStr = if (L10n.isKorean) "${compCount}권 달성" else "$compCount done"
        val cleanStreak = payload.streakText.replace("🔥", "").trim().let {
            if (L10n.isKorean && !it.endsWith("달성")) "$it 달성" else it
        }

        val metrics = listOf(
            Triple(payload.totalLabel.take(10), payload.totalValue.take(11), 0xFF1F5E9C.toInt()),
            Triple(L10n.t("읽고 있는 책", "Reading Now"), readingValStr, 0xFF2563EB.toInt()),
            Triple(L10n.t("완독 도서", "Completed"), compValStr, 0xFF059669.toInt()),
            Triple(L10n.t("연속 독서", "Streak"), cleanStreak.take(11), 0xFFD97706.toInt())
        )

        for ((idx, m) in metrics.withIndex()) {
            val cx = 90f + idx * (cardW + cardGap)
            c.drawRoundRect(RectF(cx, cy, cx + cardW, cy + cardH), 12f, 12f, cardBgPaint)
            c.drawRoundRect(RectF(cx, cy, cx + cardW, cy + cardH), 12f, 12f, cardBorderPaint)
            c.drawText(m.first, cx + 18f, cy + 40f, cardLblPaint)
            cardValPaint.color = m.third
            c.drawText(m.second, cx + 18f, cy + 86f, cardValPaint)
        }

        // 3. Chart Container Card (y: 372, h: 430)
        val chartY = 372f
        val chartH = 430f
        c.drawRoundRect(RectF(90f, chartY, w - 90f, chartY + chartH), 14f, 14f, cardBgPaint)
        c.drawRoundRect(RectF(90f, chartY, w - 90f, chartY + chartH), 14f, 14f, cardBorderPaint)

        drawSectionBadge(c, 120f, chartY + 18f, L10n.t("추이", "TREND"), 0xFFEFF6FF.toInt(), 0xFF1F5E9C.toInt(), 30f)

        val chartHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(L10n.t("독서 추이 (${payload.metricUnit})", "Reading Trend (${payload.metricUnit})"), 172f, chartY + 41f, chartHeaderPaint)

        if (payload.dailyAvgText.isNotBlank()) {
            val avgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF64748B.toInt()
                textSize = 20f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(payload.dailyAvgText, w - 120f, chartY + 41f, avgPaint)
        }

        val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1F5F9.toInt()
            strokeWidth = 1.5f
        }
        c.drawLine(120f, chartY + 62f, w - 120f, chartY + 62f, sepLinePaint)

        val pts = payload.points
        if (pts.isNotEmpty()) {
            val plotX = 145f
            val plotY = chartY + 105f
            val plotW = w - 290f
            val plotH = 250f
            val maxV = if (payload.yMax > 0) payload.yMax.toFloat() else 10f
            val stepX = if (pts.size > 1) plotW / (pts.size - 1) else plotW
            val barW = (plotW / (pts.size * 2.1f)).coerceIn(18f, 46f)

            for (gy in floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                val glY = plotY + plotH - (plotH * gy)
                c.drawLine(plotX, glY, plotX + plotW, glY, sepLinePaint)
            }

            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val valTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E293B.toInt()
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val lblTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF475569.toInt()
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }

            val trendPoints = ArrayList<PointF>()
            for ((i, p) in pts.withIndex()) {
                val bx = plotX + (i * stepX)
                val bh = (plotH * (p.value.toFloat() / maxV)).coerceIn(0f, plotH)
                val by = plotY + plotH - bh

                barPaint.color = if (i >= pts.size - 2) 0xFFBFDBFE.toInt() else 0xFFE2E8F0.toInt()
                c.drawRoundRect(RectF(bx - barW / 2f, by, bx + barW / 2f, plotY + plotH), 8f, 8f, barPaint)

                if (p.value > 0) {
                    val displayStr = if (p.displayValue.isNotBlank()) p.displayValue else "${p.value}"
                    c.drawText(displayStr, bx, by - 14f, valTextPaint)
                }

                c.drawText(p.label, bx, plotY + plotH + 30f, lblTextPaint)
                trendPoints.add(PointF(bx, by))
            }

            val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F5E9C.toInt()
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1F5E9C.toInt()
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            for (i in 0 until trendPoints.size - 1) {
                c.drawLine(trendPoints[i].x, trendPoints[i].y, trendPoints[i + 1].x, trendPoints[i + 1].y, trendPaint)
                c.drawCircle(trendPoints[i].x, trendPoints[i].y, 5f, dotPaint)
                c.drawCircle(trendPoints[i].x, trendPoints[i].y, 5f, dotBorderPaint)
            }
            if (trendPoints.isNotEmpty()) {
                val lastPt = trendPoints.last()
                c.drawCircle(lastPt.x, lastPt.y, 5f, dotPaint)
                c.drawCircle(lastPt.x, lastPt.y, 5f, dotBorderPaint)
            }
        }

        // 4. Currently Reading Books Section (y: 828, h: 370)
        val rbY = 828f
        val rbH = 370f
        val rbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF93C5FD.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRoundRect(RectF(90f, rbY, w - 90f, rbY + rbH), 14f, 14f, cardBgPaint)
        c.drawRoundRect(RectF(90f, rbY, w - 90f, rbY + rbH), 14f, 14f, rbBorderPaint)

        drawSectionBadge(c, 120f, rbY + 18f, L10n.t("독서", "READ"), 0xFF2563EB.toInt(), Color.WHITE, 30f)
        val rbHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(L10n.t("현재 읽고 있는 도서 (${readingCount}권)", "Currently Reading ($readingCount)"), 172f, rbY + 41f, rbHeaderPaint)

        val rbRightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText(L10n.t("실시간 독서 진행률", "Live Reading Progress"), w - 120f, rbY + 41f, rbRightPaint)
        c.drawLine(120f, rbY + 62f, w - 120f, rbY + 62f, sepLinePaint)

        val readingDisplay = payload.currentlyReadingBooks.take(2)
        if (readingDisplay.isEmpty()) {
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 22f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            c.drawText(L10n.t("현재 진행 중인 도서가 없습니다.", "No books currently in progress."), 120f, rbY + 140f, emptyPaint)
        } else {
            val slotColors = intArrayOf(0xFF1E3A8A.toInt(), 0xFF0F766E.toInt())
            val slotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt(); style = Paint.Style.FILL }
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E8F0.toInt(); style = Paint.Style.FILL }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt(); style = Paint.Style.FILL }

            for ((idx, b) in readingDisplay.withIndex()) {
                val ry = rbY + 80f + idx * 140f
                c.drawRoundRect(RectF(120f, ry, w - 120f, ry + 124f), 10f, 10f, slotBgPaint)
                c.drawRoundRect(RectF(120f, ry, w - 120f, ry + 124f), 10f, 10f, cardBorderPaint)

                drawMiniBookCover(c, 136f, ry + 12f, 72f, 100f, b.title, b.format, b.coverPath, slotColors[idx % slotColors.size])

                val bTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0F172A.toInt()
                    textSize = 22f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                }
                c.drawText(b.title.take(24), 228f, ry + 38f, bTitlePaint)

                val bMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF64748B.toInt()
                    textSize = 17f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    textAlign = Paint.Align.RIGHT
                }
                val authMeta = "${b.author.ifBlank { L10n.t("저자 미상", "Unknown") }.take(12)} · ${b.format.ifBlank { "EPUB" }}"
                c.drawText(authMeta, w - 140f, ry + 38f, bMetaPaint)

                val bSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF475569.toInt()
                    textSize = 17f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                c.drawText(b.subInfo.ifBlank { L10n.t("독서 진행 중", "Reading in progress") }.take(38), 228f, ry + 68f, bSubPaint)

                val bx = 228f
                val by = ry + 86f
                val bw = w - 450f
                val bh = 16f
                val pctInt = (b.progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
                c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 8f, 8f, trackPaint)
                val fillW = (bw * (pctInt / 100f)).coerceAtLeast(bh)
                c.drawRoundRect(RectF(bx, by, bx + fillW, by + bh), 8f, 8f, fillPaint)

                val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF2563EB.toInt()
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                }
                c.drawText("${pctInt}%", bx + bw + 16f, by + 15f, pctPaint)
            }
        }

        // 5. Habit & Insight Card (y: 1222, h: 210)
        val insY = 1222f
        val insH = 210f
        val insBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEFF6FF.toInt()
            style = Paint.Style.FILL
        }
        val insBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBFDBFE.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        c.drawRoundRect(RectF(90f, insY, w - 90f, insY + insH), 14f, 14f, insBgPaint)
        c.drawRoundRect(RectF(90f, insY, w - 90f, insY + insH), 14f, 14f, insBorderPaint)

        drawSectionBadge(c, 120f, insY + 18f, L10n.t("분석", "INFO"), 0xFF1F5E9C.toInt(), Color.WHITE, 30f)
        val insHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("독서 분석 및 인사이트", "Reading Habit Insights"), 172f, insY + 41f, insHeaderPaint)

        val insTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val primaryBook = payload.currentlyReadingBooks.firstOrNull()
        val paceLine = if (primaryBook != null) {
            val pct = (primaryBook.progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
            L10n.t(
                "• 현재 읽고 있는 《${primaryBook.title.take(18)}》은(는) 진행률 ${pct}%로 순조롭게 읽고 있습니다.",
                "• Currently reading 《${primaryBook.title.take(18)}》 at ${pct}% progress."
            )
        } else ""
        val combinedInsight = buildString {
            append("• ")
            append(payload.insightText.ifBlank { L10n.t("꾸준한 독서 습관이 이어지고 있습니다.", "Building a steady reading habit.") })
            if (paceLine.isNotBlank()) {
                append("\n")
                append(paceLine)
            }
        }
        val insLayout = StaticLayout.Builder.obtain(combinedInsight, 0, combinedInsight.length, insTextPaint, w - 240)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.18f)
            .setMaxLines(4)
            .build()
        c.save()
        c.translate(120f, insY + 62f)
        insLayout.draw(c)
        c.restore()

        // 6. Completed Books Section (y: 1456, h: 340)
        val booksY = 1456f
        val booksH = 340f
        c.drawRoundRect(RectF(90f, booksY, w - 90f, booksY + booksH), 14f, 14f, cardBgPaint)
        c.drawRoundRect(RectF(90f, booksY, w - 90f, booksY + booksH), 14f, 14f, cardBorderPaint)

        drawSectionBadge(c, 120f, booksY + 18f, L10n.t("완독", "DONE"), 0xFF059669.toInt(), Color.WHITE, 30f)
        val bHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("누적 완독 도서 컬렉션 (${compCount}권)", "Completed Books Collection ($compCount)"), 172f, booksY + 41f, bHeaderPaint)
        c.drawLine(120f, booksY + 60f, w - 120f, booksY + 60f, sepLinePaint)

        val bItemTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bItemSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF059669.toInt()
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val bookDisplayList = payload.completedBooksSummary.take(3)
        if (bookDisplayList.isEmpty()) {
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 20f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            }
            c.drawText(L10n.t("현재 기간 내 완독 기록이 없습니다.", "No completed books in this period."), 120f, booksY + 115f, emptyPaint)
        } else {
            for ((bi, bTitle) in bookDisplayList.withIndex()) {
                val rowY = booksY + 76f + (bi * 82f)
                val itemBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFF8FAFC.toInt()
                    style = Paint.Style.FILL
                }
                c.drawRoundRect(RectF(120f, rowY, w - 120f, rowY + 70f), 10f, 10f, itemBg)
                c.drawRoundRect(RectF(120f, rowY, w - 120f, rowY + 70f), 10f, 10f, cardBorderPaint)

                drawSectionBadge(c, 136f, rowY + 14f, L10n.t("완독", "FIN"), 0xFF059669.toInt(), Color.WHITE, 30f)

                c.drawText(bTitle.take(36), 200f, rowY + 32f, bItemTitlePaint)
                c.drawText(L10n.t("독서 여정 완독 달성", "Reading Journey Completed"), 200f, rowY + 56f, bItemSubPaint)
            }
        }

        // 7. Footer (y: 1825)
        c.drawLine(90f, 1825f, w - 90f, 1825f, rulePaint)

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText("Aether SAGA · " + L10n.t("독서 활동 분석 리포트", "Reading Activity Report"), 90f, 1858f, footerPaint)

        footerPaint.textAlign = Paint.Align.RIGHT
        c.drawText("journeys through endless stories", w - 90f, 1858f, footerPaint)

        return bmp
    }
}
