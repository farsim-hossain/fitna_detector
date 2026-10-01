package com.example.fitna_detector.detection

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.fitna_detector.model.AudioDetectionResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * High-speed detector for music and music video audio playback.
 *
 * Combines:
 * 1. High-frequency (120ms) polling of AudioManager.isMusicActive for instant reaction.
 * 2. System AudioPlaybackCallback (API 26+) for hardware stream usage attributes.
 * 3. On-device YAMNet Neural Audio Classifier (TensorFlow Lite, 521 audio event categories:
 *    150 music & instrument classes vs Speech / Lectures).
 */
class MusicDetector(
    private val context: Context,
    private val onMusicDetectedChanged: (AudioDetectionResult) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isRunning = false
    private var allowSpeechFilter = false

    // YAMNet Neural Audio Classifier (TFLite)
    private var yamnetInterpreter: Interpreter? = null

    // Fast polling thread for sub-150ms audio state changes
    private var pollingThread: Thread? = null
    private val isPolling = AtomicBoolean(false)

    // AudioRecord acoustic analyzer thread
    private var audioRecordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    // Current detection state
    private var lastResult = AudioDetectionResult()
    private var lastMediaActiveState = false
    private var lastMusicContentTypeState = false

    init {
        loadYamnetModel()
    }

    private fun loadYamnetModel() {
        try {
            val assetFileDescriptor = context.assets.openFd("yamnet.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            yamnetInterpreter = Interpreter(buffer)
            assetFileDescriptor.close()
        } catch (_: Exception) {
            yamnetInterpreter = null
        }
    }

    fun start(allowSpeechLectures: Boolean) {
        if (isRunning) return
        isRunning = true
        this.allowSpeechFilter = allowSpeechLectures

        registerSystemPlaybackCallback()
        startFastPolling()
        startAcousticAnalyzer()

        // Initial check
        checkCurrentAudioState()
    }

    fun updateSettings(allowSpeechLectures: Boolean) {
        if (this.allowSpeechFilter != allowSpeechLectures) {
            this.allowSpeechFilter = allowSpeechLectures
            if (isRunning) {
                if (allowSpeechLectures) {
                    startAcousticAnalyzer()
                } else {
                    stopAcousticAnalyzer()
                }
                checkCurrentAudioState()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        stopFastPolling()
        unregisterSystemPlaybackCallback()
        stopAcousticAnalyzer()
        try {
            yamnetInterpreter?.close()
            yamnetInterpreter = null
        } catch (_: Exception) {}

        lastResult = AudioDetectionResult()
        lastMediaActiveState = false
        lastMusicContentTypeState = false
        onMusicDetectedChanged(lastResult)
    }

    private fun startFastPolling() {
        if (isPolling.get()) return
        isPolling.set(true)
        pollingThread = Thread {
            while (isPolling.get()) {
                try {
                    val isActiveNow = audioManager.isMusicActive
                    val hasMusicContentType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            audioManager.activePlaybackConfigurations.any { config ->
                                config.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
                            }
                        } catch (_: Exception) { false }
                    } else { false }

                    if (isActiveNow != lastMediaActiveState || hasMusicContentType != lastMusicContentTypeState) {
                        lastMediaActiveState = isActiveNow
                        lastMusicContentTypeState = hasMusicContentType
                        mainHandler.post {
                            evaluateAudioState(isActiveNow, hasMusicContentType)
                        }
                    }
                    Thread.sleep(120) // 120ms polling interval for lightning-fast reaction
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply {
            name = "MusicDetector-FastPollingThread"
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun stopFastPolling() {
        isPolling.set(false)
        try {
            pollingThread?.interrupt()
            pollingThread?.join(300)
        } catch (_: Exception) {}
        pollingThread = null
    }

    private fun registerSystemPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    handlePlaybackConfigChanged(configs)
                }
            }
            playbackCallback = callback
            audioManager.registerAudioPlaybackCallback(callback, mainHandler)
        }
    }

    private fun unregisterSystemPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback?.let {
                try {
                    audioManager.unregisterAudioPlaybackCallback(it)
                } catch (_: Exception) {}
            }
            playbackCallback = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handlePlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
        val hasMusicContentType = configs?.any { config ->
            config.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
        } ?: false

        val hasAnyMedia = configs?.any { config ->
            val usage = config.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
        } ?: audioManager.isMusicActive

        evaluateAudioState(hasAnyMedia, hasMusicContentType)
    }

    private fun checkCurrentAudioState() {
        val isMusicActive = audioManager.isMusicActive
        val hasMusicContentType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                audioManager.activePlaybackConfigurations.any { config ->
                    config.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
                }
            } catch (_: Exception) { false }
        } else { false }

        evaluateAudioState(isMusicActive, hasMusicContentType)
    }

    private fun evaluateAudioState(isSystemMediaActive: Boolean, isExplicitMusicStream: Boolean = false) {
        val isDetected: Boolean
        val source: String
        var isSpeech = false

        if (!isSystemMediaActive) {
            isDetected = false
            source = "Quiet / No Media"
        } else if (isRecording.get() && lastResult.isSpeechLikely && allowSpeechFilter) {
            // Speech detected and user enabled Allow Spoken Lectures & Quran
            isSpeech = true
            isDetected = false
            source = "Speech / Lecture (Permitted)"
        } else if (isRecording.get() && lastResult.isMusicDetected) {
            // Acoustic neural classifier detected music audio
            isSpeech = false
            isDetected = true
            source = "Music Track (Acoustic Match)"
        } else {
            // General media playback (could be news, speech, lecture, or video)
            // Relies on title/video metadata in video apps or dedicated audio player rules in the service
            isDetected = false
            source = "Spoken Media / News"
        }

        val result = AudioDetectionResult(
            isMusicDetected = isDetected,
            confidence = if (isDetected) 0.95f else 0.1f,
            sourceDescription = source,
            isSpeechLikely = isSpeech
        )

        if (result.isMusicDetected != lastResult.isMusicDetected || result.sourceDescription != lastResult.sourceDescription) {
            lastResult = result
            mainHandler.post { onMusicDetectedChanged(result) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAcousticAnalyzer() {
        if (isRecording.get()) return

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) return

        isRecording.set(true)
        audioRecordThread = Thread {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    isRecording.set(false)
                    return@Thread
                }

                recorder.startRecording()
                val buffer = ShortArray(bufferSize / 2)

                // YAMNet accepts 15,600 samples of 16 kHz audio (0.975 seconds)
                val yamnetBuffer = FloatArray(15600)
                var yamnetIndex = 0
                val yamnetOutput = Array(1) { FloatArray(521) }

                var speechPauseCount = 0
                var windowCount = 0

                while (isRecording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            yamnetBuffer[yamnetIndex] = buffer[i] / 32768.0f
                            yamnetIndex = (yamnetIndex + 1) % 15600
                        }

                        var energySum = 0.0
                        var zeroCrossings = 0

                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            energySum += abs(sample)
                            if (i > 0 && ((buffer[i] > 0 && buffer[i - 1] <= 0) || (buffer[i] < 0 && buffer[i - 1] >= 0))) {
                                zeroCrossings++
                            }
                        }

                        val avgEnergy = energySum / read
                        val zcr = zeroCrossings.toFloat() / read

                        if (avgEnergy < 120.0) {
                            speechPauseCount++
                        }
                        windowCount++

                        if (windowCount >= 4) {
                            val interp = yamnetInterpreter
                            if (interp != null) {
                                try {
                                    interp.run(yamnetBuffer, yamnetOutput)
                                    val probs = yamnetOutput[0]

                                    var maxMusicScore = 0f
                                    for (c in 132..276) {
                                        if (probs[c] > maxMusicScore) maxMusicScore = probs[c]
                                    }
                                    for (c in intArrayOf(24, 29, 30, 31, 33)) {
                                        if (probs[c] > maxMusicScore) maxMusicScore = probs[c]
                                    }

                                    var maxSpeechScore = 0f
                                    for (c in 0..4) {
                                        if (probs[c] > maxSpeechScore) maxSpeechScore = probs[c]
                                    }

                                    val isMusic = maxMusicScore >= 0.20f && maxMusicScore >= maxSpeechScore
                                    val isSpeech = maxSpeechScore > maxMusicScore && maxSpeechScore > 0.25f

                                    val currentMediaActive = audioManager.isMusicActive
                                    if (currentMediaActive) {
                                        evaluateAudioStateWithSpeech(isSpeech, isMusic, maxMusicScore)
                                    }
                                } catch (_: Exception) {}
                            } else {
                                val pauseRatio = speechPauseCount.toFloat() / windowCount
                                val isSpeech = pauseRatio > 0.25f || (zcr > 0.15f && pauseRatio > 0.15f)
                                val isMusic = !isSpeech && avgEnergy > 250.0 && (zcr < 0.12f || zcr > 0.35f)

                                val currentMediaActive = audioManager.isMusicActive
                                if (currentMediaActive) {
                                    evaluateAudioStateWithSpeech(isSpeech, isMusic, 0.75f)
                                }
                            }

                            speechPauseCount = 0
                            windowCount = 0
                        }
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
                isRecording.set(false)
            }
        }.apply {
            name = "MusicDetector-AcousticThread"
            start()
        }
    }

    private fun evaluateAudioStateWithSpeech(
        isSpeech: Boolean,
        isMusic: Boolean,
        confidence: Float = 0.90f
    ) {
        val source = when {
            isSpeech -> "Speech / Lecture (Permitted)"
            isMusic -> "YAMNet Neural Music Detected"
            else -> "General Audio / Ambient"
        }
        val result = AudioDetectionResult(
            isMusicDetected = isMusic,
            confidence = confidence,
            sourceDescription = source,
            isSpeechLikely = isSpeech
        )

        if (result.isMusicDetected != lastResult.isMusicDetected || result.sourceDescription != lastResult.sourceDescription) {
            lastResult = result
            mainHandler.post { onMusicDetectedChanged(result) }
        }
    }

    private fun stopAcousticAnalyzer() {
        isRecording.set(false)
        try {
            audioRecordThread?.interrupt()
            audioRecordThread?.join(500)
        } catch (_: Exception) {}
        audioRecordThread = null
    }
}
