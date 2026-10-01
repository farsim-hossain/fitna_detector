package com.example.fitna_detector.model

import java.util.Locale

/**
 * Handles evaluation of screen titles, descriptions, and text against:
 * 1. User's Personal Allow List (Highest priority whitelist)
 * 2. Built-in Safe Exemptions (Quran, Surahs, Lectures, Adhan, etc.)
 * 3. User's Personal Flag List (Custom user blocklist)
 * 4. Built-in Prohibited Keywords (Music videos vs Romantic/Visual scenes)
 *
 * Supports all languages and Unicode scripts (English, Arabic, Bengali, Urdu, Hindi, etc.).
 */
object ContentFilter {

    // Distinct music video and track markers (Requires active audio to trigger)
    val DEFAULT_MUSIC_KEYWORDS: List<String> = listOf(
        "official music video", "official mv", "music video", "video song",
        "full song", "lyric video", "lyrics video", "official audio", "official video",
        "audio song", "dance performance", "dance cover", "choreography", "item song",
        "remix song", "remix video", "lofi remix", "lofi song", "slowed + reverb",
        "vevo", "t-series", "coke studio", "speed records", "zee music", "sony music",
        "tips official", "saregama", "yrf music", "soundtrack", "full album"
    )

    // Romantic, Couple & Prohibited visual scenes
    val DEFAULT_VISUAL_KEYWORDS: List<String> = listOf(
        "romantic scene", "romance scene", "romantic song", "romantic clip",
        "love song", "kiss scene", "kissing scene", "hot scene", "bed scene",
        "bikini", "swimsuit", "lingerie", "cleavage", "nude", "naked",
        "intimate scene", "love scene", "couple scene", "dating show",
        "sensual scene", "erotic scene"
    )

    // Statues, deity idols, worship rituals, and sculptures from other religions
    val DEFAULT_IDOL_KEYWORDS: List<String> = listOf(
        // English
        "statue", "idol", "sculpture", "murti", "idol worship", "idol worshipping",
        "puja", "pooja", "aarti", "arti puja", "aarti ceremony", "maha aarti", "visarjan", "darshan", "deity statue",
        "buddha statue", "shiva statue", "ganesha idol", "krishna murti", "durga idol",
        "kali murti", "crucifix statue", "temple ritual", "shrine ritual", "prasad offering",
        "idol immersion", "temple idol", "deity idol", "pagan ritual", "polytheism ritual",
        "deity sculpture", "altar worship", "statue worship", "god statue", "goddess statue",
        "worshipping statue", "worship statue", "bowing to statue", "temple deity", "sacred idol",
        "lord shiva idol", "hanuman murti", "ram murti", "vishnu idol", "lakshmi idol",
        "saraswati idol", "ganesh murti", "religious sculpture", "worship of idols",
        // Deities, festivals and rituals (English)
        "hindu god", "hindu gods", "hindu deity", "hindu deities", "hindu temple",
        "hindu ritual", "hindu rituals", "hindu festival", "hindu festivals", "hindu worship",
        "hinduism", "sanatan dharma puja", "diwali puja", "durga puja", "ganesh chaturthi",
        "shivratri", "navratri", "janmashtami", "pran pratishtha",
        "lord shiva", "shiva", "mahadev", "bholenath", "rudra", "shiva lingam", "shivling",
        "bhagwan shiv", "shiv mandir", "shiv chalisa", "shiv tandav",
        "lord ganesha", "ganesh", "ganesha", "ganpati", "vinayaka",
        "lord krishna", "krishna", "radha krishna", "hare krishna", "iskcon", "govinda",
        "maa durga", "durga", "durgamaa", "goddess durga",
        "maa kali", "kali maa", "goddess kali", "kali puja", "kalimaa", "devi kali",
        "lord hanuman", "hanuman", "bajrangbali", "hanuman chalisa",
        "lord rama", "lord ram", "shree ram", "ram mandir", "ayodhya ram",
        "lord vishnu", "vishnu", "narayan", "goddess lakshmi", "lakshmi", "laxmi",
        "goddess saraswati", "saraswati", "lord brahma", "brahma statue",
        "buddha", "gautama buddha", "gautam buddha", "bodhisattva", "tirthankara", "mahavira",
        "jain idol", "jain statue", "pagan god", "pagan gods", "greek god", "greek gods",
        "zeus", "poseidon", "apollo", "athena", "aphrodite", "roman god", "norse god", "thor statue", "odin",
        // Bengali (বাংলা)
        "মূর্তি", "প্রতিমা", "পূজা", "পূজো", "আরতি", "বিসর্জন", "দেবতা", "দেবী",
        "ভাস্কর্য", "মূর্তি পূজা", "শিব মূর্তি", "দুর্গা প্রতিমা", "গণেশ মূর্তি",
        "কালী প্রতিমা", "মন্দির পূজা", "প্রণাম", "বেদী", "পুজো", "ঠাকুরের মূর্তি", "প্রতিমা দর্শন",
        "হিন্দু দেবতা", "হিন্দু দেবী", "শিব ঠাকুর", "শিব পূজা", "শিবলিঙ্গ", "শিবরাত্রি",
        "শ্রীকৃষ্ণ", "গণেশ", "মা দুর্গা", "দুর্গা পূজা", "কালী পূজা", "মা কালী", "হনুমান",
        "শ্রী রাম", "শ্রীরাম", "ভগবান রাম", "প্রভু রাম", "রাম মন্দির", "রামচন্দ্র",
        "বিষ্ণু", "লক্ষ্মী", "সরস্বতী", "মহাদেব",
        // Hindi (हिन्दी)
        "मूर्ति", "प्रतिमा", "पूजा", "आरती", "विसर्जन", "दर्शन", "मूर्ति पूजा",
        "शिव प्रतिमा", "गणेश मूर्ति", "दुर्गा पूजा", "मंदिर", "देव प्रतिमा", "देवता प्रतिमा",
        "भगवान की मूर्ति", "मूर्ति विसर्जन", "प्राण प्रतिष्ठा", "हवन", "यज्ञ",
        "हिंदू देवता", "हिंदू देवी", "भगवान शिव", "शिव पूजा", "शिवलिंग", "शिवरात्रि",
        "गणेश", "माँ दुर्गा", "माँ काली", "काली पूजा",
        "हनुमान", "श्री राम", "श्रीराम", "भगवान राम", "प्रभु राम", "राम मंदिर", "जय श्री राम", "रामचंद्र",
        "विष्णु", "लक्ष्मी", "सरस्वती", "महादेव", "भोलेनाथ",
        // Arabic (العربية)
        "صنم", "أصنام", "تمثال", "تماثيل", "وثن", "أوثان", "عبادة الأصنام", "نصب تذكاري",
        "طواف بالصنم", "مجسم وثني", "سجود لصنم",
        // Urdu (اردو)
        "بت", "بت پرستی", "مورتی", "پوجا", "مورتیاں", "بت کدہ", "مجسمہ", "مندر",
        "دیوتا", "دیوی", "بت تراشی"
    )

    val DEFAULT_PROHIBITED_KEYWORDS: List<String> = DEFAULT_MUSIC_KEYWORDS + DEFAULT_VISUAL_KEYWORDS + DEFAULT_IDOL_KEYWORDS

    val DEFAULT_SAFE_EXEMPTION_KEYWORDS: List<String> = listOf(
        "no music", "without music", "no instruments", "vocal only",
        "acapella", "halal", "nasheed", "quran", "qur'an", "koran", "surah", "sura",
        "ayah", "ayat", "recitation", "tilawat", "lecture", "tafsir",
        "khutbah", "podcast", "bayan", "adhan", "azan", "dua", "dhikr", "zikr",
        "hadith", "hadeeth", "sunnah", "islamic", "alafasy", "abdul basit", "sudais",
        "shuraim", "minshawi", "al-hussary", "mahir", "al-muaiqly",
        "bbc news", "reuters", "al jazeera",
        "police release", "official report", "press briefing", "documentary",
        "prophet ibrahim", "hazrat ibrahim", "destroying idols", "breaking idols",
        "smashing idols", "shirk", "tawheed", "monotheism", "refutation", "refuting",
        "zakir naik", "nouman ali khan", "mufti menk", "yasir qadhi", "tariq jameel", "ahmad deedat",
        "kali linux", "scientific article", "research paper", "tutorial", "course", "educational"
    )

    enum class MatchResult {
        ALLOWED,            // Explicitly allowed by user or safe Islamic/educational exemption
        PROHIBITED_MUSIC,   // Music video markers (only flags if media audio is playing)
        PROHIBITED_VISUAL,  // Prohibited visual/romantic scene title
        PROHIBITED_IDOL,    // Statues, idol worship, or religious sculptures from other religions
        PROHIBITED,         // Custom user-flagged keyword
        NEUTRAL             // Neither
    }

    /**
     * Evaluates a piece of text (e.g. video title, description, or accessibility node text)
     * against custom user filters and default filters.
     *
     * Priority:
     * 1. User Personal Allow List (Explicit override - never flags)
     * 2. Built-in Safe Exemptions (Quran, Surahs, Lectures, Adhan, etc.)
     * 3. User Personal Flag List (User's custom blocklist)
     * 4. Built-in Music Keywords (Music videos, Coke Studio, tracks)
     * 5. Built-in Visual Keywords (Romantic scenes, explicit terms)
     * 6. Built-in Idol & Statue Keywords (Statues, idols, religious sculptures, puja, aarti)
     */
    fun evaluateText(
        text: String,
        customAllowed: Set<String> = emptySet(),
        customFlagged: Set<String> = emptySet(),
        builtInMusic: List<String> = DEFAULT_MUSIC_KEYWORDS,
        builtInVisual: List<String> = DEFAULT_VISUAL_KEYWORDS,
        builtInIdol: List<String> = DEFAULT_IDOL_KEYWORDS,
        builtInSafe: List<String> = DEFAULT_SAFE_EXEMPTION_KEYWORDS
    ): MatchResult {
        if (text.isBlank()) return MatchResult.NEUTRAL
        val normalized = text.lowercase(Locale.ROOT)

        // 1. Check user custom allowed list (Highest Priority)
        for (keyword in customAllowed) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.ALLOWED
            }
        }

        // 2. Check built-in safe exemptions (Quran, Tilawat, Lecture, etc.)
        for (keyword in builtInSafe) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.ALLOWED
            }
        }

        // 3. Check user custom flagged list
        for (keyword in customFlagged) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.PROHIBITED
            }
        }

        // 4. Check built-in music keywords
        for (keyword in builtInMusic) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.PROHIBITED_MUSIC
            }
        }

        // 5. Check built-in visual keywords
        for (keyword in builtInVisual) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.PROHIBITED_VISUAL
            }
        }

        // 6. Check built-in idol, statue, and sculpture keywords
        for (keyword in builtInIdol) {
            val kw = keyword.trim().lowercase(Locale.ROOT)
            if (kw.isNotEmpty() && normalized.contains(kw)) {
                return MatchResult.PROHIBITED_IDOL
            }
        }

        return MatchResult.NEUTRAL
    }
}
