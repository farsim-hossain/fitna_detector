package com.example.fitna_detector.model

/**
 * State representing visual content detection on screen.
 */
data class VisualDetectionResult(
    val isProhibited: Boolean = false,
    val prohibitedScore: Float = 0f,
    val dominantCategory: String = "neutral",
    val probabilities: Map<String, Float> = emptyMap(),
    val isIdolDetected: Boolean = false,
    val idolCategory: String = "",
    val idolConfidence: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * State representing audio/music detection.
 */
data class AudioDetectionResult(
    val isMusicDetected: Boolean = false,
    val confidence: Float = 0f,
    val sourceDescription: String = "None",
    val isSpeechLikely: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Overall shield status driving the red overlay.
 */
data class ShieldStatus(
    val isRunning: Boolean = false,
    val isShieldActive: Boolean = false,
    val isVisualProhibited: Boolean = false,
    val isMusicDetected: Boolean = false,
    val isIdolDetected: Boolean = false,
    val activeTriggerReason: String = "",
    val fps: Float = 0f
)

/**
 * User configuration settings.
 */
enum class SensitivityLevel(val displayName: String, val threshold: Float) {
    RELAXED("Relaxed", 0.45f),
    BALANCED("Balanced", 0.32f),
    STRICT("Strict", 0.22f)
}

data class DetectionSettings(
    val isVisualEnabled: Boolean = true,
    val isMusicEnabled: Boolean = true,
    val isIdolEnabled: Boolean = true,
    val sensitivity: SensitivityLevel = SensitivityLevel.BALANCED,
    val allowSpeechLectures: Boolean = false, // Immediate detection for music & videos by default
    val overlayOpacity: Float = 0.93f,
    val customAllowedKeywords: Set<String> = emptySet(),
    val customFlaggedKeywords: Set<String> = emptySet()
)
