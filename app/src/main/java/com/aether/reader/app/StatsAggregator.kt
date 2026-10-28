package com.aether.reader.app

import com.aether.reader.data.CompletedBookEntity
import com.aether.reader.data.DailyReadingStatsEntity
import com.aether.reader.data.L10n
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.*

enum class StatsPeriod {
    WEEKLY, MONTHLY, YEARLY
}

enum class StatsMetric {
    TIME, PAGES
}

data class MonthlyCompletedSummary(
    val yearMonth: String, // "yyyy-MM"
    val monthNumber: Int,  // 1..12
    val monthLabel: String, // "10월"
    val count: Int,
    val books: List<CompletedBookEntity>
)

data class DashboardResult(
    val period: StatsPeriod,
    val metric: StatsMetric,
    val points: List<StatsPoint>,
    val yMax: Int,
    val totalTimeStr: String,
    val totalPagesStr: String,
    val dailyAvgStr: String,
    val streakDays: Int,
    val maxDayStr: String,
    val insightText: String,
    val completedThisPeriod: List<CompletedBookEntity> = emptyList(),
    val completedMonthlyGroups: List<MonthlyCompletedSummary> = emptyList(),
    val totalCompletedCount: Int = 0
)

object StatsAggregator {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun formatWeeklyLabel(date: Date, isKorean: Boolean = com.aether.reader.data.L10n.isKorean): String {
        return if (isKorean) {
            SimpleDateFormat("MM/dd E", Locale.KOREAN).format(date)
        } else {
            val month = SimpleDateFormat("MMM", Locale.US).format(date).uppercase(Locale.US)
            val day = SimpleDateFormat("d", Locale.US).format(date)
            val dow = SimpleDateFormat("EEE", Locale.US).format(date)
            "$month $day, $dow"
        }
    }

    fun aggregate(
        allRecords: List<DailyReadingStatsEntity>,
        period: StatsPeriod,
        metric: StatsMetric,
        baseDate: Date = Date(),
        completedBooks: List<CompletedBookEntity> = emptyList()
    ): DashboardResult {
        val map = allRecords.associateBy { it.date }

        when (period) {
            StatsPeriod.WEEKLY -> {
                val days = mutableListOf<String>()
                val dayLabels = mutableListOf<String>()
                // 7 days ending on baseDate
                for (i in 6 downTo 0) {
                    val c = Calendar.getInstance().apply {
                        time = baseDate
                        add(Calendar.DAY_OF_YEAR, -i)
                    }
                    val dStr = dateFormat.format(c.time)
                    days.add(dStr)
                    dayLabels.add(formatWeeklyLabel(c.time))
                }

                val points = days.mapIndexed { idx, dStr ->
                    val rec = map[dStr]
                    val v = if (metric == StatsMetric.TIME) rec?.minutes ?: 0 else rec?.pages ?: 0
                    val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"
                    StatsPoint(dayLabels[idx], v, if (v > 0) "$v$unit" else "")
                }

                val totalMin = days.sumOf { map[it]?.minutes ?: 0 }
                val totalPgs = days.sumOf { map[it]?.pages ?: 0 }
                val maxVal = points.maxOfOrNull { it.value } ?: 0
                val yMax = ((maxVal * 1.25f).toInt() + 10).coerceAtLeast(if (metric == StatsMetric.TIME) 30 else 20)

                val streak = calculateStreak(map, baseDate)
                val dailyAvg = totalMin / 7
                val maxDay = points.maxByOrNull { it.value }
                val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"

                val maxDayDesc = if (maxDay != null && maxDay.value > 0) "${maxDay.label} (${maxDay.value}$unit)" else "-"
                val insight = buildWeeklyInsight(points, totalMin, totalPgs, streak)

                return DashboardResult(
                    period = period,
                    metric = metric,
                    points = points,
                    yMax = yMax,
                    totalTimeStr = formatMinutes(totalMin),
                    totalPagesStr = if (L10n.isKorean) "$totalPgs 페이지" else "$totalPgs pages",
                    dailyAvgStr = if (L10n.isKorean) "${dailyAvg}분 / 일" else "${dailyAvg}m / day",
                    streakDays = streak,
                    maxDayStr = maxDayDesc,
                    insightText = insight,
                    completedThisPeriod = emptyList(),
                    completedMonthlyGroups = emptyList(),
                    totalCompletedCount = 0
                )
            }
            StatsPeriod.MONTHLY -> {
                // 4 weeks (28 days: week 1 to 4)
                val weekPoints = mutableListOf<StatsPoint>()
                var totalMin = 0
                var totalPgs = 0
                for (w in 3 downTo 0) {
                    val weekIndex = 4 - w
                    var wMin = 0
                    var wPgs = 0
                    for (d in (w * 7 + 6) downTo (w * 7)) {
                        val c = Calendar.getInstance().apply {
                            time = baseDate
                            add(Calendar.DAY_OF_YEAR, -d)
                        }
                        val dStr = dateFormat.format(c.time)
                        val r = map[dStr]
                        wMin += r?.minutes ?: 0
                        wPgs += r?.pages ?: 0
                    }
                    totalMin += wMin
                    totalPgs += wPgs
                    val v = if (metric == StatsMetric.TIME) wMin else wPgs
                    val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"
                    val weekLabel = if (L10n.isKorean) "${weekIndex}주차" else "Wk $weekIndex"
                    weekPoints.add(StatsPoint(weekLabel, v, if (v > 0) "$v$unit" else ""))
                }

                val maxVal = weekPoints.maxOfOrNull { it.value } ?: 0
                val yMax = ((maxVal * 1.25f).toInt() + 20).coerceAtLeast(if (metric == StatsMetric.TIME) 60 else 40)
                val streak = calculateStreak(map, baseDate)
                val dailyAvg = totalMin / 28
                val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"

                val maxW = weekPoints.maxByOrNull { it.value }
                val maxDesc = if (maxW != null && maxW.value > 0) "${maxW.label} (${maxW.value}$unit)" else "-"

                // Monthly completed books
                val cal = Calendar.getInstance().apply { time = baseDate }
                val curYear = cal.get(Calendar.YEAR)
                val curMonth = cal.get(Calendar.MONTH) + 1
                val ymPrefix = String.format(Locale.US, "%04d-%02d", curYear, curMonth)
                val mBooks = completedBooks.filter { it.completedDate.startsWith(ymPrefix) }
                    .sortedByDescending { it.completedAt }
                val mName = if (L10n.isKorean) "${curMonth}월" else DateFormatSymbols.getInstance(Locale.US).shortMonths[curMonth - 1]
                val monthSummary = MonthlyCompletedSummary(ymPrefix, curMonth, mName, mBooks.size, mBooks)

                val insight = buildMonthlyInsight(weekPoints, totalMin, totalPgs, mBooks.size)

                return DashboardResult(
                    period = period,
                    metric = metric,
                    points = weekPoints,
                    yMax = yMax,
                    totalTimeStr = formatMinutes(totalMin),
                    totalPagesStr = if (L10n.isKorean) "$totalPgs 페이지" else "$totalPgs pages",
                    dailyAvgStr = if (L10n.isKorean) "${dailyAvg}분 / 일" else "${dailyAvg}m / day",
                    streakDays = streak,
                    maxDayStr = maxDesc,
                    insightText = insight,
                    completedThisPeriod = mBooks,
                    completedMonthlyGroups = listOf(monthSummary),
                    totalCompletedCount = mBooks.size
                )
            }
            StatsPeriod.YEARLY -> {
                // 12 months of current year
                val cal = Calendar.getInstance().apply { time = baseDate }
                val currentYear = cal.get(Calendar.YEAR)
                val monthPoints = mutableListOf<StatsPoint>()
                var totalMin = 0
                var totalPgs = 0

                for (m in 1..12) {
                    val monthPrefix = String.format(Locale.US, "%04d-%02d", currentYear, m)
                    var mMin = 0
                    var mPgs = 0
                    for ((dStr, r) in map) {
                        if (dStr.startsWith(monthPrefix)) {
                            mMin += r.minutes
                            mPgs += r.pages
                        }
                    }
                    totalMin += mMin
                    totalPgs += mPgs
                    val v = if (metric == StatsMetric.TIME) mMin else mPgs
                    val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"
                    val mLabel = if (L10n.isKorean) "${m}월" else DateFormatSymbols.getInstance(Locale.US).shortMonths[m - 1]
                    monthPoints.add(StatsPoint(mLabel, v, if (v > 0) "$v$unit" else ""))
                }

                val maxVal = monthPoints.maxOfOrNull { it.value } ?: 0
                val yMax = ((maxVal * 1.25f).toInt() + 60).coerceAtLeast(if (metric == StatsMetric.TIME) 120 else 80)
                val streak = calculateStreak(map, baseDate)
                val activeMonths = monthPoints.count { it.value > 0 }.coerceAtLeast(1)
                val dailyAvg = totalMin / (activeMonths * 30)
                val unit = if (metric == StatsMetric.TIME) (if (L10n.isKorean) "분" else "m") else "p"

                val maxM = monthPoints.maxByOrNull { it.value }
                val maxDesc = if (maxM != null && maxM.value > 0) "${maxM.label} (${maxM.value}$unit)" else "-"
                val approxBooks = (totalPgs / 250f)
                val booksStr = if (L10n.isKorean) {
                    if (approxBooks >= 1.0f) String.format(Locale.KOREAN, "약 %.1f권 완독 분량", approxBooks) else "약 ${totalPgs}p"
                } else {
                    if (approxBooks >= 1.0f) String.format(Locale.US, "approx. %.1f books", approxBooks) else "$totalPgs pages"
                }

                // Yearly completed books grouped by month (12 downTo 1 for recent-first order)
                val yearPrefix = "$currentYear-"
                val yearBooks = completedBooks.filter { it.completedDate.startsWith(yearPrefix) }
                val monthlyGroups = mutableListOf<MonthlyCompletedSummary>()
                for (m in 12 downTo 1) {
                    val ym = String.format(Locale.US, "%04d-%02d", currentYear, m)
                    val bList = yearBooks.filter { it.completedDate.startsWith(ym) }
                        .sortedByDescending { it.completedAt }
                    if (bList.isNotEmpty()) {
                        val mName = if (L10n.isKorean) "${m}월" else DateFormatSymbols.getInstance(Locale.US).shortMonths[m - 1]
                        monthlyGroups.add(MonthlyCompletedSummary(ym, m, mName, bList.size, bList))
                    }
                }
                val totalYearCompleted = yearBooks.size
                val countPrefix = if (totalYearCompleted > 0) {
                    if (L10n.isKorean) "총 ${totalYearCompleted}권 완독 · " else "$totalYearCompleted books finished · "
                } else ""
                val insight = if (L10n.isKorean) {
                    "올해 ${countPrefix}총 ${formatMinutes(totalMin)} ($booksStr)의 독서를 기록 중입니다."
                } else {
                    "This year, ${countPrefix}total ${formatMinutes(totalMin)} ($booksStr) recorded."
                }

                return DashboardResult(
                    period = period,
                    metric = metric,
                    points = monthPoints,
                    yMax = yMax,
                    totalTimeStr = formatMinutes(totalMin),
                    totalPagesStr = if (L10n.isKorean) "$totalPgs 페이지" else "$totalPgs pages",
                    dailyAvgStr = if (L10n.isKorean) "${dailyAvg}분 / 일" else "${dailyAvg}m / day",
                    streakDays = streak,
                    maxDayStr = maxDesc,
                    insightText = insight,
                    completedThisPeriod = yearBooks.sortedByDescending { it.completedAt },
                    completedMonthlyGroups = monthlyGroups,
                    totalCompletedCount = totalYearCompleted
                )
            }
        }
    }

    private fun calculateStreak(map: Map<String, DailyReadingStatsEntity>, baseDate: Date): Int {
        var streak = 0
        for (i in 0..365) {
            val c = Calendar.getInstance().apply {
                time = baseDate
                add(Calendar.DAY_OF_YEAR, -i)
            }
            val dStr = dateFormat.format(c.time)
            val r = map[dStr]
            if (r != null && (r.minutes > 0 || r.pages > 0)) {
                streak++
            } else {
                if (i == 0) continue // If today has 0 so far, check yesterday
                break
            }
        }
        return streak
    }

    fun formatMinutes(min: Int): String {
        if (min <= 0) return if (L10n.isKorean) "0분" else "0m"
        val h = min / 60
        val m = min % 60
        return if (L10n.isKorean) {
            when {
                h > 0 && m > 0 -> "${h}시간 ${m}분"
                h > 0 -> "${h}시간"
                else -> "${m}분"
            }
        } else {
            when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
    }

    private fun buildWeeklyInsight(points: List<StatsPoint>, totalMin: Int, totalPgs: Int, streak: Int): String {
        if (totalMin <= 0 && totalPgs <= 0) {
            return L10n.t(
                "오늘부터 책을 열고 독서 습관을 시작해 보세요!",
                "Open a book today and start your reading habit!"
            )
        }
        val weekendMin = (points.getOrNull(5)?.value ?: 0) + (points.getOrNull(6)?.value ?: 0)
        val weekdayMin = totalMin - weekendMin
        return when {
            streak >= 3 -> L10n.t(
                "${streak}일 연속으로 꾸준히 독서를 이어가고 계십니다! 🔥",
                "You're on a $streak-day reading streak! Keep it up! 🔥"
            )
            weekendMin > weekdayMin -> L10n.t(
                "주말 독서 집중도가 평일보다 높습니다.",
                "Reading focus was higher on weekends than weekdays."
            )
            else -> L10n.t(
                "이번 주 총 ${formatMinutes(totalMin)} 동안 꾸준한 독서를 기록하셨습니다.",
                "You logged a total of ${formatMinutes(totalMin)} of steady reading this week."
            )
        }
    }

    private fun buildMonthlyInsight(points: List<StatsPoint>, totalMin: Int, totalPgs: Int, completedCount: Int = 0): String {
        if (totalMin <= 0 && totalPgs <= 0 && completedCount <= 0) {
            return L10n.t("매일 10분씩 독서를 즐겨 보세요.", "Try enjoying reading for 10 minutes every day.")
        }
        val maxW = points.maxByOrNull { it.value }
        val base = if (maxW != null && maxW.value > 0) {
            if (L10n.isKorean) {
                "${maxW.label}에 독서 몰입도가 가장 높았으며, 이번 달 총 ${formatMinutes(totalMin)} 동안 ${totalPgs}페이지를 읽으셨습니다."
            } else {
                "Highest reading focus was during ${maxW.label}, reading ${totalPgs} pages over ${formatMinutes(totalMin)} this month."
            }
        } else {
            if (L10n.isKorean) {
                "이번 달 총 ${formatMinutes(totalMin)} 동안 ${totalPgs}페이지를 읽으셨습니다."
            } else {
                "Read ${totalPgs} pages over a total of ${formatMinutes(totalMin)} this month."
            }
        }
        return if (completedCount > 0) {
            if (L10n.isKorean) {
                "이번 달 총 ${completedCount}권을 완독하셨습니다! 🎉 $base"
            } else {
                "You finished $completedCount books this month! 🎉 $base"
            }
        } else {
            base
        }
    }
}
