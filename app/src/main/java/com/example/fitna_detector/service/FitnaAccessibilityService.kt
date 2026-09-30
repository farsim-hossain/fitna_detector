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
import com.example.fitna_detector.model.ContentFilter
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.ShieldStatus
import com.example.fitna_detector.overlay.RedShieldOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
@SuppressLint("AccessibilityPolicy")
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
    private var isContentExplicitlyAllowed = false

    /**
     * Whitelists system UI, settings, launchers, device utilities, and Quran/Islamic apps.
     * The shield will NEVER trigger when the user is in these apps.
     */
    private fun isWhitelistedPackage(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        if (pkg == applicationContext.packageName) return true

        // Whitelist all Quran and Islamic prayer/study apps
        if (pkg.contains("quran") ||
            pkg.contains("tarteel") ||
            pkg.contains("islam360") ||
            pkg.contains("athan") ||
            pkg.contains("adhan") ||
            pkg.contains("salat") ||
            pkg.contains("namaz") ||
            pkg.contains("muslimpro") ||
            pkg.contains("ayah") ||
            pkg.contains("hadith")) {
            return true
        }

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
                pkg.contains("reels") ||
                pkg.contains("smarttube") ||
                pkg.contains("newpipe") ||
                pkg.contains("vlc") ||
                pkg.contains("mxtech") ||
                pkg.contains("player") ||
                pkg.contains("video")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val prefs = getSharedPreferences("fitna_detector_prefs", Context.MODE_PRIVATE)
        val savedAllowed = prefs.getStringSet("custom_allowed_keywords", emptySet()) ?: emptySet()
        val savedFlagged = prefs.getStringSet("custom_flagged_keywords", emptySet()) ?: emptySet()
        settings = settings.copy(
            customAllowedKeywords = savedAllowed,
            customFlaggedKeywords = savedFlagged
        )

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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
            isContentExplicitlyAllowed = false
            mainHandler.post {
                overlay.hide()
                evaluateShieldTrigger()
            }
            return
        }

        // When user scrolls, clicks, or window content changes:
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            if (overlay.isShowing()) {
                // User is actively scrolling or swapping content away!
                isVisualProhibited = false
                mainHandler.post {
                    overlay.hide()
                    evaluateShieldTrigger()
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && settings.isVisualEnabled) {
                // Instantly trigger capture on scroll/change without waiting for periodic timer!
                if (!isScreenshotPending.get()) {
                    captureAndAnalyzeScreenshot()
                }
            }
        }

        // Only inspect video/clip titles in media, social, and browser apps
        if (isMediaOrBrowserPackage(pkg)) {
            try {
                inspectNodeHierarchy(rootInActiveWindow)
            } catch (_: Exception) {}
        }
    }

    private fun inspectNodeHierarchy(root: AccessibilityNodeInfo?) {
        if (root == null) return

        val pkg = root.packageName
        if (isWhitelistedPackage(pkg)) {
            if (isKeywordProhibited || isContentExplicitlyAllowed) {
                isKeywordProhibited = false
                isContentExplicitlyAllowed = false
                mainHandler.post { evaluateShieldTrigger() }
            }
            return
        }

        var foundAllowedTitle = false
        var foundProhibitedTitle = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var inspected = 0
        while (queue.isNotEmpty() && inspected < 250) {
            val node = queue.removeFirst()
            inspected++

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val combined = "$text $contentDesc".trim()

            if (combined.isNotEmpty()) {
                val match = ContentFilter.evaluateText(
                    text = combined,
                    customAllowed = settings.customAllowedKeywords,
                    customFlagged = settings.customFlaggedKeywords
                )
                when (match) {
                    ContentFilter.MatchResult.ALLOWED -> {
                        foundAllowedTitle = true
                    }
                    ContentFilter.MatchResult.PROHIBITED -> {
                        foundProhibitedTitle = true
                    }
                    ContentFilter.MatchResult.NEUTRAL -> {}
                }
            }

            if (foundAllowedTitle) {
                // User Allow List or Quran exemption takes precedence
                break
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        val newAllowed = foundAllowedTitle
        val newProhibited = !foundAllowedTitle && foundProhibitedTitle

        if (newProhibited != isKeywordProhibited || newAllowed != isContentExplicitlyAllowed) {
            isKeywordProhibited = newProhibited
            isContentExplicitlyAllowed = newAllowed
            mainHandler.post { evaluateShieldTrigger() }
        }
    }

    private val dedicatedMusicPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "com.soundcloud.android",
        "deezer.android.app",
        "com.amazon.mp3",
        "com.pandora.android",
        "com.tidal.mobile",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.anghami"
    )

    private fun startContinuousScanner() {
        continuousScannerJob?.cancel()
        continuousScannerJob = serviceScope.launch {
            while (isActive) {
                val currentPkg = rootInActiveWindow?.packageName

                // If user is in Settings, Home Launcher, or whitelisted app, keep shield off
                if (isWhitelistedPackage(currentPkg)) {
                    if (isKeywordProhibited || isVisualProhibited || isContentExplicitlyAllowed || overlay.isShowing()) {
                        isKeywordProhibited = false
                        isVisualProhibited = false
                        isContentExplicitlyAllowed = false
                        mainHandler.post { overlay.hide() }
                    }
                    delay(300.milliseconds)
                    continue
                }

                // 1. Dedicated music streaming app detection (Spotify, YouTube Music, SoundCloud, etc.)
                // Does NOT falsely flag general spoken videos / clean news as music
                val isDedicatedMusicApp = dedicatedMusicPackages.any { currentPkg?.contains(it) == true }
                if (isDedicatedMusicApp && audioManager.isMusicActive) {
                    if (!isMusicDetected) {
                        isMusicDetected = true
                        mainHandler.post { evaluateShieldTrigger() }
                    }
                } else if (isDedicatedMusicApp && !audioManager.isMusicActive) {
                    if (isMusicDetected) {
                        isMusicDetected = false
                        mainHandler.post { evaluateShieldTrigger() }
                    }
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
                } catch (_: Exception) {}

                delay(100.milliseconds) // Fast 100ms cycle
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
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (hardwareBitmap != null) {
                                val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()

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
        } catch (_: Exception) {
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

        // If content is explicitly allowed by User Allow List or Quran/lecture exemption,
        // suppress keyword prohibition and audio music detection immediately!
        if (isContentExplicitlyAllowed) {
            overlay.hide()
            _serviceStatus.value = _serviceStatus.value.copy(
                isShieldActive = false,
                isVisualProhibited = false,
                isMusicDetected = false,
                activeTriggerReason = "Allowed Content Exemption"
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
        try {
            inspectNodeHierarchy(rootInActiveWindow)
        } catch (_: Exception) {}
        evaluateShieldTrigger()
    }

    fun testShieldTemporarily() {
        overlay.show("TEST MODE (Active for 5s - Simulating Fitna Shield)", settings.overlayOpacity)
        _serviceStatus.value = _serviceStatus.value.copy(
            isShieldActive = true,
            activeTriggerReason = "Test Mode Simulation"
        )

        serviceScope.launch(Dispatchers.Main) {
            delay(5.seconds)
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
