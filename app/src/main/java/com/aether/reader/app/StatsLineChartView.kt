package com.aether.reader.app

import android.content.Context
import android.graphics.*
import android.view.View

data class StatsPoint(
    val label: String,
    val value: Int,
    val displayValue: String
)

class StatsLineChartView(context: Context) : View(context) {

    var isEink: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var points: List<StatsPoint> = emptyList()
    private var maxVal: Int = 10
    private var unit: String = "분"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }

    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val yAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun setData(dataPoints: List<StatsPoint>, maxVal: Int, unit: String) {
        this.points = dataPoints
        this.maxVal = maxVal.coerceAtLeast(1)
        this.unit = unit
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minH = dp(160f).toInt()
        val h = resolveSize(minH, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h.coerceAtLeast(minH), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val paddingLeft = dp(36f)
        val paddingRight = dp(24f)
        val paddingTop = dp(22f)
        val paddingBottom = dp(28f)

        val graphW = w - paddingLeft - paddingRight
        val graphH = h - paddingTop - paddingBottom
        if (graphW <= 0f || graphH <= 0f) return

        val inkColor = if (isEink) ReaderTheme.EINK_INK else 0xFF1E293B.toInt()
        val mutedColor = if (isEink) ReaderTheme.EINK_MUTED else 0xFF64748B.toInt()
        val accentColor = if (isEink) ReaderTheme.EINK_INK else ReaderTheme.ACCENT_RIDI_BLUE

        // 1. 가이드 수평선 (3단계: Top, Mid, Bottom)
        val yTop = paddingTop
        val yMid = paddingTop + graphH / 2f
        val yBottom = paddingTop + graphH

        gridPaint.pathEffect = if (isEink) DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f) else null
        gridPaint.color = if (isEink) 0xFFCCCCCC.toInt() else 0x14000000

        canvas.drawLine(paddingLeft, yTop, w - paddingRight, yTop, gridPaint)
        canvas.drawLine(paddingLeft, yMid, w - paddingRight, yMid, gridPaint)
        // Base line
        gridPaint.pathEffect = null
        gridPaint.color = if (isEink) 0xFF888888.toInt() else 0x33000000
        gridPaint.strokeWidth = dp(1.5f)
        canvas.drawLine(paddingLeft, yBottom, w - paddingRight, yBottom, gridPaint)

        // 2. Y축 눈금 라벨
        yAxisTextPaint.textSize = sp(9f)
        yAxisTextPaint.color = mutedColor
        val textOff = dp(4f)
        canvas.drawText("$maxVal", paddingLeft - textOff, yTop + dp(3.5f), yAxisTextPaint)
        canvas.drawText("${maxVal / 2}", paddingLeft - textOff, yMid + dp(3.5f), yAxisTextPaint)
        canvas.drawText("0", paddingLeft - textOff, yBottom + dp(3.5f), yAxisTextPaint)

        if (points.isEmpty()) {
            emptyTextPaint.textSize = sp(11f)
            emptyTextPaint.color = mutedColor
            canvas.drawText("기록된 독서 통계 데이터가 없습니다", w / 2f, h / 2f, emptyTextPaint)
            return
        }

        // 3. 좌표 계산
        val n = points.size
        val stepX = if (n > 1) graphW / (n - 1) else 0f
        val coords = points.mapIndexed { idx, p ->
            val cx = if (n > 1) paddingLeft + idx * stepX else paddingLeft + graphW / 2f
            val ratio = (p.value.toFloat() / maxVal).coerceIn(0f, 1f)
            val cy = yBottom - ratio * graphH
            PointF(cx, cy)
        }

        // 4. 영역 채움 (LCD/OLED 모드에서만)
        if (!isEink && coords.isNotEmpty()) {
            val fillPath = Path()
            fillPath.moveTo(coords[0].x, coords[0].y)
            for (i in 1 until coords.size) {
                fillPath.lineTo(coords[i].x, coords[i].y)
            }
            fillPath.lineTo(coords.last().x, yBottom)
            fillPath.lineTo(coords.first().x, yBottom)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, yTop, 0f, yBottom,
                intArrayOf(0x352563EB.toInt(), 0x002563EB.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
        }

        // 5. 라인 그리기
        val linePath = Path()
        coords.forEachIndexed { i, pt ->
            if (i == 0) linePath.moveTo(pt.x, pt.y)
            else linePath.lineTo(pt.x, pt.y)
        }
        linePaint.color = accentColor
        linePaint.strokeWidth = dp(2.6f)
        canvas.drawPath(linePath, linePaint)

        // 6. 데이터 포인트 도트 & 텍스트 렌더링
        valueTextPaint.textSize = sp(8.5f)
        valueTextPaint.color = inkColor

        axisTextPaint.textSize = if (n >= 7) sp(7.5f) else sp(9.5f)
        axisTextPaint.color = mutedColor

        coords.forEachIndexed { i, pt ->
            val p = points[i]

            // 점 그리기 (외부 화이트 링 + 내부 도트)
            dotBorderPaint.strokeWidth = dp(2.2f)
            canvas.drawCircle(pt.x, pt.y, dp(4.5f), dotBorderPaint)
            dotPaint.color = accentColor
            canvas.drawCircle(pt.x, pt.y, dp(3.2f), dotPaint)

            // 포인트 상단 수치 라벨 (0보다 큰 경우에만)
            if (p.value > 0) {
                val labelY = (pt.y - dp(6f)).coerceAtLeast(dp(11f))
                canvas.drawText(p.displayValue, pt.x, labelY, valueTextPaint)
            }

            // X축 하단 라벨
            val xLabelY = yBottom + dp(16f)
            canvas.drawText(p.label, pt.x, xLabelY, axisTextPaint)
        }
    }
}
