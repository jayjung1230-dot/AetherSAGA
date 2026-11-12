package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aether.reader.App
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import com.aether.reader.features.gemini.ExpectationAnalysis
import com.aether.reader.features.gemini.GeminiContextAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Concise, streamlined Gemini AI Companion & Discussion Dialog.
 *
 * Features:
 * - Hourglass (⏳) Loading View while preparing contextual expectations
 * - Direct In-Book Search & Web Knowledge Search integration
 * - Streamlined 3-Tier Layout (Quote & 2-line summary -> Search Track -> Discussion Track)
 * - In-App Live Multi-Turn Chat via Gemini REST API
 */
object GeminiDialogHelper {

    enum class GenreMode {
        LITERATURE,
        TECH,
        HISTORY
    }

    fun detectGenre(title: String, author: String, chapter: String, quote: String): GenreMode {
        val q = quote.lowercase()
        val t = "$title $chapter $author".lowercase()

        val techKeywords = listOf(
            "설명서", "사용법", "가이드", "매뉴얼", "튜토리얼", "guide", "manual", "tutorial",
            "doc", "spec", "사양서", "교과서", "기술", "공학", "아키텍처", "개발팀", "개발자", "입문",
            "메모리", "캐시", "운영체제", "tlb", "cache", "latency", "architecture", "전자종이", "e-ink", "lcd", "oled"
        )
        if (techKeywords.any { t.contains(it) || q.contains(it) }) return GenreMode.TECH

        val histKeywords = listOf(
            "역사", "실록", "연대기", "사기", "전쟁사", "한국사", "세계사", "history", "chronicle", "dynasty", "사료", "평전",
            "조선", "왕조", "태종", "혁명", "revolution", "bastille", "century", "제국", "전쟁"
        )
        if (histKeywords.any { t.contains(it) || q.contains(it) }) return GenreMode.HISTORY

        return GenreMode.LITERATURE
    }

    data class ChatMessage(
        val role: String,
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun show(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        highlight: HighlightEntity?,
        isLcd: Boolean,
        isDark: Boolean,
        onSearchInBook: ((keyword: String) -> Unit)? = null,
        onSaveMemo: ((note: String) -> Unit)? = null,
        onShareQuote: (() -> Unit)? = null
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val app = activity.application as App
        val prefs = app.prefs

        val cleanTitle = bookTitle.ifBlank { L10n.t("제목 미상", "Untitled") }
        val cleanAuthor = bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }
        val cleanChapter = chapterTitle.ifBlank { L10n.t("본문", "Body") }

        val bgCol = if (isDark) ReaderTheme.DARK_CARD else (if (isLcd) Color.WHITE else ReaderTheme.EINK_PAPER)
        val textCol = if (isDark) ReaderTheme.DARK_TEXT else ReaderTheme.EINK_INK
        val mutedCol = if (isDark) ReaderTheme.DARK_MUTED else ReaderTheme.EINK_MUTED
        val borderCol = if (isDark) ReaderTheme.DARK_BORDER else (if (isLcd) 0xFFDDE3EA.toInt() else ReaderTheme.EINK_BORDER_CONTRAST)
        val accentCol = if (isDark) 0xFF60A5FA.toInt() else ReaderTheme.ACCENT_PRIMARY_BLUE

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCol)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            enableVisibleScrollbar(isDark = isDark)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCol)
            setPadding(0, 0, dp(2), dp(8))
        }
        scroll.addView(root)

        lateinit var dlg: AlertDialog

        // Header (Sparkle, Title & Close Button — Pinned at Top)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val sparkIcon = TextView(activity).apply {
            text = "✨"
            textSize = 20f
            setPadding(0, 0, dp(8), 0)
        }
        val headerTitle = TextView(activity).apply {
            text = L10n.t("Gemini AI 독서 도우미 (Beta)", "Gemini AI Reading Companion (Beta)")
            textSize = 16f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(textCol)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            setTextColor(textCol)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { dlg.dismiss() }
        }
        headerRow.addView(sparkIcon)
        headerRow.addView(headerTitle)
        headerRow.addView(closeBtn)
        dialogRoot.addView(headerRow)

        val bookChip = TextView(activity).apply {
            text = "📖 《$cleanTitle》 ($cleanAuthor) · $cleanChapter"
            textSize = 11.5f
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(8))
        }
        dialogRoot.addView(bookChip)
        dialogRoot.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Pinned Bottom Dock (Chat Input & Action Buttons)
        val bottomDock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        dialogRoot.addView(bottomDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Loading Container (Hourglass ⏳ view)
        val loadingBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }
        val hourglassTv = TextView(activity).apply {
            text = "⏳"
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val loadingStatusTv = TextView(activity).apply {
            text = L10n.t("문맥과 핵심 기대요소를 찾고 있습니다...", "Analyzing context and key insights...")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textCol)
            gravity = Gravity.CENTER
        }
        val loadingSubTv = TextView(activity).apply {
            text = L10n.t("잠시만 기다려주세요 ✨", "Just a moment ✨")
            textSize = 11.5f
            setTextColor(mutedCol)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        loadingBox.addView(hourglassTv)
        loadingBox.addView(loadingStatusTv)
        loadingBox.addView(loadingSubTv)
        root.addView(loadingBox)

        // Main Content Container (Shown after hourglass)
        val contentBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(contentBox)

        // Analysis Execution
        Handler(Looper.getMainLooper()).postDelayed({
            val analysis = GeminiContextAnalyzer.analyze(cleanTitle, cleanAuthor, cleanChapter, quote)
            loadingBox.visibility = View.GONE
            contentBox.visibility = View.VISIBLE
            bottomDock.visibility = View.VISIBLE
            populateContent(
                activity = activity,
                container = contentBox,
                bottomDock = bottomDock,
                scroll = scroll,
                analysis = analysis,
                bookTitle = cleanTitle,
                bookAuthor = cleanAuthor,
                chapterTitle = cleanChapter,
                quote = quote,
                isLcd = isLcd,
                isDark = isDark,
                textCol = textCol,
                mutedCol = mutedCol,
                borderCol = borderCol,
                accentCol = accentCol,
                dp = ::dp,
                prefs = prefs,
                onDismiss = { dlg.dismiss() },
                onSearchInBook = onSearchInBook,
                onSaveMemo = onSaveMemo,
                onShareQuote = onShareQuote
            )
        }, 900L) // 0.9s smooth preparation

        dlg = AlertDialog.Builder(activity)
            .setView(dialogRoot)
            .create()
        dlg.showWithOptimalWidth(activity, enforceBalancedMargins = true)
    }

    private fun populateContent(
        activity: Activity,
        container: LinearLayout,
        bottomDock: LinearLayout,
        scroll: ScrollView,
        analysis: ExpectationAnalysis,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        isLcd: Boolean,
        isDark: Boolean,
        textCol: Int,
        mutedCol: Int,
        borderCol: Int,
        accentCol: Int,
        dp: (Int) -> Int,
        prefs: com.aether.reader.data.Prefs,
        onDismiss: () -> Unit,
        onSearchInBook: ((keyword: String) -> Unit)?,
        onSaveMemo: ((note: String) -> Unit)?,
        onShareQuote: (() -> Unit)?
    ) {
        container.removeAllViews()
        bottomDock.removeAllViews()

        // 1. Quote & Concise Summary Card
        val quoteCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = cardDrawable(if (isDark) 0xFF1F2937.toInt() else 0xFFF8FAFC.toInt(), dp(8), borderCol, dp(1))
        }
        val quoteTv = TextView(activity).apply {
            text = "“$quote”"
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            typeface = Typeface.create("serif", Typeface.ITALIC)
            setTextColor(textCol)
        }
        val divider1 = View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        val summaryTitle = TextView(activity).apply {
            text = L10n.t("💡 핵심 맥락 요약", "💡 Core Context Summary")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) 0xFF86EFAC.toInt() else 0xFF15803D.toInt())
            setPadding(0, 0, 0, dp(2))
        }
        val summaryTv = TextView(activity).apply {
            text = analysis.summary
            textSize = 12.5f
            setLineSpacing(0f, 1.25f)
            setTextColor(if (isDark) 0xFFCBD5E1.toInt() else 0xFF334155.toInt())
        }
        quoteCard.addView(quoteTv)
        quoteCard.addView(divider1)
        quoteCard.addView(summaryTitle)
        quoteCard.addView(summaryTv)
        container.addView(quoteCard)

        // 2. Search Track Bar (In-Book Search & Web Knowledge Search)
        val searchTrackCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = cardDrawable(
                if (isDark) 0xFF0F172A.toInt() else (if (isLcd) 0xFFF1F5F9.toInt() else Color.WHITE),
                dp(8),
                borderCol,
                dp(if (isLcd) 1 else 2)
            )
        }
        val searchPrefix = TextView(activity).apply {
            text = L10n.t("🔍 검색", "🔍 Search")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedCol)
            setPadding(dp(4), 0, dp(8), 0)
        }
        searchTrackCard.addView(searchPrefix)

        // In-Book Search Button
        val inBookSearchBtn = TextView(activity).apply {
            text = L10n.t("📖 책 안에서 '${analysis.searchKeyword}' 찾기", "📖 In Book: '${analysis.searchKeyword}'")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentCol)
            background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else Color.WHITE, dp(12), accentCol, dp(1))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                onDismiss()
                if (onSearchInBook != null) {
                    onSearchInBook(analysis.searchKeyword)
                } else {
                    Toast.makeText(activity, L10n.t("본문 검색으로 이동합니다.", "Navigating to book search."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        searchTrackCard.addView(inBookSearchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })

        // Web Knowledge Search Button
        val webSearchBtn = TextView(activity).apply {
            text = L10n.t("🌐 배경지식", "🌐 Web")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedCol)
            background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else Color.WHITE, dp(12), borderCol, dp(1))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                val searchUrl = "https://www.google.com/search?q=" + URLEncoder.encode("${analysis.searchKeyword} $bookTitle", "UTF-8")
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Throwable) {
                    Toast.makeText(activity, "검색 브라우저를 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        searchTrackCard.addView(webSearchBtn)
        container.addView(searchTrackCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 3. Discussion Track Area
        val conversationHistory = ArrayList<ChatMessage>()
        val chatMessagesBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun renderBubbles() {
            chatMessagesBox.removeAllViews()
            for (msg in conversationHistory) {
                val bubbleRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (msg.isUser) Gravity.END else Gravity.START
                    setPadding(0, dp(4), 0, dp(4))
                }
                val bubble = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = if (msg.isUser) {
                        cardDrawable(0xFF2563EB.toInt(), dp(12))
                    } else {
                        cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFEFF6FF.toInt(), dp(12), if (isDark) 0xFF334155.toInt() else 0xFFBFDBFE.toInt(), dp(1))
                    }
                }
                val authorTag = TextView(activity).apply {
                    text = if (msg.isUser) L10n.t("👤 나 (독자)", "👤 Me") else L10n.t("🤖 Gemini AI", "🤖 Gemini AI")
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (msg.isUser) 0xFFBFDBFE.toInt() else (if (isDark) 0xFF93C5FD.toInt() else 0xFF1D4ED8.toInt()))
                    setPadding(0, 0, 0, dp(2))
                }
                val textTv = TextView(activity).apply {
                    text = msg.text
                    textSize = 12.5f
                    setLineSpacing(0f, 1.25f)
                    setTextColor(if (msg.isUser) Color.WHITE else textCol)
                }
                bubble.addView(authorTag)
                bubble.addView(textTv)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (msg.isUser) leftMargin = dp(36) else rightMargin = dp(36)
                }
                bubbleRow.addView(bubble, lp)
                chatMessagesBox.addView(bubbleRow)
            }
        }

        container.addView(chatMessagesBox)

        fun executePrompt(userQuery: String) {
            val apiKey = prefs.geminiApiKey.trim()
            if (apiKey.isBlank()) {
                // No API Key -> Launch Official Gemini App / Web with prefill
                openGeminiWithPrompt(activity, bookTitle, bookAuthor, chapterTitle, quote, userQuery)
                return
            }

            conversationHistory.add(ChatMessage("user", userQuery, true))
            renderBubbles()

            val loadingRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val loadingBubble = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt(), dp(12))
            }
            val loadingTv = TextView(activity).apply {
                text = L10n.t("🤖 Gemini AI가 사유를 나누고 있습니다... ✨", "🤖 Gemini AI is thinking... ✨")
                textSize = 12f
                setTextColor(mutedCol)
            }
            loadingBubble.addView(loadingTv)
            loadingRow.addView(loadingBubble)
            chatMessagesBox.addView(loadingRow)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val reply = callGeminiRestApi(apiKey, bookTitle, bookAuthor, chapterTitle, quote, conversationHistory)
                    chatMessagesBox.removeView(loadingRow)
                    conversationHistory.add(ChatMessage("model", reply, false))
                    renderBubbles()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                } catch (e: Throwable) {
                    chatMessagesBox.removeView(loadingRow)
                    val errorMsg = L10n.t(
                        "⚠️ 응답 오류: ${e.message}\n(하단의 [외부 앱 토론]으로 바로 이어갈 수 있습니다.)",
                        "⚠️ Error: ${e.message}\n(You can continue via [Open External App] below.)"
                    )
                    conversationHistory.add(ChatMessage("model", errorMsg, false))
                    renderBubbles()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        // Discussion Suggestion Chips (Pinned in bottomDock)
        val dockTopSep = View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        bottomDock.addView(dockTopSep)

        val chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val chipBox = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (chip in analysis.discussionChips) {
            val chipBtn = TextView(activity).apply {
                text = chip
                textSize = 11.5f
                setTextColor(accentCol)
                background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else Color.WHITE, dp(12), accentCol, dp(1))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener { executePrompt(chip) }
            }
            chipBox.addView(chipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        chipScroll.addView(chipBox)
        bottomDock.addView(chipScroll)

        // Chat Input Row (Pinned in bottomDock)
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val inputEt = EditText(activity).apply {
            hint = L10n.t("Gemini에게 질문하거나 토론할 생각을 적어보세요...", "Ask a question or share thoughts...")
            textSize = 12f
            setTextColor(textCol)
            setHintTextColor(mutedCol)
            background = cardDrawable(if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF8FAFC.toInt(), dp(16), borderCol, dp(1))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val sendBtn = TextView(activity).apply {
            text = L10n.t("전송 💬", "Send 💬")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = cardDrawable(if (isLcd) 0xFF2563EB.toInt() else ReaderTheme.ACCENT_CHARCOAL, dp(16), if (isLcd) 0xFF2563EB.toInt() else ReaderTheme.EINK_INK, dp(1))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val txt = inputEt.text.toString().trim()
                if (txt.isNotBlank()) {
                    inputEt.text.clear()
                    executePrompt(txt)
                }
            }
        }
        inputRow.addView(inputEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        inputRow.addView(sendBtn)
        bottomDock.addView(inputRow)

        // 4. Action Buttons (Save Memo, Share Card, External App — Pinned in bottomDock)
        val actionDivider = View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
        bottomDock.addView(actionDivider)

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        val saveMemoBtn = activity.pillButton(
            L10n.t("📝 메모 저장", "📝 Save Note"),
            textCol,
            if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
            borderCol
        ) {
            val contentToSave = if (conversationHistory.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("[Gemini 토론 기록]\n")
                sb.append("📖 《$bookTitle》 ($bookAuthor)\n“$quote”\n\n")
                for (m in conversationHistory) {
                    sb.append(if (m.isUser) "👤 질문: " else "🤖 Gemini: ").append(m.text).append("\n\n")
                }
                sb.toString().trim()
            } else {
                "[Gemini 독서 인사이트]\n📖 《$bookTitle》 ($bookAuthor)\n“$quote”\n\n${analysis.summary}"
            }
            onSaveMemo?.invoke(contentToSave)
            Toast.makeText(activity, L10n.t("독서 메모에 저장되었습니다.", "Saved to reading notes."), Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        btnRow.addView(saveMemoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })

        val shareCardBtn = activity.pillButton(
            L10n.t("📢 카드 공유", "📢 Share Card"),
            textCol,
            if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
            borderCol
        ) {
            onDismiss()
            onShareQuote?.invoke()
        }
        btnRow.addView(shareCardBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })

        val extAppBtn = activity.pillButton(
            L10n.t("🚀 외부 앱", "🚀 External"),
            mutedCol,
            if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
            borderCol
        ) {
            onDismiss()
            openGeminiWithPrompt(activity, bookTitle, bookAuthor, chapterTitle, quote, analysis.fullDiscussionPrompt)
        }
        btnRow.addView(extAppBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))

        bottomDock.addView(btnRow)
    }

    private suspend fun callGeminiRestApi(
        apiKey: String,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val model = "gemini-1.5-flash"
        val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true

        val systemInstruction = "당신은 도서 《$bookTitle》(저자: $bookAuthor, 챕터: $chapterTitle)의 지적인 독서 어시스턴트입니다. 인용구: \"$quote\". 문맥을 고려하여 명쾌하고 깊이 있는 통찰을 한국어로 친절하게 제공하세요."
        val contentsArray = JSONArray()

        val sysTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
        }
        contentsArray.put(sysTurn)

        val ackTurn = JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", "네, 《$bookTitle》의 해당 문맥에 맞추어 유익하고 명쾌한 독서 해설과 토론을 진행하겠습니다.") }) })
        }
        contentsArray.put(ackTurn)

        for (m in history) {
            val turn = JSONObject().apply {
                put("role", if (m.isUser) "user" else "model")
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", m.text) }) })
            }
            contentsArray.put(turn)
        }

        val payload = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 1000)
            })
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code == 200) {
            val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(respStr)
            val candidates = json.getJSONArray("candidates")
            candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
        } else {
            val errStr = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
            val errorMsg = try {
                JSONObject(errStr).getJSONObject("error").getString("message")
            } catch (_: Throwable) {
                errStr
            }
            throw IOException("Gemini API ($code): $errorMsg")
        }
    }

    private fun openGeminiWithPrompt(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        customPrompt: String
    ) {
        val prompt = if (customPrompt.contains("[도서 정보]")) customPrompt else """
[도서 정보]
- 도서명: 《$bookTitle》
- 저자: $bookAuthor
- 챕터: $chapterTitle

[선택한 본문 문장]
"$quote"

[질문 및 토론 요청]
$customPrompt
""".trimIndent()

        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Gemini Prompt", prompt))
        } catch (_: Throwable) {}

        val encodedQuery = try { URLEncoder.encode(prompt, "UTF-8") } catch (_: Throwable) { Uri.encode(prompt) }
        val webUri = Uri.parse("https://gemini.google.com/app?q=$encodedQuery")
        val pm = activity.packageManager
        val geminiPackage = "com.google.android.apps.bard"
        val googlePackage = "com.google.android.googlequicksearchbox"

        // Strategy 1: Intent.ACTION_SEND to Gemini app
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                clipData = ClipData.newPlainText("Gemini Prompt", prompt)
                setPackage(geminiPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val matches = pm.queryIntentActivities(sendIntent, 0)
            if (matches.isNotEmpty()) {
                if (matches.size == 1) {
                    val ai = matches[0].activityInfo
                    sendIntent.component = ComponentName(ai.packageName, ai.name)
                }
                activity.startActivity(sendIntent)
                Toast.makeText(activity, L10n.t("✨ Gemini 대화창에 프롬프트를 자동 입력했습니다.", "✨ Prompt prefilled in Gemini!"), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Throwable) {}

        // Strategy 2: Intent.ACTION_VIEW
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                setPackage(geminiPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (viewIntent.resolveActivity(pm) != null || pm.queryIntentActivities(viewIntent, 0).isNotEmpty()) {
                activity.startActivity(viewIntent)
                Toast.makeText(activity, L10n.t("✨ Gemini 대화창으로 연결합니다.", "✨ Opening Gemini..."), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Throwable) {}

        // Strategy 3: Google Assistant / Search App
        try {
            val sendGoogle = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                clipData = ClipData.newPlainText("Gemini Prompt", prompt)
                setPackage(googlePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val matches = pm.queryIntentActivities(sendGoogle, 0)
            if (matches.isNotEmpty()) {
                activity.startActivity(sendGoogle)
                Toast.makeText(activity, L10n.t("✨ Google 어시스턴트/Gemini에 연결합니다.", "✨ Connected to Google/Gemini!"), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Throwable) {}

        // Strategy 4: Web Browser Fallback
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            Toast.makeText(activity, L10n.t("✨ Gemini 웹으로 연결합니다. (프롬프트 복사됨)", "✨ Opening Gemini Web... (Prompt copied)"), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(activity, "연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
