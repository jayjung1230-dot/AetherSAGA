package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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

    val distinctBooks = highlights.map { (it.bookTitle.ifBlank { bookTitle }).trim() to (it.bookAuthor.ifBlank { bookAuthor }).trim() }.distinct()

    // Detailed Book Information Card
    val infoCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardDrawable(cardBgCol, 8, borderCol)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    val titleTv = TextView(this).apply {
        text = if (distinctBooks.size > 1) {
            L10n.t("📚 선택 도서: ", "📚 Selected Books: ") + distinctBooks.joinToString(", ") { "《${it.first}》" } + " (${distinctBooks.size}" + L10n.t("권)", " books)")
        } else {
            "《${distinctBooks.firstOrNull()?.first?.ifBlank { bookTitle } ?: bookTitle}》"
        }
        setPtSize(9.5f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
    }
    val authorTv = TextView(this).apply {
        text = if (distinctBooks.size > 1) {
            "✍ " + L10n.t("포함 저자: ", "Authors: ") + distinctBooks.mapNotNull { it.second.ifBlank { null } }.distinct().ifEmpty { listOf(L10n.t("저자 미상", "Unknown")) }.joinToString(", ")
        } else {
            val singleAuthor = distinctBooks.firstOrNull()?.second?.ifBlank { bookAuthor } ?: bookAuthor
            "✍ " + (if (singleAuthor.isNotBlank()) L10n.t("저자: $singleAuthor", "Author: $singleAuthor") else L10n.t("저자: 저자 정보 없음", "Author: Unknown"))
        }
        setPtSize(8f)
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

    val zipAttachLabel = if (distinctBooks.size > 1) {
        L10n.t(
            "📦 첨부파일: AetherSAGA_Highlights_${distinctBooks.size}권_${highlights.size}건.zip\n(도서 정보 수록 텍스트 .txt 및 마크다운 .md 포함)",
            "📦 Attachment: AetherSAGA_Highlights_${distinctBooks.size}Books_${highlights.size}Items.zip\n(Includes text .txt & Markdown .md with book info)"
        )
    } else {
        L10n.t(
            "📦 첨부파일: AetherSAGA_Highlights_${bookTitle.take(15)}.zip\n(텍스트 .txt 및 마크다운 .md 서식 문서 포함)",
            "📦 Attachment: AetherSAGA_Highlights_${bookTitle.take(15)}.zip\n(Includes text .txt & Markdown .md documents)"
        )
    }
    val zipBadgeTv = TextView(this).apply {
        text = zipAttachLabel
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

    // 3. Checklist Header + Select All / Deselect All
    val listHeaderRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
    }
    val previewHeaderTv = TextView(this).apply {
        text = L10n.t("📝 전송 항목 및 도서 정보 선택 (${highlights.size}건)", "📝 Select Items to Send (${highlights.size})")
        setPtSize(8.3f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
    }
    val checkBoxes = ArrayList<CheckBox>()
    val toggleAllBtn = pillButton(L10n.t("전체 선택/해제", "Select All / None"), ReaderTheme.EINK_INK, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
        val allChecked = checkBoxes.all { it.isChecked }
        checkBoxes.forEach { it.isChecked = !allChecked }
    }.apply {
        setPtSize(7.4f)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        minHeight = dp(26)
        minimumHeight = dp(26)
    }
    listHeaderRow.addView(previewHeaderTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    listHeaderRow.addView(toggleAllBtn)
    root.addView(listHeaderRow)

    val previewScroll = ScrollView(this).apply {
        background = cardDrawable(cardBgCol, 6, borderCol)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        enableVisibleScrollbar()
    }
    val previewCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for ((idx, h) in highlights.withIndex()) {
        val prefix = if (h.kind == 1) "🔖 " else "🖍 "
        val notePart = if (h.note.isNotBlank()) L10n.t(" (메모: ${h.note.take(30)})", " (Note: ${h.note.take(30)})") else ""
        val chPart = h.chapterTitle.ifBlank { L10n.t("본문", "Body") }
        val itemBook = h.bookTitle.ifBlank { bookTitle }
        val itemAuthor = h.bookAuthor.ifBlank { bookAuthor }
        val bookTag = if (itemAuthor.isNotBlank()) "📖 《$itemBook》($itemAuthor)" else "📖 《$itemBook》"
        val cb = CheckBox(this).apply {
            text = "$bookTag\n$prefix[$chPart] ${h.quote.replace("\n", " ").take(45)}$notePart"
            isChecked = true // 기본값: 모두 선택
            setPtSize(7.8f)
            setTextColor(inkCol)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        checkBoxes.add(cb)
        previewCol.addView(cb)
    }
    previewScroll.addView(previewCol)
    val maxScrollH = (resources.displayMetrics.heightPixels * 0.24f).toInt().coerceIn(dp(85), dp(150))
    root.addView(previewScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (highlights.size > 2) height = maxScrollH
    })

    // Book Info in Export Option Checkbox
    val includeBookInfoCb = CheckBox(this).apply {
        text = L10n.t("🌟 압축파일(TXT/MD) 및 이메일에 도서 정보(제목·저자) 함께 포함", "🌟 Include book metadata (title, author) in ZIP & email")
        isChecked = true
        setPtSize(7.8f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
        setPadding(dp(4), dp(6), dp(4), dp(2))
    }
    root.addView(includeBookInfoCb)

    // Recipient email
    val emailLabel = TextView(this).apply {
        text = L10n.t("받는 사람 이메일 주소", "Recipient Email Address")
        setPtSize(8.5f)
        typeface = ReaderTheme.uiFontBold
        setTextColor(inkCol)
        setPadding(0, dp(10), 0, dp(4))
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
            val selected = highlights.filterIndexed { i, _ -> checkBoxes[i].isChecked }
            if (selected.isEmpty()) {
                Toast.makeText(this, L10n.t("전송할 항목을 1개 이상 선택해주세요", "Please select at least one item to send"), Toast.LENGTH_SHORT).show()
            } else {
                app.prefs.shareEmail = email
                dlg.dismiss()
                val ok = AndroidShareHelper.sendHighlightsZipEmail(
                    this,
                    email,
                    bookTitle,
                    bookAuthor,
                    selected,
                    includeBookInfo = includeBookInfoCb.isChecked
                )
                if (ok) {
                    Toast.makeText(this, L10n.t("📨 이메일 앱이 열렸습니다. 확인 후 '보내기'를 눌러주세요.", "📨 Email app opened. Please review and tap 'Send'."), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, L10n.t("이메일 앱을 열지 못했습니다. 설치된 이메일 앱을 확인해주세요.", "Failed to open email app. Please check installed email apps."), Toast.LENGTH_SHORT).show()
                }
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

fun Activity.showReadingNotesShareDialog(
    app: App,
    book: com.aether.reader.data.BookEntity,
    highlights: List<HighlightEntity>,
    totalChapters: Int = 0,
    rawCoverBytes: ByteArray? = null,
    isLcd: Boolean = false,
    initialQuote: HighlightEntity? = null,
    onBack: (() -> Unit)? = null
) {
    val resolvedFormat = book.format.ifBlank { "EPUB" }
    val resolvedTitle = book.title.ifBlank { java.io.File(book.path).nameWithoutExtension.ifBlank { L10n.t("Aether SAGA 도서", "Aether SAGA Book") } }
    val resolvedAuthor = book.author.ifBlank { L10n.t("저자 미상", "Unknown Author") }

    val candidateItems = ArrayList<HighlightEntity>()
    if (initialQuote != null) {
        candidateItems.add(initialQuote)
    }
    for (h in highlights) {
        if (initialQuote == null || (h.chapter != initialQuote.chapter || h.startChar != initialQuote.startChar)) {
            candidateItems.add(h)
        }
    }

    val pct = (book.progress * 100).toInt()
    val defaultProgressQuote = L10n.t("'${resolvedTitle}' 독서 진행률 ${pct}% 달성!", "'${resolvedTitle}' reading progress: ${pct}%!")

    val coverBmp = AndroidShareHelper.prepareCoverBitmap(
        bookTitle = resolvedTitle,
        bookAuthor = resolvedAuthor,
        bookFormat = resolvedFormat,
        rawCoverBytes = rawCoverBytes,
        cachedCoverPath = book.coverPath
    )

    val outerScroll = ScrollView(this).apply {
        enableVisibleScrollbar()
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(8))
    }
    outerScroll.addView(root)

    val noteBorderCol = if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_BORDER_CONTRAST
    val noteStrokePx = if (isLcd) dp(1) else dp(2)

    // 1. Book Metadata + Cover Image Preview Card
    val bookCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardDrawable(ReaderTheme.EINK_CARD_BG, 8, noteBorderCol, noteStrokePx)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    val coverIv = ImageView(this).apply {
        setImageBitmap(coverBmp)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = cardDrawable(Color.WHITE, 4, noteBorderCol, noteStrokePx)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    bookCard.addView(coverIv, LinearLayout.LayoutParams(dp(58), dp(82)).apply {
        rightMargin = dp(12)
    })

    val metaCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val metaBadgeTv = TextView(this).apply {
        text = L10n.t("📚 함께 전송될 도서 정보", "📚 Book Information to Share")
        setPtSize(7.5f)
        setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
        typeface = ReaderTheme.uiFontBold
    }
    val metaTitleTv = TextView(this).apply {
        text = L10n.t("제목: $resolvedTitle", "Title: $resolvedTitle")
        setPtSize(8.8f)
        setTextColor(ReaderTheme.EINK_INK)
        typeface = ReaderTheme.uiFontBold
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, dp(1))
    }
    val metaAuthorTv = TextView(this).apply {
        text = L10n.t("저자: $resolvedAuthor  •  $resolvedFormat (${(book.progress * 100).toInt()}%)", "Author: $resolvedAuthor  •  $resolvedFormat (${(book.progress * 100).toInt()}%)")
        setPtSize(7.8f)
        setTextColor(ReaderTheme.EINK_MUTED)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val attachCoverCb = CheckBox(this).apply {
        text = L10n.t("🖼 책 표지 이미지 함께 첨부", "🖼 Attach Book Cover Image")
        isChecked = true
        setPtSize(8f)
        setTextColor(ReaderTheme.EINK_INK)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    metaCol.addView(metaBadgeTv)
    metaCol.addView(metaTitleTv)
    metaCol.addView(metaAuthorTv)
    metaCol.addView(attachCoverCb)
    bookCard.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(bookCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(10)
    })

    // 2. Email Address Input
    val emailLabel = TextView(this).apply {
        text = L10n.t("받는 사람 이메일 주소", "Recipient Email Address")
        setPtSize(8.5f)
        setTextColor(ReaderTheme.EINK_INK)
        typeface = ReaderTheme.uiFontBold
        setPadding(0, 0, 0, dp(4))
    }
    val emailInput = EditText(this).apply {
        setText(app.prefs.shareEmail)
        hint = "example@email.com"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setPtSize(9f)
        setTextColor(ReaderTheme.EINK_INK)
        setHintTextColor(ReaderTheme.EINK_MUTED)
        background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, noteBorderCol, noteStrokePx)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setSingleLine(true)
    }
    root.addView(emailLabel)
    root.addView(emailInput)

    // Friendly Guide Note for Email & SNS Share
    val emailGuideTv = TextView(this).apply {
        text = L10n.t(
            "※ [이메일 전송하기] 버튼을 누르면 이메일 앱(Gmail 등)의 작성 화면으로 도서 정보와 선택 문장 및 표지가 채워져 열립니다. [SNS 독서카드 공유]를 누르면 인포그래픽 독서 카드가 생성되어 SNS로 전달됩니다.",
            "※ Tapping [Send Email] opens your email composer with book info, quotes, and cover. Tapping [Share to SNS] generates an infographic reading card for social media."
        )
        setPtSize(7.6f)
        setTextColor(ReaderTheme.EINK_MUTED)
        setPadding(0, dp(4), 0, dp(4))
    }
    root.addView(emailGuideTv)

    // 3. Checklist Header + Select All CheckBox
    val listHeaderRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(6))
    }
    val listHeaderTv = TextView(this).apply {
        text = L10n.t("전송/공유할 문장 선택 (하이라이트 / 북마크)", "Select Quotes & Notes (Highlights / Bookmarks)")
        setPtSize(8.5f)
        setTextColor(ReaderTheme.EINK_INK)
        typeface = ReaderTheme.uiFontBold
    }
    val checkBoxes = ArrayList<CheckBox>()
    val progressCb = CheckBox(this).apply {
        text = L10n.t("📊 [도서 기본 정보 및 진행률] 진행률 ${pct}% 달성", "📊 [Basic Book Info & Progress] ${pct}% completed")
        isChecked = (candidateItems.isEmpty() || initialQuote == null)
        setPtSize(8.4f)
        setTextColor(ReaderTheme.EINK_INK)
        typeface = ReaderTheme.uiFontBold
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    // Direct Select-All CheckBox with bi-directional synchronization
    var isBulkUpdating = false
    val selectAllCb = CheckBox(this).apply {
        text = L10n.t("전체 선택", "Select All")
        isChecked = (initialQuote == null && candidateItems.isNotEmpty())
        isEnabled = candidateItems.isNotEmpty()
        setPtSize(7.8f)
        setTextColor(ReaderTheme.EINK_INK)
        typeface = ReaderTheme.uiFontBold
        setPadding(dp(2), dp(2), dp(4), dp(2))
        setOnCheckedChangeListener { _, checked ->
            if (isBulkUpdating) return@setOnCheckedChangeListener
            isBulkUpdating = true
            checkBoxes.forEach { it.isChecked = checked }
            isBulkUpdating = false
        }
    }
    listHeaderRow.addView(listHeaderTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    listHeaderRow.addView(selectAllCb)
    root.addView(listHeaderRow)

    // 4. Scrollable Checklist (with touch disallow intercept so outer ScrollView does not block scrolling)
    val listScroll = object : ScrollView(this) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.onTouchEvent(ev)
        }
    }.apply {
        background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, noteBorderCol, noteStrokePx)
        setPadding(dp(8), dp(6), dp(10), dp(6))
        enableVisibleScrollbar()
        isNestedScrollingEnabled = true
    }
    val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    listCol.addView(progressCb)

    if (candidateItems.isEmpty()) {
        val noHlNotice = TextView(this).apply {
            text = L10n.t("※ 저장된 하이라이트/메모가 없어 기본 진행률 카드로 공유됩니다.", "※ No highlights saved yet; sharing with basic reading progress.")
            setPtSize(7.6f)
            setTextColor(ReaderTheme.EINK_MUTED)
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        listCol.addView(noHlNotice)
    } else {
        val div = View(this).apply {
            setBackgroundColor(noteBorderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
        listCol.addView(div)
        val syncSelectAllState = {
            if (!isBulkUpdating) {
                isBulkUpdating = true
                selectAllCb.isChecked = checkBoxes.isNotEmpty() && checkBoxes.all { it.isChecked }
                isBulkUpdating = false
            }
        }
        for ((idx, item) in candidateItems.withIndex()) {
            val isInitial = (initialQuote != null && idx == 0)
            val prefix = when {
                isInitial -> L10n.t("✨ [현재 선택 문장] ", "✨ [Current Selection] ")
                item.kind == 1 -> L10n.t("🔖 [북마크] ", "🔖 [Bookmark] ")
                else -> L10n.t("🖍 [하이라이트] ", "🖍 [Highlight] ")
            }
            val cb = CheckBox(this).apply {
                text = "$prefix${item.chapterTitle}: ${item.quote.replace("\n", " ").take(65)}"
                isChecked = if (initialQuote != null) isInitial else true
                setPtSize(8.2f)
                setTextColor(ReaderTheme.EINK_INK)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnCheckedChangeListener { _, _ -> syncSelectAllState() }
            }
            checkBoxes.add(cb)
            listCol.addView(cb)
        }
        syncSelectAllState()
    }
    listScroll.addView(listCol)
    val maxScrollH = (resources.displayMetrics.heightPixels * 0.30f).toInt().coerceAtLeast(dp(140))
    root.addView(listScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (candidateItems.size > 2) height = maxScrollH
    })

    lateinit var dlg: AlertDialog
    val executeSend = {
        val email = emailInput.text.toString().trim()
        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, L10n.t("올바른 이메일 주소를 입력해주세요", "Please enter a valid email address"), Toast.LENGTH_SHORT).show()
        } else {
            val selected = ArrayList<HighlightEntity>()
            if (progressCb.isChecked) {
                selected.add(
                    HighlightEntity(
                        id = -999L,
                        bookPath = book.path,
                        kind = 0,
                        chapter = 0,
                        startChar = 0,
                        endChar = 0,
                        quote = defaultProgressQuote,
                        note = "",
                        style = 0,
                        colorTag = 0,
                        progress = book.progress,
                        chapterTitle = L10n.t("독서 진행 현황", "Reading Progress"),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            for (i in candidateItems.indices) {
                if (checkBoxes[i].isChecked) {
                    selected.add(candidateItems[i])
                }
            }
            if (selected.isEmpty()) {
                Toast.makeText(this, L10n.t("공유할 문장이나 진행률 항목을 1개 이상 선택해주세요", "Please select at least one quote or progress item"), Toast.LENGTH_SHORT).show()
            } else {
                app.prefs.shareEmail = email

                val attachCover = attachCoverCb.isChecked
                val subject = "[Aether SAGA] $resolvedTitle ($resolvedAuthor) - " + L10n.t("독서 공유 (${selected.size}건)", "Reading Share (${selected.size} items)")
                val plainBody = com.aether.reader.data.MarkdownFormatter.formatEmailBody(
                    book = book,
                    resolvedTitle = resolvedTitle,
                    resolvedAuthor = resolvedAuthor,
                    totalChapters = totalChapters,
                    items = selected,
                    hasCoverAttachment = attachCover
                )
                val coverUri = if (attachCover) {
                    AndroidShareHelper.prepareCoverShareUri(this, resolvedTitle, coverBmp)
                } else null

                dlg.dismiss()

                val opened = AndroidShareHelper.openEmailAppDirectly(
                    ctx = this,
                    toEmail = email,
                    subject = subject,
                    body = plainBody,
                    coverUri = coverUri
                )

                if (opened) {
                    Toast.makeText(this, L10n.t("📨 이메일 앱이 열렸습니다. 내용 확인 후 '보내기'를 눌러주세요.", "📨 Email app opened. Please review and tap 'Send'."), Toast.LENGTH_LONG).show()
                } else {
                    showEmailResultDialog(
                        isSuccess = false,
                        title = L10n.t("이메일 앱 실행 실패", "Failed to Open Email App"),
                        message = L10n.t(
                            "기기에서 이메일 앱(Gmail, 삼성 이메일 등)을 열 수 없습니다.\n이메일 앱이 설치되어 있고 기본 계정이 설정되어 있는지 확인해주세요.",
                            "Could not open email app (Gmail, Samsung Email, etc.). Please check that an email app is installed and configured."
                        )
                    )
                }
            }
        }
    }

    val executeSns = {
        val quoteItems = ArrayList<AndroidShareHelper.SnsQuoteItem>()
        if (progressCb.isChecked) {
            quoteItems.add(
                AndroidShareHelper.SnsQuoteItem(
                    quote = defaultProgressQuote,
                    memo = null,
                    chapterTitle = L10n.t("독서 진행 현황", "Reading Progress"),
                    isBookmark = false
                )
            )
        }
        for (i in candidateItems.indices) {
            if (checkBoxes[i].isChecked) {
                val h = candidateItems[i]
                quoteItems.add(
                    AndroidShareHelper.SnsQuoteItem(
                        quote = h.quote,
                        memo = h.note.ifBlank { null },
                        chapterTitle = h.chapterTitle.ifBlank { null },
                        isBookmark = (h.kind == 1)
                    )
                )
            }
        }
        if (quoteItems.isEmpty()) {
            Toast.makeText(this, L10n.t("공유할 문장이나 진행률 항목을 1개 이상 선택해주세요", "Please select at least one quote or progress item"), Toast.LENGTH_SHORT).show()
        } else {
            dlg.dismiss()
            AndroidShareHelper.showSnsShareDialog(
                activity = this,
                bookTitle = resolvedTitle,
                bookAuthor = resolvedAuthor,
                items = quoteItems,
                isEink = !isLcd,
                onBack = { showReadingNotesShareDialog(app, book, highlights, totalChapters, rawCoverBytes, isLcd, initialQuote, onBack) },
                coverBitmap = coverBmp
            )
        }
    }

    val actionBtnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, 0)
    }
    val sendBtn = pillButton(L10n.t("📨 이메일 전송하기", "📨 Send Email"), Color.WHITE, ReaderTheme.ACCENT_RIDI_BLUE, ReaderTheme.ACCENT_RIDI_BLUE) {
        executeSend()
    }.apply {
        setPtSize(9f)
        typeface = ReaderTheme.uiFontBold
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    val snsBtn = pillButton(L10n.t("📱 SNS 독서카드 공유", "📱 Share to SNS"), Color.WHITE, ReaderTheme.ACCENT_KINDLE, ReaderTheme.ACCENT_KINDLE) {
        executeSns()
    }.apply {
        setPtSize(9f)
        typeface = ReaderTheme.uiFontBold
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    actionBtnRow.addView(sendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
    actionBtnRow.addView(snsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    root.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
    root.addView(actionBtnRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

    val dm = resources.displayMetrics
    val isLandscape = dm.widthPixels > dm.heightPixels
    val emailDlgH = if (isLandscape) (dm.heightPixels * 0.90f).toInt().coerceAtMost(dp(560))
                    else (dm.heightPixels * 0.80f).toInt().coerceAtMost(dp(680))

    dlg = AlertDialog.Builder(this)
        .setTitle(L10n.t("도서 정보 및 독서 노트 공유", "Share Book Info & Reading Notes"))
        .setView(outerScroll)
        .create()
    dlg.showWithOptimalWidth(this, customHeight = emailDlgH, onBack = onBack)
}

fun Activity.showEmailResultDialog(isSuccess: Boolean, title: String, message: String) {
    val outer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(10))
    }
    val tv = TextView(this).apply {
        text = message
        setPtSize(8.5f)
        setTextColor(ReaderTheme.EINK_INK)
        setLineSpacing(dp(2).toFloat(), 1.15f)
    }
    outer.addView(tv)
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(outer)
        .setPositiveButton(L10n.t("확인", "OK"), null)
        .create()
        .showWithOptimalWidth(this)
}
