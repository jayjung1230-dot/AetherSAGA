package com.aether.reader.features.gemini

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aether.reader.app.ReaderTheme
import com.aether.reader.data.L10n

/**
 * Renders a high-resolution 1080x1080 discussion quote card
 * that transforms an in-book Gemini dialogue directly into a shareable social card.
 */
object DiscussionCardSynthesizer {

    fun renderDiscussionCard(
        bookTitle: String,
        bookAuthor: String,
        quote: String,
        userQuery: String,
        distilledInsight: String
    ): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 1. Background
        val bgPaint = Paint().apply { color = 0xFF0F172A.toInt() } // Slate 900
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 2. Outer Card Frame
        val cardPaint = Paint().apply {
            color = 0xFF18181B.toInt() // Zinc 900
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = 0xFF334155.toInt() // Slate 700
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val outerRect = RectF(36f, 36f, (w - 36).toFloat(), (h - 36).toFloat())
        c.drawRoundRect(outerRect, 28f, 28f, cardPaint)
        c.drawRoundRect(outerRect, 28f, 28f, borderPaint)

        // 3. Header
        val brandPaint = TextPaint().apply {
            color = 0xFF6366F1.toInt() // Indigo 500
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        c.drawText("AETHER SAGA  •  AI READING DIALOGUE", 70f, 95f, brandPaint)

        val titlePaint = TextPaint().apply {
            color = 0xFFF8FAFC.toInt()
            textSize = 32f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAntiAlias = true
        }
        val cleanTitle = bookTitle.ifBlank { L10n.t("제목 미상", "Unknown Title") }
        val authorPart = if (bookAuthor.isNotBlank()) "  •  $bookAuthor" else ""
        c.drawText("《$cleanTitle》$authorPart", 70f, 142f, titlePaint)

        // 4. Original Quote Box
        val quoteBoxPaint = Paint().apply {
            color = 0xFF1E293B.toInt() // Slate 800
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val quoteBoxRect = RectF(70f, 175f, (w - 70).toFloat(), 345f)
        c.drawRoundRect(quoteBoxRect, 16f, 16f, quoteBoxPaint)
        c.drawRoundRect(quoteBoxRect, 16f, 16f, Paint(borderPaint).apply { strokeWidth = 1.5f })

        val quoteTagPaint = TextPaint().apply {
            color = 0xFF94A3B8.toInt()
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        c.drawText(L10n.t("📖 본문 인용구", "📖 Highlighted Quote"), 95f, 210f, quoteTagPaint)

        val quoteBodyPaint = TextPaint().apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 23f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        val cleanQuote = "“" + quote.trim().take(180) + "”"
        val quoteLayout = StaticLayout.Builder.obtain(cleanQuote, 0, cleanQuote.length, quoteBodyPaint, w - 190)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .build()
        c.save()
        c.translate(95f, 230f)
        quoteLayout.draw(c)
        c.restore()

        // 5. User Question Row
        val userRowPaint = Paint().apply {
            color = 0xFF0F172A.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val userRect = RectF(70f, 370f, (w - 70).toFloat(), 465f)
        c.drawRoundRect(userRect, 14f, 14f, userRowPaint)
        c.drawRoundRect(userRect, 14f, 14f, Paint(borderPaint).apply { color = 0xFF2563EB.toInt() })

        val userLabelPaint = TextPaint().apply {
            color = 0xFF60A5FA.toInt()
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        c.drawText("👤 " + L10n.t("독자의 질문과 사유", "Reader's Question"), 95f, 405f, userLabelPaint)

        val userQueryPaint = TextPaint().apply {
            color = 0xFFF1F5F9.toInt()
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        val cleanQuery = userQuery.trim().take(120)
        c.drawText(cleanQuery, 95f, 442f, userQueryPaint)

        // 6. Gemini Core Insight Box (Glowing Indigo)
        val insightBoxPaint = Paint().apply {
            color = 0xFF312E81.toInt() // Indigo 900
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val insightBorderPaint = Paint().apply {
            color = 0xFF6366F1.toInt() // Indigo 500
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        val insightRect = RectF(70f, 490f, (w - 70).toFloat(), 870f)
        c.drawRoundRect(insightRect, 20f, 20f, insightBoxPaint)
        c.drawRoundRect(insightRect, 20f, 20f, insightBorderPaint)

        val badgePaint = TextPaint().apply {
            color = 0xFFC7D2FE.toInt() // Indigo 200
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        c.drawText("✨ GEMINI AI × 독자 심층 문답의 정수", 100f, 540f, badgePaint)

        val insightTextPaint = TextPaint().apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAntiAlias = true
        }
        val cleanInsight = "“" + distilledInsight.trim().take(300) + "”"
        val insightLayout = StaticLayout.Builder.obtain(cleanInsight, 0, cleanInsight.length, insightTextPaint, w - 200)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(7)
            .build()
        c.save()
        c.translate(100f, 575f)
        insightLayout.draw(c)
        c.restore()

        // 7. Footer
        val hashPaint = TextPaint().apply {
            color = 0xFF818CF8.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        c.drawText("#AetherSAGA  #독서토론  #인간과사유  #GeminiAI  #책스타그램", 70f, 930f, hashPaint)

        val footNotePaint = TextPaint().apply {
            color = 0xFF64748B.toInt()
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isAntiAlias = true
        }
        c.drawText("Generated with Aether SAGA Intelligent Reader  •  gemini-1.5-flash", 70f, 980f, footNotePaint)

        return bmp
    }
}
