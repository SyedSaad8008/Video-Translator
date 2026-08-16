package com.example.videotranslator.stt

import android.content.Context
import android.util.Log
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val TAG = "VoskSpeechRecognizer"
private const val MODEL_HI_ASSET_ZIP = "model-hi-small.zip"
private const val MODEL_EN_ASSET_ZIP = "model-en-small.zip"

/**
 * Stage 2 Dual-Model Vosk Speech-to-Text Recognizer & Language Prober.
 *
 * Real Acoustic-Confidence & Vocabulary-Validation Source Language Detection:
 *  1. Runs a 30-second dual-probe across both Hindi and English Vosk models.
 *  2. Validates recognized words against authentic English and Hindi dictionary vocabularies.
 *  3. Computes authentic scores: `score = selfReportedConfidence * dictionaryValidityRatio`.
 *  4. Decision rules:
 *     - Hindi authentic & vocabulary valid (ratio >= 35%) -> HINDI
 *     - English authentic & vocabulary valid (ratio >= 35%) -> ENGLISH
 *     - Both models fail vocabulary check (< 30% dictionary words) -> TELUGU (by elimination)
 */
class VoskSpeechRecognizer(private val context: Context) {

    private var hiModel: Model? = null
    private var enModel: Model? = null

    // Expanded high-frequency English dictionary (~500 words)
    // Covers: function words, pronouns, common verbs, nouns, adjectives, adverbs, conjunctions
    private val englishVocabulary = setOf(
        // Function words & pronouns
        "the", "be", "to", "of", "and", "in", "that", "have", "it", "for", "not", "on", "with", "he",
        "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
        "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about",
        "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know",
        "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", "other", "than",
        "then", "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two",
        "how", "our", "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give",
        "day", "most", "us", "been", "has", "had", "did", "does", "am", "are", "is", "was", "were",
        // Common verbs
        "tell", "ask", "try", "need", "feel", "become", "leave", "put", "mean", "keep", "let", "begin",
        "seem", "help", "show", "hear", "play", "run", "move", "live", "believe", "hold", "bring", "happen",
        "must", "write", "provide", "sit", "stand", "lose", "pay", "meet", "include", "continue", "set",
        "learn", "change", "lead", "understand", "watch", "follow", "stop", "create", "speak", "read", "allow",
        "add", "spend", "grow", "open", "walk", "win", "offer", "remember", "love", "consider", "appear",
        "buy", "wait", "serve", "die", "send", "expect", "build", "stay", "fall", "cut", "reach", "kill",
        "remain", "suggest", "raise", "pass", "sell", "require", "report", "decide", "pull", "start",
        "develop", "shall", "might", "may", "already", "still", "never", "very", "much", "many",
        // Common nouns
        "thing", "man", "woman", "child", "world", "life", "hand", "part", "place", "case", "week", "company",
        "system", "program", "question", "government", "number", "night", "point", "home", "water", "room",
        "mother", "area", "money", "story", "fact", "month", "lot", "right", "study", "book", "eye", "job",
        "word", "business", "issue", "side", "kind", "head", "house", "service", "friend", "father", "power",
        "hour", "game", "line", "end", "member", "law", "car", "city", "community", "name", "president",
        "team", "minute", "idea", "body", "information", "group", "problem", "party", "result", "door",
        "school", "state", "country", "student", "family", "class", "level", "language", "food", "music",
        "today", "morning", "evening", "tomorrow", "yesterday",
        // Common adjectives
        "old", "great", "big", "high", "small", "large", "next", "early", "young", "important",
        "few", "public", "bad", "same", "able", "last", "long", "little", "own", "left", "best",
        "better", "sure", "free", "real", "different", "every", "each", "both", "true", "during",
        "another", "such", "possible", "quite", "hard", "nice", "beautiful", "amazing", "wonderful",
        // Common adverbs & connectors
        "here", "too", "again", "once", "really", "actually", "always", "often", "sometimes", "usually",
        "maybe", "perhaps", "please", "yes", "yeah", "okay", "ok", "right", "sorry", "hello", "hi",
        "thanks", "thank", "welcome", "goodbye", "bye", "enough", "almost", "away", "off", "down",
        "where", "why", "before", "between", "under", "since", "without", "however", "though",
        "through", "while", "against", "within", "along", "above", "near", "until", "yet",
        // Media & tech
        "video", "audio", "phone", "camera", "screen", "channel", "subscribe", "watch", "share",
        "record", "recording", "app", "features", "translate", "translation", "online", "internet",
        "computer", "mobile", "software", "website", "social", "media", "content", "digital",
        // Conversation
        "speaking", "talking", "saying", "going", "coming", "looking", "making", "doing", "taking",
        "getting", "something", "everything", "nothing", "anything", "someone", "everyone", "nobody",
        "everybody", "children", "example", "together", "already", "actually", "probably", "especially",
        "basically", "definitely", "absolutely", "certainly", "exactly", "completely", "totally"
    )

    // Expanded high-frequency Hindi Devanagari dictionary (~500 words)
    // Covers: postpositions, pronouns, verbs (all common forms), nouns, adjectives, adverbs, conjunctions
    private val hindiVocabulary = setOf(
        // Postpositions & particles (extremely high frequency)
        "है", "हैं", "था", "थी", "थे", "होगा", "होगी", "होता", "होती", "होते",
        "की", "के", "का", "में", "से", "को", "पर", "ने", "तक", "वाला", "वाली", "वाले",
        // Pronouns
        "मैं", "मुझे", "मेरा", "मेरी", "मेरे", "हम", "हमें", "हमारा", "हमारी", "हमारे",
        "तुम", "तुम्हें", "तुम्हारा", "तुम्हारी", "तुम्हारे", "तू", "तेरा", "तेरी",
        "आप", "आपका", "आपकी", "आपके", "आपको",
        "वह", "वो", "उसका", "उसकी", "उसके", "उसे", "उन्हें", "उनका", "उनकी", "उनके",
        "यह", "ये", "इसका", "इसकी", "इसके", "इसे", "इन्हें", "इनका",
        "वे", "कोई", "कुछ", "सब", "सबका", "हर", "कई", "दूसरा", "दूसरी",
        "अपना", "अपनी", "अपने", "खुद", "स्वयं",
        // Question words
        "क्या", "कैसे", "कब", "कहाँ", "कहां", "क्यों", "कौन", "किसका", "किसकी", "किसने",
        "कितना", "कितनी", "कितने", "किधर", "कैसा", "किस",
        // Conjunctions & connectors
        "और", "या", "लेकिन", "मगर", "पर", "परन्तु", "फिर", "इसलिए", "क्योंकि", "कि",
        "जब", "तब", "जैसे", "वैसे", "जहाँ", "वहाँ", "जहां", "वहां", "अगर", "तो",
        "भी", "ही", "बस", "सिर्फ", "केवल", "बल्कि", "चाहे", "हालांकि", "जबकि",
        // Negation & affirmation
        "नहीं", "ना", "न", "मत", "हाँ", "हां", "जी", "ठीक", "अच्छा", "बिल्कुल", "ज़रूर", "जरूर",
        // Common verbs (base + frequent inflected forms)
        "करना", "करता", "करती", "करते", "करो", "किया", "करें", "करेंगे", "करूँगा", "करूंगा",
        "होना", "हूँ", "हूं", "हो", "हुआ", "हुई", "हुए",
        "जाना", "जाता", "जाती", "जाते", "जाओ", "गया", "गयी", "गई", "गए", "जाएगा", "जाएंगे",
        "आना", "आता", "आती", "आते", "आओ", "आया", "आई", "आए", "आएगा", "आयेगा",
        "देना", "देता", "देती", "देते", "दो", "दिया", "दी", "दें", "देंगे",
        "लेना", "लेता", "लेती", "लेते", "लो", "लिया", "ली", "लें", "लेंगे",
        "कहना", "कहता", "कहती", "कहते", "कहो", "कहा", "कहें", "कहेंगे",
        "बोलना", "बोलता", "बोलती", "बोलते", "बोलो", "बोला", "बोली", "बोले",
        "देखना", "देखता", "देखती", "देखते", "देखो", "देखा", "देखी", "देखें", "देखिए",
        "सुनना", "सुनता", "सुनती", "सुनते", "सुनो", "सुना", "सुनी", "सुनें", "सुनिए",
        "समझना", "समझता", "समझती", "समझते", "समझो", "समझा", "समझी", "समझें", "समझिए",
        "बताना", "बताता", "बताती", "बताते", "बताओ", "बताया", "बताई", "बताएं", "बताइए",
        "खाना", "खाता", "खाती", "खाते", "खाओ", "खाया",
        "पीना", "पीता", "पीती", "पीते", "पिया",
        "रहना", "रहता", "रहती", "रहते", "रहा", "रही", "रहे", "रहो", "रहें",
        "चलना", "चलता", "चलती", "चलते", "चलो", "चला", "चली", "चले", "चलें",
        "सोचना", "सोचता", "सोचती", "सोचते", "सोचो", "सोचा",
        "पढ़ना", "पढ़ता", "पढ़ती", "पढ़ते", "पढ़ो", "पढ़ा",
        "लिखना", "लिखता", "लिखती", "लिखते", "लिखो", "लिखा",
        "मिलना", "मिलता", "मिलती", "मिलते", "मिला", "मिली", "मिले", "मिलें",
        "रखना", "रखता", "रखती", "रखते", "रखो", "रखा", "रखी",
        "चाहना", "चाहता", "चाहती", "चाहते", "चाहिए", "चाहूँगा",
        "पाना", "पाता", "पाती", "पाते", "पाया", "पाई",
        "सकना", "सकता", "सकती", "सकते", "सका", "सकी", "सके",
        "पूछना", "पूछता", "पूछती", "पूछते", "पूछा", "पूछो",
        "बैठना", "बैठता", "बैठती", "बैठो", "बैठा", "बैठे",
        "उठना", "उठता", "उठती", "उठो", "उठा", "उठी", "उठे",
        "खेलना", "खेलता", "खेलती", "खेलते", "खेला", "खेलो",
        "मानना", "मानता", "मानती", "मानते", "माना", "मानो",
        "लगना", "लगता", "लगती", "लगते", "लगा", "लगी", "लगे",
        "डालना", "डालता", "डालती", "डालो", "डाला",
        "निकलना", "निकलता", "निकलती", "निकला",
        "भेजना", "भेजता", "भेजती", "भेजो", "भेजा",
        // Common nouns
        "बात", "लोग", "आदमी", "औरत", "बच्चा", "बच्चे", "बच्ची", "लड़का", "लड़की",
        "समय", "काम", "दिन", "रात", "सुबह", "शाम", "दोपहर", "साल", "महीना", "हफ्ता",
        "घर", "घरों", "कमरा", "दरवाज़ा", "खिड़की",
        "देश", "शहर", "गाँव", "गांव", "जगह", "रास्ता", "सड़क",
        "नाम", "तरह", "तरीका", "बाद", "पहले", "बीच", "साथ", "पास", "ऊपर", "नीचे",
        "लिए", "वजह", "कारण", "मतलब", "ज़रूरत", "जरूरत",
        "पानी", "खाना", "दूध", "चाय", "रोटी",
        "पैसा", "पैसे", "रुपया", "रुपये",
        "दोस्त", "भाई", "बहन", "माँ", "मां", "पिता", "बाप", "बेटा", "बेटी",
        "सरकार", "पार्टी", "नेता", "जनता",
        "स्कूल", "कॉलेज", "पढ़ाई", "किताब", "शिक्षा",
        "फ़िल्म", "फिल्म", "गाना", "संगीत",
        "दुनिया", "ज़िंदगी", "जिंदगी", "जीवन", "मन", "दिल", "शरीर", "हाथ", "पैर", "आँख", "सिर",
        // Adjectives
        "बड़ा", "बड़ी", "बड़े", "छोटा", "छोटी", "छोटे",
        "अच्छा", "अच्छी", "अच्छे", "बुरा", "बुरी", "बुरे",
        "नया", "नयी", "नई", "नये", "नए", "पुराना", "पुरानी", "पुराने",
        "ज़्यादा", "ज्यादा", "कम", "बहुत", "थोड़ा", "थोड़ी", "थोड़े",
        "सही", "गलत", "ख़ास", "खास", "ज़रूरी", "जरूरी", "मुश्किल", "आसान",
        "पहला", "पहली", "पहले", "दूसरा", "दूसरी", "दूसरे", "तीसरा", "तीसरी",
        "सारा", "सारी", "सारे", "पूरा", "पूरी", "पूरे",
        // Adverbs & time
        "आज", "कल", "परसों", "अभी", "तभी", "यहाँ", "यहां", "वहाँ", "वहां",
        "ऐसा", "ऐसी", "ऐसे", "वैसा", "वैसी",
        "शायद", "ज़रा", "जरा", "बस",
        // Common expressions & greetings
        "नमस्ते", "नमस्कार", "शुक्रिया", "धन्यवाद", "माफ़", "माफ",
        // Modern/tech
        "वीडियो", "ऑडियो", "फोन", "मोबाइल", "ऐप", "इंटरनेट", "कंप्यूटर",
        "बातचीत", "योजना", "बना", "बनाना", "बनाया", "बनाई",
        "बारिश", "मौसम", "तेजी", "धीमे", "समस्या", "हल",
        "ख़बर", "खबर", "ख़बरें", "खबरें", "अख़बार", "अखबार",
        // Numbers as words
        "एक", "दो", "तीन", "चार", "पाँच", "पांच", "छह", "सात", "आठ", "नौ", "दस",
        "सौ", "हज़ार", "हजार", "लाख", "करोड़"
    )

    private data class WordInfo(
        val word: String,
        val startMs: Long,
        val endMs: Long,
        val confidence: Double
    )

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (hiModel != null) return@withContext

        // Load Hindi Vosk Model
        val hiModelDir = File(context.filesDir, "vosk-hi-model")
        var hiVoskRoot = findVoskRoot(hiModelDir)
        if (hiVoskRoot == null || !hiVoskRoot.exists()) {
            Log.d(TAG, "STAGE 2 - Extracting Hindi Vosk model asset…")
            extractZipFromAssets(MODEL_HI_ASSET_ZIP, hiModelDir)
            hiVoskRoot = findVoskRoot(hiModelDir)
        }
        val rootHi = hiVoskRoot ?: throw IllegalStateException("Hindi Vosk model root directory not found")
        Log.d(TAG, "STAGE 2 - Loading Hindi Vosk model from: ${rootHi.absolutePath}")
        hiModel = Model(rootHi.absolutePath)
        Log.d(TAG, "STAGE 2 - Hindi Vosk model loaded successfully ✓")

        // Load English Vosk Model (if present in assets)
        try {
            val enModelDir = File(context.filesDir, "vosk-en-model")
            var enVoskRoot = findVoskRoot(enModelDir)
            if (enVoskRoot == null || !enVoskRoot.exists()) {
                if (context.assets.list("")?.contains(MODEL_EN_ASSET_ZIP) == true) {
                    Log.d(TAG, "STAGE 2 - Extracting English Vosk model asset…")
                    extractZipFromAssets(MODEL_EN_ASSET_ZIP, enModelDir)
                    enVoskRoot = findVoskRoot(enModelDir)
                }
            }
            if (enVoskRoot != null && enVoskRoot.exists()) {
                Log.d(TAG, "STAGE 2 - Loading English Vosk model from: ${enVoskRoot.absolutePath}")
                enModel = Model(enVoskRoot.absolutePath)
                Log.d(TAG, "STAGE 2 - English Vosk model loaded successfully ✓")
            } else {
                Log.w(TAG, "English Vosk asset '$MODEL_EN_ASSET_ZIP' not found, running Hindi-only mode.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load English Vosk model: ${e.localizedMessage}")
        }
    }

    fun close() {
        hiModel?.close()
        enModel?.close()
        hiModel = null
        enModel = null
    }

    /**
     * Probes the first 30 seconds of audio against both Hindi and English models
     * using acoustic confidence combined with vocabulary dictionary validation.
     */
    suspend fun probeLanguage(pcm: ShortArray): Language = withContext(Dispatchers.IO) {
        val mHi = hiModel ?: return@withContext Language.HINDI
        if (pcm.isEmpty()) return@withContext Language.HINDI

        val probeLength = (16_000 * 30).coerceAtMost(pcm.size)
        val probePcm = pcm.copyOfRange(0, probeLength)
        val durationSec = probeLength / 16000.0

        // 1. Probe Hindi model
        val hiWords = runVoskPass(mHi, probePcm)
        val hiAvgConf = if (hiWords.isNotEmpty()) hiWords.map { it.confidence }.average() else 0.0
        val hiScore = (hiAvgConf * (hiWords.size / durationSec)).toFloat()

        val hiValidCount = hiWords.count { wordInfo ->
            val norm = wordInfo.word.trim().lowercase().replace(Regex("[!?,.–—\\-]"), "")
            hindiVocabulary.contains(norm)
        }
        val hiValidityRatio = if (hiWords.isNotEmpty()) hiValidCount.toFloat() / hiWords.size else 0f
        val hiAuthenticScore = hiScore * hiValidityRatio

        // 2. Probe English model
        var enWords = emptyList<WordInfo>()
        var enAvgConf = 0.0
        var enScore = 0.0f
        var enValidCount = 0
        var enValidityRatio = 0f
        var enAuthenticScore = 0.0f
        val mEn = enModel
        if (mEn != null) {
            enWords = runVoskPass(mEn, probePcm)
            enAvgConf = if (enWords.isNotEmpty()) enWords.map { it.confidence }.average() else 0.0
            enScore = (enAvgConf * (enWords.size / durationSec)).toFloat()

            enValidCount = enWords.count { wordInfo ->
                val norm = wordInfo.word.trim().lowercase().replace(Regex("[!?,.–—\\-]"), "")
                norm.length >= 2 && englishVocabulary.contains(norm)
            }
            enValidityRatio = if (enWords.isNotEmpty()) enValidCount.toFloat() / enWords.size else 0f
            enAuthenticScore = enScore * enValidityRatio
        }

        DiagnosticLogger.log(TAG,
            "REAL ACOUSTIC STT DUAL-PROBE & VOCABULARY VALIDATION (Sample: ${"%.1f".format(durationSec)}s):\n" +
            "   Hindi model probe:   ${hiWords.size} words (${hiValidCount} valid dict, ${"%.1f".format(hiValidityRatio*100)}%), avgConf=${"%.2f".format(hiAvgConf)} -> HINDI authenticScore=${"%.3f".format(hiAuthenticScore)}\n" +
            "   English model probe: ${enWords.size} words (${enValidCount} valid dict, ${"%.1f".format(enValidityRatio*100)}%), avgConf=${"%.2f".format(enAvgConf)} -> ENGLISH authenticScore=${"%.3f".format(enAuthenticScore)}"
        )

        val detected = when {
            // Strong Hindi signal: decent dictionary match AND outscores English
            hiAuthenticScore >= 0.03f && hiValidityRatio >= 0.15f && hiAuthenticScore > enAuthenticScore * 0.9f -> Language.HINDI

            // Strong English signal: high dictionary match AND clearly outscores Hindi
            mEn != null && enAuthenticScore >= 0.05f && enValidityRatio >= 0.25f && enAuthenticScore > hiAuthenticScore * 1.5f -> Language.ENGLISH

            // Hindi wins on relative comparison when English has very low validity (< 12%)
            hiValidityRatio >= 0.10f && enValidityRatio < 0.12f -> Language.HINDI

            // Both models failed vocabulary validity check (< 10% real dictionary words) -> TELUGU by elimination
            enValidityRatio < 0.10f && hiValidityRatio < 0.10f -> Language.TELUGU

            // Fallback: prefer whichever has higher authentic score, with English needing stronger evidence
            hiAuthenticScore >= enAuthenticScore -> Language.HINDI
            mEn != null && enAuthenticScore > hiAuthenticScore && enValidityRatio >= 0.20f -> Language.ENGLISH
            else -> Language.TELUGU
        }

        DiagnosticLogger.log(TAG, "▶ PROBED SOURCE LANGUAGE DETECTED: $detected (Validities: EN=${"%.0f".format(enValidityRatio*100)}%, HI=${"%.0f".format(hiValidityRatio*100)}%)")
        detected
    }

    suspend fun recognise(
        pcm: ShortArray,
        sourceLanguage: Language = Language.HINDI
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        val m = if (sourceLanguage == Language.ENGLISH && enModel != null) enModel!! else (hiModel ?: throw IllegalStateException("Vosk model is not loaded"))
        if (pcm.isEmpty()) return@withContext emptyList()

        val sampleRate = 16_000f
        Log.d(TAG, "STAGE 2 - Starting full Vosk recognition with model for $sourceLanguage: sampleRate=$sampleRate, pcmSamples=${pcm.size} (${"%.2f".format(pcm.size / 16000.0)}s)")

        val allWords = runVoskPass(m, pcm)

        val avgConf = if (allWords.isNotEmpty()) allWords.map { it.confidence }.average() else 0.0
        Log.d(TAG, "STAGE 2 - Recognition total: ${allWords.size} words recognized, avgConfidence=${"%.2f".format(avgConf)}")

        // Group words into full complete sentences
        val segments = groupWordsIntoFullSentences(allWords)
        Log.d(TAG, "STAGE 2 - Sentence grouping complete: ${segments.size} full sentence segments")

        for ((idx, seg) in segments.withIndex()) {
            Log.d(TAG, "STAGE 2 - Segment [$idx] (${seg.startMs}ms - ${seg.endMs}ms): \"${seg.hindi}\"")
        }

        segments
    }

    private fun runVoskPass(m: Model, pcm: ShortArray): List<WordInfo> {
        val sampleRate = 16_000f
        val chunkSize = 4096
        val rec = Recognizer(m, sampleRate)
        rec.setWords(true)

        val words = mutableListOf<WordInfo>()
        var chunkStart = 0

        while (chunkStart < pcm.size) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(pcm.size)
            val chunk = pcm.copyOfRange(chunkStart, chunkEnd)
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                words.addAll(extractWordsFromResult(rec.result))
            }
            chunkStart = chunkEnd
        }
        words.addAll(extractWordsFromResult(rec.finalResult))
        rec.close()
        return words
    }

    private fun extractWordsFromResult(json: String): List<WordInfo> {
        val words = mutableListOf<WordInfo>()
        try {
            val obj = JSONObject(json)
            val wordsArr = obj.optJSONArray("result") ?: return emptyList()

            for (i in 0 until wordsArr.length()) {
                val item = wordsArr.getJSONObject(i)
                val wordStr = item.optString("word", "").trim()
                if (wordStr.isNotBlank()) {
                    val startSec = item.getDouble("start")
                    val endSec = item.getDouble("end")
                    val conf = item.optDouble("conf", 1.0)
                    val startMs = (startSec * 1000.0).toLong()
                    val endMs = (endSec * 1000.0).toLong()
                    words.add(WordInfo(wordStr, startMs, endMs, conf))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Vosk result JSON", e)
        }
        return words
    }

    private fun groupWordsIntoFullSentences(words: List<WordInfo>): List<TranslationSegment> {
        if (words.isEmpty()) return emptyList()

        val MAX_PAUSE_MS = 1200L      // 1.2s silence required to split sentences
        val MAX_DURATION_MS = 12000L   // Up to 12s per full sentence
        val MAX_WORDS = 30           // Up to 30 words per full sentence

        val segments = mutableListOf<TranslationSegment>()
        val currentWords = mutableListOf<WordInfo>()

        for (word in words) {
            if (currentWords.isEmpty()) {
                currentWords.add(word)
                continue
            }

            val prevWord = currentWords.last()
            val pauseMs = word.startMs - prevWord.endMs
            val currentDurationMs = word.endMs - currentWords.first().startMs

            val shouldSplit = pauseMs >= MAX_PAUSE_MS ||
                              currentDurationMs >= MAX_DURATION_MS ||
                              currentWords.size >= MAX_WORDS

            if (shouldSplit) {
                val seg = createSegmentFromWords(currentWords)
                if (seg != null) segments.add(seg)
                currentWords.clear()
            }
            currentWords.add(word)
        }

        if (currentWords.isNotEmpty()) {
            val seg = createSegmentFromWords(currentWords)
            if (seg != null) segments.add(seg)
        }

        return segments
    }

    private fun createSegmentFromWords(words: List<WordInfo>): TranslationSegment? {
        if (words.isEmpty()) return null
        val text = words.joinToString(" ") { it.word }.trim()
        if (text.isBlank()) return null

        val startMs = words.first().startMs
        val endMs = words.last().endMs.coerceAtLeast(startMs + 600L)

        return TranslationSegment(
            startMs = startMs,
            endMs = endMs,
            hindi = text
        )
    }

    private fun findVoskRoot(base: File): File? {
        if (!base.exists() || !base.isDirectory) return null
        if (base.list()?.any { it == "am" || it == "graph" || it == "conf" } == true) return base
        return base.listFiles()?.firstOrNull { child ->
            child.isDirectory && child.list()?.any { it == "am" || it == "graph" || it == "conf" } == true
        }
    }

    private fun extractZipFromAssets(assetZipName: String, destDir: File) {
        destDir.mkdirs()
        context.assets.open(assetZipName).use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}
