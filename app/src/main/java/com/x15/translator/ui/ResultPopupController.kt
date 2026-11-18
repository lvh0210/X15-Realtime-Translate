package com.x15.translator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Sleek floating popup card shown after Drag-Crop translation with Copy, AI Re-translate, and Pin actions.
 */
class ResultPopupController(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var rootView: View? = null

    fun show(
        originalText: String,
        translatedText: String,
        engineLabel: String,
        region: Rect,
        onRetryAi: () -> Unit,
        onPinRegion: (Rect) -> Unit
    ) {
        dismiss()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F172A"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "Kết quả • $engineLabel"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = makeActionChip("✕", "#FB7185") { dismiss() }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
        }
        val bodyCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val transTv = TextView(context).apply {
            text = translatedText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.18f)
        }
        val origTv = TextView(context).apply {
            text = originalText
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }
        bodyCol.addView(transTv)
        bodyCol.addView(origTv)
        scroll.addView(bodyCol)
        card.addView(scroll)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(makeActionChip("Copy", "#34D399") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("X15 Translation", translatedText))
            Toast.makeText(context, "Đã copy bản dịch", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(makeActionChip("Dịch AI", "#FBBF24") {
            dismiss()
            onRetryAi()
        })
        btnRow.addView(makeActionChip("Ghim vùng", "#38BDF8") {
            dismiss()
            onPinRegion(region)
        })
        card.addView(btnRow)

        val dm = context.resources.displayMetrics
        val cardWidth = (dm.widthPixels * 0.88f).toInt()
        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (region.bottom + dp(12)).coerceIn(dp(60), (dm.heightPixels * 0.62f).toInt())
        }

        rootView = card
        runCatching { windowManager.addView(card, params) }
    }

    fun dismiss() {
        rootView?.let { v ->
            runCatching { windowManager.removeViewImmediate(v) }
        }
        rootView = null
    }

    private fun makeActionChip(label: String, hexColor: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(hexColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
