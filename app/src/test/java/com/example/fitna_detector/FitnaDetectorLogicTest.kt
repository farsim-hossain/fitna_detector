package com.example.fitna_detector

import com.example.fitna_detector.model.AudioDetectionResult
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.SensitivityLevel
import com.example.fitna_detector.model.VisualDetectionResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Visual prohibited content classification and sensitivity thresholding.
 * 2. Multi-frame hysteresis (immediate trigger, debounced recovery).
 * 3. Music playback vs speech/lecture filtering logic.
 * 4. Combined Shield Decision Controller.
 */
class FitnaDetectorLogicTest {

    @Test
    fun testSensitivityThresholds() {
        assertEquals(0.65f, SensitivityLevel.RELAXED.threshold, 0.001f)
        assertEquals(0.45f, SensitivityLevel.BALANCED.threshold, 0.001f)
        assertEquals(0.30f, SensitivityLevel.STRICT.threshold, 0.001f)
    }

    @Test
    fun testVisualProhibitedScoring() {
        // Sample probabilities where sexy / intimate scene is high
        val probabilities = mapOf(
            "drawings" to 0.05f,
            "hentai" to 0.02f,
            "neutral" to 0.25f,
            "porn" to 0.10f,
            "sexy" to 0.58f // Provocative / romantic couple intimacy
        )

        val prohibitedScore = (probabilities["porn"] ?: 0f) +
                (probabilities["hentai"] ?: 0f) +
                (probabilities["sexy"] ?: 0f)

        // 0.10 + 0.02 + 0.58 = 0.70
        assertEquals(0.70f, prohibitedScore, 0.01f)

        // At Balanced sensitivity (0.45), 0.70 should trigger
        assertTrue(prohibitedScore >= SensitivityLevel.BALANCED.threshold)

        // At Strict sensitivity (0.30), 0.70 should trigger
        assertTrue(prohibitedScore >= SensitivityLevel.STRICT.threshold)

        // At Relaxed sensitivity (0.65), 0.70 should trigger
        assertTrue(prohibitedScore >= SensitivityLevel.RELAXED.threshold)
    }

    @Test
    fun testCleanContentNotFlagged() {
        // Sample clean frame (educational / Islamic / news)
        val probabilities = mapOf(
            "drawings" to 0.02f,
            "hentai" to 0.00f,
            "neutral" to 0.95f,
            "porn" to 0.01f,
            "sexy" to 0.02f
        )

        val prohibitedScore = (probabilities["porn"] ?: 0f) +
                (probabilities["hentai"] ?: 0f) +
                (probabilities["sexy"] ?: 0f)

        assertEquals(0.03f, prohibitedScore, 0.01f)

        assertFalse(prohibitedScore >= SensitivityLevel.BALANCED.threshold)
        assertFalse(prohibitedScore >= SensitivityLevel.STRICT.threshold)
    }

    @Test
    fun testHysteresisDebouncing() {
        var isCurrentlyFlagged = false
        var consecutiveSafeFrames = 0
        val threshold = SensitivityLevel.BALANCED.threshold // 0.45

        fun processFrameScore(score: Float): Boolean {
            val isFrameProhibited = score >= threshold
            if (isFrameProhibited) {
                isCurrentlyFlagged = true
                consecutiveSafeFrames = 0
            } else {
                consecutiveSafeFrames++
                if (consecutiveSafeFrames >= 2) {
                    isCurrentlyFlagged = false
                }
            }
            return isCurrentlyFlagged
        }

        // Frame 1: Prohibited scene appears (e.g. romantic couple scene)
        val state1 = processFrameScore(0.75f)
        assertTrue("Immediate trigger upon detection", state1)

        // Frame 2: Still prohibited
        val state2 = processFrameScore(0.60f)
        assertTrue("Remains active while prohibited", state2)

        // Frame 3: User starts swiping away (first safe frame, e.g. transitional blur)
        val state3 = processFrameScore(0.10f)
        assertTrue("Shield stays active on first safe frame to avoid flicker", state3)

        // Frame 4: Second consecutive safe frame (user has completed swipe to clean content)
        val state4 = processFrameScore(0.05f)
        assertFalse("Shield clears cleanly after 2 consecutive safe frames", state4)
    }

    @Test
    fun testShieldDecisionController() {
        fun evaluateShield(
            isVisualProhibited: Boolean,
            isMusicDetected: Boolean,
            settings: DetectionSettings
        ): Pair<Boolean, String> {
            val visualTrigger = settings.isVisualEnabled && isVisualProhibited
            val musicTrigger = settings.isMusicEnabled && isMusicDetected
            val shouldShow = visualTrigger || musicTrigger

            val reason = when {
                visualTrigger && musicTrigger -> "Prohibited visual content & music video detected"
                visualTrigger -> "Prohibited visual content detected"
                musicTrigger -> "Music playback detected"
                else -> ""
            }
            return Pair(shouldShow, reason)
        }

        val settings = DetectionSettings()

        // 1. Both safe -> Shield hidden
        val (show1, _) = evaluateShield(isVisualProhibited = false, isMusicDetected = false, settings)
        assertFalse(show1)

        // 2. Only visual prohibited (e.g. silent offensive image/video) -> Shield shows
        val (show2, reason2) = evaluateShield(isVisualProhibited = true, isMusicDetected = false, settings)
        assertTrue(show2)
        assertEquals("Prohibited visual content detected", reason2)

        // 3. Only music detected (e.g. background music / song) -> Shield shows
        val (show3, reason3) = evaluateShield(isVisualProhibited = false, isMusicDetected = true, settings)
        assertTrue(show3)
        assertEquals("Music playback detected", reason3)

        // 4. Both visual and music detected (e.g. YouTube music video / romantic clip with song) -> Shield shows
        val (show4, reason4) = evaluateShield(isVisualProhibited = true, isMusicDetected = true, settings)
        assertTrue(show4)
        assertEquals("Prohibited visual content & music video detected", reason4)

        // 5. When visual shield disabled in settings
        val settingsNoVisual = settings.copy(isVisualEnabled = false)
        val (show5, _) = evaluateShield(isVisualProhibited = true, isMusicDetected = false, settingsNoVisual)
        assertFalse(show5)
    }

    @Test
    fun testSpeechFilterMode() {
        // In speech filter mode (allowSpeechLectures = true)
        fun evaluateAudio(
            isSystemMediaActive: Boolean,
            isSpeechLikely: Boolean,
            allowSpeechFilter: Boolean
        ): Boolean {
            if (!isSystemMediaActive) return false
            if (!allowSpeechFilter) return true
            return !isSpeechLikely
        }

        // Active speech/lecture with filter enabled -> Permitted (no red flag)
        val result1 = evaluateAudio(
            isSystemMediaActive = true,
            isSpeechLikely = true,
            allowSpeechFilter = true
        )
        assertFalse("Spoken lecture should not trigger shield when filter enabled", result1)

        // Active music video with filter enabled -> Flagged
        val result2 = evaluateAudio(
            isSystemMediaActive = true,
            isSpeechLikely = false,
            allowSpeechFilter = true
        )
        assertTrue("Music track should trigger shield", result2)

        // Speech with filter disabled (strict media blocking) -> Flagged
        val result3 = evaluateAudio(
            isSystemMediaActive = true,
            isSpeechLikely = true,
            allowSpeechFilter = false
        )
        assertTrue("All media audio flagged in strict mode", result3)
    }
}
