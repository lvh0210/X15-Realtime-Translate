package com.x15.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen drag-to-select rectangle overlay for instant region translation or pinning an Auto Realtime zone.
 */
class RegionSelectorView(
    context: Context,
    private val promptLabel: String,
    private val onRegionSelected: (Rect) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val scrimPaint = Paint().apply {
        color = Color.argb(125, 0, 0, 0)
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#38BDF8")
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 15, 23, 42)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val selRectF = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (dragging) {
            selRectF.set(min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY))
            canvas.drawRect(selRectF, clearPaint)
            canvas.drawRect(selRectF, borderPaint)
        }

        val bannerW = dp(290f)
        val bannerH = dp(40f)
        val bx = (width - bannerW) / 2f
        val by = dp(54f)
        canvas.drawRoundRect(bx, by, bx + bannerW, by + bannerH, dp(20f), dp(20f), hintBgPaint)
        canvas.drawText(promptLabel, width / 2f, by + bannerH / 2f + dp(5f), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val l = min(startX, event.x).toInt().coerceIn(0, width)
                val t = min(startY, event.y).toInt().coerceIn(0, height)
                val r = max(startX, event.x).toInt().coerceIn(0, width)
                val b = max(startY, event.y).toInt().coerceIn(0, height)
                if (abs(r - l) >= dp(28f) && abs(b - t) >= dp(20f)) {
                    onRegionSelected(Rect(l, t, r, b))
                } else {
                    onCancelled()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onCancelled()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
