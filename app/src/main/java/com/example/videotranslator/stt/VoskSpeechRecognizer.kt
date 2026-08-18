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
 * Robust Multi-Signal Substantive Vocabulary Validation:
 *  1. Probes speech across Hindi and English acoustic models.
 *  2. Evaluates substantive dictionary words (excluding 1-2 letter phonetic false positives).
 *  3. Decision boundaries:
 *     - Authentic Hindi dictionary presence (Devanagari ratio >= 18% & validCount >= 3) -> HINDI
 *     - Substantive English dictionary presence (ratio >= 30% & >= 2 multi-syllable content words) -> ENGLISH
 *     - Non-matching / Dravidian audio (neither Hindi nor English authentic) -> TELUGU
 */
class VoskSpeechRecognizer(private val context: Context) {

    private var hiModel: Model? = null
    private var enModel: Model? = null

    // Substantive English vocabulary (length >= 3, avoiding single/double letter noise tokens)
    private val englishSubstantiveVocabulary = setOf(
        // Function words & pronouns (3+ chars)
        "the", "and", "that", "have", "for", "not", "with", "you", "this", "but", "his", "from",
        "they", "say", "her", "she", "will", "one", "all", "would", "there", "their", "what",
        "out", "about", "who", "get", "which", "when", "make", "can", "like", "time", "just",
        "him", "know", "take", "people", "into", "year", "your", "good", "some", "could", "them",
        "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
        "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
        "even", "new", "want", "because", "any", "these", "give", "day", "most", "been",
        "has", "had", "did", "does", "are", "was", "were",
        // Common verbs
        "tell", "ask", "try", "need", "feel", "become", "leave", "put", "mean", "keep", "let", "begin",
        "seem", "help", "show", "hear", "play", "run", "move", "live", "believe", "hold", "bring", "happen",
        "must", "write", "provide", "sit", "stand", "lose", "pay", "meet", "include", "continue", "set",
        "learn", "change", "lead", "understand", "watch", "follow", "stop", "create", "speak", "read", "allow",
        "add", "spend", "grow", "open", "walk", "win", "offer", "remember", "love", "consider", "appear",
        "buy", "wait", "serve", "die", "send", "expect", "build", "stay", "fall", "cut", "reach", "kill",
        "remain", "suggest", "raise", "pass", "sell", "require", "report", "decide", "pull", "start",
        "develop", "shall", "might", "already", "still", "never", "very", "much", "many",
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
        "maybe", "perhaps", "please", "yes", "yeah", "okay", "sorry", "hello",
        "thanks", "thank", "welcome", "goodbye", "enough", "almost", "away", "down",
        "where", "why", "before", "between", "under", "since", "without", "however", "though",
        "through", "while", "against", "within", "along", "above", "near", "until",
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
    private val hindiVocabulary = setOf(
        // Postpositions & particles
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
        // Common verbs
        "करना", "करता", "करती", "करते", "करो", "किया", "करें", "करेंगे", "करूँगा", "करूंगा",
        "होना", "हूँ", "हूं", "हो", "हुआ", "हुई", "हुए",
        "जाना", "जाता", "जाती", "जाते", "जाओ", "गया", "गई", "गए", "जाएं",
        "आना", "आता", "आती", "आते", "आओ", "आया", "आई", "आए", "आइए", "आएं",
        "देना", "देता", "देती", "देते", "दो", "दिया", "दी", "दिए", "दें", "दीजिए",
        "लेना", "लेता", "लेती", "लेते", "लो", "लिया", "ली", "लिए", "लें", "लीजिए",
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
     * using substantive acoustic confidence and multi-syllable dictionary validation.
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

        // 2. Probe English model (requiring substantive 3+ letter words to avoid false positives)
        var enWords = emptyList<WordInfo>()
        var enAvgConf = 0.0
        var enScore = 0.0f
        var enValidSubstantiveCount = 0
        var enSubstantive4PlusCount = 0
        var enValidityRatio = 0f
        var enAuthenticScore = 0.0f
        val mEn = enModel
        if (mEn != null) {
            enWords = runVoskPass(mEn, probePcm)
            enAvgConf = if (enWords.isNotEmpty()) enWords.map { it.confidence }.average() else 0.0
            enScore = (enAvgConf * (enWords.size / durationSec)).toFloat()

            enValidSubstantiveCount = enWords.count { wordInfo ->
                val norm = wordInfo.word.trim().lowercase().replace(Regex("[!?,.–—\\-]"), "")
                norm.length >= 3 && englishSubstantiveVocabulary.contains(norm)
            }
            enSubstantive4PlusCount = enWords.count { wordInfo ->
                val norm = wordInfo.word.trim().lowercase().replace(Regex("[!?,.–—\\-]"), "")
                norm.length >= 4 && englishSubstantiveVocabulary.contains(norm)
            }
            enValidityRatio = if (enWords.isNotEmpty()) enValidSubstantiveCount.toFloat() / enWords.size else 0f
            enAuthenticScore = enScore * enValidityRatio
        }

        DiagnosticLogger.log(TAG,
            "REAL ACOUSTIC STT DUAL-PROBE & SUBSTANTIVE VOCAB VALIDATION (${"%.1f".format(durationSec)}s):\n" +
            "   Hindi model probe:   ${hiWords.size} words (${hiValidCount} valid dict, ${"%.1f".format(hiValidityRatio*100)}%), avgConf=${"%.2f".format(hiAvgConf)} -> HINDI authenticScore=${"%.3f".format(hiAuthenticScore)}\n" +
            "   English model probe: ${enWords.size} words (${enValidSubstantiveCount} valid 3+ char dict, ${enSubstantive4PlusCount} 4+ char, ${"%.1f".format(enValidityRatio*100)}%), avgConf=${"%.2f".format(enAvgConf)} -> ENGLISH authenticScore=${"%.3f".format(enAuthenticScore)}"
        )

        val isAuthenticHindi = (hiAuthenticScore >= 0.030f && hiValidityRatio >= 0.16f && hiValidCount >= 3) ||
                               (hiValidityRatio >= 0.25f && hiValidCount >= 2)

        val isAuthenticEnglish = mEn != null &&
                                 enAuthenticScore >= 0.065f &&
                                 enValidityRatio >= 0.28f &&
                                 enSubstantive4PlusCount >= 2 &&
                                 enValidSubstantiveCount >= 3

        val detected = when {
            isAuthenticHindi && !isAuthenticEnglish -> Language.HINDI
            isAuthenticEnglish && !isAuthenticHindi -> Language.ENGLISH
            isAuthenticHindi && isAuthenticEnglish -> {
                if (hiAuthenticScore >= enAuthenticScore) Language.HINDI else Language.ENGLISH
            }
            // If neither Hindi nor English is authentic, speech is Dravidian / Non-Hindi / Non-English -> TELUGU!
            else -> Language.TELUGU
        }

        DiagnosticLogger.log(TAG, "▶ PROBED SOURCE LANGUAGE DETECTED: $detected (Authentic: HI=$isAuthenticHindi, EN=$isAuthenticEnglish)")
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
        val recognizer = Recognizer(m, sampleRate)
        recognizer.setWords(true)

        val words = mutableListOf<WordInfo>()
        var offset = 0

        while (offset < pcm.size) {
            val end = (offset + chunkSize).coerceAtMost(pcm.size)
            val chunk = pcm.copyOfRange(offset, end)
            if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                val resJson = recognizer.result
                words.addAll(parseWordInfos(resJson))
            }
            offset = end
        }

        val finalJson = recognizer.finalResult
        words.addAll(parseWordInfos(finalJson))
        recognizer.close()

        return words
    }

    private fun parseWordInfos(jsonStr: String): List<WordInfo> {
        val words = mutableListOf<WordInfo>()
        try {
            val obj = JSONObject(jsonStr)
            if (obj.has("result")) {
                val array = obj.getJSONArray("result")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val word = item.optString("word", "").trim()
                    val startSec = item.optDouble("start", 0.0)
                    val endSec = item.optDouble("end", 0.0)
                    val conf = item.optDouble("conf", 1.0)
                    if (word.isNotBlank()) {
                        words.add(
                            WordInfo(
                                word = word,
                                startMs = (startSec * 1000).toLong(),
                                endMs = (endSec * 1000).toLong(),
                                confidence = conf
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk JSON result: ${e.localizedMessage}")
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
            hindi = text,
            sourceText = text
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
                    val file = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { out ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (zip.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}
