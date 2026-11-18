# ⚡ X15 Dịch Realtime (Xiaomi 15 Realtime Screen Translator)

Ứng dụng Android Native (Kotlin) **siêu nhẹ, tốc độ cao và tối ưu pin/RAM**, thiết kế chuyên biệt cho **Xiaomi 15 (HyperOS / Android 14–15, màn hình 120Hz, Snapdragon 8 Elite)** có **Temp Root (`su`)** — kèm cơ chế tự động dự phòng `MediaProjection` khi chưa bật root.

---

## ✨ Tính năng nổi bật

### 1. Hai chế độ hoạt động chuyển đổi tức thì
- **Auto Realtime (Dịch liên tục tự động)**:
  - Tự động theo dõi thay đổi khung hình và chỉ chạy OCR + dịch khi nội dung thực sự thay đổi.
  - Hỗ trợ **Ghim vùng (Pin Region)** để chỉ quét một khung cố định (phù hợp đọc Visual Novel, RPG, phụ đề video/livestream) — tăng tốc OCR gấp 3–5 lần.
  - Hai kiểu hiển thị: **Đè trực tiếp lên chữ gốc** (`IN_PLACE`) hoặc **Thanh phụ đề nổi** (`SUBTITLE_BAR`, 0 nháy màn hình).
- **Floating Bubble & Kéo vùng (Drag-Crop)**:
  - Bong bóng nổi gọn nhẹ tự động hít sát mép màn hình và giảm độ sáng khi rảnh.
  - **Chạm bong bóng**: Mở menu điều khiển nhanh (`Auto Realtime`, `Dịch 1 lần`, `Kéo vùng`, `Ghim vùng`, `Ẩn/Hiện chữ`, đổi nhanh Ngôn ngữ OCR / Kiểu hiển thị / Engine dịch).
  - **Nhấn giữ bong bóng**: Bật/tắt nhanh chế độ **Auto Realtime** không cần mở menu.
  - **Kéo vùng chọn**: Vẽ khung bất kỳ trên màn hình → hiện thẻ kết quả có nút **Copy**, **Dịch lại bằng AI**, và **Ghim vùng**.

### 2. Tối ưu sâu cho Temp Root (`su`) trên Xiaomi 15
- **Persistent Root Shell (`RootCaptureEngine`)**: Duy trì tiến trình `su` liên tục để chụp raw framebuffer qua `screencap` (tự nhận diện header 12/16 byte, copy trực tiếp vào `ByteBuffer`, không tốn chi phí nén PNG), tránh hộp thoại xin quyền MediaProjection mỗi lần mở và chụp được cả nội dung giới hạn hiển thị.
- **Tự cấp quyền HyperOS 1 chạm**: Nút **Cấp quyền Root** tự động chạy `appops set <pkg> SYSTEM_ALERT_WINDOW allow` và `dumpsys deviceidle whitelist +<pkg>` để HyperOS không bóp tiến trình nền.
- **Fallback thông minh (`MediaProjectionCapture`)**: Nếu máy khởi động lại và chưa kích hoạt lại temp root, app tự động chuyển sang `MediaProjection` để vẫn dùng bình thường.

### 3. OCR Đa Ngôn Ngữ & Engine Dịch Hybrid + Cache
- **On-device OCR (Google ML Kit v2)**: Hỗ trợ đầy đủ **Tiếng Anh (Latin)**, **Tiếng Trung (ZH)**, **Tiếng Nhật (JA)**, **Tiếng Hàn (KO)** và chế độ **Tự động (Auto EN/CJK)** kèm lọc nhiễu & khử trùng lặp hộp văn bản theo IoU.
- **3 Engine dịch thuật linh hoạt**:
  1. **Google Nhanh (Mặc định)**: Gom tất cả các đoạn chữ trên màn hình vào **1 HTTP request duy nhất** (batch `⟦i⟧`), tốc độ phản hồi nhanh và không cần API key.
  2. **ML Kit Offline**: Dịch trực tiếp trên máy không cần mạng; hỗ trợ tải/kiểm tra gói `VI + EN + ZH + JA + KO` ngay trong giao diện chính.
  3. **AI LLM (OpenRouter / DeepSeek / Gemini)**: Hỗ trợ API chuẩn OpenAI (`/chat/completions`) với System Prompt tùy chỉnh theo ngữ cảnh game, truyện tranh, giao diện.
- **LRU Translation Cache**: Lưu đệm bản dịch trên RAM và đĩa (`x15_trans_cache.json`), đạt 0ms cho các chuỗi/nút giao diện lặp lại.

---

## 🏗️ Cấu trúc dự án

```text
Xiaomi15_RealtimeTranslator/
├── README.md
├── PLAN.md
├── CHECKPOINT.md
├── build_apk.ps1                                  # Script build APK & chạy unit test bằng Gradle cục bộ
├── build.gradle / settings.gradle / gradle.properties
└── app/
    ├── build.gradle                               # Cấu hình arm64-v8a, R8 minify, ML Kit OCR/Translate
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/                               # Giao diện AMOLED tối giản & icon thích ứng
        │   └── java/com/x15/translator/
        │       ├── X15App.kt                      # Application & NotificationChannel
        │       ├── SettingsStore.kt               # Lưu cấu hình chế độ, tốc độ, AI API, vùng ghim
        │       ├── capture/
        │       │   ├── RootCaptureEngine.kt       # Chụp raw screencap qua persistent su + cấp quyền HyperOS
        │       │   ├── MediaProjectionCapture.kt  # Dự phòng khi không có root
        │       │   └── FrameDiffDetector.kt       # So sánh lưới luminance 32x32 (<2ms, zero-alloc)
        │       ├── ocr/
        │       │   └── MultiScriptOcrEngine.kt    # ML Kit Text Recognition v2 (EN/ZH/JA/KO/Auto)
        │       ├── translate/
        │       │   ├── HybridTranslator.kt        # Google Batch + ML Kit Offline + AI OpenAI-compat
        │       │   └── TranslationCache.kt        # LRU Cache RAM + JSON persistence
        │       ├── service/
        │       │   └── OverlayService.kt          # Vòng lặp Auto Realtime, bong bóng nổi, menu nhanh
        │       └── ui/
        │           ├── MainActivity.kt            # Màn hình cài đặt, kiểm tra Root, quản lý gói Offline
        │           ├── TranslationOverlayView.kt  # Vẽ bản dịch đè chữ gốc / thanh phụ đề
        │           ├── RegionSelectorView.kt      # Màn hình kéo chọn vùng / ghim vùng
        │           ├── ResultPopupController.kt   # Thẻ kết quả kéo vùng (Copy / Dịch AI / Ghim)
        │           └── ProjectionPermissionActivity.kt
        └── test/java/com/x15/translator/
            └── TranslatorCoreTest.kt              # Bộ Unit Test JVM (6/6 pass)
```

---

## 📱 Hướng dẫn cài đặt & sử dụng trên Xiaomi 15

### Cài đặt APK
1. Tải file `X15-Realtime-Translate-v1.2.0.apk` từ mục **[Releases](https://github.com/lvh0210/X15-Realtime-Translate/releases)** (hoặc tự build theo hướng dẫn bên dưới).
2. Cài đặt qua ADB hoặc cài trực tiếp trên điện thoại:
   ```powershell
   adb install -r X15-Realtime-Translate-v1.2.0.apk
   ```
3. Mở **X15 Dịch Realtime**:
   - Cấp quyền `su` khi trình quản lý Root (Magisk / KernelSU / APatch) hỏi.
   - Bấm **Cấp quyền Root** để tự động mở quyền *Hiển thị trên ứng dụng khác* và bỏ giới hạn pin HyperOS.
   - Nếu dùng engine **Offline**, bấm **Tải đủ gói Offline (VI + EN + ZH + JA + KO)** một lần.
   - Bấm **Bật Bong Bóng Dịch** để bắt đầu.

### Thao tác nhanh khi sử dụng
| Thao tác | Chức năng |
| :--- | :--- |
| **Chạm bong bóng** | Mở/đóng bảng điều khiển nhanh |
| **Nhấn giữ bong bóng** | Bật / tắt nhanh chế độ **Auto Realtime** |
| **Kéo thả bong bóng** | Di chuyển vị trí (tự động hít sát cạnh màn hình) |
| **✂ Kéo vùng** | Khoanh khung bất kỳ để dịch tức thì, Copy hoặc bấm **Dịch AI** |
| **📌 Ghim vùng** | Khóa vùng cố định cho chế độ Auto Realtime (siêu nhanh cho phụ đề/game) |

---

## 🛠️ Build từ mã nguồn & Kiểm thử

Yêu cầu: **OpenJDK 11**, **Android SDK Platform 34 & Build-Tools 34.0.0**, **Gradle 7.5.1**.

- Chạy Unit Test (`TranslatorCoreTest` — 6/6 test pass):
  ```powershell
  .\build_apk.ps1 -Task testDebugUnitTest
  ```
- Build APK Release tối ưu cho `arm64-v8a`:
  ```powershell
  .\build_apk.ps1 -Task assembleRelease
  ```
  File đầu ra: `app/build/outputs/apk/release/X15-Realtime-Translate-v1.2.0.apk`.

---

## ✅ Trạng thái kiểm thử
- `TranslatorCoreTest`: **6/6 pass** (kiểm tra `RawScreencapParser` header 12/16 byte, `FrameDiffMath`, `BatchMarkerCodec`, `ScriptLanguageDetector`, `OcrPostProcessor`, `TranslationCache` LRU & lưu đĩa).
- `assembleRelease`: Build thành công với R8 minification & resource shrinking (`~31.8 MB`, `arm64-v8a`).
