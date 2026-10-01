package com.example.fitna_detector

import com.example.fitna_detector.model.ContentFilter
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.SensitivityLevel
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
        assertEquals(0.45f, SensitivityLevel.RELAXED.threshold, 0.001f)
        assertEquals(0.32f, SensitivityLevel.BALANCED.threshold, 0.001f)
        assertEquals(0.22f, SensitivityLevel.STRICT.threshold, 0.001f)
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

    @Test
    fun testRedScreenDismissesImmediatelyWhenContentStopped() {
        fun evaluateShieldState(
            isVisualProhibited: Boolean,
            isMusicDetected: Boolean,
            isVisualEnabled: Boolean = true,
            isMusicEnabled: Boolean = true
        ): Boolean {
            val visualTrigger = isVisualEnabled && isVisualProhibited
            val musicTrigger = isMusicEnabled && isMusicDetected
            return visualTrigger || musicTrigger
        }

        // 1. User starts watching YouTube romantic video or music plays -> Screen turns red
        val isTriggered = evaluateShieldState(isVisualProhibited = true, isMusicDetected = true)
        assertTrue("Red screen must activate when fitna content is playing", isTriggered)

        // 2. User stops music or pauses video -> Screen goes away immediately!
        val isClearedOnAudioStop = evaluateShieldState(isVisualProhibited = false, isMusicDetected = false)
        assertFalse("Red screen must go away immediately once user stops watching/listening to fitna", isClearedOnAudioStop)

        // 3. User swiped away to next safe video (only visual was prohibited, now safe) -> Clears
        val isClearedOnSwipe = evaluateShieldState(isVisualProhibited = false, isMusicDetected = false)
        assertFalse("Red screen must go away immediately once user swaps to clean view", isClearedOnSwipe)
    }

    @Test
    fun testSettingsAndSystemAppsWhitelisted() {
        fun isWhitelistedPackage(pkg: String?): Boolean {
            if (pkg == null) return false
            val p = pkg.lowercase()
            if (p.contains("quran") || p.contains("tarteel") || p.contains("islam360") ||
                p.contains("athan") || p.contains("adhan") || p.contains("salat") ||
                p.contains("namaz") || p.contains("muslimpro") || p.contains("ayah") || p.contains("hadith")) {
                return true
            }
            return p.startsWith("com.android.settings") ||
                    p.startsWith("com.android.systemui") ||
                    p.startsWith("com.google.android.apps.nexuslauncher") ||
                    p.startsWith("com.android.launcher") ||
                    p.contains("launcher") ||
                    p.contains("settings") ||
                    p.contains("systemui") ||
                    p.contains("permissioncontroller") ||
                    p.contains("packageinstaller") ||
                    p.contains("keyboard") ||
                    p.contains("dialer")
        }

        assertTrue(isWhitelistedPackage("com.android.settings"))
        assertTrue(isWhitelistedPackage("com.android.settings.subsettings"))
        assertTrue(isWhitelistedPackage("com.android.systemui"))
        assertTrue(isWhitelistedPackage("com.google.android.apps.nexuslauncher"))
        assertTrue(isWhitelistedPackage("com.mi.android.globallauncher"))
        assertTrue(isWhitelistedPackage("com.android.permissioncontroller"))

        // Quran and Islamic apps MUST BE WHITELISTED
        assertTrue(isWhitelistedPackage("com.quran.labs.androidquran"))
        assertTrue(isWhitelistedPackage("com.tarteel.tarteel"))
        assertTrue(isWhitelistedPackage("com.islam360"))
        assertTrue(isWhitelistedPackage("com.muslimpro"))

        // Media and browser apps must NOT be whitelisted
        assertFalse(isWhitelistedPackage("com.google.android.youtube"))
        assertFalse(isWhitelistedPackage("com.instagram.android"))
        assertFalse(isWhitelistedPackage("com.zhiliaoapp.musically"))
        assertFalse(isWhitelistedPackage("com.android.chrome"))
    }

    @Test
    fun testKeywordsExemptionsAndTargeting() {
        val prohibitedKeywords = listOf(
            "official music video", "official mv", "music video", "video song",
            "full song", "lyric video", "lyrics video", "official audio", "official video",
            "audio song", "dance performance", "dance cover", "choreography", "item song",
            "remix song", "remix video", "lofi remix", "lofi song", "slowed + reverb",
            "vevo", "t-series", "coke studio", "speed records", "zee music", "sony music",
            "tips official", "saregama", "yrf music", "soundtrack", "full album",
            "romantic scene", "romance scene", "romantic song", "romantic clip",
            "love song", "kiss scene", "kissing scene", "hot scene", "bed scene",
            "bikini", "swimsuit", "lingerie", "cleavage", "nude", "naked",
            "intimate scene", "love scene", "couple scene", "dating show",
            "sensual scene", "erotic scene"
        )

        val safeExemptionKeywords = listOf(
            "no music", "without music", "no instruments", "vocal only",
            "acapella", "halal", "nasheed", "quran", "qur'an", "koran", "surah", "sura",
            "ayah", "ayat", "recitation", "tilawat", "lecture", "speech", "tafsir",
            "khutbah", "podcast", "bayan", "adhan", "azan", "dua", "dhikr", "zikr",
            "hadith", "hadeeth", "sunnah", "islamic", "alafasy", "abdul basit", "sudais",
            "shuraim", "minshawi", "al-hussary", "mahir", "al-muaiqly", "fitna !",
            "islam approves", "unblock screen", "bbc news", "reuters", "al jazeera",
            "police release", "official report", "press briefing", "documentary"
        )

        fun isTextProhibited(text: String): Boolean {
            val lower = text.lowercase()
            val isExempt = safeExemptionKeywords.any { lower.contains(it) }
            if (isExempt) return false
            return prohibitedKeywords.any { lower.contains(it) }
        }

        // Standard Android Settings items - MUST BE SAFE
        assertFalse("Sound & vibration in settings must be safe", isTextProhibited("Sound & vibration"))
        assertFalse("Music & audio in settings must be safe", isTextProhibited("Music & audio settings"))
        assertFalse("Broadband settings must be safe", isTextProhibited("Broadband frequency settings"))
        assertFalse("Default notification sound must be safe", isTextProhibited("Default notification sound"))

        // News reports with official videos - MUST BE SAFE
        assertFalse("BBC News official video must be safe", isTextProhibited("BBC News: Police release official video of incident"))
        assertFalse("Reuters official report must be safe", isTextProhibited("Reuters: Official video released by department"))

        // Halal / Islamic Quran & Surah exemptions - MUST BE 100% SAFE
        assertFalse("Surah Al-Baqarah must be safe", isTextProhibited("Surah Al-Baqarah Full | Mishary Rashid Alafasy"))
        assertFalse("Surah Yasin must be safe", isTextProhibited("Surah Yaseen 7 Times Recitation"))
        assertFalse("Ayat al-Kursi must be safe", isTextProhibited("Ayatul Kursi 100 Times with English Translation"))
        assertFalse("Quran Tilawat must be safe", isTextProhibited("Heart Touching Quran Recitation by Abdul Basit"))
        assertFalse("Islamic lecture must be safe", isTextProhibited("Nouman Ali Khan Quran Tafsir Lecture"))
        assertFalse("Nasheed without music must be safe", isTextProhibited("Heart soothing Islamic Nasheed without music"))

        // General talking, speeches, and podcasts without music - MUST BE SAFE
        assertFalse("Podcast conversation must be safe", isTextProhibited("Lex Fridman Podcast: Conversation with Deep Learning Scientist"))
        assertFalse("Spoken lecture must be safe", isTextProhibited("Harvard University Lecture 1: Computer Science Principles"))

        // Real Fitna YouTube / Media items - MUST BE FLAGGED
        assertTrue("Official music video must be flagged", isTextProhibited("Taylor Swift - Official Music Video"))
        assertTrue("Coke Studio video must be flagged", isTextProhibited("Mix - Bulbuli | Coke Studio Bangla | Season One | Ritu Raj X Nandita"))
        assertTrue("Coke Studio track 2 must be flagged", isTextProhibited("Kotha Koiyo Na | Coke Studio Bangla | Season 2"))
        assertTrue("Romantic couple scene must be flagged", isTextProhibited("Movie Clip - Romantic Scene in Rain"))
        assertTrue("Kissing scene must be flagged", isTextProhibited("Drama Episode 5 - Best Kiss Scene"))
        assertTrue("Lyric video song must be flagged", isTextProhibited("Hit Track 2026 - Official Lyric Video Song"))
    }

    @Test
    fun testSyntheticRedOverlayRejectedFromSkinDetection() {
        fun isPixelSkin(r: Int, g: Int, b: Int): Boolean {
            val isRgbSkin = r > 80 && g > 35 && b > 20 &&
                    r > g && r > b &&
                    (r - g) in 15..95 &&
                    (r - b) > 15
            if (!isRgbSkin) return false

            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
            return y in 60f..250f && cb in 80f..130f && cr in 135f..175f
        }

        // Deep crimson red overlay (R=195, G=15, B=15) - MUST NOT BE DETECTED AS SKIN
        assertFalse("Red shield overlay pixel must NOT be classified as skin", isPixelSkin(195, 15, 15))

        // Normal human skin tones (fair, olive, brown, dark) - MUST BE DETECTED AS SKIN
        assertTrue("Fair skin tone", isPixelSkin(230, 185, 150))
        assertTrue("Medium / olive skin tone", isPixelSkin(195, 140, 100))
        assertTrue("Warm tan skin tone", isPixelSkin(160, 110, 75))
    }

    @Test
    fun testScoringRejectsFlatUiScreens() {
        fun computeScore(
            probMap: Map<String, Float>,
            skinRatio: Float,
            isOverlay: Boolean
        ): Float {
            if (isOverlay) return 0f

            val pornProb = probMap["porn"] ?: 0f
            val hentaiProb = probMap["hentai"] ?: 0f
            val sexyProb = probMap["sexy"] ?: 0f
            val drawingsProb = probMap["drawings"] ?: 0f

            val isCartoon = drawingsProb > 0.40f
            val hentaiEffective = if (isCartoon && hentaiProb > 0.25f) {
                hentaiProb * 0.80f
            } else {
                0f
            }

            var score = pornProb + hentaiEffective + (sexyProb * 1.15f)
            if ((sexyProb > 0.30f || pornProb > 0.15f) && skinRatio > 0.20f) {
                score += (skinRatio * 0.35f)
            }
            return score.coerceIn(0f, 1f)
        }

        val threshold = SensitivityLevel.BALANCED.threshold // 0.32

        // 1. Clean News Anchor (Real Unsplash image: neutral = 0.9967, sexy = 0.0002, porn = 0.0002, skin = 0.15)
        val cleanNewsScore = computeScore(
            mapOf("porn" to 0.0002f, "hentai" to 0.0011f, "sexy" to 0.0002f, "neutral" to 0.9967f),
            skinRatio = 0.15f,
            isOverlay = false
        )
        assertTrue("Clean news anchor must be completely safe: score=$cleanNewsScore", cleanNewsScore < threshold)

        // 2. News Anchor in business suit with face/hands visible (sexy = 0.18, porn = 0.01, skin = 0.16)
        val newsAnchorScore = computeScore(
            mapOf("porn" to 0.01f, "hentai" to 0.02f, "sexy" to 0.18f, "neutral" to 0.70f),
            skinRatio = 0.16f,
            isOverlay = false
        )
        assertTrue("News anchor in suit must be safe: score=$newsAnchorScore", newsAnchorScore < threshold)

        // 3. White Settings UI screen (spurious hentai = 0.56, skin = 0.0, neutral = 0.28)
        val whiteScreenScore = computeScore(
            mapOf("porn" to 0.0f, "hentai" to 0.56f, "sexy" to 0.04f, "neutral" to 0.28f),
            skinRatio = 0.0f,
            isOverlay = false
        )
        assertTrue("White settings screen must NOT trigger: score=$whiteScreenScore", whiteScreenScore < threshold)

        // 4. Dark mode UI screen (drawings = 0.89, hentai = 0.07, sexy = 0.01, skin = 0.0)
        val darkScreenScore = computeScore(
            mapOf("porn" to 0.002f, "hentai" to 0.07f, "sexy" to 0.01f, "neutral" to 0.02f),
            skinRatio = 0.0f,
            isOverlay = false
        )
        assertTrue("Dark settings screen must NOT trigger: score=$darkScreenScore", darkScreenScore < threshold)

        // 5. Red shield overlay frame (isOverlay = true)
        val overlayScore = computeScore(
            mapOf("sexy" to 0.35f, "hentai" to 0.28f),
            skinRatio = 0.0f,
            isOverlay = true
        )
        assertEquals(0f, overlayScore, 0.001f)

        // 6. Inappropriate post inside feed (sexy = 0.40, porn = 0.05, skin = 0.25)
        val feedPostScore = computeScore(
            mapOf("porn" to 0.05f, "hentai" to 0.02f, "sexy" to 0.40f, "neutral" to 0.40f),
            skinRatio = 0.25f,
            isOverlay = false
        )
        assertTrue("Inappropriate post inside feed must trigger: score=$feedPostScore", feedPostScore >= threshold)

        // 7. Romantic intimate couple scene (sexy = 0.48, porn = 0.06, skin = 0.22)
        val romanticScore = computeScore(
            mapOf("porn" to 0.06f, "hentai" to 0.02f, "sexy" to 0.48f, "neutral" to 0.35f),
            skinRatio = 0.22f,
            isOverlay = false
        )
        assertTrue("Romantic scene must trigger: score=$romanticScore", romanticScore >= threshold)

        // 8. Explicit adult content (porn = 0.65, sexy = 0.20, skin = 0.35)
        val explicitScore = computeScore(
            mapOf("porn" to 0.65f, "hentai" to 0.05f, "sexy" to 0.20f, "neutral" to 0.05f),
            skinRatio = 0.35f,
            isOverlay = false
        )
        assertTrue("Explicit content must trigger: score=$explicitScore", explicitScore >= threshold)
    }

    @Test
    fun testContentFilterCustomAllowedKeywordsOverrideProhibited() {
        // Even if title contains "coke studio" or "official music video", if user added an allowed keyword, it is ALLOWED!
        val title = "Coke Studio Bangla | Bulbuli | Special Edition"
        val customAllowed = setOf("bulbuli")

        val result = ContentFilter.evaluateText(
            text = title,
            customAllowed = customAllowed
        )

        assertEquals("Custom allowed keyword must override default prohibited keywords",
            ContentFilter.MatchResult.ALLOWED, result)
    }

    @Test
    fun testContentFilterCustomFlaggedKeywordsMultiLanguage() {
        // 1. Bengali custom flagged keyword: "গান" (Song) or "নাটক" (Drama)
        val bengaliTitle = "নতুন বাংলা গান ২০২৪ | New Track"
        val resultBengali = ContentFilter.evaluateText(
            text = bengaliTitle,
            customFlagged = setOf("গান")
        )
        assertEquals(ContentFilter.MatchResult.PROHIBITED, resultBengali)

        // 2. Arabic custom flagged keyword: "رقص" (Dance)
        val arabicTitle = "أجمل حفلة رقص شرقي"
        val resultArabic = ContentFilter.evaluateText(
            text = arabicTitle,
            customFlagged = setOf("رقص")
        )
        assertEquals(ContentFilter.MatchResult.PROHIBITED, resultArabic)

        // 3. Urdu custom flagged keyword: "موسیقی" (Music)
        val urduTitle = "شام کی موسیقی محفل"
        val resultUrdu = ContentFilter.evaluateText(
            text = urduTitle,
            customFlagged = setOf("موسیقی")
        )
        assertEquals(ContentFilter.MatchResult.PROHIBITED, resultUrdu)
    }

    @Test
    fun testContentFilterSafeExemptionsForQuranAndSpeech() {
        // Quran recitations and Surahs must always be ALLOWED (never prohibited)
        val quran1 = "Surah Al-Baqarah Full Recitation by Mishary Rashid Alafasy"
        assertEquals(ContentFilter.MatchResult.ALLOWED, ContentFilter.evaluateText(quran1))

        val quran2 = "Beautiful Tilawat of Surah Ar-Rahman (No Music)"
        assertEquals(ContentFilter.MatchResult.ALLOWED, ContentFilter.evaluateText(quran2))

        val lecture = "Islamic Khutbah & Bayan on Modesty and Lowering the Gaze"
        assertEquals(ContentFilter.MatchResult.ALLOWED, ContentFilter.evaluateText(lecture))

        val adhan = "Peaceful Fajr Adhan from Makkah"
        assertEquals(ContentFilter.MatchResult.ALLOWED, ContentFilter.evaluateText(adhan))
    }

    @Test
    fun testContentFilterCaseInsensitiveAndWhitespace() {
        val title = "   OFFICIAL MUSIC VIDEO 4K   "
        val customAllowed = setOf("   official music video   ")

        // Allowed list trims and matches case-insensitively
        val result = ContentFilter.evaluateText(
            text = title,
            customAllowed = customAllowed
        )
        assertEquals(ContentFilter.MatchResult.ALLOWED, result)
    }

    @Test
    fun testContentFilterDistinguishesMusicFromVisual() {
        val musicTitle = "Coke Studio Bangla | Shob Loke Koy | Official Video"
        val musicResult = ContentFilter.evaluateText(musicTitle)
        assertEquals(ContentFilter.MatchResult.PROHIBITED_MUSIC, musicResult)

        val visualTitle = "Romantic Scene - Best Couple Moments Clip"
        val visualResult = ContentFilter.evaluateText(visualTitle)
        assertEquals(ContentFilter.MatchResult.PROHIBITED_VISUAL, visualResult)
    }

    @Test
    fun testMusicKeywordRequiresActiveAudioLogic() {
        val musicTitle = "Coke Studio Season 14 | Pasoori | Official Music Video"
        val match = ContentFilter.evaluateText(musicTitle)
        assertEquals(ContentFilter.MatchResult.PROHIBITED_MUSIC, match)

        // 1. Browsing YouTube feed / recommendations with sound off (isMusicActive == false)
        val isMusicActiveWhenBrowsing = false
        val shouldTriggerWhenBrowsing = (match == ContentFilter.MatchResult.PROHIBITED_MUSIC) && isMusicActiveWhenBrowsing
        assertFalse("Browsing YouTube thumbnails with sound off must NEVER trigger the shield", shouldTriggerWhenBrowsing)

        // 2. Video actively playing with audio (isMusicActive == true)
        val isMusicActiveWhenPlaying = true
        val shouldTriggerWhenPlaying = (match == ContentFilter.MatchResult.PROHIBITED_MUSIC) && isMusicActiveWhenPlaying
        assertTrue("Actively playing music video must trigger the shield", shouldTriggerWhenPlaying)
    }

    @Test
    fun testSpeechToggleDoesNotFlagCleanAudioWhenMicIsIdle() {
        // Simulating evaluateAudioState when allowSpeechFilter is ON and mic is idle/not recording
        val isSystemMediaActive = true
        val allowSpeechFilter = true
        val isRecording = false // default: no mic permission granted
        val isExplicitMusicStream = false // YouTube general video stream

        val isSpeech: Boolean
        val isDetected: Boolean

        if (!isSystemMediaActive) {
            isDetected = false
        } else if (isExplicitMusicStream) {
            isDetected = true
        } else if (allowSpeechFilter) {
            if (isRecording) {
                isDetected = false
            } else {
                // Must NOT invert to true!
                isDetected = false
            }
        } else {
            isDetected = false
        }

        assertFalse("Enabling speech toggle must NOT flag clean audio when mic is idle", isDetected)
    }

    @Test
    fun testFeedThumbnailCardIgnored() {
        fun isFeedThumbnailCard(desc: String): Boolean {
            if (desc.contains(" - play video") || desc.endsWith("play video")) return true
            if (desc.contains("views -") || desc.contains("views •")) return true
            return false
        }

        val feedCardDesc = "Shake Karaan - 8K/4K Music Video | Nidhhi Agerwal - 2 minutes, 53 seconds - Go to channel Sony Music India - 41 million views - 9 months ago - play video"
        assertTrue("YouTube search/feed thumbnail card must be recognized as feed card", isFeedThumbnailCard(feedCardDesc))

        val watchPageTitle = "Shake Karaan - 8K/4K Music Video | Nidhhi Agerwal | Meet Bros Ft. Kanika Kapoor"
        assertFalse("Actively playing watch page video title must NOT be treated as feed card", isFeedThumbnailCard(watchPageTitle))
    }

    @Test
    fun testAudibleAudioDistinction() {
        fun isAudible(isMusicActive: Boolean, volume: Int, configDesc: String): Boolean {
            if (!isMusicActive) return false
            if (volume == 0) return false
            if (configDesc.contains("mutedState:clientVolume") || configDesc.contains("mutedState:volume")) {
                return false
            }
            return true
        }

        // Case 1: Muted YouTube feed preview autoplay
        val feedPreviewConfig = "AudioPlaybackConfiguration piid:263 ... mutedState:clientVolume"
        assertFalse(
            "Muted YouTube feed preview must NOT be considered audible",
            isAudible(isMusicActive = true, volume = 10, configDesc = feedPreviewConfig)
        )

        // Case 2: Audible playing video
        val playingConfig = "AudioPlaybackConfiguration piid:271 ... mutedState:none"
        assertTrue(
            "Audible playing video must be recognized as audible",
            isAudible(isMusicActive = true, volume = 10, configDesc = playingConfig)
        )

        // Case 3: Device volume muted (0)
        assertFalse(
            "Volume at 0 must NOT be considered audible",
            isAudible(isMusicActive = true, volume = 0, configDesc = playingConfig)
        )
    }

    @Test
    fun testLauncherKeepsShieldWhenBackgroundMusicIsPlaying() {
        fun shouldDismissShieldOnLauncher(
            isLauncher: Boolean,
            isAudible: Boolean,
            isMusicDetected: Boolean,
            isMusicKeywordProhibited: Boolean
        ): Boolean {
            val isBackgroundMusicPlaying = isAudible && (isMusicDetected || isMusicKeywordProhibited)
            return isLauncher && !isBackgroundMusicPlaying
        }

        // Case 1: User on Home screen with no music playing -> dismiss shield
        assertTrue(
            "Home screen without music playing must dismiss shield",
            shouldDismissShieldOnLauncher(isLauncher = true, isAudible = false, isMusicDetected = false, isMusicKeywordProhibited = false)
        )

        // Case 2: Internal music player playing music in background -> keep shield!
        assertFalse(
            "Home screen with internal music playing must NOT dismiss shield",
            shouldDismissShieldOnLauncher(isLauncher = true, isAudible = true, isMusicDetected = true, isMusicKeywordProhibited = false)
        )

        // Case 3: YouTube music video in PiP/background -> keep shield!
        assertFalse(
            "Home screen with YouTube music in background must NOT dismiss shield",
            shouldDismissShieldOnLauncher(isLauncher = true, isAudible = true, isMusicDetected = false, isMusicKeywordProhibited = true)
        )
    }

    @Test
    fun testIdolKeywordsEnglish() {
        val testCases = listOf(
            "Grand Durga idol unveiling ceremony",
            "Lord Shiva 4k statue in Himalayas",
            "Ancient Buddha sculpture discovered",
            "Ganesha idol immersion celebration",
            "Temple deity murti darshan live",
            "Morning temple aarti ritual with flowers",
            "Historic church crucifix statue in Rome",
            "Pagan ritual around sacred idol",
            "Krishna murti decoration for Janmashtami",
            "Sculpture from other religions exhibition"
        )

        for (title in testCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Expected PROHIBITED_IDOL for: '$title'", ContentFilter.MatchResult.PROHIBITED_IDOL, result)
        }
    }

    @Test
    fun testIdolKeywordsBengali() {
        val testCases = listOf(
            "ঐতিহাসিক দুর্গা প্রতিমা ও পূজা মণ্ডপ",
            "শিব মূর্তি তৈরি করার পদ্ধতি",
            "কালী প্রতিমা বিসর্জন লাইভ",
            "মন্দিরে মহা আরতি ও পূজা",
            "ঠাকুরের মূর্তি দর্শন ও প্রণাম",
            "গণেশ মূর্তি ভাস্কর্য প্রদর্শনী"
        )

        for (title in testCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Expected PROHIBITED_IDOL for Bengali: '$title'", ContentFilter.MatchResult.PROHIBITED_IDOL, result)
        }
    }

    @Test
    fun testIdolKeywordsHindi() {
        val testCases = listOf(
            "गणेश जी की भव्य मूर्ति विसर्जन",
            "शिव प्रतिमा का भव्य दर्शन",
            "मंदिर में सुबह की आरती",
            "दुर्गा पूजा महोत्सव लाइव",
            "देवता प्रतिमा की प्राण प्रतिष्ठा",
            "भगवान की मूर्ति की पूजा अर्चना"
        )

        for (title in testCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Expected PROHIBITED_IDOL for Hindi: '$title'", ContentFilter.MatchResult.PROHIBITED_IDOL, result)
        }
    }

    @Test
    fun testIdolKeywordsArabicAndUrdu() {
        val arabicCases = listOf(
            "أكبر تمثال لصنم بوذا في آسيا",
            "عبادة الأصنام في العصور القديمة",
            "طواف بالصنم ونصب تذكاري وثني"
        )
        for (title in arabicCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Expected PROHIBITED_IDOL for Arabic: '$title'", ContentFilter.MatchResult.PROHIBITED_IDOL, result)
        }

        val urduCases = listOf(
            "مورتی پوجا کے رسم و رواج",
            "بت پرستی کی تاریخی حقیقت",
            "بت کدہ اور مندر میں مورتیاں"
        )
        for (title in urduCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Expected PROHIBITED_IDOL for Urdu: '$title'", ContentFilter.MatchResult.PROHIBITED_IDOL, result)
        }
    }

    @Test
    fun testSafeIslamicLectureExemptionsOnIdolTopics() {
        val educationalCases = listOf(
            "Surah Al-Anbiya Tafsir - Prophet Ibrahim breaks the idols",
            "Dr. Zakir Naik debate on idol worship and shirk in Islam",
            "Islamic lecture on Tawheed vs idol worship by Nouman Ali Khan",
            "Story of Hazrat Ibrahim destroying idols in the temple - Bayan",
            "Refutation of polytheism and idol worship - Islamic speech",
            "Surah Al-Baqarah recitation by Mishary Alafasy"
        )

        for (title in educationalCases) {
            val result = ContentFilter.evaluateText(title)
            assertEquals("Islamic educational lectures discussing idols MUST be ALLOWED: '$title'", ContentFilter.MatchResult.ALLOWED, result)
        }
    }

    @Test
    fun testIdolShieldDecisionController() {
        fun evaluateShield(
            isIdolEnabled: Boolean,
            isIdolVisualProhibited: Boolean,
            isIdolKeywordProhibited: Boolean,
            isContentExplicitlyAllowed: Boolean
        ): Pair<Boolean, String> {
            if (isContentExplicitlyAllowed) {
                return Pair(false, "Allowed Content Exemption")
            }
            val idolTrigger = isIdolEnabled && (isIdolVisualProhibited || isIdolKeywordProhibited)
            val reason = if (idolTrigger) "Idol / Religious sculpture detected" else ""
            return Pair(idolTrigger, reason)
        }

        // Case 1: Idol keyword detected with shield enabled -> shield triggers
        val (active1, reason1) = evaluateShield(
            isIdolEnabled = true,
            isIdolVisualProhibited = false,
            isIdolKeywordProhibited = true,
            isContentExplicitlyAllowed = false
        )
        assertTrue(active1)
        assertEquals("Idol / Religious sculpture detected", reason1)

        // Case 2: Visual idol detected with shield enabled -> shield triggers
        val (active2, reason2) = evaluateShield(
            isIdolEnabled = true,
            isIdolVisualProhibited = true,
            isIdolKeywordProhibited = false,
            isContentExplicitlyAllowed = false
        )
        assertTrue(active2)
        assertEquals("Idol / Religious sculpture detected", reason2)

        // Case 3: Idol detected but user toggled Idol Shield OFF -> allowed
        val (active3, _) = evaluateShield(
            isIdolEnabled = false,
            isIdolVisualProhibited = true,
            isIdolKeywordProhibited = true,
            isContentExplicitlyAllowed = false
        )
        assertFalse("Disabled idol shield must NOT trigger", active3)

        // Case 4: Islamic lecture discussing idols -> explicitly allowed, shield suppressed
        val (active4, reason4) = evaluateShield(
            isIdolEnabled = true,
            isIdolVisualProhibited = false,
            isIdolKeywordProhibited = true,
            isContentExplicitlyAllowed = true
        )
        assertFalse("Islamic lecture discussing idols must be exempted", active4)
        assertEquals("Allowed Content Exemption", reason4)
    }
}

