package com.aether.reader.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.aether.reader.App
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.button(text: String, fg: Int = Color.BLACK, bg: Int = Color.WHITE, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        setPtSize(8.5f)
        isAllCaps = false
        typeface = ReaderTheme.uiFontMedium
        setTextColor(fg)
        background = GradientDrawable().apply { setColor(bg); setStroke(dp(1).coerceAtLeast(2), fg); cornerRadius = dp(4).toFloat() }
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

fun Context.seekDialog(title: String, min: Int, max: Int, cur: Int, fmt: (Int) -> String = { it.toString() }, onSet: (Int) -> Unit) {
    val tv = TextView(this).apply {
        text = fmt(cur)
        gravity = Gravity.CENTER
        setPtSize(9.5f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(ReaderTheme.EINK_INK)
    }
    val sb = SeekBar(this).apply {
        this.max = max - min; progress = (cur - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = fmt(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)); addView(tv); addView(sb) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton(L10n.t("확인", "OK")) { _, _ -> onSet(sb.progress + min) }
        .setNegativeButton(L10n.t("취소", "Cancel"), null)
        .create().showWithOptimalWidth(this)
}

fun Context.inputDialog(
    title: String,
    initial: String = "",
    multiline: Boolean = false,
    imeAction: Int = if (title.contains("검색") || title.contains("Search")) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE,
    onOk: (String) -> Unit
) {
    lateinit var dlg: AlertDialog
    val et = EditText(this).apply {
        setText(initial)
        setPtSize(9f)
        typeface = ReaderTheme.uiFontRegular
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        } else {
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            this.imeOptions = imeAction
            setOnEditorActionListener { _, actionId, event ->
                val isEnterKey = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == imeAction || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || isEnterKey) {
                    val textStr = text.toString()
                    dlg.dismiss()
                    onOk(textStr)
                    true
                } else {
                    false
                }
            }
        }
        if (initial.isNotEmpty()) {
            setSelection(initial.length)
        }
    }
    dlg = AlertDialog.Builder(this).setTitle(title).setView(et)
        .setPositiveButton(L10n.t("확인", "OK")) { _, _ -> onOk(et.text.toString()) }
        .setNegativeButton(L10n.t("취소", "Cancel"), null)
        .create()
    dlg.showWithOptimalWidth(this)
}

fun Context.listDialog(
    title: String,
    items: List<String>,
    customHeight: Int? = null,
    onBack: (() -> Unit)? = null,
    onPick: (Int) -> Unit
) {
    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.apply {
                setPtSize(9f)
                typeface = ReaderTheme.uiFontMedium
                setTextColor(ReaderTheme.EINK_INK)
                setPadding(dp(20), dp(11), dp(20), dp(11))
            }
            return v
        }
    }
    AlertDialog.Builder(this).setTitle(title).setAdapter(adapter) { _, i -> onPick(i) }
        .create().showWithOptimalWidth(this, customHeight = customHeight, onBack = onBack)
}

fun View.hideSystemBars() {
    @Suppress("DEPRECATION")
    systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

fun android.app.Activity.showSystemNavigationBar() {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        window.insetsController?.show(android.view.WindowInsets.Type.navigationBars())
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        window.navigationBarColor = Color.WHITE
    }
}

fun Context.showEmailHighlightsZipDialog(
    app: App,
    bookTitle: String,
    bookAuthor: String,
    highlights: List<HighlightEntity>,
    onBack: (() -> Unit)? = null
) {
    if (highlights.isEmpty()) {
        Toast.makeText(this, L10n.t("전송할 하이라이트/메모가 없습니다", "No highlights or notes to send"), Toast.LENGTH_SHORT).show()
        return
    }

    val inkCol = ReaderTheme.EINK_INK
    val cardBgCol = ReaderTheme.EINK_CARD_BG
    val borderCol = ReaderTheme.EINK_BORDER
    val mutedCol = ReaderTheme.EINK_MUTED

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
    }

    // Friendly introduction banner
    val introTv = TextView(this).apply {
        text = L10n.t(
            "📖 소중하게 기록하신 독서 하이라이트와 메모를 도서 상세 정보와 함께 정리하여 이메일로 전송합니다.",
            "📖 Summarizes your reading highlights, notes, and detailed book information to send via email."
        )
        setPtSize(8.3f)
        setTextColor(inkCol)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, 0, 0, dp(10))
    }
    root.addView(introTv)

    // Detailed Book Information Card
    val infoCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardDrawable(cardBgCol, 8, borderCol)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    val titleTv = TextView(this).apply {
        text = "《$bookTitle》"
        setPtSize(10f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
    }
    val authorPart = if (bookAuthor.isNotBlank()) L10n.t("저자: $bookAuthor", "Author: $bookAuthor") else L10n.t("저자: 저자 정보 없음", "Author: Unknown")
    val authorTv = TextView(this).apply {
        text = "✍ $authorPart"
        setPtSize(8.2f)
        setTextColor(mutedCol)
        setPadding(0, dp(3), 0, dp(4))
    }

    val hlCount = highlights.count { it.kind == 0 }
    val bmCount = highlights.count { it.kind == 1 }
    val memoCount = highlights.count { it.note.isNotBlank() }
    val statsTv = TextView(this).apply {
        text = L10n.t("📊 기록 내역: 하이라이트 ${hlCount}건 · 북마크 ${bmCount}건", "📊 Record: Highlights $hlCount · Bookmarks $bmCount") +
            (if (memoCount > 0) L10n.t(" · 작성 메모 ${memoCount}건", " · Notes $memoCount") else "")
        setPtSize(8f)
        typeface = ReaderTheme.uiFontMedium
        setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
        setPadding(0, 0, 0, dp(3))
    }

    val latestDate = highlights.maxOfOrNull { it.createdAt } ?: System.currentTimeMillis()
    val dateStr = if (L10n.isKorean) SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN).format(Date(latestDate)) else SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(latestDate))
    val dateTv = TextView(this).apply {
        text = L10n.t("📅 최근 기록일: $dateStr", "📅 Last recorded: $dateStr")
        setPtSize(7.5f)
        setTextColor(mutedCol)
        setPadding(0, 0, 0, dp(6))
    }

    val zipBadgeTv = TextView(this).apply {
        text = L10n.t(
            "📦 첨부파일: AetherSAGA_Highlights_${bookTitle.take(15)}.zip\n(텍스트 .txt 및 마크다운 .md 서식 문서 포함)",
            "📦 Attachment: AetherSAGA_Highlights_${bookTitle.take(15)}.zip\n(Includes text .txt & Markdown .md documents)"
        )
        setPtSize(7.5f)
        setTextColor(ReaderTheme.ACCENT_KINDLE)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    infoCard.addView(titleTv)
    infoCard.addView(authorTv)
    infoCard.addView(statsTv)
    infoCard.addView(dateTv)
    infoCard.addView(zipBadgeTv)
    root.addView(infoCard)

    // Highlights Preview Box
    val previewHeaderTv = TextView(this).apply {
        text = L10n.t("📝 전송 대상 하이라이트 목록 (${highlights.size}건)", "📝 Highlights to send (${highlights.size})")
        setPtSize(8.3f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
        setPadding(0, dp(12), 0, dp(4))
    }
    root.addView(previewHeaderTv)

    val previewScroll = ScrollView(this).apply {
        background = cardDrawable(cardBgCol, 6, borderCol)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        enableVisibleScrollbar()
    }
    val previewCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for ((idx, h) in highlights.withIndex()) {
        val itemTv = TextView(this).apply {
            val prefix = if (h.kind == 1) "🔖 " else "🖍 "
            val notePart = if (h.note.isNotBlank()) L10n.t("\n   ✍ 메모: ${h.note.take(50)}", "\n   ✍ Note: ${h.note.take(50)}") else ""
            val chPart = h.chapterTitle.ifBlank { L10n.t("본문", "Body") }
            text = "${idx + 1}. $prefix[$chPart] ${h.quote.replace("\n", " ").take(65)}$notePart"
            setPtSize(7.8f)
            setTextColor(inkCol)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        previewCol.addView(itemTv)
        if (idx < highlights.size - 1) {
            previewCol.addView(View(this).apply {
                setBackgroundColor(borderCol)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
            })
        }
    }
    previewScroll.addView(previewCol)
    val maxScrollH = (resources.displayMetrics.heightPixels * 0.22f).toInt().coerceIn(dp(80), dp(140))
    root.addView(previewScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (highlights.size > 2) height = maxScrollH
    })

    // Recipient email
    val emailLabel = TextView(this).apply {
        text = L10n.t("받는 사람 이메일 주소", "Recipient Email Address")
        setPtSize(8.5f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
        setPadding(0, dp(12), 0, dp(4))
    }
    val emailInput = EditText(this).apply {
        setText(app.prefs.shareEmail)
        hint = "example@email.com"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setPtSize(9f)
        setTextColor(inkCol)
        setHintTextColor(mutedCol)
        background = cardDrawable(cardBgCol, 6, borderCol)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isSingleLine = true
    }
    root.addView(emailLabel)
    root.addView(emailInput)

    val guideTv = TextView(this).apply {
        text = L10n.t(
            "※ [이메일 전송하기] 버튼을 누르면 이메일 앱(Gmail 등)이 열려 도서 상세 정보와 전체 하이라이트 및 ZIP 파일이 자동 첨부됩니다. 확인 후 '보내기'를 눌러주세요.",
            "※ Tapping [Send Email] opens your email app (Gmail, etc.) with book details, all highlights, and ZIP file attached automatically. Please review and tap 'Send'."
        )
        setPtSize(7.5f)
        setTextColor(mutedCol)
        setPadding(0, dp(6), 0, dp(10))
    }
    root.addView(guideTv)

    lateinit var dlg: AlertDialog
    val executeSend = {
        val email = emailInput.text.toString().trim()
        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, L10n.t("올바른 이메일 주소를 입력해주세요", "Please enter a valid email address"), Toast.LENGTH_SHORT).show()
        } else {
            app.prefs.shareEmail = email
            dlg.dismiss()
            val ok = AndroidShareHelper.sendHighlightsZipEmail(
                this,
                email,
                bookTitle,
                bookAuthor,
                highlights
            )
            if (ok) {
                Toast.makeText(this, L10n.t("📨 이메일 앱이 열렸습니다. 확인 후 '보내기'를 눌러주세요.", "📨 Email app opened. Please review and tap 'Send'."), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, L10n.t("이메일 앱을 열지 못했습니다. 설치된 이메일 앱을 확인해주세요.", "Failed to open email app. Please check installed email apps."), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Unified primary action button (Single Button for share)
    val sendActionBtn = pillButton(L10n.t("📨 이메일 전송하기", "📨 Send Email"), Color.WHITE, ReaderTheme.ACCENT_RIDI_BLUE, ReaderTheme.ACCENT_RIDI_BLUE) {
        executeSend()
    }.apply {
        setPtSize(9.5f)
        typeface = ReaderTheme.uiFontBold
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    root.addView(sendActionBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

    val scroll = ScrollView(this).apply {
        isFillViewport = true
        enableVisibleScrollbar()
    }
    scroll.addView(root)

    dlg = AlertDialog.Builder(this)
        .setTitle(L10n.t("📦 도서 하이라이트 압축파일(ZIP) 전송", "📦 Export Highlights (ZIP) via Email"))
        .setView(scroll)
        .create()
    dlg.showWithOptimalWidth(this, onBack = onBack)
}
