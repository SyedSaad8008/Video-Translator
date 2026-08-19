import re

def score_language_evidence(text: str, mel_low_ratio: float, mel_mid_ratio: float, mel_high_ratio: float):
    """
    Combines Unicode script character counting + acoustic spectral formant ratios.
    ZERO silent fallbacks to Hindi.
    """
    telugu_chars = len(re.findall(r'[\u0C00-\u0C7F]', text))
    hindi_chars = len(re.findall(r'[\u0900-\u097F]', text))
    english_words = len(re.findall(r'\b[A-Za-z]{2,}\b', text))

    print(f"Text: \"{text}\"")
    print(f"Counts -> Telugu chars: {telugu_chars}, Hindi chars: {hindi_chars}, English words: {english_words}")

    # Primary signal: Decoded script characters
    if telugu_chars > 0 and telugu_chars >= hindi_chars and telugu_chars >= english_words:
        conf = min(0.98, 0.75 + telugu_chars * 0.03)
        return "TELUGU", conf
    if hindi_chars > 0 and hindi_chars >= telugu_chars and hindi_chars >= english_words:
        conf = min(0.98, 0.75 + hindi_chars * 0.03)
        return "HINDI", conf
    if english_words > 0 and english_words >= telugu_chars and english_words >= hindi_chars:
        conf = min(0.98, 0.75 + english_words * 0.05)
        return "ENGLISH", conf

    # Secondary signal: Acoustic Formant Ratios (> 150 Hz)
    # Telugu: High retroflex mid-band (1.8-3.4 kHz)
    # English: High sibilant high-band (3.5-7.0 kHz)
    # Hindi: Balanced low-mid band
    if mel_high_ratio >= 0.28:
        return "ENGLISH", round(min(0.95, mel_high_ratio / 0.35), 2)
    if mel_mid_ratio >= 0.38 or mel_mid_ratio > mel_low_ratio:
        return "TELUGU", round(min(0.95, mel_mid_ratio / 0.42), 2)
    if mel_low_ratio >= 0.50:
        return "HINDI", round(min(0.95, mel_low_ratio / 0.55), 2)

    return "UNKNOWN", 0.30

# Test 1: Telugu decoded text
lang1, conf1 = score_language_evidence("నేను ఈరోజు మా కాలేజీకి వెళ్తున్నాను", 0.42, 0.45, 0.13)
print(f"Test 1 (Telugu text) -> Detected: {lang1}, Conf: {conf1}")
assert lang1 == "TELUGU"

# Test 2: Hindi decoded text
lang2, conf2 = score_language_evidence("मैं आज कॉलेज जा रहा हूँ", 0.52, 0.35, 0.13)
print(f"Test 2 (Hindi text) -> Detected: {lang2}, Conf: {conf2}")
assert lang2 == "HINDI"

# Test 3: English decoded text
lang3, conf3 = score_language_evidence("I am going to my college today", 0.32, 0.33, 0.35)
print(f"Test 3 (English text) -> Detected: {lang3}, Conf: {conf3}")
assert lang3 == "ENGLISH"

# Test 4: Ambiguous/Empty acoustic probe with high mid-ratio
lang4, conf4 = score_language_evidence("", 0.36, 0.46, 0.18)
print(f"Test 4 (Telugu acoustic probe) -> Detected: {lang4}, Conf: {conf4}")
assert lang4 == "TELUGU"

print("✓ All language detection tests passed!")
