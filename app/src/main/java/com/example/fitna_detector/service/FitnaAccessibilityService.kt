package com.example.fitna_detector.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.fitna_detector.detection.MusicDetector
import com.example.fitna_detector.detection.VisualDetector
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.ShieldStatus
import com.example.fitna_detector.overlay.RedShieldOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-Permission Accessibility Service:
 * Real-time on-device screen frame and audio monitoring with sub-200ms reaction.
 *
 * Uses:
 * 1. TYPE_ACCESSIBILITY_OVERLAY (zero extra overlay permission required)
 * 2. takeScreenshot API (Android 11+ / API 30+, non-blocking screenshot pipeline)
 * 3. Accessibility Node inspection (detects YouTube music video titles, romantic clips)
 * 4. AudioManager high-frequency playback detection (zero audio permission required)
 * 5. Instant dismissal the moment fitna content stops or is swiped away.
 */
class FitnaAccessibilityService : AccessibilityService() {

    companion object {
        private val _serviceStatus = MutableStateFlow(ShieldStatus())
        val serviceStatus: StateFlow<ShieldStatus> = _serviceStatus.asStateFlow()

        var instance: FitnaAccessibilityService? = null
            private set
    }

    private lateinit var overlay: RedShieldOverlay
    private lateinit var visualDetector: VisualDetector
    private lateinit var musicDetector: MusicDetector
    private lateinit var audioManager: AudioManager

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private var settings = DetectionSettings()
    private var isVisualProhibited = false
    private var isMusicDetected = false
    private var isKeywordProhibited = false

    private val isScreenshotPending = AtomicBoolean(false)
    private var continuousScannerJob: Job? = null

    // Targeted phrases for YouTube music videos and romantic/intimate scenes (avoid broad single words)
    private val prohibitedKeywords = listOf(
        // Music & Songs (Multi-word or distinct music video markers)
        "official music video", "official mv", "music video", "video song",
        "full song", "lyric video", "lyrics video", "official audio", "official video",
        "audio song", "dance performance", "dance cover", "choreography", "item song",
        "remix song", "remix video", "lofi remix", "lofi song", "slowed + reverb",
        "vevo", "t-series",
        // Romantic, Couple & Prohibited visual scenes
        "romantic scene", "romance scene", "romantic song", "romantic clip",
        "love song", "kiss scene", "kissing scene", "hot scene", "bed scene",
        "bikini", "swimsuit", "lingerie", "cleavage", "nude", "naked",
        "intimate scene", "love scene", "couple scene", "dating show",
        "sensual scene", "erotic scene"
    )

    // Exemptions for Islamic lectures, speeches, nasheeds, and Quran recitations
    private val safeExemptionKeywords = listOf(
        "no music", "without music", "no instruments", "vocal only",
        "acapella", "halal", "nasheed", "quran", "recitation", "tilawat", "lecture",
        "speech", "tafsir", "khutbah", "podcast", "bayan", "fitna !", "islam approves",
        "unblock screen"
    )

    /**
     * Whitelists system UI, settings, launchers, and device utilities.
     * The shield will NEVER trigger when the user is in these apps.
     */
    private fun isWhitelistedPackage(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        if (pkg == applicationContext.packageName) return true
        return pkg.startsWith("com.android.settings") ||
                pkg.startsWith("com.android.systemui") ||
                pkg.startsWith("com.google.android.apps.nexuslauncher") ||
                pkg.startsWith("com.android.launcher") ||
                pkg.startsWith("com.mi.android.globallauncher") ||
                pkg.startsWith("com.sec.android.app.launcher") ||
                pkg.startsWith("com.oppo.launcher") ||
                pkg.startsWith("com.huawei.android.launcher") ||
                pkg.contains(".launcher") ||
                pkg.contains("launcher3") ||
                pkg.contains("settings") ||
                pkg.contains("systemui") ||
                pkg.contains("permissioncontroller") ||
                pkg.contains("packageinstaller") ||
                pkg.contains("inputmethod") ||
                pkg.contains("keyboard") ||
                pkg.contains("dialer") ||
                pkg.contains("contacts") ||
                pkg.contains("deskclock") ||
                pkg.contains("calculator")
    }

    /**
     * Checks if the active package is a video, social, or browser app suitable for keyword inspection.
     */
    private fun isMediaOrBrowserPackage(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        return pkg.contains("youtube") ||
                pkg.contains("instagram") ||
                pkg.contains("tiktok") ||
                pkg.contains("facebook") ||
                pkg.contains("twitter") ||
                pkg.contains("snapchat") ||
                pkg.contains("chrome") ||
                pkg.contains("firefox") ||
                pkg.contains("browser") ||
                pkg.contains("vimeo") ||
                pkg.contains("dailymotion") ||
                pkg.contains("netflix") ||
                pkg.contains("reels")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        overlay = RedShieldOverlay(this, isAccessibilityMode = true)
        visualDetector = VisualDetector(this)
        musicDetector = MusicDetector(this) { audioResult ->
            val currentPkg = rootInActiveWindow?.packageName
            if (isWhitelistedPackage(currentPkg)) {
                isMusicDetected = false
            } else {
                isMusicDetected = audioResult.isMusicDetected
            }
            evaluateShieldTrigger()
        }

        musicDetector.start(settings.allowSpeechLectures)
        startContinuousScanner()

        _serviceStatus.value = _serviceStatus.value.copy(isRunning = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName ?: rootInActiveWindow?.packageName
        if (isWhitelistedPackage(pkg)) {
            // Immediate full unblock when in Settings, Home, or system utility
            isKeywordProhibited = false
            isVisualProhibited = false
            mainHandler.post {
                overlay.hide()
                evaluateShieldTrigger()
            }
            return
        }

        // When user scrolls or swipes away to swap content:
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // User is actively scrolling or swapping content away!
            if (overlay.isShowing()) {
                // Temporarily clear visual flag to allow clean re-scan of the new view
                isVisualProhibited = false
                mainHandler.post {
                    overlay.hide()
                    evaluateShieldTrigger()
                }
            }
        }

        // Only inspect video/clip titles in media, social, and browser apps
        if (isMediaOrBrowserPackage(pkg)) {
            try {
                inspectNodeHierarchy(rootInActiveWindow)
            } catch (ignored: Exception) {}
        }
    }

    private fun inspectNodeHierarchy(root: AccessibilityNodeInfo?) {
        if (root == null) return

        val pkg = root.packageName
        if (isWhitelistedPackage(pkg)) {
            if (isKeywordProhibited) {
                isKeywordProhibited = false
                mainHandler.post { evaluateShieldTrigger() }
            }
            return
        }

        var foundProhibitedTitle = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var inspected = 0
        while (queue.isNotEmpty() && inspected < 50) {
            val node = queue.removeFirst()
            inspected++

            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            val combined = "$text $contentDesc"

            // Check if node contains safe exemption (e.g. "no music", "quran", "lecture")
            val isExempt = safeExemptionKeywords.any { combined.contains(it) }

            if (!isExempt) {
                for (kw in prohibitedKeywords) {
                    if (combined.contains(kw)) {
                        foundProhibitedTitle = true
                        break
                    }
                }
            }

            if (foundProhibitedTitle) break

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        if (foundProhibitedTitle != isKeywordProhibited) {
            isKeywordProhibited = foundProhibitedTitle
            mainHandler.post { evaluateShieldTrigger() }
        }
    }

    private fun startContinuousScanner() {
        continuousScannerJob?.cancel()
        continuousScannerJob = serviceScope.launch {
            while (isActive) {
                val currentPkg = rootInActiveWindow?.packageName

                // If user is in Settings, Home Launcher, or whitelisted app, keep shield off
                if (isWhitelistedPackage(currentPkg)) {
                    if (isKeywordProhibited || isVisualProhibited || overlay.isShowing()) {
                        isKeywordProhibited = false
                        isVisualProhibited = false
                        mainHandler.post { overlay.hide() }
                    }
                    delay(300)
                    continue
                }

                // 1. Direct audio check for instantaneous music reaction
                val musicActive = audioManager.isMusicActive
                if (musicActive != isMusicDetected) {
                    isMusicDetected = musicActive
                    mainHandler.post { evaluateShieldTrigger() }
                }

                // 2. High-speed visual screenshot analysis (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && settings.isVisualEnabled) {
                    if (!isScreenshotPending.get()) {
                        captureAndAnalyzeScreenshot()
                    }
                }

                // 3. Periodic node check if YouTube or media app is visible
                try {
                    val root = rootInActiveWindow
                    if (root != null && isMediaOrBrowserPackage(root.packageName)) {
                        inspectNodeHierarchy(root)
                    }
                } catch (ignored: Exception) {}

                delay(220) // Fast 220ms cycle
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndAnalyzeScreenshot() {
        if (!isScreenshotPending.compareAndSet(false, true)) return

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (bitmap != null) {
                                // Safely copy pixels to software bitmap BEFORE closing hardware buffer
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()

                                if (softwareBitmap != null) {
                                    val result = visualDetector.analyzeFrame(softwareBitmap, settings.sensitivity)
                                    softwareBitmap.recycle()

                                    val wasProhibited = isVisualProhibited
                                    isVisualProhibited = result.isProhibited

                                    if (wasProhibited != isVisualProhibited) {
                                        mainHandler.post { evaluateShieldTrigger() }
                                    }

                                    _serviceStatus.value = _serviceStatus.value.copy(
                                        isVisualProhibited = isVisualProhibited
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            hardwareBuffer.close()
                            isScreenshotPending.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isScreenshotPending.set(false)
                    }
                }
            )
        } catch (e: Exception) {
            isScreenshotPending.set(false)
        }
    }

    private fun evaluateShieldTrigger() {
        val currentPkg = rootInActiveWindow?.packageName
        if (isWhitelistedPackage(currentPkg)) {
            overlay.hide()
            _serviceStatus.value = _serviceStatus.value.copy(
                isShieldActive = false,
                isVisualProhibited = false,
                isMusicDetected = false,
                activeTriggerReason = ""
            )
            return
        }

        // Visual trigger: AI frame classifier OR prohibited keyword in active video
        val visualTrigger = settings.isVisualEnabled && (isVisualProhibited || isKeywordProhibited)
        // Music trigger: Audio stream active with music/video
        val musicTrigger = settings.isMusicEnabled && isMusicDetected
        val isCurrentlyProhibited = visualTrigger || musicTrigger

        val reason = when {
            visualTrigger && musicTrigger -> "Prohibited visual content & music detected"
            visualTrigger -> "Prohibited visual content detected"
            musicTrigger -> "Music playback detected"
            else -> ""
        }

        if (isCurrentlyProhibited) {
            overlay.show(reason, settings.overlayOpacity)
        } else {
            // Dismisses immediately once the user stops or swaps away from the fitna content
            overlay.hide()
        }

        _serviceStatus.value = _serviceStatus.value.copy(
            isShieldActive = isCurrentlyProhibited,
            isVisualProhibited = visualTrigger,
            isMusicDetected = musicTrigger,
            activeTriggerReason = reason
        )
    }

    fun updateSettings(newSettings: DetectionSettings) {
        settings = newSettings
        musicDetector.updateSettings(newSettings.allowSpeechLectures)
        evaluateShieldTrigger()
    }

    fun testShieldTemporarily() {
        overlay.show("TEST MODE (Active for 5s - Simulating Fitna Shield)", settings.overlayOpacity)
        _serviceStatus.value = _serviceStatus.value.copy(
            isShieldActive = true,
            activeTriggerReason = "Test Mode Simulation"
        )

        serviceScope.launch(Dispatchers.Main) {
            delay(5000)
            evaluateShieldTrigger()
        }
    }

    override fun onInterrupt() {
        overlay.hide()
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        screenshotExecutor.shutdown()
        continuousScannerJob?.cancel()
        overlay.hide()
        overlay.destroy()
        musicDetector.stop()
        visualDetector.close()
        _serviceStatus.value = ShieldStatus()
        super.onDestroy()
    }
}
