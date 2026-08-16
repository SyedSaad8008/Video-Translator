package com.example.videotranslator.translation

import com.example.videotranslator.util.DiagnosticLogger

private const val TAG = "DisfluencyCleaner"

/**
 * Multilingual Speech Disfluency Cleanup Engine (Hindi & English).
 *
 * Pre-processes coarse STT transcript text before passing to ML Kit NMT:
 *  1. **Stutter & Word Repetition Removal**: Collapses immediate word repetitions
 *     (e.g., "मैं मैं चाहता हूँ" → "मैं चाहता हूँ", "the the book" → "the book").
 *  2. **Disfluent Filler Removal**: Cleans meaningless hesitation markers in Hindi
 *     ("उम्म", "अहह", "मतलब कि", "यानी कि") and English ("um", "uh", "umm", "uhh", "like", "you know", "I mean", "hmm", "er")
 *     when used disfluently inside a sentence.
 *  3. **False-Start Resolution**: Resolves false starts and self-correction fragments
 *     (e.g., "मैं गया— मेरा मतलब मैं जा रहा था" → "मैं जा रहा था", "I went— I mean I was going" → "I was going").
 *  4. **Expressive Interjection Protection**: Explicitly preserves valid short reactions
 *     in Hindi ("वाह", "अरे", "ओह", "अच्छा", "हाँ") and English ("wow", "oh", "hey", "yeah", "ouch", "ah", "bravo", "well")
 *     when acting as standalone reaction units.
 *  5. **Clean Formatting & Punctuation**: Strips leftover leading punctuation and ensures capitalized sentence starts.
 */
class DisfluencyCleaner {

    // Natural expressive interjections that MUST be preserved as valid speech units
    private val expressiveInterjections = setOf(
        // Hindi
        "वाह", "अरे", "ओह", "अच्छा", "हाँ", "अहा", "शाबाश", "बिल्कुल", "ज़रूर", "अरे वाह", "ओहो",
        // English
        "wow", "oh", "hey", "yeah", "ouch", "ah", "bravo", "well", "yes", "no", "oops", "hurray"
    )

    // Hesitation fillers & disfluency markers
    private val hesitationFillers = listOf(
        // Hindi
        "उम्म", "अहह", "अं", "मम्म", "मतलब कि", "यानी कि", "जैसे कि",
        // English
        "um", "uh", "umm", "uhh", "like", "you know", "i mean", "hmm", "er"
    )

    data class CleanupResult(
        val originalText: String,
        val cleanedText: String,
        val detectedDisfluencies: List<String>,
        val preservedInterjections: List<String>
    )

    fun clean(text: String): CleanupResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return CleanupResult(text, text, emptyList(), emptyList())
        }

        val disfluenciesFound = mutableListOf<String>()
        val interjectionsFound = mutableListOf<String>()

        // 1. Check if the segment is a standalone expressive interjection
        val words = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanPunctuation = trimmed.replace(Regex("[!?,.–—\\-]"), "").lowercase().trim()

        if (words.size <= 2 && expressiveInterjections.any { cleanPunctuation.contains(it) }) {
            interjectionsFound.add(trimmed)
            DiagnosticLogger.log(TAG, "PRESERVED EXPRESSIVE INTERJECTION: \"$trimmed\"")
            return CleanupResult(
                originalText = text,
                cleanedText = trimmed,
                detectedDisfluencies = emptyList(),
                preservedInterjections = interjectionsFound
            )
        }

        var processing = trimmed

        // 2. Resolve false starts / self-corrections (Hindi: "X— मेरा मतलब Y", English: "X— I mean Y")
        val falseStartRegex = Regex("(?<fragment>[\\p{L}\\s]{2,20})[—–\\-]\\s*(?:मेरा मतलब|यानी|कि|i mean|you know)\\s+(?<correction>[\\p{L}\\s]+)", RegexOption.IGNORE_CASE)
        if (falseStartRegex.containsMatchIn(processing)) {
            falseStartRegex.findAll(processing).forEach { match ->
                disfluenciesFound.add("False start fragment: '${match.value}'")
            }
            processing = falseStartRegex.replace(processing) { matchResult ->
                matchResult.groups["correction"]?.value?.trim() ?: matchResult.value
            }
        }

        // 3. Remove disfluent hesitation fillers in full sentences
        for (filler in hesitationFillers) {
            val fillerRegex = Regex("(?i)\\b${Regex.escape(filler)}\\b")
            if (fillerRegex.containsMatchIn(processing)) {
                disfluenciesFound.add("Filler: '$filler'")
                processing = fillerRegex.replace(processing, " ")
            }
        }

        // 4. Remove immediate stutters / duplicate word repetitions (e.g. "मैं मैं", "the the")
        val wordTokens = processing.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanedTokens = mutableListOf<String>()

        var i = 0
        while (i < wordTokens.size) {
            val currentWord = wordTokens[i]
            val normalizedCurrent = currentWord.replace(Regex("[!?,.–—\\-]"), "").lowercase()

            if (cleanedTokens.isNotEmpty()) {
                val previousWord = cleanedTokens.last()
                val normalizedPrev = previousWord.replace(Regex("[!?,.–—\\-]"), "").lowercase()

                // Stutter detection: same word repeated sequentially (and not an expressive interjection like "yeah yeah")
                if (normalizedCurrent.equals(normalizedPrev, ignoreCase = true) &&
                    !expressiveInterjections.contains(normalizedCurrent)
                ) {
                    disfluenciesFound.add("Stutter repeat: '$currentWord'")
                    i++
                    continue
                }
            }
            cleanedTokens.add(currentWord)
            i++
        }

        val cleanedFinal = cleanedTokens.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[!?,.–—\\-\\s]+"), "")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .trim()

        if (disfluenciesFound.isNotEmpty()) {
            DiagnosticLogger.log(TAG, "DISFLUENCY CLEANUP: \"$trimmed\" → \"$cleanedFinal\" (Removed: ${disfluenciesFound.joinToString(", ")})")
        }

        return CleanupResult(
            originalText = text,
            cleanedText = if (cleanedFinal.isNotBlank()) cleanedFinal else trimmed,
            detectedDisfluencies = disfluenciesFound,
            preservedInterjections = interjectionsFound
        )
    }
}
