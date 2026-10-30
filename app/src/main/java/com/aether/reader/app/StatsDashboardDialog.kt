package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aether.reader.App
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.BookEntity
import com.aether.reader.data.CompletedBookEntity
import com.aether.reader.data.DailyReadingStatsEntity
import com.aether.reader.data.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

object StatsDashboardDialog {

    fun show(activity: Activity, app: App, isEink: Boolean, onBack: (() -> Unit)? = null) {
        val scope = CoroutineScope(Dispatchers.Main)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()

        val inkCol = if (isEink) ReaderTheme.EINK_INK else 0xFF1E293B.toInt()
        val cardBgCol = if (isEink) ReaderTheme.EINK_CARD_BG else Color.WHITE
        val borderCol = if (isEink) ReaderTheme.EINK_BORDER else 0xFFCBD5E1.toInt()
        val mutedCol = if (isEink) ReaderTheme.EINK_MUTED else 0xFF64748B.toInt()
        val accentCol = if (isEink) ReaderTheme.EINK_INK else ReaderTheme.ACCENT_RIDI_BLUE

        var currentPeriod = StatsPeriod.WEEKLY
        var currentMetric = StatsMetric.TIME
        var cachedRecords: List<DailyReadingStatsEntity> = emptyList()
        var cachedCompletedBooks: List<CompletedBookEntity> = emptyList()
        var cachedAllBooks: List<BookEntity> = emptyList()

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            enableVisibleScrollbar(isDark = false)
        }
        val rootBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setBackgroundColor(if (isEink) ReaderTheme.EINK_PAPER else 0xFFF8FAFC.toInt())
        }
        scroll.addView(rootBox)

        lateinit var dlg: AlertDialog

        // 1. Header with '✕' close button
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10f))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(activity).apply {
            text = L10n.t("📊 독서 통계 리포트", "📊 Reading Stats Report")
            textSize = 17f
            setTextColor(inkCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val subtitleTv = TextView(activity).apply {
            text = L10n.t("주간 · 월간 · 연간 독서 트렌드 비교", "Weekly, Monthly & Yearly Reading Trends")
            textSize = 12f
            setTextColor(mutedCol)
        }
        titleCol.addView(titleTv)
        titleCol.addView(subtitleTv)

        val closeBtn = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            setTextColor(inkCol)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { dlg.dismiss() }
        }
        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        rootBox.addView(headerRow)

        // 2. Period Tabs: 주간, 월간, 연간
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(10f))
        }
        lateinit var tabWeekly: Button
        lateinit var tabMonthly: Button
        lateinit var tabYearly: Button

        fun styleTabBtn(btn: Button, selected: Boolean) {
            btn.apply {
                if (selected) {
                    setTextColor(Color.WHITE)
                    background = activity.pillDrawable(if (isEink) ReaderTheme.EINK_INK else accentCol, if (isEink) ReaderTheme.EINK_INK else accentCol)
                } else {
                    setTextColor(inkCol)
                    background = activity.pillDrawable(cardBgCol, borderCol)
                }
            }
        }

        val chartView = StatsLineChartView(activity).apply {
            this.isEink = isEink
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(175f))
        }

        // Summary Metric & Total
        val metricRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(8f))
        }
        lateinit var btnTime: Button
        lateinit var btnPages: Button
        val totalLabelTv = TextView(activity).apply {
            text = L10n.t("합계: ", "Total: ")
            textSize = 12f
            setTextColor(mutedCol)
            gravity = Gravity.RIGHT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val totalValTv = TextView(activity).apply {
            text = if (L10n.isKorean) "0분" else "0m"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(inkCol)
        }

        // Summary 3-Column Card
        val summaryCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        }
        fun makeStatSubCol(label: String, valTv: TextView): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(4f), 0, dp(10f), 0)
                val lbl = TextView(activity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(mutedCol)
                    gravity = Gravity.END
                }
                valTv.gravity = Gravity.END
                addView(lbl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(valTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val avgTv = TextView(activity).apply { text = if (L10n.isKorean) "0분" else "0m"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(inkCol); gravity = Gravity.END; setPadding(0, dp(2f), 0, 0) }
        val pagesTv = TextView(activity).apply { text = "0p"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(inkCol); gravity = Gravity.END; setPadding(0, dp(2f), 0, 0) }
        val streakTv = TextView(activity).apply { text = if (L10n.isKorean) "0일" else "0 days"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(inkCol); gravity = Gravity.END; setPadding(0, dp(2f), 0, 0) }

        summaryCard.addView(makeStatSubCol(L10n.t("일평균 독서", "Daily Avg"), avgTv))
        summaryCard.addView(View(activity).apply { setBackgroundColor(borderCol); layoutParams = LinearLayout.LayoutParams(dp(1f), ViewGroup.LayoutParams.MATCH_PARENT) })
        summaryCard.addView(makeStatSubCol(L10n.t("누적 독서량", "Total Pages"), pagesTv))
        summaryCard.addView(View(activity).apply { setBackgroundColor(borderCol); layoutParams = LinearLayout.LayoutParams(dp(1f), ViewGroup.LayoutParams.MATCH_PARENT) })
        summaryCard.addView(makeStatSubCol(L10n.t("연속 독서", "Day Streak"), streakTv))

        // Insight Card
        val insightCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val iconTv = TextView(activity).apply { text = "💡"; textSize = 15f; setPadding(0, 0, dp(8f), 0) }
        val insightTv = TextView(activity).apply {
            text = L10n.t("독서 데이터를 불러오는 중입니다...", "Loading reading data...")
            textSize = 12f
            setTextColor(inkCol)
        }
        insightCard.addView(iconTv)
        insightCard.addView(insightTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Completed Books Card (월간 / 연간 완독 도서 목록)
        val completedCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            visibility = View.GONE
        }
        val completedHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        val completedTitleTv = TextView(activity).apply {
            text = L10n.t("📚 완독 도서", "📚 Completed Books")
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(inkCol)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val completedCountBadgeTv = TextView(activity).apply {
            text = if (L10n.isKorean) "0권" else "0 books"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentCol)
        }
        completedHeaderRow.addView(completedTitleTv)
        completedHeaderRow.addView(completedCountBadgeTv)
        completedCard.addView(completedHeaderRow)

        val completedDivider = View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                bottomMargin = dp(6f)
            }
        }
        completedCard.addView(completedDivider)

        val completedListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        completedCard.addView(completedListContainer)

        fun estimateBookPages(book: BookEntity): Pair<Int, Int> {
            val exactTotal: Int? = when (book.format.uppercase(Locale.US)) {
                "PDF" -> runCatching {
                    val f = File(book.path)
                    if (f.exists()) {
                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            PdfRenderer(pfd).use { it.pageCount }
                        }
                    } else null
                }.getOrNull()
                "CBZ" -> runCatching {
                    val f = File(book.path)
                    if (f.exists()) {
                        ZipFile(f).use { zip ->
                            zip.entries().asSequence().count { entry ->
                                !entry.isDirectory && entry.name.let { n ->
                                    n.endsWith(".jpg", true) || n.endsWith(".jpeg", true) ||
                                    n.endsWith(".png", true) || n.endsWith(".webp", true) ||
                                    n.endsWith(".gif", true)
                                }
                            }
                        }
                    } else null
                }.getOrNull()
                "EPUB" -> runCatching {
                    val f = File(book.path)
                    if (f.exists()) {
                        ZipFile(f).use { zip ->
                            var totalTextBytes = 0L
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (!entry.isDirectory) {
                                    val name = entry.name.lowercase(Locale.US)
                                    if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                                        val sz = if (entry.size > 0) entry.size else entry.compressedSize * 2
                                        totalTextBytes += sz
                                    }
                                }
                            }
                            if (totalTextBytes > 0) {
                                ((totalTextBytes / 1300.0).toInt()).coerceIn(1, 3000)
                            } else null
                        }
                    } else null
                }.getOrNull()
                else -> null
            }

            val total = if (exactTotal != null && exactTotal > 0) {
                exactTotal
            } else {
                if (book.sizeBytes > 0) {
                    val divisor = when (book.format.uppercase(Locale.US)) {
                        "EPUB" -> 2200.0
                        "TXT", "MD", "MARKDOWN" -> 1200.0
                        else -> 1800.0
                    }
                    ((book.sizeBytes / divisor).toInt()).coerceIn(1, 2000)
                } else {
                    100
                }
            }

            val cur = if (book.progress >= 0.98f) {
                total
            } else {
                (1 + ((total - 1) * book.progress.coerceIn(0f, 1f))).toInt().coerceIn(1, total)
            }
            return cur to total
        }

        fun renderDashboard() {
            styleTabBtn(tabWeekly, currentPeriod == StatsPeriod.WEEKLY)
            styleTabBtn(tabMonthly, currentPeriod == StatsPeriod.MONTHLY)
            styleTabBtn(tabYearly, currentPeriod == StatsPeriod.YEARLY)

            styleTabBtn(btnTime, currentMetric == StatsMetric.TIME)
            styleTabBtn(btnPages, currentMetric == StatsMetric.PAGES)

            val res = StatsAggregator.aggregate(cachedRecords, currentPeriod, currentMetric, Date(), cachedCompletedBooks)
            chartView.setData(res.points, res.yMax, if (currentMetric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p")

            totalValTv.text = if (currentMetric == StatsMetric.TIME) res.totalTimeStr else res.totalPagesStr
            avgTv.text = res.dailyAvgStr
            pagesTv.text = res.totalPagesStr
            streakTv.text = if (L10n.isKorean) "${res.streakDays}일 연속 🔥" else "${res.streakDays}-day streak 🔥"
            insightTv.text = res.insightText

            val curCal = Calendar.getInstance()
            val curYear = curCal.get(Calendar.YEAR)
            val curMonth = curCal.get(Calendar.MONTH) + 1
            val curMonthName = java.text.DateFormatSymbols(Locale.US).months.getOrNull(curMonth - 1) ?: "$curMonth"

            subtitleTv.text = when (currentPeriod) {
                StatsPeriod.WEEKLY -> L10n.t("최근 7일간 독서 트렌드 비교", "7-Day Reading Trend")
                StatsPeriod.MONTHLY -> L10n.t("최근 4주간 주차별 독서 비교", "4-Week Reading Trend")
                StatsPeriod.YEARLY -> if (L10n.isKorean) "${curYear}년 월별 연간 독서 트렌드" else "$curYear Monthly Reading Trends"
            }

            when (currentPeriod) {
                StatsPeriod.WEEKLY -> {
                    completedCard.visibility = View.VISIBLE
                    completedTitleTv.text = L10n.t("📚 최근 7일간 읽은 도서", "📚 Books Read in Past 7 Days")

                    val weekDates = (0..6).map { i ->
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
                    }.toSet()

                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -6)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val weekStartTs = cal.timeInMillis

                    val completedInWeek = cachedCompletedBooks.filter { c ->
                        c.completedAt >= weekStartTs || weekDates.contains(c.completedDate)
                    }

                    val weeklyBooks = cachedAllBooks.filter { b ->
                        b.lastRead >= weekStartTs || completedInWeek.any { c ->
                            c.bookPath == b.path || (b.title.isNotBlank() && c.title.equals(b.title, ignoreCase = true))
                        }
                    }.toMutableList()

                    for (c in completedInWeek) {
                        if (weeklyBooks.none { it.path == c.bookPath || (it.title.isNotBlank() && it.title.equals(c.title, ignoreCase = true)) }) {
                            weeklyBooks.add(
                                BookEntity(
                                    path = c.bookPath,
                                    title = c.title,
                                    author = c.author,
                                    format = "EPUB",
                                    sizeBytes = 0L,
                                    addedAt = c.completedAt,
                                    lastRead = c.completedAt,
                                    progress = 1.0f
                                )
                            )
                        }
                    }
                    weeklyBooks.sortByDescending { it.lastRead }

                    completedCountBadgeTv.text = if (L10n.isKorean) "총 ${weeklyBooks.size}권" else "Total: ${weeklyBooks.size}"
                    completedListContainer.removeAllViews()

                    if (weeklyBooks.isEmpty()) {
                        val emptyTv = TextView(activity).apply {
                            text = L10n.t("최근 7일간 읽은 도서가 없습니다.", "No books read in the past 7 days.")
                            textSize = 12f
                            setTextColor(mutedCol)
                            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                        }
                        completedListContainer.addView(emptyTv)
                    } else {
                        weeklyBooks.forEachIndexed { idx, book ->
                            val isCompleted = book.progress >= 0.98f || cachedCompletedBooks.any {
                                it.bookPath == book.path || (book.title.isNotBlank() && it.title.equals(book.title, ignoreCase = true))
                            }

                            val row = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, dp(4f), 0, dp(4f))
                            }
                            val markTv = TextView(activity).apply {
                                text = if (isCompleted) "✓" else "•"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(if (isCompleted) accentCol else mutedCol)
                                setPadding(0, 0, dp(6f), 0)
                            }
                            val infoCol = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val bTitleTv = TextView(activity).apply {
                                text = book.title.ifBlank { File(book.path).nameWithoutExtension }
                                textSize = 12.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(inkCol)
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                            infoCol.addView(bTitleTv)
                            if (book.author.isNotBlank()) {
                                val authorTv = TextView(activity).apply {
                                    text = book.author
                                    textSize = 10.5f
                                    setTextColor(mutedCol)
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                }
                                infoCol.addView(authorTv)
                            }

                            val statusTv = TextView(activity).apply {
                                text = if (isCompleted) {
                                    L10n.t("완독", "Finished")
                                } else {
                                    val (curPage, totalPage) = estimateBookPages(book)
                                    "$curPage / $totalPage"
                                }
                                textSize = 11.5f
                                if (isCompleted) {
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(accentCol)
                                } else {
                                    setTextColor(mutedCol)
                                }
                                gravity = Gravity.END
                                setPadding(dp(6f), 0, 0, 0)
                            }

                            row.addView(markTv)
                            row.addView(infoCol)
                            row.addView(statusTv)
                            completedListContainer.addView(row)

                            if (idx < weeklyBooks.size - 1) {
                                completedListContainer.addView(View(activity).apply {
                                    setBackgroundColor(borderCol)
                                    alpha = 0.5f
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.7f)).apply {
                                        topMargin = dp(3f)
                                        bottomMargin = dp(3f)
                                    }
                                })
                            }
                        }
                    }
                }
                StatsPeriod.MONTHLY -> {
                    completedCard.visibility = View.VISIBLE
                    completedTitleTv.text = if (L10n.isKorean) "📚 ${curMonth}월 완독 도서" else "📚 Finished in $curMonthName"
                    completedCountBadgeTv.text = if (L10n.isKorean) "총 ${res.totalCompletedCount}권" else "Total: ${res.totalCompletedCount}"
                    completedListContainer.removeAllViews()

                    if (res.completedThisPeriod.isEmpty()) {
                        val emptyTv = TextView(activity).apply {
                            text = if (L10n.isKorean) "${curMonth}월에 완독한 도서가 없습니다." else "No books finished in $curMonthName."
                            textSize = 12f
                            setTextColor(mutedCol)
                            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                        }
                        completedListContainer.addView(emptyTv)
                    } else {
                        res.completedThisPeriod.forEachIndexed { idx, book ->
                            val row = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, dp(4f), 0, dp(4f))
                            }
                            val checkTv = TextView(activity).apply {
                                text = "✓"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(accentCol)
                                setPadding(0, 0, dp(6f), 0)
                            }
                            val infoCol = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val bTitleTv = TextView(activity).apply {
                                text = book.title
                                textSize = 12.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(inkCol)
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                            infoCol.addView(bTitleTv)
                            if (book.author.isNotBlank()) {
                                val authorTv = TextView(activity).apply {
                                    text = book.author
                                    textSize = 10.5f
                                    setTextColor(mutedCol)
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                }
                                infoCol.addView(authorTv)
                            }

                            val dateLabel = try {
                                val dt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(book.completedDate)
                                if (dt != null) {
                                    if (L10n.isKorean) java.text.SimpleDateFormat("M월 d일 완독", java.util.Locale.KOREAN).format(dt)
                                    else java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(dt)
                                } else {
                                    if (L10n.isKorean) "${book.completedDate} 완독" else "Finished ${book.completedDate}"
                                }
                            } catch (_: Exception) {
                                if (L10n.isKorean) "${book.completedDate} 완독" else "Finished ${book.completedDate}"
                            }
                            val dateTv = TextView(activity).apply {
                                text = dateLabel
                                textSize = 11f
                                setTextColor(mutedCol)
                                gravity = Gravity.END
                                setPadding(dp(6f), 0, 0, 0)
                            }

                            row.addView(checkTv)
                            row.addView(infoCol)
                            row.addView(dateTv)
                            completedListContainer.addView(row)

                            if (idx < res.completedThisPeriod.size - 1) {
                                completedListContainer.addView(View(activity).apply {
                                    setBackgroundColor(borderCol)
                                    alpha = 0.5f
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.7f)).apply {
                                        topMargin = dp(3f)
                                        bottomMargin = dp(3f)
                                    }
                                })
                            }
                        }
                    }
                }
                StatsPeriod.YEARLY -> {
                    completedCard.visibility = View.VISIBLE
                    completedTitleTv.text = if (L10n.isKorean) "📚 ${curYear}년 월별 완독 도서" else "📚 $curYear Completed Books"
                    completedCountBadgeTv.text = if (L10n.isKorean) "연간 총 ${res.totalCompletedCount}권" else "Annual Total: ${res.totalCompletedCount}"
                    completedListContainer.removeAllViews()

                    if (res.totalCompletedCount == 0) {
                        val emptyTv = TextView(activity).apply {
                            text = if (L10n.isKorean) "${curYear}년에 완독한 도서가 없습니다." else "No books finished in $curYear."
                            textSize = 12f
                            setTextColor(mutedCol)
                            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                        }
                        completedListContainer.addView(emptyTv)
                    } else {
                        res.completedMonthlyGroups.forEachIndexed { gIdx, group ->
                            val groupHeader = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                background = activity.pillDrawable(if (isEink) ReaderTheme.EINK_PAPER else 0xFFF1F5F9.toInt(), borderCol)
                                setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = if (gIdx > 0) dp(8f) else dp(2f)
                                    bottomMargin = dp(4f)
                                }
                            }
                            val monthLabelTv = TextView(activity).apply {
                                text = group.monthLabel
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(inkCol)
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val monthCountTv = TextView(activity).apply {
                                text = if (L10n.isKorean) "${group.count}권 완독" else "${group.count} finished"
                                textSize = 11.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(accentCol)
                            }
                            groupHeader.addView(monthLabelTv)
                            groupHeader.addView(monthCountTv)
                            completedListContainer.addView(groupHeader)

                            group.books.forEach { book ->
                                val row = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(dp(6f), dp(3f), dp(4f), dp(3f))
                                }
                                val bulletTv = TextView(activity).apply {
                                    text = "•"
                                    textSize = 13f
                                    setTextColor(inkCol)
                                    setPadding(0, 0, dp(6f), 0)
                                }
                                val infoCol = LinearLayout(activity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                }
                                val yTitleTv = TextView(activity).apply {
                                    text = book.title
                                    textSize = 12f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(inkCol)
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                }
                                infoCol.addView(yTitleTv)
                                if (book.author.isNotBlank()) {
                                    val authorTv = TextView(activity).apply {
                                        text = book.author
                                        textSize = 10f
                                        setTextColor(mutedCol)
                                        maxLines = 1
                                        ellipsize = android.text.TextUtils.TruncateAt.END
                                    }
                                    infoCol.addView(authorTv)
                                }

                                val dateLabel = try {
                                    val dt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(book.completedDate)
                                    if (dt != null) {
                                        if (L10n.isKorean) java.text.SimpleDateFormat("M.d 완독", java.util.Locale.KOREAN).format(dt)
                                        else java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(dt)
                                    } else book.completedDate
                                } catch (_: Exception) {
                                    book.completedDate
                                }
                                val dateTv = TextView(activity).apply {
                                    text = dateLabel
                                    textSize = 10.5f
                                    setTextColor(mutedCol)
                                    gravity = Gravity.END
                                    setPadding(dp(6f), 0, 0, 0)
                                }

                                row.addView(bulletTv)
                                row.addView(infoCol)
                                row.addView(dateTv)
                                completedListContainer.addView(row)
                            }
                        }
                    }
                }
            }
        }

        tabWeekly = activity.pillButton(L10n.t("주간 (Weekly)", "Weekly"), inkCol, cardBgCol, borderCol) {
            currentPeriod = StatsPeriod.WEEKLY
            renderDashboard()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4f) } }

        tabMonthly = activity.pillButton(L10n.t("월간 (Monthly)", "Monthly"), inkCol, cardBgCol, borderCol) {
            currentPeriod = StatsPeriod.MONTHLY
            renderDashboard()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4f) } }

        tabYearly = activity.pillButton(L10n.t("연간 (Yearly)", "Yearly"), inkCol, cardBgCol, borderCol) {
            currentPeriod = StatsPeriod.YEARLY
            renderDashboard()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }

        tabRow.addView(tabWeekly)
        tabRow.addView(tabMonthly)
        tabRow.addView(tabYearly)
        rootBox.addView(tabRow)

        btnTime = activity.pillButton(L10n.t("독서 시간 (분)", "Time (min)"), inkCol, cardBgCol, borderCol) {
            currentMetric = StatsMetric.TIME
            renderDashboard()
        }.apply { setPtSize(7.5f); setPadding(dp(10f), dp(4f), dp(10f), dp(4f)) }

        btnPages = activity.pillButton(L10n.t("읽은 페이지 (p)", "Pages (p)"), inkCol, cardBgCol, borderCol) {
            currentMetric = StatsMetric.PAGES
            renderDashboard()
        }.apply { setPtSize(7.5f); setPadding(dp(10f), dp(4f), dp(10f), dp(4f)) }

        metricRow.addView(btnTime)
        metricRow.addView(View(activity), LinearLayout.LayoutParams(dp(4f), 1))
        metricRow.addView(btnPages)
        metricRow.addView(totalLabelTv)
        metricRow.addView(totalValTv)
        rootBox.addView(metricRow)

        // Line Chart Container Card
        val chartCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
        }
        chartCard.addView(chartView)
        rootBox.addView(chartCard)

        rootBox.addView(View(activity), LinearLayout.LayoutParams(1, dp(10f)))
        rootBox.addView(summaryCard)
        rootBox.addView(View(activity), LinearLayout.LayoutParams(1, dp(8f)))
        rootBox.addView(insightCard)
        rootBox.addView(View(activity), LinearLayout.LayoutParams(1, dp(8f)))
        rootBox.addView(completedCard)

        val snsShareBtn = activity.pillButton(
            if (isEink) L10n.t("💾 독서 통계 카드 저장 · 공유", "💾 Save & Share Stats Card")
            else L10n.t("📱 독서 통계 SNS 공유 및 이미지 저장", "📱 Share Stats & Save Image"),
            Color.WHITE,
            if (isEink) ReaderTheme.EINK_INK else ReaderTheme.ACCENT_RIDI_BLUE,
            if (isEink) ReaderTheme.EINK_INK else ReaderTheme.ACCENT_RIDI_BLUE
        ) {
            val periodLabel = when (currentPeriod) {
                StatsPeriod.WEEKLY -> L10n.t("주간 독서 통계 리포트", "Weekly Reading Report")
                StatsPeriod.MONTHLY -> L10n.t("월간 독서 통계 리포트", "Monthly Reading Report")
                StatsPeriod.YEARLY -> L10n.t("연간 독서 통계 리포트", "Yearly Reading Report")
            }
            val res = StatsAggregator.aggregate(cachedRecords, currentPeriod, currentMetric, Date(), cachedCompletedBooks)
            val subLabel = when (currentPeriod) {
                StatsPeriod.WEEKLY -> L10n.t("최근 7일간 독서 트렌드", "Past 7 Days Reading Trend")
                StatsPeriod.MONTHLY -> L10n.t("최근 4주간 주차별 독서 트렌드", "Past 4 Weeks Reading Trend")
                StatsPeriod.YEARLY -> L10n.t("월별 연간 독서 트렌드", "Monthly Annual Reading Trend")
            } + " · " + if (currentMetric == StatsMetric.TIME) L10n.t("독서 시간 기준", "Time-based") else L10n.t("읽은 페이지 기준", "Pages-based")

            val unitStr = if (currentMetric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"
            val totalLbl = if (currentMetric == StatsMetric.TIME) L10n.t("총 독서 시간", "Total Reading Time") else L10n.t("총 읽은 페이지", "Total Pages Read")
            val totalVal = if (currentMetric == StatsMetric.TIME) res.totalTimeStr else res.totalPagesStr
            val streakVal = if (L10n.isKorean) "${res.streakDays}일 연속 🔥" else "${res.streakDays}-day streak 🔥"
            val avgVal = if (L10n.isKorean) "일평균: ${res.dailyAvgStr}" else "Daily Avg: ${res.dailyAvgStr}"

            val chartPoints = res.points.map { pt ->
                AndroidShareHelper.StatsChartPoint(label = pt.label, value = pt.value, displayValue = pt.displayValue)
            }

            val completedList = cachedCompletedBooks.take(5).map { b ->
                "${b.title} (${b.completedDate})"
            }

            val payload = AndroidShareHelper.StatsSharePayload(
                periodName = periodLabel,
                periodSubLabel = subLabel,
                totalLabel = totalLbl,
                totalValue = totalVal,
                streakText = streakVal,
                dailyAvgText = avgVal,
                metricUnit = unitStr,
                points = chartPoints,
                yMax = res.yMax,
                insightText = res.insightText,
                completedBooksSummary = completedList
            )

            AndroidShareHelper.showStatsSnsShareDialog(
                activity = activity,
                payload = payload,
                isEink = isEink,
                onBack = { renderDashboard() }
            )
        }.apply {
            setPtSize(8.5f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        rootBox.addView(View(activity), LinearLayout.LayoutParams(1, dp(12f)))
        rootBox.addView(snsShareBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        dlg = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(activity, enforceBalancedMargins = true, onBack = onBack)

        // Load data from DB in background
        scope.launch {
            app.syncExistingCompletedBooks()
            val dbRecords = withContext(Dispatchers.IO) {
                runCatching { app.db.stats().all() }.getOrDefault(emptyList())
            }
            val dbCompleted = withContext(Dispatchers.IO) {
                runCatching { app.db.completedBooks().all() }.getOrDefault(emptyList())
            }
            val dbBooks = withContext(Dispatchers.IO) {
                runCatching { app.db.books().all() }.getOrDefault(emptyList())
            }
            val todayStr = app.prefs.statsDate.ifBlank {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
            }
            val merged = dbRecords.toMutableList()
            if (merged.none { it.date == todayStr } && (app.prefs.todayMinutes > 0 || app.prefs.todayPages > 0)) {
                merged.add(DailyReadingStatsEntity(todayStr, app.prefs.todayMinutes, app.prefs.todayPages))
            }
            cachedRecords = merged
            cachedCompletedBooks = dbCompleted
            cachedAllBooks = dbBooks
            renderDashboard()
        }
    }
}
