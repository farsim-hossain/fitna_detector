# Wiqayah (وقاية) 🛡️

> *Bismillah. In an era where mobile screens are everywhere, safeguarding the hearts and minds of our youth has become an urgent necessity.*
>
> *Wiqayah was created to act as an instant moral reflex on Android — quietly monitoring and pausing the screen whenever inappropriate visual scenes, music, or idolatrous imagery appear.*
>
> *I’ve decided to open-source the codebase for everyone. If you’re a developer, researcher, or designer, feel free to fork it, improve it, and deploy it for your community.*
>
> *My contribution for the Ummah — please keep this effort in your duas! 🤲*

---

## 🌟 Overview

**Wiqayah** is an open-source, on-device digital guardian for Android. Acting as an instantaneous moral reflex, it runs unobtrusively in the background to detect and intercept Islamically prohibited content across any application (browsers, social media feeds, video platforms, and media players) in real-time.

When prohibited content is detected, Wiqayah raises a full-screen **"Pause & Reflect"** crimson shield overlay, reminding the user to guard their sight and hearing. The shield automatically dismisses the moment the content is stopped, swiped away, or the user returns home.

Crucially, Wiqayah is engineered to **never obstruct wholesome, halal, educational, or Islamic content** — Quran recitations, nasheeds without instruments, Islamic lectures, scientific research, and daily tutorials run completely uninterrupted.

---

## ⚡ Key Features

- **🧠 100% On-Device AI Inference**:
  - **Visual Classifier**: Powered by MobileNetV4 NSFW classification via ONNX Runtime Mobile, combined with multi-space (RGB + YCbCr) human skin-tone exposure analysis.
  - **Idol & Sculpture Detection**: Leverages Google ML Kit Image Labeling to instantly identify statues, deities, shrines, and idolatrous sculptures.
  - **Audio & Music Detection**: Uses AudioManager playback stream heuristics and keyword inspection to detect musical audio while exempting speech and lectures.

- **🔒 Zero Extra Permissions (Single-Permission Architecture)**:
  - Requires **only 1 permission**: Android Accessibility Service.
  - Uses `TYPE_ACCESSIBILITY_OVERLAY` to render the alert screen without requiring the invasive `SYSTEM_ALERT_WINDOW` ("Draw over other apps") permission.
  - Zero camera permissions, zero microphone permissions, and zero network calls.

- **🛡️ "Pause & Reflect" Shield**:
  - Full-screen crimson alert with clear Islamic guidance: *"change the content. Watch something that Islam approves"*.
  - Displays specific detection reasons (*Idol / Religious sculpture detected*, *Prohibited visual content detected*, *Music playback detected*).
  - Sub-200ms trigger response.

- **🕌 Intelligent Whitelisting & Exemption**:
  - Pure Quran, Hadith, and prayer applications are completely whitelisted.
  - Quran recitation videos (e.g. *Surah Al-Mulk recited by Mishary Alafasy*) play without interruption.
  - Educational and everyday content (scientific articles, tech tutorials, cooking recipes) are rigorously verified against false positives.

- **🔐 100% Privacy Focused**:
  - No data ever leaves the device. No telemetry, no cloud processing, no analytics, no external servers.

---

## 🏗️ Architecture & Technology Stack

- **Language**: Kotlin 2.0+ (Coroutine-based asynchronous reactive pipeline)
- **UI Framework**: Jetpack Compose & Material 3 (Dashboard), Custom Android WindowManager FrameLayout (Accessibility Overlay)
- **ML / AI Engine**:
  - [ONNX Runtime Mobile](https://onnxruntime.ai/) (`onnxruntime-android:1.17.0`) for MobileNetV4 inference.
  - [Google ML Kit Image Labeling](https://developers.google.com/ml-kit/vision/image-labeling) for on-device visual object categorization.
- **System APIs**:
  - Android 11+ (API 30+) non-blocking `takeScreenshot` API.
  - Android `AccessibilityNodeInfo` tree traversal for live media title analysis.
  - Android `AudioManager` high-frequency stream monitoring.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Koala or newer.
- JDK 17 or JDK 21.
- Android SDK Platform 34 (Android 14) / Min SDK 26 (Android 8.0).
- Physical Android device or Emulator running Android 11+ (API 30+).

### Building from Source

1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-username/wiqayah.git
   cd wiqayah
   ```

2. **Build Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install on device/emulator:**
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Activate Wiqayah:**
   - Open the **Wiqayah** app.
   - Tap **"Enable Wiqayah (1-Tap Grant)"** on the setup card.
   - Toggle on the accessibility service in the Android settings prompt.
   - The dashboard card will transition to vibrant green: **Shield Active**.

---

## 🤝 Contributing

Contributions from developers, UI/UX designers, ML researchers, and scholars across the Ummah are warmly welcomed!

### Areas for Improvement:
- [ ] Optimizing ONNX quantization (INT8) for lower battery consumption on low-end chipsets.
- [ ] Expanding dataset and fine-tuning for custom Islamic edge-case classifications.
- [ ] Adding multilingual UI support (Arabic, Bengali, Urdu, Indonesian, French, Turkish, etc.).
- [ ] Implementing customizable sensitivity presets within the dashboard.

To contribute:
1. Fork the Project.
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`).
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the Branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

## 📜 License

This project is open-sourced under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

*“Whoever among you sees an evil, let him change it with his hand; and if he is not able to, then with his tongue; and if he is not able to, then with his heart — and that is the weakest of faith.”* — **Sahih Muslim**
