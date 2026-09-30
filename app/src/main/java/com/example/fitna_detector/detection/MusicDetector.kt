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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * High-speed detector for music and music video audio playback.
 *
 * Combines:
 * 1. High-frequency (120ms) polling of AudioManager.isMusicActive for instant reaction.
 * 2. System AudioPlaybackCallback (API 26+) for hardware stream usage attributes.
 * 3. Optional acoustic feature analysis (distinguishes music beats from pure speech when enabled).
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

    // Fast polling thread for sub-150ms audio state changes
    private var pollingThread: Thread? = null
    private val isPolling = AtomicBoolean(false)

    // AudioRecord acoustic analyzer thread
    private var audioRecordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    // Current detection state
    private var lastResult = AudioDetectionResult()
    private var lastMediaActiveState = false

    fun start(allowSpeechLectures: Boolean) {
        if (isRunning) return
        isRunning = true
        this.allowSpeechFilter = allowSpeechLectures

        registerSystemPlaybackCallback()
        startFastPolling()

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

        stopFastPolling()
        unregisterSystemPlaybackCallback()
        stopAcousticAnalyzer()

        lastResult = AudioDetectionResult()
        lastMediaActiveState = false
        onMusicDetectedChanged(lastResult)
    }

    private fun startFastPolling() {
        if (isPolling.get()) return
        isPolling.set(true)
        pollingThread = Thread {
            while (isPolling.get()) {
                try {
                    val isActiveNow = audioManager.isMusicActive
                    if (isActiveNow != lastMediaActiveState) {
                        lastMediaActiveState = isActiveNow
                        mainHandler.post {
                            evaluateAudioState(isActiveNow)
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
        evaluateAudioState(isMusicActive, false)
    }

    private fun evaluateAudioState(isSystemMediaActive: Boolean, isExplicitMusicStream: Boolean = false) {
        val isDetected: Boolean
        val source: String
        var isSpeech = false

        if (!isSystemMediaActive) {
            isDetected = false
            source = "Quiet / No Media"
        } else if (isExplicitMusicStream) {
            // Explicit hardware stream marked as CONTENT_TYPE_MUSIC (Spotify, Music players)
            isDetected = true
            source = "Music Stream"
        } else if (allowSpeechFilter) {
            // Speech filter mode: Check acoustic analysis
            isSpeech = lastResult.isSpeechLikely
            isDetected = !isSpeech
            source = if (isSpeech) "Speech / Lecture (Permitted)" else "Music Playback"
        } else {
            // General media playback (could be news, speech, or video)
            // Relies on title/video metadata in video apps or acoustic analyzer
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

                var speechPauseCount = 0
                var windowCount = 0

                while (isRecording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
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
                            val pauseRatio = speechPauseCount.toFloat() / windowCount
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
                } catch (_: Exception) {}
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
            sourceDescription = if (isSpeech) "Speech / Lecture (Permitted)" else "Music Beat Detected",
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
