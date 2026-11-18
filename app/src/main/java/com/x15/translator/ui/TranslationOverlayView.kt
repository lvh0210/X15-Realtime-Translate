package com.x15.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.x15.translator.OverlayStyle
import com.x15.translator.translate.TranslatedBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Seamless Canvas overlay:
 * - IN_PLACE: borderless adaptive color-matched patches directly replacing source text without clunky frames.
 * - SUBTITLE_BAR: clean floating subtitle strip at bottom.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private var blocks: List<TranslatedBlock> = emptyList()
    private var displayStyle: OverlayStyle = OverlayStyle.IN_PLACE
    private var pinnedRegion: Rect? = null
    private var pinHintUntilMs: Long = 0L
    private var baseFontSizeSp: Float = 13f
    private var bgAlpha: Int = 242
    private var overlayVisibleContent: Boolean = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val subtitleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(90, 56, 189, 248)
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(160, 52, 211, 153)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val tmpRectF = RectF()
    private val subtitleBounds = Rect()
    private val renderedRects = ArrayList<Rect>()

    fun updateData(
        newBlocks: List<TranslatedBlock>,
        style: OverlayStyle,
        pinned: Rect?,
        fontSp: Float,
        alpha: Int,
        flashPinGuide: Boolean = false
    ) {
        blocks = newBlocks
        displayStyle = style
        pinnedRegion = pinned
        if (flashPinGuide && pinned != null) {
            pinHintUntilMs = SystemClock.uptimeMillis() + 1800L
            postDelayed({ invalidate() }, 1850L)
        }
        baseFontSizeSp = fontSp
        bgAlpha = alpha.coerceIn(140, 255)
        invalidate()
    }

    fun setContentVisible(visible: Boolean) {
        overlayVisibleContent = visible
        invalidate()
    }

    fun isContentVisible(): Boolean = overlayVisibleContent

    fun hasRenderedBlocks(): Boolean = overlayVisibleContent && blocks.isNotEmpty()

    fun getOccupiedRects(): List<Rect> {
        if (!overlayVisibleContent || blocks.isEmpty()) return emptyList()
        return if (displayStyle == OverlayStyle.SUBTITLE_BAR) {
            if (subtitleBounds.isEmpty) emptyList() else listOf(Rect(subtitleBounds))
        } else {
            renderedRects.map { Rect(it) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        if (pinnedRegion != null && now < pinHintUntilMs) {
            tmpRectF.set(pinnedRegion!!)
            canvas.drawRoundRect(tmpRectF, dp(6f), dp(6f), pinPaint)
        }

        renderedRects.clear()
        if (!overlayVisibleContent || blocks.isEmpty()) {
            subtitleBounds.setEmpty()
            return
        }

        if (displayStyle == OverlayStyle.SUBTITLE_BAR) {
            drawSubtitleBar(canvas)
        } else {
            subtitleBounds.setEmpty()
            drawSeamlessInPlace(canvas)
        }
    }

    private fun drawSeamlessInPlace(canvas: Canvas) {
        val padH = dp(2.5f)
        val padV = dp(1f)
        val radius = dp(3f)
        val minPx = sp(8.5f)

        for (blk in blocks) {
            val box = blk.box
            // Estimate natural font size proportional to original line height
            val estimatedFromBox = (box.height() * 0.62f).coerceIn(sp(9.5f), sp(baseFontSizeSp + 2f))
            var fontSize = min(sp(baseFontSizeSp + 1f), estimatedFromBox)
            val maxAllowedW = min(box.width() + dp(26f).toInt(), width - dp(12f).toInt())
            val wrapWidth = max(maxAllowedW - (padH * 2).toInt(), dp(36f).toInt())

            var layout = buildStaticLayout(blk.translated, wrapWidth, fontSize)
            val maxTargetH = max(box.height() * 1.45f, dp(22f))
            while (layout.height > maxTargetH && fontSize > minPx) {
                fontSize -= sp(0.9f)
                layout = buildStaticLayout(blk.translated, wrapWidth, fontSize)
            }

            var actualTextW = 0f
            for (i in 0 until layout.lineCount) {
                actualTextW = max(actualTextW, layout.getLineWidth(i))
            }

            val patchW = max(box.width().toFloat(), actualTextW + padH * 2f)
                .coerceAtMost(maxAllowedW.toFloat())
            val patchH = max(box.height().toFloat(), layout.height + padV * 2f)

            val left = box.left.toFloat().coerceIn(dp(2f), max(dp(2f), width - patchW - dp(2f)))
            val top = (box.centerY() - patchH / 2f).coerceIn(dp(2f), max(dp(2f), height - patchH - dp(2f)))

            // Adaptive background matching sampled pixels around original text (borderless!)
            val baseBg = blk.bgColor
            bgPaint.color = Color.argb(
                bgAlpha.coerceAtLeast(235),
                Color.red(baseBg),
                Color.green(baseBg),
                Color.blue(baseBg)
            )
            textPaint.color = blk.textColor

            tmpRectF.set(left, top, left + patchW, top + patchH)
            renderedRects.add(
                Rect(
                    tmpRectF.left.toInt(),
                    tmpRectF.top.toInt(),
                    tmpRectF.right.toInt(),
                    tmpRectF.bottom.toInt()
                )
            )
            canvas.drawRoundRect(tmpRectF, radius, radius, bgPaint)

            canvas.save()
            canvas.translate(tmpRectF.left + padH, tmpRectF.top + (patchH - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawSubtitleBar(canvas: Canvas) {
        val combined = blocks.joinToString("\n") { it.translated }
        val marginH = dp(14f)
        val maxTextW = (width - marginH * 2 - dp(24f)).toInt().coerceAtLeast(100)
        textPaint.color = Color.WHITE
        val layout = buildStaticLayout(combined, maxTextW, sp(baseFontSizeSp + 1f))
        val pad = dp(11f)
        val cardH = layout.height + pad * 2f
        val bottomMargin = dp(52f)
        val top = (height - bottomMargin - cardH).coerceAtLeast(dp(40f))

        bgPaint.color = Color.argb(228, 12, 16, 24)
        tmpRectF.set(marginH, top, width - marginH, top + cardH)
        subtitleBounds.set(
            tmpRectF.left.toInt(),
            tmpRectF.top.toInt(),
            tmpRectF.right.toInt(),
            tmpRectF.bottom.toInt()
        )
        canvas.drawRoundRect(tmpRectF, dp(10f), dp(10f), bgPaint)
        canvas.drawRoundRect(tmpRectF, dp(10f), dp(10f), subtitleBorderPaint)

        canvas.save()
        canvas.translate(tmpRectF.left + pad, tmpRectF.top + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildStaticLayout(text: String, widthPx: Int, textSizePx: Float): StaticLayout {
        textPaint.textSize = textSizePx
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, widthPx.coerceAtLeast(16))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.06f)
            .setIncludePad(false)
            .build()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
