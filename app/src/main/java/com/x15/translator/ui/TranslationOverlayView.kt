package com.x15.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
 * Lightweight Canvas overlay rendering either in-place translated pills over source text
 * or a consolidated subtitle bar, plus optional pinned-region guide border.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private var blocks: List<TranslatedBlock> = emptyList()
    private var displayStyle: OverlayStyle = OverlayStyle.IN_PLACE
    private var pinnedRegion: Rect? = null
    private var baseFontSizeSp: Float = 13f
    private var bgAlpha: Int = 215
    private var overlayVisibleContent: Boolean = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(110, 56, 189, 248)
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(140, 52, 211, 153)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val tmpRectF = RectF()
    private val subtitleBounds = Rect()

    fun updateData(
        newBlocks: List<TranslatedBlock>,
        style: OverlayStyle,
        pinned: Rect?,
        fontSp: Float,
        alpha: Int
    ) {
        blocks = newBlocks
        displayStyle = style
        pinnedRegion = pinned
        baseFontSizeSp = fontSp
        bgAlpha = alpha.coerceIn(100, 255)
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
            blocks.map { it.box }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        pinnedRegion?.let { pr ->
            tmpRectF.set(pr)
            canvas.drawRoundRect(tmpRectF, dp(8f), dp(8f), pinPaint)
        }

        if (!overlayVisibleContent || blocks.isEmpty()) {
            subtitleBounds.setEmpty()
            return
        }

        bgPaint.color = Color.argb(bgAlpha, 12, 16, 24)

        if (displayStyle == OverlayStyle.SUBTITLE_BAR) {
            drawSubtitleBar(canvas)
        } else {
            subtitleBounds.setEmpty()
            drawInPlaceBlocks(canvas)
        }
    }

    private fun drawInPlaceBlocks(canvas: Canvas) {
        val padH = dp(6f)
        val padV = dp(3f)
        val radius = dp(6f)
        val defaultPx = sp(baseFontSizeSp)
        val minPx = sp(9.5f)

        for (blk in blocks) {
            val box = blk.box
            val availW = max(box.width(), dp(72f).toInt())
            val maxW = min(availW + dp(48f).toInt(), (width - dp(16f).toInt()))
            val left = box.left.coerceIn(dp(4f).toInt(), max(dp(4f).toInt(), width - maxW - dp(4f).toInt()))

            var fontSize = defaultPx
            var layout = buildStaticLayout(blk.translated, maxW - (padH * 2).toInt(), fontSize)
            val targetMaxH = max(box.height() * 2.2f, dp(42f))
            while (layout.height > targetMaxH && fontSize > minPx) {
                fontSize -= sp(1.2f)
                layout = buildStaticLayout(blk.translated, maxW - (padH * 2).toInt(), fontSize)
            }

            var actualTextW = 0f
            for (i in 0 until layout.lineCount) {
                actualTextW = max(actualTextW, layout.getLineWidth(i))
            }
            val cardW = max(box.width().toFloat(), actualTextW + padH * 2f).coerceAtMost(maxW.toFloat())
            val cardH = max(box.height().toFloat(), layout.height + padV * 2f)
            val top = box.top.toFloat().coerceIn(dp(4f), max(dp(4f), height - cardH - dp(4f)))

            tmpRectF.set(left.toFloat(), top, left + cardW, top + cardH)
            canvas.drawRoundRect(tmpRectF, radius, radius, bgPaint)
            canvas.drawRoundRect(tmpRectF, radius, radius, borderPaint)

            canvas.save()
            canvas.translate(tmpRectF.left + padH, tmpRectF.top + (cardH - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawSubtitleBar(canvas: Canvas) {
        val combined = blocks.joinToString("\n") { it.translated }
        val marginH = dp(14f)
        val maxTextW = (width - marginH * 2 - dp(24f)).toInt().coerceAtLeast(100)
        val layout = buildStaticLayout(combined, maxTextW, sp(baseFontSizeSp + 1f))
        val pad = dp(12f)
        val cardH = layout.height + pad * 2f
        val bottomMargin = dp(52f)
        val top = (height - bottomMargin - cardH).coerceAtLeast(dp(40f))

        tmpRectF.set(marginH, top, width - marginH, top + cardH)
        subtitleBounds.set(
            tmpRectF.left.toInt(),
            tmpRectF.top.toInt(),
            tmpRectF.right.toInt(),
            tmpRectF.bottom.toInt()
        )
        canvas.drawRoundRect(tmpRectF, dp(12f), dp(12f), bgPaint)
        canvas.drawRoundRect(tmpRectF, dp(12f), dp(12f), borderPaint)

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
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
