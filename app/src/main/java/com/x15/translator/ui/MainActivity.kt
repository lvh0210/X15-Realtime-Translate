package com.x15.translator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.x15.translator.AiPreset
import com.x15.translator.OcrScript
import com.x15.translator.OverlayStyle
import com.x15.translator.R
import com.x15.translator.ScanSpeed
import com.x15.translator.TranslateEngine
import com.x15.translator.TranslationMode
import com.x15.translator.X15App
import com.x15.translator.capture.RootCaptureEngine
import com.x15.translator.service.OverlayService
import com.x15.translator.translate.HybridTranslator
import com.x15.translator.translate.TranslationCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val settings by lazy { X15App.instance.settings }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rootCapture: RootCaptureEngine
    private lateinit var cache: TranslationCache
    private lateinit var translator: HybridTranslator

    private lateinit var tvRootStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnGrantRootPriv: Button
    private lateinit var tvOfflineModels: TextView
    private lateinit var btnDownloadOfflinePack: Button
    private lateinit var tvCacheInfo: TextView

    private lateinit var etAiBaseUrl: EditText
    private lateinit var etAiApiKey: EditText
    private lateinit var etAiModel: EditText
    private lateinit var etAiPrompt: EditText

    private var rootDetected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootCapture = RootCaptureEngine(this)
        cache = TranslationCache(this)
        translator = HybridTranslator(settings, cache)

        bindViews()
        setupSpinners()
        setupActions()
        requestPostNotificationsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshOfflineModels()
    }

    override fun onDestroy() {
        uiScope.cancel()
        translator.close()
        super.onDestroy()
    }

    private fun bindViews() {
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnGrantRootPriv = findViewById(R.id.btnGrantRootPriv)
        tvOfflineModels = findViewById(R.id.tvOfflineModels)
        btnDownloadOfflinePack = findViewById(R.id.btnDownloadOfflinePack)
        tvCacheInfo = findViewById(R.id.tvCacheInfo)

        etAiBaseUrl = findViewById(R.id.etAiBaseUrl)
        etAiApiKey = findViewById(R.id.etAiApiKey)
        etAiModel = findViewById(R.id.etAiModel)
        etAiPrompt = findViewById(R.id.etAiPrompt)

        etAiBaseUrl.setText(settings.aiBaseUrl)
        etAiApiKey.setText(settings.aiApiKey)
        etAiModel.setText(settings.aiModel)
        etAiPrompt.setText(settings.aiSystemPrompt)
    }

    private fun setupSpinners() {
        setupSpinner(
            R.id.spMode,
            listOf("Auto Realtime (Tự động dịch khi màn hình đổi)", "Bong bóng thủ công (Chạm / Kéo vùng)"),
            settings.mode.ordinal
        ) { idx ->
            settings.mode = TranslationMode.values()[idx]
            notifyServiceRefresh()
        }

        setupSpinner(
            R.id.spOverlayStyle,
            listOf("Đè trực tiếp lên chữ gốc", "Thanh phụ đề nổi (0 nháy màn hình)"),
            settings.overlayStyle.ordinal
        ) { idx ->
            settings.overlayStyle = OverlayStyle.values()[idx]
            notifyServiceRefresh()
        }

        setupSpinner(
            R.id.spOcrScript,
            OcrScript.values().map { it.label },
            settings.ocrScript.ordinal
        ) { idx ->
            settings.ocrScript = OcrScript.values()[idx]
            notifyServiceRefresh()
        }

        setupSpinner(
            R.id.spScanSpeed,
            ScanSpeed.values().map { it.label },
            settings.scanSpeed.ordinal
        ) { idx ->
            settings.scanSpeed = ScanSpeed.values()[idx]
            notifyServiceRefresh()
        }

        setupSpinner(
            R.id.spTranslateEngine,
            TranslateEngine.values().map { it.label },
            settings.translateEngine.ordinal
        ) { idx ->
            settings.translateEngine = TranslateEngine.values()[idx]
            notifyServiceRefresh()
        }

        val swRoot = findViewById<Switch>(R.id.swPreferRoot)
        swRoot.isChecked = settings.preferRootCapture
        swRoot.setOnCheckedChangeListener { _, isChecked ->
            settings.preferRootCapture = isChecked
        }

        val spPreset = findViewById<Spinner>(R.id.spAiPreset)
        spPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AiPreset.values().map { it.label }
        )
        spPreset.setSelection(settings.aiPreset.ordinal, false)
        spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var initial = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (initial) {
                    initial = false
                    return
                }
                val preset = AiPreset.values()[pos]
                settings.aiPreset = preset
                if (preset != AiPreset.CUSTOM) {
                    etAiBaseUrl.setText(preset.baseUrl)
                    etAiModel.setText(preset.defaultModel)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpinner(
        viewId: Int,
        items: List<String>,
        selectedIndex: Int,
        onChanged: (Int) -> Unit
    ) {
        val sp = findViewById<Spinner>(viewId)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selectedIndex.coerceIn(0, items.lastIndex), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (first) {
                    first = false
                    return
                }
                onChanged(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupActions() {
        btnToggleService.setOnClickListener {
            if (OverlayService.isServiceRunning) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                btnToggleService.postDelayed({ refreshStatus() }, 250)
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                if (rootDetected) {
                    uiScope.launch(Dispatchers.IO) {
                        val ok = rootCapture.grantHyperOsPrivileges(packageName)
                        withContext(Dispatchers.Main) {
                            if (ok && Settings.canDrawOverlays(this@MainActivity)) {
                                startOverlayService()
                            } else {
                                openOverlayPermissionSettings()
                            }
                        }
                    }
                } else {
                    openOverlayPermissionSettings()
                }
                return@setOnClickListener
            }

            startOverlayService()
        }

        btnGrantRootPriv.setOnClickListener {
            uiScope.launch(Dispatchers.IO) {
                val isRoot = rootCapture.isRootAvailable()
                val granted = if (isRoot) rootCapture.grantHyperOsPrivileges(packageName) else false
                withContext(Dispatchers.Main) {
                    if (granted) {
                        Toast.makeText(
                            this@MainActivity,
                            "Đã cấp quyền Overlay & bỏ giới hạn pin HyperOS qua Root",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Không lấy được quyền su — hãy kiểm tra quản lý Root",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    refreshStatus()
                }
            }
        }

        btnDownloadOfflinePack.setOnClickListener {
            btnDownloadOfflinePack.isEnabled = false
            btnDownloadOfflinePack.text = "Đang tải gói ngôn ngữ Offline..."
            uiScope.launch {
                val langs = listOf(
                    TranslateLanguage.VIETNAMESE,
                    TranslateLanguage.ENGLISH,
                    TranslateLanguage.CHINESE,
                    TranslateLanguage.JAPANESE,
                    TranslateLanguage.KOREAN
                )
                var okCount = 0
                for (lang in langs) {
                    if (translator.downloadLanguageModel(lang)) okCount++
                }
                btnDownloadOfflinePack.isEnabled = true
                btnDownloadOfflinePack.text = "Tải đủ gói Offline (VI + EN + ZH + JA + KO)"
                Toast.makeText(
                    this@MainActivity,
                    "Đã sẵn sàng $okCount/${langs.size} gói Offline",
                    Toast.LENGTH_SHORT
                ).show()
                refreshOfflineModels()
            }
        }

        findViewById<Button>(R.id.btnSaveAi).setOnClickListener {
            settings.aiBaseUrl = etAiBaseUrl.text.toString()
            settings.aiApiKey = etAiApiKey.text.toString()
            settings.aiModel = etAiModel.text.toString()
            settings.aiSystemPrompt = etAiPrompt.text.toString()
            notifyServiceRefresh()
            Toast.makeText(this, "Đã lưu cấu hình AI", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            cache.clear()
            tvCacheInfo.text = "Cache: 0 câu đã lưu"
            Toast.makeText(this, "Đã xóa cache bản dịch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        startForegroundService(intent)
        btnToggleService.postDelayed({ refreshStatus() }, 300)
    }

    private fun notifyServiceRefresh() {
        if (OverlayService.isServiceRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH_SETTINGS))
        }
    }

    private fun refreshStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (canOverlay) {
            "Quyền hiển thị nổi (Overlay): Đã cấp"
        } else {
            "Quyền hiển thị nổi (Overlay): Chưa cấp (bấm Bật hoặc Cấp quyền Root)"
        }

        btnToggleService.text = if (OverlayService.isServiceRunning) {
            "Tắt Bong Bóng Dịch"
        } else {
            "Bật Bong Bóng Dịch"
        }
        tvCacheInfo.text = "Cache: ${cache.size()} câu đã lưu"

        uiScope.launch(Dispatchers.IO) {
            val ok = rootCapture.isRootAvailable()
            withContext(Dispatchers.Main) {
                rootDetected = ok
                tvRootStatus.text = if (ok) {
                    "Temp Root (su): Đã sẵn sàng • Raw Screencap siêu tốc"
                } else {
                    "Temp Root (su): Chưa phát hiện • Tự động dùng MediaProjection"
                }
            }
        }
    }

    private fun refreshOfflineModels() {
        uiScope.launch {
            val installed = translator.getDownloadedLanguageCodes()
            val required = listOf("vi", "en", "zh", "ja", "ko")
            val summary = required.joinToString("  ") { code ->
                val tag = code.uppercase()
                if (installed.contains(code)) "✓$tag" else "✗$tag"
            }
            tvOfflineModels.text = "Trạng thái model: $summary"
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}
