package com.example.videotranslator.stt

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger

private const val TAG = "SourceLanguageDetector"

/**
 * Automatic Source Language Detector for uploaded video speech.
 *
 * Analyzes recognized STT transcript segments to determine whether the
 * source video audio is in Hindi, Telugu, or English by:
 *  1. Unicode script character distribution (Devanagari / Telugu / Latin).
 *  2. High-frequency vocabulary token matching per language.
 *
 * Decision: whichever language accumulates the highest combined score wins.
 * Falls back to HINDI when scores are tied or all near-zero.
 */
class SourceLanguageDetector {

    // High-frequency Telugu vocabulary tokens (Telugu script)
    private val teluguTokens = setOf(
        "ఉంది", "ఉన్నాను", "ఉన్నారు", "చేస్తున్నాను", "చేసారు", "చేయండి",
        "నేను", "మీరు", "అతను", "అతని", "ఆమె", "మనం", "వారు",
        "అవును", "కాదు", "సరే", "ఇది", "అది", "ఏమి", "ఎందుకు",
        "వచ్చారు", "వెళ్ళాలి", "చాలా", "కొంచెం", "మంచి", "చెడు",
        "తెలుగు", "నమస్కారం", "ధన్యవాదాలు", "క్షమించండి"
    )

    // High-frequency Hindi vocabulary tokens (Devanagari script)
    private val hindiTokens = setOf(
        "है", "हैं", "था", "थी", "थे", "होगा", "होगी",
        "मैं", "तुम", "आप", "वह", "यह", "हम", "वे",
        "और", "या", "की", "के", "का", "में", "से", "को",
        "नहीं", "हाँ", "ठीक", "अच्छा", "बहुत", "थोड़ा",
        "जाना", "आना", "करना", "देखना", "बोलना", "खाना",
        "नमस्ते", "शुक्रिया", "धन्यवाद", "क्षमा"
    )

    // High-frequency English vocabulary tokens (Latin script)
    private val englishTokens = setOf(
        "the", "is", "are", "was", "were", "will", "would", "can", "could",
        "i", "you", "he", "she", "we", "they", "it", "this", "that",
        "and", "or", "but", "so", "because", "what", "when", "where", "why", "how",
        "not", "yes", "no", "okay", "good", "bad", "very", "just", "like",
        "going", "doing", "have", "had", "been", "said", "know", "think",
        "hello", "thank", "please", "sorry", "welcome"
    )

    data class DetectionResult(
        val detectedLanguage: Language,
        val hindiScore: Float,
        val teluguScore: Float,
        val englishScore: Float,
        val devanagariCharRatio: Float,
        val teluguCharRatio: Float,
        val latinCharRatio: Float,
        val totalCharsAnalyzed: Int
    )

    fun detect(segments: List<TranslationSegment>): DetectionResult {
        if (segments.isEmpty()) {
            DiagnosticLogger.log(TAG, "No segments to analyze — defaulting to HINDI")
            return DetectionResult(Language.HINDI, 1f, 0f, 0f, 1f, 0f, 0f, 0)
        }

        // Concatenate all raw text from first field (whatever Vosk produced)
        val allText = segments.joinToString(" ") { it.hindi }.trim()
        val totalChars = allText.length.coerceAtLeast(1)

        // 1. Unicode script character counts
        var devanagariCount = 0
        var teluguCount = 0
        var latinCount = 0

        for (ch in allText) {
            val cp = ch.code
            when {
                cp in 0x0900..0x097F -> devanagariCount++   // Devanagari (Hindi)
                cp in 0x0C00..0x0C7F -> teluguCount++       // Telugu script
                ch.isLetter() && cp < 0x0100 -> latinCount++ // Basic Latin (English/romanized)
            }
        }

        val devanagariRatio = devanagariCount.toFloat() / totalChars
        val teluguRatio     = teluguCount.toFloat() / totalChars
        val latinRatio      = latinCount.toFloat() / totalChars

        // 2. Vocabulary token matching
        val words = allText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }

        var hindiTokenMatches   = 0
        var teluguTokenMatches  = 0
        var englishTokenMatches = 0

        for (word in words) {
            val clean = word.replace(Regex("[!?,.;:\"'()\\[\\]\\-–—]"), "")
            when {
                clean in hindiTokens   -> hindiTokenMatches++
                clean in teluguTokens  -> teluguTokenMatches++
                clean in englishTokens -> englishTokenMatches++
            }
        }

        val wordCount = words.size.coerceAtLeast(1)
        val hindiTokenRatio   = hindiTokenMatches.toFloat() / wordCount
        val teluguTokenRatio  = teluguTokenMatches.toFloat() / wordCount
        val englishTokenRatio = englishTokenMatches.toFloat() / wordCount

        // 3. Composite score: script weight 0.6 + vocabulary weight 0.4
        val hindiScore   = 0.6f * devanagariRatio + 0.4f * hindiTokenRatio
        val teluguScore  = 0.6f * teluguRatio     + 0.4f * teluguTokenRatio
        val englishScore = 0.6f * latinRatio       + 0.4f * englishTokenRatio

        val detected = when {
            teluguScore > hindiScore && teluguScore > englishScore -> Language.TELUGU
            englishScore > hindiScore && englishScore > teluguScore -> Language.ENGLISH
            else -> Language.HINDI
        }

        DiagnosticLogger.log(TAG,
            "SOURCE LANGUAGE DETECTION:\n" +
            "   Devanagari chars: $devanagariCount (${"%5.1f".format(devanagariRatio * 100)}%) | Hindi tokens: $hindiTokenMatches → HINDI score=${"%.3f".format(hindiScore)}\n" +
            "   Telugu chars:     $teluguCount     (${"%5.1f".format(teluguRatio * 100)}%) | Telugu tokens: $teluguTokenMatches → TELUGU score=${"%.3f".format(teluguScore)}\n" +
            "   Latin chars:      $latinCount      (${"%5.1f".format(latinRatio * 100)}%) | English tokens: $englishTokenMatches → ENGLISH score=${"%.3f".format(englishScore)}\n" +
            "   ▶ DETECTED SOURCE LANGUAGE: $detected"
        )

        return DetectionResult(
            detectedLanguage    = detected,
            hindiScore          = hindiScore,
            teluguScore         = teluguScore,
            englishScore        = englishScore,
            devanagariCharRatio = devanagariRatio,
            teluguCharRatio     = teluguRatio,
            latinCharRatio      = latinRatio,
            totalCharsAnalyzed  = totalChars
        )
    }
}
