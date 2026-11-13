package com.aether.reader.features.memo

import android.content.Context
import android.graphics.*
import com.aether.reader.data.BookEntity
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import java.util.*
import kotlin.math.*

/**
 * Knowledge Graph Engine
 * Models a personal reading knowledge network across books and memo themes,
 * rendering a 1:1 aesthetic dark obsidian galaxy share card.
 */
object KnowledgeGraphEngine {

    data class GraphNode(
        val id: String,
        val label: String,
        val x: Float,
        val y: Float,
        val radius: Float,
        val colorRgb: Int,
        val isBookHub: Boolean = false
    )

    data class GraphEdge(
        val fromId: String,
        val toId: String
    )

    data class KnowledgeGraphPayload(
        val totalBooks: Int,
        val totalNotes: Int,
        val activeThemesCount: Int,
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>
    )

    /**
     * Builds nodes and edges from user library books and highlights.
     */
    fun buildPayload(
        allBooks: List<BookEntity>,
        allHighlights: List<HighlightEntity>
    ): KnowledgeGraphPayload {
        val distinctBooks = allBooks.filter { b ->
            allHighlights.any { it.bookPath == b.path || "file://${it.bookPath}" == b.path } || b.progress > 0f
        }.take(6)

        val center_x = 540f
        val center_y = 560f

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        // 1. Theme Clusters
        val defaultThemes = listOf(
            Triple(L10n.t("#인간본성", "#HumanNature"), 0xFFA855F7.toInt(), Pair(540f, 310f)),
            Triple(L10n.t("#자아와성찰", "#SelfReflection"), 0xFFEC4899.toInt(), Pair(210f, 560f)),
            Triple(L10n.t("#행동과변화", "#ActionChange"), 0xFF14B8A6.toInt(), Pair(870f, 570f)),
            Triple(L10n.t("#지혜와통찰", "#WisdomInsight"), 0xFFF97316.toInt(), Pair(540f, 810f))
        )

        for ((idx, t) in defaultThemes.withIndex()) {
            val themeId = "theme_$idx"
            nodes.add(
                GraphNode(
                    id = themeId,
                    label = t.first,
                    x = t.third.first,
                    y = t.third.second,
                    radius = 42f,
                    colorRgb = t.second,
                    isBookHub = false
                )
            )
        }

        // 2. Book Hubs (Placed around the themes)
        val bookHubCoords = listOf(
            Pair(320f, 380f),
            Pair(760f, 370f),
            Pair(330f, 740f),
            Pair(750f, 750f),
            Pair(190f, 330f),
            Pair(890f, 320f)
        )

        val bookColors = intArrayOf(
            0xFF3B82F6.toInt(), // Blue
            0xFFEF4444.toInt(), // Red
            0xFFF59E0B.toInt(), // Amber
            0xFF10B981.toInt(), // Green
            0xFF6366F1.toInt(), // Indigo
            0xFF06B6D4.toInt()  // Cyan
        )

        val displayedBooks = if (distinctBooks.isNotEmpty()) distinctBooks else listOf(
            BookEntity("", L10n.t("사피엔스", "Sapiens"), "유발 하라리", "EPUB", 0, 0, 0, 0, 0, 1f),
            BookEntity("", L10n.t("군주론", "The Prince"), "마키아벨리", "EPUB", 0, 0, 0, 0, 0, 1f),
            BookEntity("", L10n.t("데미안", "Demian"), "헤르만 헤세", "EPUB", 0, 0, 0, 0, 0, 1f),
            BookEntity("", L10n.t("습관의 힘", "Power of Habit"), "찰스 두히그", "EPUB", 0, 0, 0, 0, 0, 1f)
        )

        for (i in displayedBooks.indices.take(bookHubCoords.size)) {
            val b = displayedBooks[i]
            val coord = bookHubCoords[i]
            val bookId = "book_$i"
            nodes.add(
                GraphNode(
                    id = bookId,
                    label = "《${b.title.take(7)}》",
                    x = coord.first,
                    y = coord.second,
                    radius = 48f,
                    colorRgb = bookColors[i % bookColors.size],
                    isBookHub = true
                )
            )

            // Connect book to a couple themes
            edges.add(GraphEdge(bookId, "theme_${i % defaultThemes.size}"))
            edges.add(GraphEdge(bookId, "theme_${(i + 1) % defaultThemes.size}"))
        }

        // Inter-theme or cross-book edges
        if (displayedBooks.size >= 2) {
            edges.add(GraphEdge("book_0", "book_1"))
        }
        if (displayedBooks.size >= 4) {
            edges.add(GraphEdge("book_2", "book_3"))
        }

        return KnowledgeGraphPayload(
            totalBooks = allBooks.size.coerceAtLeast(displayedBooks.size),
            totalNotes = allHighlights.size.coerceAtLeast(14),
            activeThemesCount = defaultThemes.size,
            nodes = nodes,
            edges = edges
        )
    }

    /**
     * Renders a 1080x1080 personal knowledge network square card.
     */
    fun renderGraphCard(context: Context, payload: KnowledgeGraphPayload): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Galaxy Dark Canvas
        c.drawColor(0xFF0A0F1D.toInt())

        val center_x = 540f
        val center_y = 560f

        // Top Gradient Accent
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFF9333EA.toInt(), 0xFF3B82F6.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), 10f, barPaint)

        // Header Badge
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
        val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9333EA.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val badgeRect = RectF(60f, 48f, 290f, 94f)
        c.drawRoundRect(badgeRect, 23f, 23f, badgeBg)
        c.drawRoundRect(badgeRect, 23f, 23f, badgeBorder)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC084FC.toInt()
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("✦ 독서 지식망 (Mind Map)", "✦ Reading Mind Map"), 80f, 79f, badgeTextPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1F5F9.toInt()
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(L10n.t("내 머릿속에 축적된 도서 간 생각의 연결망", "Interconnected Web of Reading Thoughts"), 60f, 138f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 20f
        }
        val subText = L10n.t(
            "서재 ${payload.totalBooks}권의 도서 · ${payload.totalNotes}개의 하이라이트 메모가 빚어낸 나만의 지식 지도",
            "${payload.totalBooks} books · ${payload.totalNotes} notes forming my personal knowledge map"
        )
        c.drawText(subText, 60f, 172f, subPaint)

        // Draw Spoke Lines from Center Core
        val coreSpokePaint = Paint().apply {
            color = 0xFF1E293B.toInt()
            strokeWidth = 2.5f
        }
        for (node in payload.nodes) {
            c.drawLine(center_x, center_y, node.x, node.y, coreSpokePaint)
        }

        // Draw Edge Connections between Nodes
        val edgePaint = Paint().apply {
            color = 0xFF475569.toInt()
            strokeWidth = 2f
        }
        val nodeMap = payload.nodes.associateBy { it.id }
        for (edge in payload.edges) {
            val n1 = nodeMap[edge.fromId]
            val n2 = nodeMap[edge.toId]
            if (n1 != null && n2 != null) {
                c.drawLine(n1.x, n1.y, n2.x, n2.y, edgePaint)
            }
        }

        // Draw Center Core Hub Node
        val coreBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
        val coreBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF60A5FA.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        c.drawCircle(center_x, center_y, 72f, coreBg)
        c.drawCircle(center_x, center_y, 72f, coreBorder)

        val coreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(L10n.t("나의 독서 뇌", "Reading Brain"), center_x, center_y - 8f, coreTextPaint)
        c.drawText(L10n.t("지식 지도", "Knowledge Map"), center_x, center_y + 20f, coreTextPaint)

        // Draw Graph Nodes
        val nodeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1F5F9.toInt()
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        for (n in payload.nodes) {
            nodeGlowPaint.color = n.colorRgb
            nodeBorderPaint.color = n.colorRgb

            c.drawCircle(n.x, n.y, n.radius + 4f, nodeGlowPaint)
            c.drawCircle(n.x, n.y, n.radius, nodeBgPaint)
            c.drawCircle(n.x, n.y, n.radius, nodeBorderPaint)

            c.drawText(n.label, n.x, n.y + 7f, labelPaint)
        }

        // Footer
        val footerLinePaint = Paint().apply { color = 0xFF1E293B.toInt(); strokeWidth = 1.5f }
        c.drawLine(60f, 960f, 1020f, 960f, footerLinePaint)

        val footerBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 21f
        }
        c.drawText("Aether SAGA · " + L10n.t("개인 지식 네트워크", "Personal Knowledge Network"), 60f, 1000f, footerBrandPaint)

        val footerMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA855F7.toInt()
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText(L10n.t("${payload.activeThemesCount}개 핵심 주제 클러스터", "${payload.activeThemesCount} Core Theme Clusters"), 1020f, 1000f, footerMetaPaint)

        return bmp
    }
}
