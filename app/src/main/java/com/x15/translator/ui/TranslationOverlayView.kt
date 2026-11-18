package com.x15.translator.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.x15.translator.OverlayStyle
import com.x15.translator.translate.TranslatedBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-seamless in-place translation overlay:
 * - Line-level feathered erasure (BlurMaskFilter): zero hard rectangular borders, melts softly into background.
 * - Crisp native typography with soft contrast halo/shadow: looks like original app/web text.
 * - Smooth 140ms alpha fade-in transition: zero jarring pop-in on 120Hz display.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private var blocks: List<TranslatedBlock> = emptyList()
    private var displayStyle: OverlayStyle = OverlayStyle.IN_PLACE
    private var pinnedRegion: Rect? = null
    private var pinHintUntilMs: Long = 0L
    private var baseFontSizeSp: Float = 13f
    private var bgAlpha: Int = 242
    private var overlayVisibleContent: Boolean = true

    // Smooth transition animator
    private var transitionAlpha: Float = 1f
    private var fadeAnimator: ValueAnimator? = null

    private val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(dp(3.5f), BlurMaskFilter.Blur.NORMAL)
    }

    private val solidErasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val subtitleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val subtitleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(80, 56, 189, 248)
    }

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(160, 52, 211, 153)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        val hasContentChanged = newBlocks != blocks
        blocks = newBlocks
        displayStyle = style
        pinnedRegion = pinned
        if (flashPinGuide && pinned != null) {
            pinHintUntilMs = SystemClock.uptimeMillis() + 1800L
            postDelayed({ invalidate() }, 1850L)
        }
        baseFontSizeSp = fontSp
        bgAlpha = alpha.coerceIn(140, 255)

        if (hasContentChanged && newBlocks.isNotEmpty() && overlayVisibleContent) {
            startSmoothFadeIn()
        } else {
            transitionAlpha = 1f
            invalidate()
        }
    }

    private fun startSmoothFadeIn() {
        fadeAnimator?.cancel()
        transitionAlpha = 0.15f
        fadeAnimator = ValueAnimator.ofFloat(0.15f, 1f).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                transitionAlpha = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setContentVisible(visible: Boolean) {
        overlayVisibleContent = visible
        if (visible && blocks.isNotEmpty()) {
            startSmoothFadeIn()
        } else {
            fadeAnimator?.cancel()
            invalidate()
        }
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
            drawSeamlessLineLevelInplace(canvas)
        }
    }

    /**
     * Seamless line-level in-place rendering:
     * 1. Line-level feathered erase: melts softly into surrounding pixels without any border box.
     * 2. Crisp typography with soft halo/shadow: looks naturally native.
     */
    private fun drawSeamlessLineLevelInplace(canvas: Canvas) {
        val minPx = sp(8.5f)
        val padH = dp(3f)
        val padV = dp(1.5f)
        val featherRadius = dp(3f)
        val animAlphaFraction = transitionAlpha.coerceIn(0f, 1f)

        for (blk in blocks) {
            val box = blk.box
            val baseBg = blk.bgColor
            val r = Color.red(baseBg)
            val g = Color.green(baseBg)
            val b = Color.blue(baseBg)
            val effectiveBgAlpha = ((bgAlpha.coerceAtLeast(230) / 255f) * animAlphaFraction * 255).toInt()
            val fgColor = blk.textColor
            val isLightText = Color.luminance(fgColor) > 0.5f

            // 1. Line-level feathered background erase (eliminates rectangular sticker feel!)
            val lineList = if (blk.lineBoxes.isNotEmpty()) blk.lineBoxes else listOf(box)
            solidErasePaint.color = Color.argb(effectiveBgAlpha, r, g, b)
            featherPaint.color = Color.argb((effectiveBgAlpha * 0.85f).toInt(), r, g, b)

            for (lBox in lineList) {
                val l = (lBox.left - dp(2f)).coerceIn(0f, width.toFloat())
                val t = (lBox.top - dp(1.5f)).coerceIn(0f, height.toFloat())
                val rt = (lBox.right + dp(2f)).coerceIn(0f, width.toFloat())
                val bt = (lBox.bottom + dp(1.5f)).coerceIn(0f, height.toFloat())

                // Draw soft feathered halo edge first
                tmpRectF.set(l - featherRadius, t - featherRadius, rt + featherRadius, bt + featherRadius)
                canvas.drawRoundRect(tmpRectF, featherRadius * 2, featherRadius * 2, featherPaint)

                // Draw solid core to cleanly cover original characters
                tmpRectF.set(l, t, rt, bt)
                canvas.drawRoundRect(tmpRectF, dp(2f), dp(2f), solidErasePaint)
            }

            // 2. Measure & layout translated text
            val estimatedFromBox = (box.height() * 0.65f).coerceIn(sp(9.5f), sp(baseFontSizeSp + 2f))
            var fontSize = min(sp(baseFontSizeSp + 1f), estimatedFromBox)
            val maxAllowedW = min(box.width() + dp(32f).toInt(), width - dp(12f).toInt())
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
            val patchW = max(box.width().toFloat(), actualTextW + padH * 2f).coerceAtMost(maxAllowedW.toFloat())
            val patchH = max(box.height().toFloat(), layout.height + padV * 2f)

            val left = box.left.toFloat().coerceIn(dp(2f), max(dp(2f), width - patchW - dp(2f)))
            val top = (box.centerY() - patchH / 2f).coerceIn(dp(2f), max(dp(2f), height - patchH - dp(2f)))

            renderedRects.add(
                Rect(
                    left.toInt(),
                    top.toInt(),
                    (left + patchW).toInt(),
                    (top + patchH).toInt()
                )
            )

            // 3. Crisp Typography with soft halo/shadow
            textPaint.apply {
                this.textSize = fontSize
                this.color = Color.argb((animAlphaFraction * 255).toInt(), Color.red(fgColor), Color.green(fgColor), Color.blue(fgColor))
                if (isLightText) {
                    setShadowLayer(dp(2.8f), 0f, 0f, Color.argb((animAlphaFraction * 210).toInt(), 0, 0, 0))
                } else {
                    setShadowLayer(dp(2.8f), 0f, 0f, Color.argb((animAlphaFraction * 210).toInt(), 255, 255, 255))
                }
            }

            canvas.save()
            canvas.translate(left + padH, top + (patchH - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawSubtitleBar(canvas: Canvas) {
        val animAlphaFraction = transitionAlpha.coerceIn(0f, 1f)
        val combined = blocks.joinToString("\n") { it.translated }
        val marginH = dp(14f)
        val maxTextW = (width - marginH * 2 - dp(24f)).toInt().coerceAtLeast(100)

        textPaint.apply {
            textSize = sp(baseFontSizeSp + 1f)
            color = Color.argb((animAlphaFraction * 255).toInt(), 255, 255, 255)
            setShadowLayer(dp(2.5f), 0f, 0f, Color.argb((animAlphaFraction * 180).toInt(), 0, 0, 0))
        }

        val layout = buildStaticLayout(combined, maxTextW, sp(baseFontSizeSp + 1f))
        val pad = dp(11f)
        val cardH = layout.height + pad * 2f
        val bottomMargin = dp(52f)
        val top = (height - bottomMargin - cardH).coerceAtLeast(dp(40f))

        subtitleBgPaint.color = Color.argb((animAlphaFraction * 228).toInt(), 12, 16, 24)
        subtitleBorderPaint.color = Color.argb((animAlphaFraction * 80).toInt(), 56, 189, 248)

        tmpRectF.set(marginH, top, width - marginH, top + cardH)
        subtitleBounds.set(
            tmpRectF.left.toInt(),
            tmpRectF.top.toInt(),
            tmpRectF.right.toInt(),
            tmpRectF.bottom.toInt()
        )
        canvas.drawRoundRect(tmpRectF, dp(10f), dp(10f), subtitleBgPaint)
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
