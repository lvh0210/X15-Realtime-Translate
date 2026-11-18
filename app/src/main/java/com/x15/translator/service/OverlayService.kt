package com.x15.translator.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.x15.translator.OcrScript
import com.x15.translator.OverlayStyle
import com.x15.translator.R
import com.x15.translator.TranslateEngine
import com.x15.translator.TranslationMode
import com.x15.translator.X15App
import com.x15.translator.capture.FrameDiffDetector
import com.x15.translator.capture.MediaProjectionCapture
import com.x15.translator.capture.RootCaptureEngine
import com.x15.translator.ocr.MultiScriptOcrEngine
import com.x15.translator.translate.HybridTranslator
import com.x15.translator.translate.TranslationCache
import com.x15.translator.ui.MainActivity
import com.x15.translator.ui.ProjectionPermissionActivity
import com.x15.translator.ui.RegionSelectorView
import com.x15.translator.ui.ResultPopupController
import com.x15.translator.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class OverlayService : Service() {

    private val settings by lazy { X15App.instance.settings }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var rootCapture: RootCaptureEngine
    private lateinit var mediaCapture: MediaProjectionCapture
    private val diffDetector = FrameDiffDetector()
    private val ocrEngine = MultiScriptOcrEngine()
    private lateinit var cache: TranslationCache
    private lateinit var translator: HybridTranslator

    private lateinit var overlayView: TranslationOverlayView
    private lateinit var popupController: ResultPopupController

    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var regionSelector: RegionSelectorView? = null

    private var autoLoopJob: Job? = null
    private var oneShotJob: Job? = null

    @Volatile
    private var useRootMode = false
    @Volatile
    private var autoRunning = false
    private var lastTextSignature = ""
    private var lastPinnedRegion: Rect? = null
    private val recentRenderedTexts = HashSet<String>()
    private var idleProbeCounter = 0

    private var screenW = 1200
    private var screenH = 2670

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

        rootCapture = RootCaptureEngine(this)
        mediaCapture = MediaProjectionCapture(this)
        cache = TranslationCache(this)
        translator = HybridTranslator(settings, cache)
        popupController = ResultPopupController(this, windowManager)

        startForegroundTyped(useProjection = false)
        setupOverlayLayer()
        setupFloatingBubble()

        serviceScope.launch(Dispatchers.IO) {
            val rootOk = settings.preferRootCapture && rootCapture.isRootAvailable()
            if (rootOk) {
                rootCapture.grantHyperOsPrivileges(packageName)
                useRootMode = rootCapture.startSession()
            }
            withContext(Dispatchers.Main) {
                refreshBubbleState()
                if (!useRootMode && !mediaCapture.isReady) {
                    Toast.makeText(
                        this@OverlayService,
                        "Chưa có Root -> dùng MediaProjection dự phòng",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (settings.mode == TranslationMode.AUTO_REALTIME) {
                    setAutoRealtime(true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION_RESULT -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null) {
                    startForegroundTyped(useProjection = true)
                    if (mediaCapture.start(code, data)) {
                        useRootMode = false
                        Toast.makeText(this, "Đã bật chụp màn hình MediaProjection", Toast.LENGTH_SHORT).show()
                        if (settings.mode == TranslationMode.AUTO_REALTIME) {
                            setAutoRealtime(true)
                        }
                    }
                }
            }
            ACTION_TOGGLE_AUTO -> setAutoRealtime(!autoRunning)
            ACTION_REFRESH_SETTINGS -> {
                updateScreenMetrics()
                lastPinnedRegion = settings.getPinnedRegionPixels(screenW, screenH)
                overlayView.updateData(
                    emptyList(),
                    settings.overlayStyle,
                    lastPinnedRegion,
                    settings.fontSizeSp,
                    settings.overlayAlpha
                )
                lastTextSignature = ""
                recentRenderedTexts.clear()
                diffDetector.reset()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        autoLoopJob?.cancel()
        oneShotJob?.cancel()
        serviceScope.cancel()
        popupController.dismiss()
        dismissMenu()
        dismissRegionSelector()
        runCatching { windowManager.removeViewImmediate(overlayView) }
        bubbleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        rootCapture.closeSession()
        mediaCapture.stop()
        ocrEngine.close()
        translator.close()
        super.onDestroy()
    }

    private fun startForegroundTyped(useProjection: Boolean) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = Notification.Builder(this, X15App.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Tắt dịch", stopIntent).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (useProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            if (fgsType != 0) {
                startForeground(NOTIF_ID, notif, fgsType)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateScreenMetrics() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        lastPinnedRegion = settings.getPinnedRegionPixels(screenW, screenH)
    }

    private fun setupOverlayLayer() {
        overlayView = TranslationOverlayView(this)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayView, params)
        overlayView.updateData(
            emptyList(),
            settings.overlayStyle,
            lastPinnedRegion,
            settings.fontSizeSp,
            settings.overlayAlpha
        )
    }

    private fun setupFloatingBubble() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1f).toInt(), Color.parseColor("#8838BDF8"))
            }
            alpha = 0.55f
        }
        val label = TextView(this).apply {
            text = "⚡"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        container.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - dp(54)
            y = (screenH * 0.36f).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                setAutoRealtime(!autoRunning)
            }
        }

        container.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.95f
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (startX + dx).coerceIn(0, screenW - v.width)
                        params.y = (startY + dy).coerceIn(dp(24), screenH - v.height - dp(24))
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed) {
                        toggleQuickMenu()
                    } else if (moved) {
                        params.x = if (params.x + v.width / 2 < screenW / 2) dp(4) else screenW - v.width - dp(4)
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    mainHandler.postDelayed({ v.alpha = if (autoRunning) 0.65f else 0.48f }, 1500L)
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        bubbleLabel = label
        bubbleParams = params
        windowManager.addView(container, params)
        refreshBubbleState()
    }

    private fun refreshBubbleState() {
        val bg = bubbleView?.background as? GradientDrawable
        if (autoRunning) {
            bubbleLabel?.text = "● AUTO"
            bubbleLabel?.setTextColor(Color.parseColor("#34D399"))
            bg?.setStroke(dp(1f).toInt(), Color.parseColor("#AA34D399"))
        } else {
            bubbleLabel?.text = "⚡"
            bubbleLabel?.setTextColor(Color.parseColor("#F8FAFC"))
            bg?.setStroke(dp(1f).toInt(), Color.parseColor("#8838BDF8"))
        }
    }

    private fun setAutoRealtime(enable: Boolean) {
        if (enable && !ensureCaptureReady()) return
        autoRunning = enable
        settings.mode = if (enable) TranslationMode.AUTO_REALTIME else TranslationMode.MANUAL_BUBBLE
        refreshBubbleState()
        autoLoopJob?.cancel()

        if (!enable) {
            return
        }

        diffDetector.reset()
        lastTextSignature = ""
        recentRenderedTexts.clear()
        idleProbeCounter = 0
        autoLoopJob = serviceScope.launch {
            while (isActive && autoRunning) {
                runAutoCycle()
                delay(settings.scanSpeed.intervalMs)
            }
        }
    }

    /**
     * Zero-flicker two-phase auto loop:
     * 1) Probe screen WITHOUT hiding overlay (excluding overlay & system bars). If unchanged -> return immediately (0 blinks).
     * 2) Only when content actually changes, wait brief scroll-settle, hide overlay cleanly for SurfaceFlinger commit, OCR, and re-render.
     */
    private suspend fun runAutoCycle() {
        updateScreenMetrics()
        val pinned = lastPinnedRegion
        val excludes = buildExcludeRects(includeOverlayBoxes = true)

        // Phase 1: Non-intrusive probe (never hides overlay!)
        val probeFrame = captureScreenClean(hideOverlay = false) ?: return
        val nonOverlayChanged = try {
            withContext(Dispatchers.Default) {
                diffDetector.hasSignificantChange(
                    bitmap = probeFrame,
                    cropRegion = pinned,
                    excludeRects = excludes,
                    threshold = 0.016f
                )
            }
        } finally {
            if (!probeFrame.isRecycled) probeFrame.recycle()
        }

        val periodicPinnedVerify = pinned != null &&
            settings.overlayStyle == OverlayStyle.IN_PLACE &&
            overlayView.hasRenderedBlocks() &&
            (++idleProbeCounter >= 5)

        if (!nonOverlayChanged && !periodicPinnedVerify) {
            return
        }
        idleProbeCounter = 0

        if (nonOverlayChanged) {
            // Let active scroll settle slightly so we don't OCR blurred mid-scroll frames
            delay(110L)
        }

        // Phase 2: Clean OCR capture (only hides IN_PLACE overlay when needed)
        val needHideOverlay =
            settings.overlayStyle == OverlayStyle.IN_PLACE && overlayView.hasRenderedBlocks()
        val ocrFrame = captureScreenClean(hideOverlay = needHideOverlay) ?: return

        try {
            val ocrExcludes = buildExcludeRects(includeOverlayBoxes = false)
            val rawBlocks = ocrEngine.recognize(
                fullBitmap = ocrFrame,
                script = settings.ocrScript,
                cropRegion = pinned,
                excludeRects = ocrExcludes
            )
            val ocrBlocks = rawBlocks.filter { blk ->
                !recentRenderedTexts.contains(TranslationCache.normalize(blk.text))
            }

            if (ocrBlocks.isEmpty()) {
                if (lastTextSignature.isNotEmpty()) {
                    lastTextSignature = ""
                    recentRenderedTexts.clear()
                    overlayView.updateData(
                        emptyList(),
                        settings.overlayStyle,
                        pinned,
                        settings.fontSizeSp,
                        settings.overlayAlpha
                    )
                }
                return
            }

            val signature = ocrBlocks.joinToString("|") { it.text }
            if (signature == lastTextSignature) return

            val translated = translator.translateBlocks(ocrBlocks)
            if (!autoRunning) return

            lastTextSignature = signature
            recentRenderedTexts.clear()
            for (tb in translated) {
                recentRenderedTexts.add(TranslationCache.normalize(tb.translated))
            }

            overlayView.setContentVisible(true)
            overlayView.updateData(
                translated,
                settings.overlayStyle,
                pinned,
                settings.fontSizeSp,
                settings.overlayAlpha
            )

            // Re-sync diff baseline after rendering new overlay so our own pills don't trigger next tick
            delay(50L)
            captureScreenClean(hideOverlay = false)?.let { postFrame ->
                try {
                    val postExcludes = buildExcludeRects(includeOverlayBoxes = true)
                    withContext(Dispatchers.Default) {
                        diffDetector.reset()
                        diffDetector.hasSignificantChange(postFrame, pinned, postExcludes)
                    }
                } finally {
                    if (!postFrame.isRecycled) postFrame.recycle()
                }
            }
        } finally {
            if (!ocrFrame.isRecycled) ocrFrame.recycle()
        }
    }

    private fun triggerOneShotTranslate() {
        if (!ensureCaptureReady()) return
        dismissMenu()
        oneShotJob?.cancel()
        oneShotJob = serviceScope.launch {
            updateScreenMetrics()
            val frame = captureScreenClean(hideOverlay = overlayView.hasRenderedBlocks()) ?: return@launch
            try {
                val blocks = ocrEngine.recognize(
                    fullBitmap = frame,
                    script = settings.ocrScript,
                    cropRegion = lastPinnedRegion,
                    excludeRects = buildExcludeRects(includeOverlayBoxes = false)
                )
                if (blocks.isEmpty()) {
                    Toast.makeText(
                        this@OverlayService,
                        "Không phát hiện văn bản ngoại ngữ",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val translated = translator.translateBlocks(blocks)
                recentRenderedTexts.clear()
                translated.forEach { recentRenderedTexts.add(TranslationCache.normalize(it.translated)) }
                overlayView.setContentVisible(true)
                overlayView.updateData(
                    translated,
                    settings.overlayStyle,
                    lastPinnedRegion,
                    settings.fontSizeSp,
                    settings.overlayAlpha
                )
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    private fun startDragRegionFlow(pinOnly: Boolean) {
        if (!ensureCaptureReady()) return
        dismissMenu()
        popupController.dismiss()

        serviceScope.launch {
            val preShot = if (!pinOnly) captureScreenClean(hideOverlay = overlayView.hasRenderedBlocks()) else null
            showRegionSelector(
                promptLabel = if (pinOnly) "Kéo chọn vùng để Ghim Auto Realtime" else "Kéo chọn vùng cần dịch tức thì",
                onSelected = { rect ->
                    if (pinOnly) {
                        preShot?.recycle()
                        applyPinnedRegion(rect)
                    } else if (preShot != null) {
                        translateCroppedBitmap(preShot, rect, overrideEngine = null)
                    }
                },
                onCancel = {
                    preShot?.recycle()
                }
            )
        }
    }

    private fun applyPinnedRegion(rect: Rect?) {
        updateScreenMetrics()
        settings.setPinnedRegionPixels(rect, screenW, screenH)
        lastPinnedRegion = rect
        diffDetector.reset()
        lastTextSignature = ""
        recentRenderedTexts.clear()
        overlayView.updateData(
            emptyList(),
            settings.overlayStyle,
            rect,
            settings.fontSizeSp,
            settings.overlayAlpha,
            flashPinGuide = rect != null
        )
        if (rect != null) {
            Toast.makeText(this, "Đã ghim vùng Auto Realtime", Toast.LENGTH_SHORT).show()
            if (!autoRunning) setAutoRealtime(true)
        } else {
            Toast.makeText(this, "Đã bỏ ghim (dịch toàn màn hình)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateCroppedBitmap(
        bitmap: Bitmap,
        rect: Rect,
        overrideEngine: TranslateEngine?
    ) {
        oneShotJob?.cancel()
        oneShotJob = serviceScope.launch {
            try {
                val blocks = ocrEngine.recognize(
                    fullBitmap = bitmap,
                    script = settings.ocrScript,
                    cropRegion = rect
                )
                if (blocks.isEmpty()) {
                    Toast.makeText(this@OverlayService, "Không thấy văn bản cần dịch trong vùng", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val translated = translator.translateBlocks(blocks, overrideEngine = overrideEngine)
                if (translated.isEmpty()) {
                    Toast.makeText(this@OverlayService, "Văn bản đã ở Tiếng Việt", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val orig = translated.joinToString("\n") { it.original }
                val trans = translated.joinToString("\n") { it.translated }
                val eng = translated.firstOrNull()?.engineUsed ?: ""

                val snapshotCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                popupController.show(
                    originalText = orig,
                    translatedText = trans,
                    engineLabel = eng,
                    region = rect,
                    onRetryAi = {
                        translateCroppedBitmap(snapshotCopy, rect, TranslateEngine.AI_LLM)
                    },
                    onPinRegion = { pinRect ->
                        snapshotCopy.recycle()
                        applyPinnedRegion(pinRect)
                    }
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun showRegionSelector(
        promptLabel: String,
        onSelected: (Rect) -> Unit,
        onCancel: () -> Unit
    ) {
        dismissRegionSelector()
        val selector = RegionSelectorView(
            context = this,
            promptLabel = promptLabel,
            onRegionSelected = { rect ->
                dismissRegionSelector()
                onSelected(rect)
            },
            onCancelled = {
                dismissRegionSelector()
                onCancel()
            }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        regionSelector = selector
        windowManager.addView(selector, params)
    }

    private fun dismissRegionSelector() {
        regionSelector?.let { runCatching { windowManager.removeViewImmediate(it) } }
        regionSelector = null
    }

    private fun toggleQuickMenu() {
        if (menuView != null) {
            dismissMenu()
            return
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F00B1120"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeMenuBtn(if (autoRunning) "⏸ Dừng Auto" else "▶ Auto Realtime", "#34D399") {
            dismissMenu()
            setAutoRealtime(!autoRunning)
        })
        row1.addView(makeMenuBtn("⚡ Dịch 1 lần", "#38BDF8") {
            triggerOneShotTranslate()
        })
        card.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row2.addView(makeMenuBtn("✂ Kéo vùng", "#FBBF24") {
            startDragRegionFlow(pinOnly = false)
        })
        val hasPin = settings.getPinnedRegionNormalized() != null
        row2.addView(makeMenuBtn(if (hasPin) "📍 Xóa ghim" else "📌 Ghim vùng", "#A78BFA") {
            dismissMenu()
            if (hasPin) applyPinnedRegion(null) else startDragRegionFlow(pinOnly = true)
        })
        card.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row3.addView(makeMenuBtn("👁 ${if (overlayView.isContentVisible()) "Ẩn chữ" else "Hiện chữ"}", "#94A3B8") {
            overlayView.setContentVisible(!overlayView.isContentVisible())
            dismissMenu()
        })
        row3.addView(makeMenuBtn("Ngữ: ${settings.ocrScript.name}", "#CBD5E1") {
            val all = OcrScript.values()
            settings.ocrScript = all[(settings.ocrScript.ordinal + 1) % all.size]
            lastTextSignature = ""
            Toast.makeText(this, "OCR: ${settings.ocrScript.label}", Toast.LENGTH_SHORT).show()
            dismissMenu()
        })
        card.addView(row3)

        val row4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row4.addView(makeMenuBtn(
            if (settings.overlayStyle == OverlayStyle.IN_PLACE) "Kiểu: Đè chữ" else "Kiểu: Phụ đề",
            "#CBD5E1"
        ) {
            settings.overlayStyle = if (settings.overlayStyle == OverlayStyle.IN_PLACE) {
                OverlayStyle.SUBTITLE_BAR
            } else {
                OverlayStyle.IN_PLACE
            }
            lastTextSignature = ""
            diffDetector.reset()
            dismissMenu()
        })
        row4.addView(makeMenuBtn("Engine: ${settings.translateEngine.name.take(6)}", "#38BDF8") {
            val all = TranslateEngine.values()
            settings.translateEngine = all[(settings.translateEngine.ordinal + 1) % all.size]
            lastTextSignature = ""
            Toast.makeText(this, "Dịch: ${settings.translateEngine.label}", Toast.LENGTH_SHORT).show()
            dismissMenu()
        })
        row4.addView(makeMenuBtn("✕", "#FB7185") {
            dismissMenu()
            stopSelf()
        })
        card.addView(row4)

        val bp = bubbleParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = ((bp?.y ?: (screenH / 3)) + dp(40)).coerceIn(dp(60), screenH - dp(240))
        }
        menuView = card
        windowManager.addView(card, params)
    }

    private fun dismissMenu() {
        menuView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        menuView = null
    }

    private fun makeMenuBtn(text: String, hexColor: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(hexColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun ensureCaptureReady(): Boolean {
        if (useRootMode || mediaCapture.isReady) return true
        val intent = Intent(this, ProjectionPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        return false
    }

    private suspend fun captureScreenClean(hideOverlay: Boolean): Bitmap? {
        if (hideOverlay) {
            setOverlayVisibilitySync(View.INVISIBLE)
            // Wait ~42ms after vsync so RenderThread + SurfaceFlinger commit the hidden layer before screencap
            delay(42L)
        }
        return try {
            withContext(Dispatchers.IO) {
                if (useRootMode) {
                    val bmp = rootCapture.captureFrame()
                    if (bmp != null) return@withContext bmp
                    useRootMode = false
                }
                if (mediaCapture.isReady) mediaCapture.captureCopy() else null
            }
        } finally {
            if (hideOverlay) {
                withContext(Dispatchers.Main) {
                    overlayView.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun setOverlayVisibilitySync(vis: Int) {
        withContext(Dispatchers.Main) {
            overlayView.visibility = vis
            val latch = CountDownLatch(1)
            overlayView.postOnAnimation { latch.countDown() }
            withContext(Dispatchers.IO) {
                latch.await(36L, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun buildExcludeRects(includeOverlayBoxes: Boolean): List<Rect> {
        val list = ArrayList<Rect>(12)
        // Exclude status bar & gesture navigation bar so clock/battery/icons never trigger false rescans
        list.add(Rect(0, 0, screenW, (screenH * 0.045f).toInt()))
        list.add(Rect(0, (screenH * 0.96f).toInt(), screenW, screenH))

        bubbleView?.let { bv ->
            val bp = bubbleParams
            if (bp != null && bv.width > 0 && bv.height > 0) {
                list.add(Rect(bp.x - 8, bp.y - 8, bp.x + bv.width + 8, bp.y + bv.height + 8))
            }
        }
        if (includeOverlayBoxes) {
            for (r in overlayView.getOccupiedRects()) {
                list.add(Rect(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6))
            }
        } else if (settings.overlayStyle == OverlayStyle.SUBTITLE_BAR) {
            list.addAll(overlayView.getOccupiedRects())
        }
        return list
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val ACTION_STOP = "com.x15.translator.STOP"
        const val ACTION_TOGGLE_AUTO = "com.x15.translator.TOGGLE_AUTO"
        const val ACTION_REFRESH_SETTINGS = "com.x15.translator.REFRESH"
        const val ACTION_PROJECTION_RESULT = "com.x15.translator.PROJ_RESULT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 1501

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }
}
