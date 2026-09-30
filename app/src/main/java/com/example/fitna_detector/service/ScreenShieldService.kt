package com.example.fitna_detector.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.fitna_detector.MainActivity
import com.example.fitna_detector.R
import com.example.fitna_detector.detection.MusicDetector
import com.example.fitna_detector.detection.VisualDetector
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.SensitivityLevel
import com.example.fitna_detector.model.ShieldStatus
import com.example.fitna_detector.overlay.RedShieldOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Foreground service that coordinates:
 * 1. Screen capture via MediaProjection and AI inference via VisualDetector.
 * 2. Background music playback detection via MusicDetector.
 * 3. Screen red overlay presentation via RedShieldOverlay.
 */
class ScreenShieldService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "fitna_shield_service_channel"
        const val MIN_SHIELD_LOCKOUT_MS = 30_000L // Minimum 30 seconds hold

        const val ACTION_START = "com.example.fitna_detector.ACTION_START"
        const val ACTION_STOP = "com.example.fitna_detector.ACTION_STOP"
        const val ACTION_UPDATE_SETTINGS = "com.example.fitna_detector.ACTION_UPDATE_SETTINGS"
        const val ACTION_TEST_SHIELD = "com.example.fitna_detector.ACTION_TEST_SHIELD"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val EXTRA_SENSITIVITY = "extra_sensitivity"
        const val EXTRA_ALLOW_SPEECH = "extra_allow_speech"
        const val EXTRA_OPACITY = "extra_opacity"
        const val EXTRA_VISUAL_ENABLED = "extra_visual_enabled"
        const val EXTRA_MUSIC_ENABLED = "extra_music_enabled"

        private val _shieldStatus = MutableStateFlow(ShieldStatus())
        val shieldStatus: StateFlow<ShieldStatus> = _shieldStatus.asStateFlow()
    }

    private lateinit var overlay: RedShieldOverlay
    private lateinit var visualDetector: VisualDetector
    private lateinit var musicDetector: MusicDetector

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var countdownJob: Job? = null

    private var settings = DetectionSettings()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isVisualProhibited = false
    private var isMusicDetected = false
    private var isTestingShield = false
    private var lockoutUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        overlay = RedShieldOverlay(this)
        visualDetector = VisualDetector(this)
        musicDetector = MusicDetector(this) { audioResult ->
            isMusicDetected = audioResult.isMusicDetected
            evaluateShieldTrigger()
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                extractSettings(intent)
                startForegroundWithNotification()

                if (resultCode != 0 && resultData != null) {
                    initMediaProjection(resultCode, resultData)
                }
                musicDetector.start(settings.allowSpeechLectures)

                _shieldStatus.value = _shieldStatus.value.copy(isRunning = true)
            }
            ACTION_STOP -> {
                stopProtection()
                stopSelf()
            }
            ACTION_UPDATE_SETTINGS -> {
                extractSettings(intent)
                musicDetector.updateSettings(settings.allowSpeechLectures)
                evaluateShieldTrigger()
            }
            ACTION_TEST_SHIELD -> {
                testShieldTemporarily()
            }
        }

        return START_NOT_STICKY
    }

    private fun extractSettings(intent: Intent) {
        val sensName = intent.getStringExtra(EXTRA_SENSITIVITY) ?: settings.sensitivity.name
        val sensitivity = try {
            SensitivityLevel.valueOf(sensName)
        } catch (e: Exception) {
            SensitivityLevel.BALANCED
        }

        settings = settings.copy(
            sensitivity = sensitivity,
            allowSpeechLectures = intent.getBooleanExtra(EXTRA_ALLOW_SPEECH, settings.allowSpeechLectures),
            overlayOpacity = intent.getFloatExtra(EXTRA_OPACITY, settings.overlayOpacity),
            isVisualEnabled = intent.getBooleanExtra(EXTRA_VISUAL_ENABLED, settings.isVisualEnabled),
            isMusicEnabled = intent.getBooleanExtra(EXTRA_MUSIC_ENABLED, settings.isMusicEnabled)
        )
    }

    private fun startForegroundWithNotification() {
        val notification = createServiceNotification("Monitoring active: Screen & Audio protected")
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createServiceNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Fitna Shield Active")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopScreenCapture()
            }
        }, mainHandler)

        startScreenCapture(projection)
    }

    @SuppressLint("WrongConstant")
    private fun startScreenCapture(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        // Downsample capture resolution to 360x640 for high performance and low battery consumption
        val width = 360
        val height = 640
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "FitnaShieldDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        captureJob?.cancel()
        captureJob = lifecycleScope.launch(Dispatchers.Default) {
            var lastFrameTime = System.currentTimeMillis()

            // Continuous frame sampling loop (~3.3 FPS = sample every ~300ms)
            while (isActive) {
                val reader = imageReader ?: break
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }

                if (image != null && settings.isVisualEnabled) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop if row padding was added by ImageReader
                        val cleanBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                                bitmap.recycle()
                            }
                        } else {
                            bitmap
                        }

                        val result = visualDetector.analyzeFrame(cleanBitmap, settings.sensitivity)
                        cleanBitmap.recycle()

                        isVisualProhibited = result.isProhibited
                        evaluateShieldTrigger()

                        val now = System.currentTimeMillis()
                        val delta = (now - lastFrameTime).coerceAtLeast(1)
                        val fps = 1000f / delta
                        lastFrameTime = now

                        _shieldStatus.value = _shieldStatus.value.copy(
                            isVisualProhibited = isVisualProhibited,
                            fps = fps
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        image.close()
                    }
                } else {
                    image?.close()
                }

                delay(300) // Sample interval: ~3.3 FPS
            }
        }
    }

    private fun evaluateShieldTrigger() {
        if (isTestingShield) return

        val visualTrigger = settings.isVisualEnabled && isVisualProhibited
        val musicTrigger = settings.isMusicEnabled && isMusicDetected
        val isCurrentlyProhibited = visualTrigger || musicTrigger

        val reason = when {
            visualTrigger && musicTrigger -> "Prohibited visual content & music video detected"
            visualTrigger -> "Prohibited visual content detected"
            musicTrigger -> "Music playback detected"
            else -> ""
        }

        if (isCurrentlyProhibited) {
            overlay.show(reason, settings.overlayOpacity)
        } else {
            // Dismisses immediately once user stops or swaps away from the fitna content
            overlay.hide()
        }

        _shieldStatus.value = _shieldStatus.value.copy(
            isShieldActive = isCurrentlyProhibited,
            isVisualProhibited = visualTrigger,
            isMusicDetected = musicTrigger,
            activeTriggerReason = reason
        )
    }

    private fun testShieldTemporarily() {
        isTestingShield = true
        overlay.show("TEST MODE (Active for 5s - Simulating Fitna Shield)", settings.overlayOpacity)
        _shieldStatus.value = _shieldStatus.value.copy(
            isShieldActive = true,
            activeTriggerReason = "Test Mode Simulation"
        )

        lifecycleScope.launch(Dispatchers.Main) {
            delay(5000)
            isTestingShield = false
            evaluateShieldTrigger()
        }
    }

    private fun stopScreenCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (ignored: Exception) {}
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (ignored: Exception) {}
        mediaProjection = null
    }

    private fun stopProtection() {
        countdownJob?.cancel()
        countdownJob = null
        lockoutUntilMs = 0L
        stopScreenCapture()
        musicDetector.stop()
        overlay.hide()
        overlay.destroy()
        visualDetector.close()

        _shieldStatus.value = ShieldStatus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fitna Shield Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for active Fitna Shield"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopProtection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
