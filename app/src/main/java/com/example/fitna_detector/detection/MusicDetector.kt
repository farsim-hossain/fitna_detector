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
import androidx.core.content.ContextCompat
import com.example.fitna_detector.model.AudioDetectionResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Real-time detector for music and music video audio playback.
 *
 * Combines:
 * 1. Android OS System Playback Callback (instantaneous, zero battery drain)
 * 2. Real-time Acoustic Feature Analyzer (distinguishes music/beats from spoken lectures & recitation)
 */
class MusicDetector(
    private val context: Context,
    private val onMusicDetectedChanged: (AudioDetectionResult) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isRunning = false
    private var allowSpeechFilter = true

    // AudioRecord acoustic analyzer thread
    private var audioRecordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    // Current detection state
    private var lastResult = AudioDetectionResult()

    fun start(allowSpeechLectures: Boolean) {
        if (isRunning) return
        isRunning = true
        this.allowSpeechFilter = allowSpeechLectures

        registerSystemPlaybackCallback()
        if (allowSpeechLectures) {
            startAcousticAnalyzer()
        }

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

        unregisterSystemPlaybackCallback()
        stopAcousticAnalyzer()

        lastResult = AudioDetectionResult()
        onMusicDetectedChanged(lastResult)
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
                } catch (ignored: Exception) {}
            }
            playbackCallback = null
        }
    }

    private fun handlePlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
        val hasActiveMedia = configs?.any { config ->
            val usage = config.audioAttributes.usage
            val contentType = config.audioAttributes.contentType
            usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    contentType == AudioAttributes.CONTENT_TYPE_MUSIC
        } ?: audioManager.isMusicActive

        evaluateAudioState(hasActiveMedia)
    }

    private fun checkCurrentAudioState() {
        val isMusicActive = audioManager.isMusicActive
        evaluateAudioState(isMusicActive)
    }

    private fun evaluateAudioState(isSystemMediaActive: Boolean) {
        val isDetected: Boolean
        val source: String
        var isSpeech = false

        if (!isSystemMediaActive) {
            isDetected = false
            source = "Quiet / No Media"
        } else if (!allowSpeechFilter) {
            // Strict mode: Any active media audio is flagged immediately
            isDetected = true
            source = "Active Media Playback"
        } else {
            // Speech filter mode: Check if acoustic analysis marked it as speech
            isSpeech = lastResult.isSpeechLikely
            isDetected = !isSpeech
            source = if (isSpeech) "Speech / Lecture (Permitted)" else "Music Playback"
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

    /**
     * Optional acoustic analyzer to detect pauses and spectral rhythm
     * to distinguish speech/lectures from musical instruments/beats.
     */
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

                var speechPauseCount = 0
                var windowCount = 0

                while (isRecording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Calculate energy and zero-crossing rate
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

                        // Speech characteristics: Frequent intermittent pauses in energy
                        if (avgEnergy < 120.0) {
                            speechPauseCount++
                        }
                        windowCount++

                        // Every 1 second (~4 windows of 4096 samples at 16kHz)
                        if (windowCount >= 4) {
                            val pauseRatio = speechPauseCount.toFloat() / windowCount
                            // Human speech typically contains > 25% micro-pauses between syllables/words
                            // Music maintains continuous steady rhythmic energy
                            val isSpeech = pauseRatio > 0.25f || (zcr > 0.15f && pauseRatio > 0.15f)

                            val currentMediaActive = audioManager.isMusicActive
                            if (currentMediaActive) {
                                evaluateAudioStateWithSpeech(isSpeech)
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
                } catch (ignored: Exception) {}
                isRecording.set(false)
            }
        }.apply {
            name = "MusicDetector-AcousticThread"
            start()
        }
    }

    private fun evaluateAudioStateWithSpeech(isSpeech: Boolean) {
        val isMusic = !isSpeech
        val result = AudioDetectionResult(
            isMusicDetected = isMusic,
            confidence = if (isMusic) 0.90f else 0.2f,
            sourceDescription = if (isSpeech) "Speech / Lecture (Permitted)" else "Music / Musical Beat Detected",
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
        } catch (ignored: Exception) {}
        audioRecordThread = null
    }
}
