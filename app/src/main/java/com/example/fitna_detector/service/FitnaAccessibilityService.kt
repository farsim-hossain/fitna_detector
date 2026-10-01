package com.example.fitna_detector.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
    private var isVisualKeywordProhibited = false
    private var isMusicKeywordProhibited = false
    private var isIdolVisualProhibited = false
    private var isIdolKeywordProhibited = false
    private var isContentExplicitlyAllowed = false
    private val isScreenshotPending = AtomicBoolean(false)
    private var continuousScannerJob: Job? = null

    /**
     * Checks if the active package is a Quran, Hadith, or Islamic prayer app.
     * These apps are 100% exempted from any shield block.
     */
    fun isWhitelistedQuranOrPrayerApp(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        return pkg.contains("quran") ||
                pkg.contains("tarteel") ||
                pkg.contains("islam360") ||
                pkg.contains("athan") ||
                pkg.contains("adhan") ||
                pkg.contains("salat") ||
                pkg.contains("namaz") ||
                pkg.contains("muslimpro") ||
                pkg.contains("ayah") ||
                pkg.contains("hadith")
    }

    /**
     * Checks if package is system UI, settings, dialer, launcher.
     */
    fun isLauncherOrSystemUI(packageName: CharSequence?): Boolean {
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

    fun isWhitelistedPackage(packageName: CharSequence?): Boolean {
        return isWhitelistedQuranOrPrayerApp(packageName) || isLauncherOrSystemUI(packageName)
    }

    /**
     * Dynamic detection of ANY audio player or music app installed on device:
     * - Checks OS ApplicationInfo.CATEGORY_AUDIO (API 26+)
     * - Matches local MP3 players, built-in AOSP Music, OEM players, and streaming services.
     */
    fun isAudioPlayerPackage(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        if (isWhitelistedQuranOrPrayerApp(pkg)) return false
        if (pkg.contains("video") || pkg.contains("camera")) return false

        // 1. Check Android OS App Category (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                if (appInfo.category == ApplicationInfo.CATEGORY_AUDIO) {
                    return true
                }
            } catch (_: Exception) {}
        }

        // 2. Audio/Music app package patterns
        return pkg.contains("music") ||
                pkg.contains("audio") ||
                pkg.contains("mp3") ||
                pkg.contains("audioplayer") ||
                pkg.contains("musicplayer") ||
                pkg.contains("sound") ||
                pkg.contains("spotify") ||
                pkg.contains("deezer") ||
                pkg.contains("tidal") ||
                pkg.contains("soundcloud") ||
                pkg.contains("jiobeats") ||
                pkg.contains("gaana") ||
                pkg.contains("anghami") ||
                pkg.contains("poweramp") ||
                pkg.contains("aimp") ||
                pkg.contains("winamp") ||
                pkg.contains("shazam")
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

    /**
     * Checks if audible media audio is actually playing through the device speakers.
     * Returns false if volume is 0 or if all audio tracks are muted (e.g. YouTube feed preview).
     */
    fun isAudibleAudioActive(): Boolean {
        if (!audioManager.isMusicActive) return false
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volume == 0) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val configs = audioManager.activePlaybackConfigurations
                if (configs.isNotEmpty()) {
                    val hasAudiblePlayer = configs.any { config ->
                        val desc = config.toString()
                        !desc.contains("mutedState:clientVolume") && !desc.contains("mutedState:volume")
                    }
                    if (!hasAudiblePlayer) return false
                }
            } catch (_: Exception) {}
        }
        return true
    }

    /**
     * Determines whether an inspected node is merely a search result / feed recommendation thumbnail card.
     * Prevents false triggers while browsing video feeds without watching.
     */
    fun isFeedThumbnailCard(node: AccessibilityNodeInfo, text: String): Boolean {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(" - play video") || desc.endsWith("play video")) {
            return true
        }
        if (desc.contains("views -") || desc.contains("views •")) {
            return true
        }
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val prefs = getSharedPreferences("fitna_detector_prefs", Context.MODE_PRIVATE)
        val savedAllowed = prefs.getStringSet("custom_allowed_keywords", emptySet()) ?: emptySet()
        val savedFlagged = prefs.getStringSet("custom_flagged_keywords", emptySet()) ?: emptySet()
        val savedIdol = prefs.getBoolean("is_idol_enabled", true)
        settings = settings.copy(
            customAllowedKeywords = savedAllowed,
            customFlaggedKeywords = savedFlagged,
            isIdolEnabled = savedIdol
        )

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        overlay = RedShieldOverlay(this, isAccessibilityMode = true)
        visualDetector = VisualDetector(this)
        musicDetector = MusicDetector(this) { audioResult ->
            val currentPkg = rootInActiveWindow?.packageName
            // Only clear music if user is inside a Quran/Islamic study app!
            // If user is on the home screen or another app while music is playing, keep isMusicDetected!
            if (isWhitelistedQuranOrPrayerApp(currentPkg)) {
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
        val isBackgroundMusicPlaying = audioManager.isMusicActive && (isMusicDetected || isMusicKeywordProhibited)

        // Pure Quran/Islamic apps always clear shield
        if (isWhitelistedQuranOrPrayerApp(pkg)) {
            isVisualKeywordProhibited = false
            isMusicKeywordProhibited = false
            isIdolKeywordProhibited = false
            isVisualProhibited = false
            isIdolVisualProhibited = false
            isMusicDetected = false
            isContentExplicitlyAllowed = false
            mainHandler.post {
                overlay.hide()
                evaluateShieldTrigger()
            }
            return
        }

        // Home screen launcher or Settings clears shield ONLY if no music is actively playing in the background
        if (isLauncherOrSystemUI(pkg) && !isBackgroundMusicPlaying) {
            isVisualKeywordProhibited = false
            isMusicKeywordProhibited = false
            isIdolKeywordProhibited = false
            isVisualProhibited = false
            isIdolVisualProhibited = false
            isContentExplicitlyAllowed = false
            mainHandler.post {
                overlay.hide()
                evaluateShieldTrigger()
            }
            return
        }

        // Fast reaction when opening an audio player app while audio is active
        if (isAudioPlayerPackage(pkg) && audioManager.isMusicActive) {
            if (!isMusicDetected) {
                isMusicDetected = true
                mainHandler.post { evaluateShieldTrigger() }
            }
        }

        // When user actively scrolls:
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (overlay.isShowing()) {
                // User is actively scrolling content away!
                isVisualProhibited = false
                isIdolVisualProhibited = false
                visualDetector.resetState()
                mainHandler.post {
                    overlay.hide()
                    evaluateShieldTrigger()
                }
            }
        }

        // When content changes, scrolls, or clicked, trigger screenshot analysis
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (settings.isVisualEnabled || settings.isIdolEnabled)) {
                if (!isScreenshotPending.get()) {
                    captureAndAnalyzeScreenshot()
                }
            }
        }

        // Inspect all candidate windows (including Picture-in-Picture)
        try {
            inspectAllActiveRoots()
        } catch (_: Exception) {}
    }

    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            candidates.add(activeRoot)
        }

        try {
            val allWindows = windows
            if (!allWindows.isNullOrEmpty()) {
                for (w in allWindows) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString()?.lowercase() ?: ""
                    val isPiP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && w.isInPictureInPictureMode
                    val isMedia = isMediaOrBrowserPackage(pkg)
                    if ((isPiP || isMedia) && candidates.none { it.packageName == root.packageName }) {
                        candidates.add(root)
                    }
                }
            }
        } catch (_: Exception) {}

        return candidates
    }

    private fun inspectAllActiveRoots() {
        val roots = getCandidateRoots()
        if (roots.isEmpty()) return

        val hasMediaCandidate = roots.any { isMediaOrBrowserPackage(it.packageName) }
        val isOnlyWhitelisted = roots.all { isWhitelistedPackage(it.packageName) }

        if (isOnlyWhitelisted && !hasMediaCandidate) {
            if (isVisualKeywordProhibited || isMusicKeywordProhibited || isIdolKeywordProhibited || isContentExplicitlyAllowed) {
                isVisualKeywordProhibited = false
                isMusicKeywordProhibited = false
                isIdolKeywordProhibited = false
                isContentExplicitlyAllowed = false
                mainHandler.post { evaluateShieldTrigger() }
            }
            return
        }

        var foundAllowedTitle = false
        var foundProhibitedMusic = false
        var foundProhibitedVisual = false
        var foundProhibitedIdol = false

        for (root in roots) {
            if (root.packageName == packageName || root.packageName == "com.example.fitna_detector") continue
            if (isWhitelistedPackage(root.packageName) && hasMediaCandidate) continue

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
                        ContentFilter.MatchResult.PROHIBITED_MUSIC -> {
                            // Music video keywords ONLY flag if audible audio is active AND not a feed thumbnail card!
                            if (isAudibleAudioActive() && !isFeedThumbnailCard(node, combined)) {
                                foundProhibitedMusic = true
                            }
                        }
                        ContentFilter.MatchResult.PROHIBITED_VISUAL -> {
                            foundProhibitedVisual = true
                        }
                        ContentFilter.MatchResult.PROHIBITED_IDOL -> {
                            foundProhibitedIdol = true
                        }
                        ContentFilter.MatchResult.PROHIBITED -> {
                            // Custom user-flagged keyword
                            if (isAudibleAudioActive() && !isFeedThumbnailCard(node, combined)) {
                                foundProhibitedMusic = true
                            } else if (!isFeedThumbnailCard(node, combined)) {
                                foundProhibitedVisual = true
                            }
                        }
                        ContentFilter.MatchResult.NEUTRAL -> {}
                    }
                }

                if (foundAllowedTitle) break

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) queue.add(child)
                }
            }

            if (foundAllowedTitle) break
        }

        val newAllowed = foundAllowedTitle
        val newMusicProhibited = !foundAllowedTitle && foundProhibitedMusic
        val newVisualProhibited = !foundAllowedTitle && foundProhibitedVisual
        val newIdolProhibited = !foundAllowedTitle && foundProhibitedIdol

        if (newAllowed != isContentExplicitlyAllowed ||
            newMusicProhibited != isMusicKeywordProhibited ||
            newVisualProhibited != isVisualKeywordProhibited ||
            newIdolProhibited != isIdolKeywordProhibited) {

            isContentExplicitlyAllowed = newAllowed
            isMusicKeywordProhibited = newMusicProhibited
            isVisualKeywordProhibited = newVisualProhibited
            isIdolKeywordProhibited = newIdolProhibited
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
                val isAudible = isAudibleAudioActive()
                val isBackgroundMusicPlaying = isAudible && (isMusicDetected || isMusicKeywordProhibited)

                // 1. Pure Quran / Islamic study apps always clear shield
                if (isWhitelistedQuranOrPrayerApp(currentPkg)) {
                    if (isVisualKeywordProhibited || isMusicKeywordProhibited || isIdolKeywordProhibited || isVisualProhibited || isIdolVisualProhibited || isMusicDetected || isContentExplicitlyAllowed || overlay.isShowing()) {
                        isVisualKeywordProhibited = false
                        isMusicKeywordProhibited = false
                        isIdolKeywordProhibited = false
                        isVisualProhibited = false
                        isIdolVisualProhibited = false
                        isMusicDetected = false
                        isContentExplicitlyAllowed = false
                        visualDetector.resetState()
                        mainHandler.post { overlay.hide() }
                    }
                    delay(300.milliseconds)
                    continue
                }

                // 2. Home Launcher or Settings clears shield ONLY if no music is actively playing in the background
                if (isLauncherOrSystemUI(currentPkg) && !isBackgroundMusicPlaying) {
                    if (isVisualKeywordProhibited || isMusicKeywordProhibited || isIdolKeywordProhibited || isVisualProhibited || isIdolVisualProhibited || isMusicDetected || isContentExplicitlyAllowed || overlay.isShowing()) {
                        isVisualKeywordProhibited = false
                        isMusicKeywordProhibited = false
                        isIdolKeywordProhibited = false
                        isVisualProhibited = false
                        isIdolVisualProhibited = false
                        isMusicDetected = false
                        isContentExplicitlyAllowed = false
                        visualDetector.resetState()
                        mainHandler.post { overlay.hide() }
                    }
                    delay(300.milliseconds)
                    continue
                }

                // 3. If audio stopped playing or muted, clear music triggers immediately
                if (!isAudible) {
                    if (isMusicKeywordProhibited || isMusicDetected) {
                        isMusicKeywordProhibited = false
                        isMusicDetected = false
                        mainHandler.post { evaluateShieldTrigger() }
                    }
                }

                // 4. Audio / Music app detection (AOSP Music, Samsung Music, Spotify, YouTube Music, local MP3 players, etc.)
                val isAudioApp = isAudioPlayerPackage(currentPkg)
                if (isAudioApp && isAudible) {
                    if (!isMusicDetected) {
                        isMusicDetected = true
                        mainHandler.post { evaluateShieldTrigger() }
                    }
                } else if (isAudioApp && !isAudible) {
                    if (isMusicDetected) {
                        isMusicDetected = false
                        mainHandler.post { evaluateShieldTrigger() }
                    }
                }

                // 5. High-speed visual screenshot analysis (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (settings.isVisualEnabled || settings.isIdolEnabled)) {
                    if (!isScreenshotPending.get()) {
                        captureAndAnalyzeScreenshot()
                    }
                }

                // 6. Periodic node check across active windows (including PiP)
                try {
                    inspectAllActiveRoots()
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

                                    val wasVisualProhibited = isVisualProhibited
                                    val wasIdolProhibited = isIdolVisualProhibited

                                    isVisualProhibited = result.isProhibited
                                    isIdolVisualProhibited = result.isIdolDetected

                                    if (wasVisualProhibited != isVisualProhibited || wasIdolProhibited != isIdolVisualProhibited) {
                                        mainHandler.post { evaluateShieldTrigger() }
                                    }

                                    _serviceStatus.value = _serviceStatus.value.copy(
                                        isVisualProhibited = isVisualProhibited,
                                        isIdolDetected = isIdolVisualProhibited || isIdolKeywordProhibited
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
        if (isWhitelistedQuranOrPrayerApp(currentPkg)) {
            overlay.hide()
            _serviceStatus.value = _serviceStatus.value.copy(
                isShieldActive = false,
                isVisualProhibited = false,
                isMusicDetected = false,
                isIdolDetected = false,
                activeTriggerReason = ""
            )
            return
        }

        val isAudible = isAudibleAudioActive()
        val isBackgroundMusicPlaying = isAudible && !isContentExplicitlyAllowed && (isMusicDetected || isMusicKeywordProhibited)
        if (isLauncherOrSystemUI(currentPkg) && !isBackgroundMusicPlaying) {
            overlay.hide()
            _serviceStatus.value = _serviceStatus.value.copy(
                isShieldActive = false,
                isVisualProhibited = false,
                isMusicDetected = false,
                isIdolDetected = false,
                activeTriggerReason = ""
            )
            return
        }

        // Text keyword and audio music triggers are safely exempted if content is Quran/lecture or whitelisted
        // BUT visual frame classification (actual idols, sculptures, pornography) is ground-truth visual analysis!
        val idolTrigger = settings.isIdolEnabled && (isIdolVisualProhibited || (!isContentExplicitlyAllowed && isIdolKeywordProhibited))
        val visualTrigger = settings.isVisualEnabled && (isVisualProhibited || (!isContentExplicitlyAllowed && isVisualKeywordProhibited))
        val musicTrigger = settings.isMusicEnabled && !isContentExplicitlyAllowed && isAudible && (isMusicDetected || isMusicKeywordProhibited)

        val isCurrentlyProhibited = idolTrigger || visualTrigger || musicTrigger

        val reason = when {
            idolTrigger && musicTrigger -> "Idol / Religious sculpture & music detected"
            idolTrigger -> "Idol / Religious sculpture detected"
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
            isIdolDetected = idolTrigger,
            activeTriggerReason = reason
        )
    }

    fun updateSettings(newSettings: DetectionSettings) {
        settings = newSettings
        musicDetector.updateSettings(newSettings.allowSpeechLectures)
        try {
            inspectAllActiveRoots()
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
