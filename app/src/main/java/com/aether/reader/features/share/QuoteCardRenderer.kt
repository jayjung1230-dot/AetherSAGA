package com.aether.reader.features.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.L10n
import java.util.Locale

/**
 * Dedicated renderer module for 1:1 Quote Cards (1080x1080), 9:16 Story Bundle Cards (1080x1920),
 * and fallback Typographic Book Covers (480x680).
 */
object QuoteCardRenderer {

    fun renderTypographicCover(rawTitle: String, rawAuthor: String, rawFormat: String): Bitmap {
        val w = 480
        val h = 680
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

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

        val ruleY = (180f + sl.height + 24f).coerceAtMost(h - 160f)
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            strokeWidth = 3f
        }
        c.drawLine(60f, ruleY, 160f, ruleY, rulePaint)

        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt()
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val authorText = rawAuthor.ifBlank { "Aether SAGA Edition" }.take(40)
        c.drawText(authorText, 60f, ruleY + 44f, authorPaint)

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText("Aether SAGA Library", 60f, h - 60f, footerPaint)
        return bmp
    }

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

        val isLocalCover = (coverBitmap == null)
        val coverToDraw: Bitmap = coverBitmap ?: renderTypographicCover(bookTitle, bookAuthor, "")
        val maxCoverW = 175f
        val maxCoverH = 248f
        val bw = coverToDraw.width.toFloat().coerceAtLeast(1f)
        val bh = coverToDraw.height.toFloat().coerceAtLeast(1f)
        val scale = minOf(maxCoverW / bw, maxCoverH / bh)
        val coverW = (bw * scale).coerceAtLeast(1f)
        val coverH = (bh * scale).coerceAtLeast(1f)
        val coverX = w - 100f - coverW
        val coverY = 75f
        try {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000.toInt() }
            c.drawRoundRect(RectF(coverX + 4f, coverY + 6f, coverX + coverW + 4f, coverY + coverH + 6f), 8f, 8f, shadowPaint)

            val coverRect = RectF(coverX, coverY, coverX + coverW, coverY + coverH)
            val clipPath = Path().apply { addRoundRect(coverRect, 8f, 8f, Path.Direction.CW) }
            c.save()
            c.clipPath(clipPath)
            c.drawBitmap(coverToDraw, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()

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
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("Aether SAGA", 100f, 125f, brandPaint)

        val bookTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2825.toInt()
            textSize = 36f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText(bookTitle.take(22), 100f, 175f, bookTitlePaint)

        val unknownAuthor = L10n.t("저자 미상", "Unknown Author")
        val bookAuthorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A7468.toInt()
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(bookAuthor.ifBlank { unknownAuthor }.take(22), 100f, 215f, bookAuthorPaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0DDD5.toInt()
            strokeWidth = 2f
        }
        c.drawLine(100f, 245f, coverX - 35f, 245f, rulePaint)

        val quoteMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD4CCBC.toInt()
            textSize = 120f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText("“", 95f, 335f, quoteMarkPaint)

        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val contentW = w - 200
        val quoteText = quote.trim()
        val maxSquareQuoteH = if (!memo.isNullOrBlank()) 480f else 600f
        var qSize = if (quoteText.length > 250) 32f else if (quoteText.length > 120) 38f else 46f
        var quoteLayout: StaticLayout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, TextPaint(quotePaint).apply { textSize = qSize }, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.22f)
            .build()
        while (qSize > 18f && quoteLayout.height > maxSquareQuoteH) {
            qSize -= 1.5f
            quoteLayout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, TextPaint(quotePaint).apply { textSize = qSize }, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1.18f)
                .build()
        }

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

    fun renderStoryBundleCard(
        bookTitle: String,
        bookAuthor: String,
        items: List<AndroidShareHelper.SnsQuoteItem>,
        coverBitmap: Bitmap? = null
    ): Bitmap {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

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

        val isLocalCover = (coverBitmap == null)
        val coverToDraw: Bitmap = coverBitmap ?: renderTypographicCover(bookTitle, bookAuthor, "")
        val maxCoverW = 175f
        val maxCoverH = 248f
        val bw = coverToDraw.width.toFloat().coerceAtLeast(1f)
        val bh = coverToDraw.height.toFloat().coerceAtLeast(1f)
        val scale = minOf(maxCoverW / bw, maxCoverH / bh)
        val coverW = (bw * scale).coerceAtLeast(1f)
        val coverH = (bh * scale).coerceAtLeast(1f)
        val coverX = w - 100f - coverW
        val coverY = 80f
        try {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000.toInt() }
            c.drawRoundRect(RectF(coverX + 4f, coverY + 6f, coverX + coverW + 4f, coverY + coverH + 6f), 10f, 10f, shadowPaint)

            val coverRect = RectF(coverX, coverY, coverX + coverW, coverY + coverH)
            val clipPath = Path().apply { addRoundRect(coverRect, 10f, 10f, Path.Direction.CW) }
            c.save()
            c.clipPath(clipPath)
            c.drawBitmap(coverToDraw, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()

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
            textSize = 36f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText(bookTitle.take(22), 100f, 210f, bookTitlePaint)

        val unknownAuthor = L10n.t("저자 미상", "Unknown Author")
        val bookAuthorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A7468.toInt()
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(bookAuthor.ifBlank { unknownAuthor }.take(22), 100f, 256f, bookAuthorPaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0DDD5.toInt()
            strokeWidth = 2f
        }
        c.drawLine(100f, 305f, coverX - 30f, 305f, rulePaint)

        val contentW = (w - 200)
        val bundleItems = ArrayList<AndroidShareHelper.SnsQuoteItem>()
        var accChars = 0
        val maxStoryChars = 600
        for (it in items) {
            val len = it.quote.trim().length
            if (bundleItems.isEmpty() || accChars + len <= maxStoryChars) {
                bundleItems.add(it)
                accChars += len
            } else {
                break
            }
        }
        val n = bundleItems.size.coerceAtLeast(1)
        val totalAvailableH = 1460f

        val slotHeights = FloatArray(n)
        if (n == 1) {
            slotHeights[0] = totalAvailableH
        } else {
            val baseHeights = FloatArray(n) { i ->
                val hasMemo = !bundleItems[i].memo.isNullOrBlank()
                val isProg = bundleItems[i].chapterTitle?.contains("진행") == true || bundleItems[i].quote.contains("진행률")
                if (hasMemo) 150f else if (isProg) (if (n == 1) totalAvailableH else 240f) else 105f
            }
            val weights = FloatArray(n) { i ->
                val qLen = bundleItems[i].quote.trim().length
                val mLen = if (!bundleItems[i].memo.isNullOrBlank()) bundleItems[i].memo!!.trim().length else 0
                (qLen + mLen).coerceAtLeast(15).toFloat()
            }
            val totalBase = baseHeights.sum()
            val totalWeight = weights.sum().coerceAtLeast(1f)
            val remainingH = (totalAvailableH - totalBase).coerceAtLeast(0f)

            for (i in 0 until n) {
                slotHeights[i] = baseHeights[i] + (remainingH * (weights[i] / totalWeight))
            }
        }

        val uniformQmSize = if (n == 1) 80f else if (n == 2) 60f else 48f
        val quoteMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD4CCBC.toInt()
            textSize = uniformQmSize
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val quoteTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2825.toInt()
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

        val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = if (n <= 2) 26f else 22f
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        }

        val uniformTagSize = if (n <= 2) 30f else 26f
        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F5E9C.toInt()
            textSize = uniformTagSize
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        val slotDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2DDD2.toInt()
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        var currentSlotTop = 350f
        for ((idx, item) in bundleItems.withIndex()) {
            val slotTop = currentSlotTop
            val slotH = slotHeights[idx]
            currentSlotTop += slotH
            var curY = slotTop + 20f

            val isProgress = item.chapterTitle?.contains("진행") == true || item.quote.contains("진행률")

            val tagText = buildString {
                append(if (isProgress) "📊 " else if (item.isBookmark) "🔖 " else "🖍 ")
                if (!item.chapterTitle.isNullOrBlank()) {
                    append(item.chapterTitle.take(30))
                } else {
                    append(L10n.t("인용구", "Quote"))
                    append(" #${idx + 1}")
                }
            }
            c.drawText(tagText, 100f, curY, tagPaint)
            curY += 42f

            c.drawText("“", 100f, curY + 6f, quoteMarkPaint)
            curY += if (n == 1) 50f else 42f

            val quoteText = item.quote.trim()
            val maxQuoteForSlot = slotH - (if (!item.memo.isNullOrBlank()) 140f else if (isProgress) 120f else 80f)
            val baseSize = when {
                isProgress -> when {
                    n == 1 -> 58f
                    n == 2 -> 44f
                    else -> 36f
                }
                quoteText.length < 50 -> when {
                    n == 1 -> 50f
                    n == 2 -> 38f
                    else -> 32f
                }
                n == 1 -> if (quoteText.length > 250) 34f else 42f
                n == 2 -> if (slotH > 500f) 34f else 30f
                else -> if (slotH > 350f) 28f else 24f
            }
            var curSize = baseSize
            val activeQuotePaint = if (isProgress) {
                TextPaint(quoteTextPaint).apply {
                    textSize = curSize
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                }
            } else {
                TextPaint(quoteTextPaint).apply { textSize = curSize }
            }
            var quoteLayout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, activeQuotePaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(if (n <= 2) 6f else 4f, 1.18f)
                .build()

            while (curSize > 16f && quoteLayout.height > maxQuoteForSlot) {
                curSize -= 1.5f
                val loopPaint = if (isProgress) {
                    TextPaint(quoteTextPaint).apply {
                        textSize = curSize
                        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    }
                } else {
                    TextPaint(quoteTextPaint).apply { textSize = curSize }
                }
                quoteLayout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, loopPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(if (n <= 2) 5f else 3f, 1.16f)
                    .build()
            }

            c.save()
            c.translate(100f, curY)
            quoteLayout.draw(c)
            c.restore()

            curY += quoteLayout.height + 15f

            if (isProgress) {
                val pct = Regex("(\\d+)%").find(quoteText)?.groupValues?.get(1)?.toIntOrNull()
                if (pct != null) {
                    val barH = if (n == 1) 22f else 14f
                    val barW = contentW.toFloat()
                    val fillW = (barW * (pct.coerceIn(0, 100) / 100f)).coerceAtLeast(barH)
                    val barY = curY + 12f
                    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5E0D5.toInt() }
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F5E9C.toInt() }
                    c.drawRoundRect(RectF(100f, barY, 100f + barW, barY + barH), barH / 2f, barH / 2f, trackPaint)
                    c.drawRoundRect(RectF(100f, barY, 100f + fillW, barY + barH), barH / 2f, barH / 2f, fillPaint)
                    curY += barH + 20f

                    if (n == 1) {
                        val subPctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0xFF1F5E9C.toInt()
                            textSize = 28f
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        }
                        c.drawText(L10n.t("${pct}% 완독을 향해 순항 중", "${pct}% on the reading journey"), 100f, curY + 22f, subPctPaint)
                        curY += 45f
                    }
                }
            }

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

            if (idx < bundleItems.size - 1) {
                val divY = slotTop + slotH - 8f
                c.drawLine(120f, divY, w - 120f, divY, slotDividerPaint)
            }
        }

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
}
