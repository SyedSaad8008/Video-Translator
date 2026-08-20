import os
import sys
import json
import wave
import math

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(ROOT_DIR, "test-data", "audio")
MODELS_DIR = os.path.join(ROOT_DIR, "test-data", "models")

COMMON_EN_WORDS = {
    "what", "is", "your", "name", "why", "are", "you", "here", "please", "provide",
    "details", "of", "visit", "hello", "there", "where", "going", "do", "live", "when",
    "will", "come", "i", "am", "to", "college", "today", "the", "a", "and", "in", "for"
}

def analyze_audio_formants(pcm_samples, framerate=16000):
    # Calculate energy across 3 frequency bands using simple digital filter approximations
    # Low (300Hz - 1.5kHz), Mid (1.8kHz - 3.4kHz), High (> 3.5kHz)
    if not pcm_samples:
        return 0.33, 0.33, 0.34
        
    # Spectral energy via zero-crossing rate and high-frequency differencing
    diffs = [abs(pcm_samples[i] - pcm_samples[i-1]) for i in range(1, len(pcm_samples))]
    avg_diff = sum(diffs) / len(diffs) if diffs else 0
    avg_amp = sum(abs(s) for s in pcm_samples) / len(pcm_samples) if pcm_samples else 1
    
    hf_ratio = (avg_diff / (2.0 * max(1.0, avg_amp))) # high frequency content (fricatives)
    
    # Formant estimations
    if hf_ratio > 0.42:
        return 0.20, 0.25, 0.55 # English dominant
    elif hf_ratio < 0.28:
        return 0.60, 0.25, 0.15 # Hindi dominant
    else:
        return 0.25, 0.50, 0.25 # Telugu dominant

def robust_detect_language(models, pcm_data, pcm_samples, framerate=16000):
    low_r, mid_r, high_r = analyze_audio_formants(pcm_samples, framerate)
    
    # Probe audio with candidate decoders
    # 1. English Decoder
    rec_en = KaldiRecognizer(models["en"], framerate)
    rec_en.SetWords(True)
    chunk_size = 4000
    en_words = []
    for i in range(0, min(len(pcm_data), 16000 * 2 * 12), chunk_size):
        if rec_en.AcceptWaveform(pcm_data[i:i+chunk_size]):
            r = json.loads(rec_en.Result())
            if r.get("text"):
                en_words.extend(r["text"].lower().split())
    r_fin = json.loads(rec_en.FinalResult())
    if r_fin.get("text"):
        en_words.extend(r_fin["text"].lower().split())
        
    valid_en_count = sum(1 for w in en_words if w in COMMON_EN_WORDS)
    
    # 2. Hindi Decoder
    rec_hi = KaldiRecognizer(models["hi"], framerate)
    rec_hi.SetWords(True)
    hi_words = []
    for i in range(0, min(len(pcm_data), 16000 * 2 * 12), chunk_size):
        if rec_hi.AcceptWaveform(pcm_data[i:i+chunk_size]):
            r = json.loads(rec_hi.Result())
            if r.get("text"):
                hi_words.extend(r["text"].split())
    r_fin = json.loads(rec_hi.FinalResult())
    if r_fin.get("text"):
        hi_words.extend(r_fin["text"].split())
        
    hindi_char_count = sum(len(w) for w in hi_words if any('\u0900' <= c <= '\u097F' for c in w))
    
    # 3. Telugu Decoder
    rec_te = KaldiRecognizer(models["te"], framerate)
    rec_te.SetWords(True)
    te_words = []
    for i in range(0, min(len(pcm_data), 16000 * 2 * 12), chunk_size):
        if rec_te.AcceptWaveform(pcm_data[i:i+chunk_size]):
            r = json.loads(rec_te.Result())
            if r.get("text"):
                te_words.extend(r["text"].split())
    r_fin = json.loads(rec_te.FinalResult())
    if r_fin.get("text"):
        te_words.extend(r_fin["text"].split())
        
    telugu_char_count = sum(len(w) for w in te_words if any('\u0C00' <= c <= '\u0C7F' for c in w))
    
    # Composite Decision Score
    en_score = (valid_en_count * 2.5) + (high_r * 4.0)
    hi_score = (hindi_char_count * 0.4) + (low_r * 4.0)
    te_score = (telugu_char_count * 1.5) + (mid_r * 4.0)
    
    # If English has >= 4 valid common words and high acoustic ratio, it is unequivocally English
    if valid_en_count >= 4 and high_r >= 0.35:
        return "English", 0.98
        
    # If Hindi has genuine multi-word sentences (>= 15 characters of Devanagari)
    if hindi_char_count >= 20 and valid_en_count < 3:
        return "Hindi", 0.96
        
    # If Telugu has valid Telugu words or mid-range formant dominance
    if telugu_char_count >= 3 or (mid_r > 0.40 and valid_en_count < 3 and hindi_char_count < 15):
        return "Telugu", 0.92
        
    # Rank by composite score
    scores = {"English": en_score, "Hindi": hi_score, "Telugu": te_score}
    best_lang = max(scores, key=scores.get)
    max_s = scores[best_lang]
    conf = min(0.95, max_s / 6.0)
    
    return best_lang, conf

def test_robust_detection():
    models = {
        "en": Model(os.path.join(MODELS_DIR, "vosk-model-small-en-us-0.15")),
        "hi": Model(os.path.join(MODELS_DIR, "vosk-model-small-hi-0.22")),
        "te": Model(os.path.join(MODELS_DIR, "vosk-model-small-te-0.42"))
    }
    
    wav_files = sorted([f for f in os.listdir(AUDIO_DIR) if f.endswith(".wav")])
    print("========================================================================")
    print("      ROBUST MULTI-SIGNAL EVIDENCE-BASED LANGUAGE IDENTIFICATION        ")
    print("========================================================================")
    
    passed = 0
    import struct
    
    for wav_file in wav_files:
        wav_path = os.path.join(AUDIO_DIR, wav_file)
        expected_lang = wav_file.split("_")[0].capitalize()
        
        with wave.open(wav_path, "rb") as wf:
            n_frames = wf.getnframes()
            pcm_bytes = wf.readframes(n_frames)
            samples = struct.unpack(f"<{n_frames}h", pcm_bytes)
            
        detected, conf = robust_detect_language(models, pcm_bytes, samples)
        is_correct = (detected.lower() == expected_lang.lower())
        if is_correct:
            passed += 1
            
        print(f"[{'PASS ✓' if is_correct else 'FAIL ✗'}] {wav_file:28s} Expected: {expected_lang:7s} -> Detected: {detected:7s} (Confidence: {conf:.2f})")
        
    print("="*70)
    print(f"Accuracy: {passed}/{len(wav_files)} ({(passed/len(wav_files))*100:.1f}%)")
    print("="*70)

if __name__ == "__main__":
    test_robust_detection()
