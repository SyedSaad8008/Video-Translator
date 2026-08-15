package com.example.videotranslator.translation

import com.example.videotranslator.util.DiagnosticLogger

private const val TAG = "DisfluencyCleaner"

/**
 * Hindi Speech Disfluency Cleanup Engine.
 *
 * Pre-processes coarse STT transcript text before passing to ML Kit NMT:
 *  1. **Stutter & Word Repetition Removal**: Collapses immediate word repetitions
 *     (e.g., "मैं मैं चाहता हूँ" → "मैं चाहता हूँ", "वह वह गया" → "वह गया").
 *  2. **Disfluent Filler Removal**: Cleans meaningless hesitation markers
 *     (e.g., "उम्म", "अहह", "मतलब की", "यानी कि") when used disfluently inside a sentence.
 *  3. **False-Start Resolution**: Resolves false starts and self-correction fragments
 *     (e.g., "मैं गया— मेरा मतलब मैं जा रहा था" → "मैं जा रहा था").
 *  4. **Expressive Interjection Protection**: Explicitly preserves valid short reactions
 *     (e.g., "वाह", "अरे!", "ओह", "अच्छा", "हाँ", "वाह यार") when acting as standalone reaction units.
 */
class DisfluencyCleaner {

    // Natural expressive interjections that MUST be preserved as valid speech units
    private val expressiveInterjections = setOf(
        "वाह", "अरे", "ओह", "अच्छा", "हाँ", "अहा", "शाबाश", "बिल्कुल", "ज़रूर", "अरे वाह", "ओहो"
    )

    // Hesitation fillers & disfluency markers
    private val hesitationFillers = listOf(
        "उम्म", "अहह", "अं", "मम्म", "मतलब कि", "यानी कि", "जैसे कि"
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
        val cleanPunctuation = trimmed.replace(Regex("[!?,.–—-]"), "").trim()

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

        // 2. Resolve false starts / self-corrections (e.g., "X— मेरा मतलब Y" or "X— यानी Y")
        val falseStartRegex = Regex("(?<fragment>[\\p{L}\\s]{2,20})[—–-]\\s*(?:मेरा मतलब|यानी|कि)\\s+(?<correction>[\\p{L}\\s]+)")
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
            val fillerRegex = Regex("(?<=\\s|^)$filler(?=\\s|$)")
            if (fillerRegex.containsMatchIn(processing)) {
                disfluenciesFound.add("Filler: '$filler'")
                processing = fillerRegex.replace(processing, " ")
            }
        }

        // 4. Remove immediate stutters / duplicate word repetitions (e.g. "मैं मैं", "वह वह")
        val wordTokens = processing.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanedTokens = mutableListOf<String>()

        var i = 0
        while (i < wordTokens.size) {
            val currentWord = wordTokens[i]
            val normalizedCurrent = currentWord.replace(Regex("[!?,.–—-]"), "")

            if (cleanedTokens.isNotEmpty()) {
                val previousWord = cleanedTokens.last()
                val normalizedPrev = previousWord.replace(Regex("[!?,.–—-]"), "")

                // Stutter detection: same word repeated sequentially (and not an expressive interjection like "हाँ हाँ")
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

        val cleanedFinal = cleanedTokens.joinToString(" ").replace(Regex("\\s+"), " ").trim()

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
