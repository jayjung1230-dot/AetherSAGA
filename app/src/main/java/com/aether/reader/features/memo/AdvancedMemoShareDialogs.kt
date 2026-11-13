package com.aether.reader.features.memo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aether.reader.App
import com.aether.reader.app.ReaderTheme
import com.aether.reader.app.cardDrawable
import com.aether.reader.app.dp
import com.aether.reader.app.enableVisibleScrollbar
import com.aether.reader.app.pillButton
import com.aether.reader.app.setPtSize
import com.aether.reader.app.showWithOptimalWidth
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.BookEntity
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import com.aether.reader.features.gemini.GeminiApiClient
import com.aether.reader.features.memo.CrossBookSerendipityEngine.CrossBookPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Advanced Memo & Thought Connection Sharing Dialogs.
 * - e-ink mode: Pure classic typography cards (0 AI network calls, 0 screen flicker).
 * - LCD/OLED mode: Gemini AI enhanced insights with hourglass loading indicator and 1-click SNS dispatch.
 */
object AdvancedMemoShareDialogs {

    fun showAdvancedMemoMenu(
        activity: Activity,
        app: App,
        currentBook: BookEntity,
        currentHighlights: List<HighlightEntity>,
        isLcd: Boolean,
        onBack: (() -> Unit)? = null
    ) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_BORDER_CONTRAST
        val strokePx = if (isLcd) activity.dp(1) else activity.dp(2)
        val mutedCol = ReaderTheme.EINK_MUTED

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, activity.dp(10), activity.dp(4))
        }
        scroll.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogRoot.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Intro Banner
        val introTv = TextView(activity).apply {
            text = if (isLcd) {
                L10n.t(
                    "💡 단순한 발췌문 카드를 넘어, Gemini AI 심층 분석과 도서 간 지적 연결을 담은 특별한 SNS 카드를 발행합니다.",
                    "💡 Go beyond quotes: synthesize Gemini AI insights and cross-book synergies into aesthetic SNS cards."
                )
            } else {
                L10n.t(
                    "💡 단순한 발췌문 카드를 넘어, 내 생각의 깊이와 도서 간의 지적인 연결을 담은 특별한 독서 카드를 발행합니다.",
                    "💡 Go beyond quotes: share your intellectual reflections and cross-book connections with classic cards."
                )
            }
            setPtSize(8.2f)
            setTextColor(inkCol)
            setLineSpacing(activity.dp(2).toFloat(), 1.15f)
            setPadding(0, 0, 0, activity.dp(10))
        }
        root.addView(introTv)

        lateinit var dlg: AlertDialog

        fun addOptionCard(
            badgeText: String,
            titleText: String,
            descText: String,
            formatPill: String,
            accentColor: Int,
            onClick: () -> Unit
        ) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(cardBgCol, 8, borderCol, strokePx)
                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dlg.dismiss()
                    onClick()
                }
            }

            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, activity.dp(4))
            }
            val badgeTv = TextView(activity).apply {
                text = badgeText
                setPtSize(7.6f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(accentColor)
            }
            val formatTv = TextView(activity).apply {
                text = formatPill
                setPtSize(7.2f)
                setTextColor(mutedCol)
                gravity = Gravity.END
            }
            topRow.addView(badgeTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(formatTv)

            val titleTv = TextView(activity).apply {
                text = titleText
                setPtSize(9.2f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(inkCol)
                setPadding(0, 0, 0, activity.dp(3))
            }

            val descTv = TextView(activity).apply {
                text = descText
                setPtSize(7.8f)
                setTextColor(mutedCol)
                setLineSpacing(activity.dp(2).toFloat(), 1.15f)
            }

            card.addView(topRow)
            card.addView(titleTv)
            card.addView(descTv)

            root.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = activity.dp(10)
            })
        }

        // Option 1: Cross-Book Serendipity Card
        addOptionCard(
            badgeText = if (isLcd) L10n.t("✦ 과거 읽은 책과의 대화 (AI 통찰)", "✦ Cross-Book Serendipity (AI)")
                        else L10n.t("✦ 과거 읽은 책과의 대화 (클래식)", "✦ Cross-Book Serendipity (Classic)"),
            titleText = L10n.t("두 권의 책이 하나의 생각으로 만나는 카드", "When Two Books Meet in a Single Thought"),
            descText = if (isLcd) {
                L10n.t(
                    "과거 읽었던 책의 메모와 지금 읽는 책을 Gemini AI가 비교 분석하여 '지적 연결 통찰' 배지와 함께 1:1 카드로 발행합니다.",
                    "Gemini AI analyzes past notes with your current book, connected by an intellectual synergy badge (1:1 Feed)."
                )
            } else {
                L10n.t(
                    "과거 읽었던 책의 메모와 지금 읽는 책의 생각을 비교하여 '핵심 연결 고리' 배지와 함께 1:1 카드로 발행합니다.",
                    "Compares past notes with your current book, connected by a core keyword badge (1:1 Feed)."
                )
            },
            formatPill = L10n.t("1:1 피드 카드", "1:1 Feed Card"),
            accentColor = 0xFF2563EB.toInt()
        ) {
            shareCrossBookCard(activity, app, currentBook, currentHighlights, isLcd, onBack)
        }

        // Option 2: Idea Evolution Timeline Story
        addOptionCard(
            badgeText = if (isLcd) L10n.t("🌱 생각의 진화 연대기 (AI 내러티브)", "🌱 Evolution Timeline (AI Story)")
                        else L10n.t("🌱 생각의 진화 연대기 (클래식)", "🌱 Evolution Timeline (Classic)"),
            titleText = L10n.t("한 권의 책이 바꾼 나의 생각들 (완독 연대기)", "Thoughts Transformed by a Single Book"),
            descText = L10n.t(
                "1장 시작부터 완독까지 남긴 메모들을 4단계 성장 연대기로 엮어 인스타그램 9:16 스토리 카드로 발행합니다.",
                "Compiles notes from Chapter 1 to completion into a dramatic 4-step vertical story report (9:16 Instagram Story)."
            ),
            formatPill = L10n.t("9:16 스토리 카드", "9:16 Story Card"),
            accentColor = 0xFFD97706.toInt()
        ) {
            shareEvolutionTimelineCard(activity, app, currentBook, currentHighlights, isLcd, onBack)
        }

        // Option 3: Personal Knowledge Mind Map
        addOptionCard(
            badgeText = L10n.t("🧠 나의 독서 뇌 지도", "🧠 Reading Brain Mind Map"),
            titleText = L10n.t("내 머릿속에 축적된 도서 간 생각의 연결망", "Personal Knowledge Network Map"),
            descText = L10n.t(
                "서재의 책들과 주제 태그(#권력, #자아, #성장)들이 신경망처럼 얽힌 1:1 다크 나이트 지식 지도로 발행합니다.",
                "Renders an aesthetic galaxy graph of books and interconnected themes across your reading journey (1:1 Square Feed)."
            ),
            formatPill = L10n.t("1:1 마인드맵 카드", "1:1 Mind Map Card"),
            accentColor = 0xFF7C3AED.toInt()
        ) {
            shareKnowledgeGraphCard(activity, app, isLcd, onBack)
        }

        val dm = activity.resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val customH = if (isLandscape) (dm.heightPixels * 0.90f).toInt().coerceAtMost(activity.dp(560))
                      else (dm.heightPixels * 0.82f).toInt().coerceAtMost(activity.dp(640))

        dlg = AlertDialog.Builder(activity)
            .setTitle(L10n.t("✨ 스페셜 독서 메모 & 연대기 카드", "✨ Special Reading Memo & Story Cards"))
            .setView(dialogRoot)
            .create()
        dlg.showWithOptimalWidth(activity, customHeight = customH, onBack = onBack)
    }

    private fun showHourglassLoadingDialog(
        activity: Activity,
        msg: String
    ): AlertDialog {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
        }
        val hourglassTv = TextView(activity).apply {
            text = "⏳"
            setPtSize(28f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, activity.dp(12))
        }
        val msgTv = TextView(activity).apply {
            text = msg
            setPtSize(9.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            gravity = Gravity.CENTER
        }
        val subTv = TextView(activity).apply {
            text = L10n.t("잠시만 기다려 주세요. 사유의 연결 고리를 분석 중입니다... ✨", "Please wait. Connecting thoughts and insights... ✨")
            setPtSize(7.8f)
            setTextColor(ReaderTheme.EINK_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(6), 0, 0)
        }
        root.addView(hourglassTv)
        root.addView(msgTv)
        root.addView(subTv)

        val dlg = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create()
        dlg.showWithOptimalWidth(activity)
        return dlg
    }

    private fun showApiKeyInputDialog(
        activity: Activity,
        app: App,
        onKeySaved: (String) -> Unit,
        onSkipClassic: () -> Unit
    ) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
        }

        val titleTv = TextView(activity).apply {
            text = L10n.t("✨ Gemini AI 지적 독서 통찰 활성화", "✨ Activate Gemini AI Reading Insights")
            setPtSize(10f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            setPadding(0, 0, 0, activity.dp(6))
        }
        val descTv = TextView(activity).apply {
            text = L10n.t(
                "Google Gemini 무료 API 키를 등록하면, 두 도서 간의 철학적 통찰을 실시간 분석하여 1080p 카드에 각인합니다. (개인 무료 사용)",
                "Register a free Google Gemini API key to analyze philosophical synergies and synthesize 1080p cards in-app."
            )
            setPtSize(8f)
            setTextColor(ReaderTheme.EINK_MUTED)
            setLineSpacing(activity.dp(2).toFloat(), 1.15f)
            setPadding(0, 0, 0, activity.dp(10))
        }

        val input = EditText(activity).apply {
            hint = "AIzaSy..."
            setText(app.prefs.geminiApiKey)
            setPtSize(8.5f)
            setSingleLine(true)
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            background = cardDrawable(Color.WHITE, 6, ReaderTheme.EINK_BORDER, activity.dp(1))
        }

        root.addView(titleTv)
        root.addView(descTv)
        root.addView(input)

        val linkTv = TextView(activity).apply {
            text = L10n.t("🌐 [무료 API 키 1분 발급받기 - Google AI Studio]", "🌐 [Get free API Key - Google AI Studio]")
            setPtSize(7.6f)
            setTextColor(0xFF2563EB.toInt())
            setPadding(0, activity.dp(6), 0, activity.dp(12))
            isClickable = true
            setOnClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/app/apikey"))
                    activity.startActivity(intent)
                } catch (_: Throwable) {}
            }
        }
        root.addView(linkTv)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        lateinit var dlg: AlertDialog

        val classicBtn = activity.pillButton(
            L10n.t("클래식 카드로 계속", "Continue with Classic"),
            ReaderTheme.EINK_INK,
            Color.WHITE,
            ReaderTheme.EINK_BORDER
        ) {
            dlg.dismiss()
            onSkipClassic()
        }.apply {
            setPtSize(8f)
            setPadding(activity.dp(8), activity.dp(9), activity.dp(8), activity.dp(9))
        }

        val saveBtn = activity.pillButton(
            L10n.t("🔑 저장하고 바로 분석", "🔑 Save & Analyze Now"),
            Color.WHITE,
            0xFF4338CA.toInt(),
            0xFF4338CA.toInt()
        ) {
            val key = input.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(activity, L10n.t("API 키를 입력해주세요", "Please enter API Key"), Toast.LENGTH_SHORT).show()
            } else {
                app.prefs.geminiApiKey = key
                dlg.dismiss()
                onKeySaved(key)
            }
        }.apply {
            setPtSize(8.2f)
            typeface = ReaderTheme.uiFontBold
            setPadding(activity.dp(8), activity.dp(9), activity.dp(8), activity.dp(9))
        }

        btnRow.addView(classicBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = activity.dp(6) })
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        root.addView(btnRow)

        dlg = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(activity)
    }

    private fun shareCrossBookCard(
        activity: Activity,
        app: App,
        currentBook: BookEntity,
        currentHighlights: List<HighlightEntity>,
        isLcd: Boolean,
        onBack: (() -> Unit)?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val allHl = app.db.highlights().all()
            val allBooks = app.db.books().all()

            val payload = CrossBookSerendipityEngine.findBestMatch(
                currentBookPath = currentBook.path,
                currentBookTitle = currentBook.title,
                currentBookAuthor = currentBook.author,
                currentHighlights = currentHighlights,
                allHighlights = allHl,
                allBooks = allBooks
            )

            withContext(Dispatchers.Main) {
                if (payload == null) {
                    Toast.makeText(
                        activity,
                        L10n.t("서재의 다른 책에 저장된 하이라이트/메모가 1개 이상 필요합니다.", "Requires at least 1 note in another book in library."),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }

                // If on LCD and user has API key -> invoke Gemini API with Hourglass
                if (isLcd) {
                    val apiKey = app.prefs.geminiApiKey.trim()
                    if (apiKey.isBlank()) {
                        showApiKeyInputDialog(
                            activity = activity,
                            app = app,
                            onKeySaved = { validKey ->
                                generateAndDispatchAiCrossBook(activity, payload, validKey, isLcd, onBack)
                            },
                            onSkipClassic = {
                                generateAndDispatchClassicCrossBook(activity, payload, isLcd, onBack)
                            }
                        )
                    } else {
                        generateAndDispatchAiCrossBook(activity, payload, apiKey, isLcd, onBack)
                    }
                } else {
                    // e-ink: Pure classic card, zero network delay
                    generateAndDispatchClassicCrossBook(activity, payload, isLcd = false, onBack = onBack)
                }
            }
        }
    }

    private fun generateAndDispatchAiCrossBook(
        activity: Activity,
        payload: CrossBookPayload,
        apiKey: String,
        isLcd: Boolean,
        onBack: (() -> Unit)?
    ) {
        val hourglassDlg = showHourglassLoadingDialog(
            activity,
            L10n.t("⏳ Gemini AI가 두 도서의 지적 통찰을 분석하고 있습니다... ✨", "⏳ Gemini AI is analyzing intellectual synergies... ✨")
        )

        CoroutineScope(Dispatchers.IO).launch {
            val insight = runCatching {
                GeminiApiClient.generateSerendipityInsight(
                    apiKey = apiKey,
                    bookTitleA = payload.pastBook.bookTitle,
                    authorA = payload.pastBook.bookAuthor,
                    quoteA = payload.pastBook.quote,
                    noteA = payload.pastBook.note,
                    bookTitleB = payload.currentBook.bookTitle,
                    authorB = payload.currentBook.bookAuthor,
                    quoteB = payload.currentBook.quote,
                    noteB = payload.currentBook.note
                )
            }.getOrNull()

            val cardBmp = CrossBookSerendipityEngine.renderCrossBookCard(payload, insight)

            withContext(Dispatchers.Main) {
                hourglassDlg.dismiss()
                AndroidShareHelper.showSnsTargetPicker(
                    activity = activity,
                    dialogTitle = L10n.t("✦ 과거 읽은 책과의 대화 (AI 통찰) 공유", "Share Cross-Book Serendipity Card"),
                    subtitle = "《${payload.pastBook.bookTitle}》 × 《${payload.currentBook.bookTitle}》",
                    isEink = !isLcd,
                    onBack = onBack
                ) { target: AndroidShareHelper.SnsTarget ->
                    AndroidShareHelper.dispatchSnsTarget(
                        activity = activity,
                        target = target,
                        cardBitmap = cardBmp,
                        textToShare = "[Aether SAGA] 《${payload.pastBook.bookTitle}》 × 《${payload.currentBook.bookTitle}》\n" +
                            (if (!insight.isNullOrBlank()) "💡 $insight\n" else "") +
                            "#AetherSAGA #독서세렌디피티 #GeminiAI",
                        isStory = false
                    )
                }
            }
        }
    }

    private fun generateAndDispatchClassicCrossBook(
        activity: Activity,
        payload: CrossBookPayload,
        isLcd: Boolean,
        onBack: (() -> Unit)?
    ) {
        val cardBmp = CrossBookSerendipityEngine.renderCrossBookCard(payload, null)
        AndroidShareHelper.showSnsTargetPicker(
            activity = activity,
            dialogTitle = L10n.t("✦ 과거 읽은 책과의 대화 카드 공유", "Share Cross-Book Serendipity Card"),
            subtitle = "《${payload.pastBook.bookTitle}》 × 《${payload.currentBook.bookTitle}》",
            isEink = !isLcd,
            onBack = onBack
        ) { target: AndroidShareHelper.SnsTarget ->
            AndroidShareHelper.dispatchSnsTarget(
                activity = activity,
                target = target,
                cardBitmap = cardBmp,
                textToShare = "[Aether SAGA] 《${payload.pastBook.bookTitle}》 × 《${payload.currentBook.bookTitle}》\n" +
                    L10n.t("💡 두 권의 책이 나눈 지적인 대화", "💡 A dialogue between two books") + "\n#AetherSAGA #독서세렌디피티",
                isStory = false
            )
        }
    }

    private fun shareEvolutionTimelineCard(
        activity: Activity,
        app: App,
        currentBook: BookEntity,
        currentHighlights: List<HighlightEntity>,
        isLcd: Boolean,
        onBack: (() -> Unit)?
    ) {
        val payload = EvolutionTimelineEngine.buildPayload(currentBook, currentHighlights)
        if (payload == null) {
            Toast.makeText(
                activity,
                L10n.t("이 책에 저장된 하이라이트나 메모가 1개 이상 필요합니다.", "Requires at least 1 highlight or note in this book."),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val cardBmp = EvolutionTimelineEngine.renderStoryCard(activity, payload)
        AndroidShareHelper.showSnsTargetPicker(
            activity = activity,
            dialogTitle = L10n.t("🌱 생각의 진화 연대기 스토리 카드 공유", "Share Evolution Timeline Story"),
            subtitle = "《${payload.bookTitle}》 — " + L10n.t("한 권의 책이 바꾼 나의 생각들", "Thoughts Transformed by Book"),
            isEink = !isLcd,
            onBack = { showAdvancedMemoMenu(activity, app, currentBook, currentHighlights, isLcd, onBack) }
        ) { target: AndroidShareHelper.SnsTarget ->
            AndroidShareHelper.dispatchSnsTarget(
                activity = activity,
                target = target,
                cardBitmap = cardBmp,
                textToShare = "[Aether SAGA] 《${payload.bookTitle}》 " + L10n.t("생각의 진화 연대기", "Idea Evolution Story") + "\n#AetherSAGA #완독연대기",
                isStory = true
            )
        }
    }

    fun shareKnowledgeGraphCard(
        activity: Activity,
        app: App,
        isLcd: Boolean,
        onBack: (() -> Unit)?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val allHl = app.db.highlights().all()
            val allBooks = app.db.books().all()
            val payload = KnowledgeGraphEngine.buildPayload(allBooks, allHl)

            withContext(Dispatchers.Main) {
                val cardBmp = KnowledgeGraphEngine.renderGraphCard(activity, payload)
                AndroidShareHelper.showSnsTargetPicker(
                    activity = activity,
                    dialogTitle = L10n.t("🧠 나의 독서 뇌 지도 (Mind Map) 공유", "Share Reading Brain Mind Map"),
                    subtitle = L10n.t("서재 ${payload.totalBooks}권 · ${payload.totalNotes}개의 생각 연결망", "${payload.totalBooks} books · ${payload.totalNotes} thoughts"),
                    isEink = !isLcd,
                    onBack = onBack
                ) { target: AndroidShareHelper.SnsTarget ->
                    AndroidShareHelper.dispatchSnsTarget(
                        activity = activity,
                        target = target,
                        cardBitmap = cardBmp,
                        textToShare = "[Aether SAGA] " + L10n.t("나의 독서 뇌 지도 (Personal Knowledge Map)", "My Reading Brain Knowledge Map") + "\n#AetherSAGA #독서마인드맵",
                        isStory = false
                    )
                }
            }
        }
    }
}
