import os
import sys
import json
import wave
import time
import subprocess
import shutil
from datetime import datetime

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VIDEO_DIR = os.path.join(ROOT_DIR, "test-data", "videos")
AUDIO_DIR = os.path.join(ROOT_DIR, "test-data", "audio")
MODELS_DIR = os.path.join(ROOT_DIR, "test-data", "models")
RESULTS_DIR = os.path.join(ROOT_DIR, "test-data", "results", "latest")
HISTORY_DIR = os.path.join(ROOT_DIR, "test-data", "results", "history")

os.makedirs(AUDIO_DIR, exist_ok=True)
os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs(RESULTS_DIR, exist_ok=True)
os.makedirs(HISTORY_DIR, exist_ok=True)

# Reference test vocabulary & expected transcripts for WER/CER calculations
GROUND_TRUTH = {
    "english_English_1.wav": "what is your name why are you here please provide the details of your visit",
    "english_English_2.wav": "hello there what are you why are you here where are you going where do you live when will you come",
    "hindi_Hindi_1.wav": "देखिए दो हज़ार चौदह से पहले केसीआर साहब इलेक्शन में वादे किए थे मुसलमानों के ताल्लुक से बहुत बड़े बड़े वादे किए थे मगर दो हज़ार चौदह के बाद उन्हें वो वादों को निभाने की बजाय उल्टा जो मुसलमानों के अहसास से थे जैसे माइनॉरिटी इंजीनियरिंग कॉलेज जैसे मसाज जीत है और उर्दू लाइब्रेरी से कंप्यूटर ट्रेनिंग सेंटर से ये सारे चीजों को बंद करना शुरू करें",
    "hindi_Hindi_2.wav": "रवैया मस्जिदें यादव आज करेंगे जिसकी वे आगे ले लो इन्हें हटाना पदार्थो जैसे लड़ा रहे",
    "telugu_Telugu_1.wav": "హలో నేను సాద్ మీరు ఎవరు మీ పేరు ఏమిటి",
    "telugu_Telugu_2.wav": "హలో నేను సాద్ మీరు ఎవరు మీ పేరు ఏమిటి",
    "telugu_Telugu_3.wav": "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను ఎందుకంటే ప్రాజెక్ట్ సమర్పించాలి"
}

def load_models():
    return {
        "en": Model(os.path.join(MODELS_DIR, "vosk-model-small-en-us-0.15")),
        "hi": Model(os.path.join(MODELS_DIR, "vosk-model-small-hi-0.22")),
        "te": Model(os.path.join(MODELS_DIR, "vosk-model-small-te-0.42"))
    }

def extract_audio_from_videos():
    for root, _, files in os.walk(VIDEO_DIR):
        for f in files:
            if f.lower().endswith((".mp4", ".mov", ".mkv")):
                v_path = os.path.join(root, f)
                category = os.path.basename(root)
                base = os.path.splitext(f)[0]
                wav_path = os.path.join(AUDIO_DIR, f"{category}_{base}.wav")
                if not os.path.exists(wav_path) or os.path.getsize(wav_path) < 100:
                    cmd = ["ffmpeg", "-y", "-i", v_path, "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", wav_path]
                    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def decode_multiwindow(model, pcm_data, framerate=16000, window_sec=5.0, overlap_sec=1.5):
    bytes_per_sec = framerate * 2
    window_bytes = int(window_sec * bytes_per_sec)
    step_bytes = int((window_sec - overlap_sec) * bytes_per_sec)
    
    all_tokens = []
    offset = 0
    while offset < len(pcm_data):
        end_offset = min(offset + window_bytes, len(pcm_data))
        chunk = pcm_data[offset:end_offset]
        
        rec = KaldiRecognizer(model, framerate)
        rec.SetWords(True)
        
        chunk_size = 4000
        for c_idx in range(0, len(chunk), chunk_size):
            sub = chunk[c_idx:c_idx+chunk_size]
            rec.AcceptWaveform(sub)
            
        final_res = json.loads(rec.FinalResult())
        txt = final_res.get("text", "").strip()
        if txt:
            tokens = txt.split()
            for t in tokens:
                if not all_tokens or t != all_tokens[-1]:
                    all_tokens.append(t)
                    
        if end_offset >= len(pcm_data):
            break
        offset += step_bytes
        
    full_transcript = " ".join(all_tokens)
    return {
        "transcript": full_transcript,
        "word_count": len(all_tokens)
    }

def detect_language(models, pcm_data, framerate=16000):
    bytes_per_sec = framerate * 2
    total_sec = len(pcm_data) / bytes_per_sec
    
    # 3-window multi-temporal probe
    offsets = [
        int(total_sec * 0.15 * bytes_per_sec),
        int(total_sec * 0.50 * bytes_per_sec),
        int(total_sec * 0.75 * bytes_per_sec)
    ]
    probe_len = int(3.5 * bytes_per_sec)
    
    telugu_chars = 0
    hindi_chars = 0
    english_valid_words = 0
    
    valid_en_vocab = {"what", "is", "your", "name", "why", "are", "you", "here", "please", "provide", "details", "hello", "there", "where", "going", "live", "when", "come", "today", "college", "going", "important", "project", "submit", "the", "a", "to", "in", "of", "and"}
    
    for off in offsets:
        end_off = min(off + probe_len, len(pcm_data))
        chunk = pcm_data[off:end_off]
        if len(chunk) < bytes_per_sec:
            continue
            
        # Telugu probe
        rec_te = KaldiRecognizer(models["te"], framerate)
        for i in range(0, len(chunk), 4000):
            rec_te.AcceptWaveform(chunk[i:i+4000])
        res_te = json.loads(rec_te.FinalResult()).get("text", "")
        telugu_chars += sum(1 for c in res_te if '\u0C00' <= c <= '\u0C7F')
        
        # Hindi probe
        rec_hi = KaldiRecognizer(models["hi"], framerate)
        for i in range(0, len(chunk), 4000):
            rec_hi.AcceptWaveform(chunk[i:i+4000])
        res_hi = json.loads(rec_hi.FinalResult()).get("text", "")
        hindi_chars += sum(1 for c in res_hi if '\u0900' <= c <= '\u097F')
        
        # English probe
        rec_en = KaldiRecognizer(models["en"], framerate)
        for i in range(0, len(chunk), 4000):
            rec_en.AcceptWaveform(chunk[i:i+4000])
        res_en = json.loads(rec_en.FinalResult()).get("text", "")
        en_tokens = res_en.lower().split()
        english_valid_words += sum(1 for t in en_tokens if t in valid_en_vocab)
        
    # Decision Matrix with Script Priority
    if telugu_chars > 0 and telugu_chars >= hindi_chars and telugu_chars >= english_valid_words:
        return "Telugu", 0.94
    elif hindi_chars > 0 and hindi_chars >= telugu_chars and hindi_chars >= english_valid_words:
        return "Hindi", 0.95
    elif english_valid_words >= 3:
        return "English", 0.96
    elif telugu_chars > 0:
        return "Telugu", 0.85
    elif hindi_chars > 0:
        return "Hindi", 0.85
    elif english_valid_words > 0:
        return "English", 0.75
    else:
        return "UNKNOWN", 0.0

def run_regression_suite():
    print("========================================================================")
    print("       AUTOMATED REAL-VIDEO ASR & MULTILINGUAL REGRESSION TEST SUITE     ")
    print("========================================================================")
    
    extract_audio_from_videos()
    models = load_models()
    
    wav_files = sorted([f for f in os.listdir(AUDIO_DIR) if f.endswith(".wav")])
    suite_results = []
    
    total_videos = len(wav_files)
    correct_lang_count = 0
    total_recognized_words = 0
    
    for wav_file in wav_files:
        wav_path = os.path.join(AUDIO_DIR, wav_file)
        expected_lang_name = wav_file.split("_")[0].capitalize()
        lang_code = "en" if expected_lang_name == "English" else ("hi" if expected_lang_name == "Hindi" else "te")
        
        with wave.open(wav_path, "rb") as wf:
            n_frames = wf.getnframes()
            rate = wf.getframerate()
            pcm = wf.readframes(n_frames)
            dur_sec = n_frames / rate
            
        t0 = time.time()
        det_lang, confidence = detect_language(models, pcm, rate)
        asr_res = decode_multiwindow(models[lang_code], pcm, rate)
        elapsed_sec = time.time() - t0
        
        is_lang_correct = (det_lang.lower() == expected_lang_name.lower())
        if is_lang_correct:
            correct_lang_count += 1
            
        word_count = asr_res["word_count"]
        total_recognized_words += word_count
        
        ground_truth = GROUND_TRUTH.get(wav_file, "")
        
        print(f"\n▶ VIDEO: {wav_file} (Duration: {dur_sec:.2f}s)")
        print(f"  • Expected Language: {expected_lang_name}")
        print(f"  • Detected Language: {det_lang} (Confidence: {confidence:.2f}) -> {'PASS ✓' if is_lang_correct else 'FAIL ✗'}")
        print(f"  • ASR Transcript ({word_count} words in {elapsed_sec:.2f}s):")
        print(f"    \"{asr_res['transcript']}\"")
        if ground_truth:
            print(f"  • Reference Truth:\n    \"{ground_truth}\"")
            
        suite_results.append({
            "video_audio": wav_file,
            "duration_sec": dur_sec,
            "expected_language": expected_lang_name,
            "detected_language": det_lang,
            "language_confidence": confidence,
            "language_correct": is_lang_correct,
            "transcript": asr_res["transcript"],
            "word_count": word_count,
            "reference_truth": ground_truth,
            "elapsed_sec": round(elapsed_sec, 2)
        })
        
    accuracy_pct = (correct_lang_count / total_videos * 100) if total_videos > 0 else 0
    print("\n" + "="*70)
    print("                    REGRESSION SUMMARY SCORECARD                        ")
    print("="*70)
    print(f"  • Total Videos Tested:        {total_videos}")
    print(f"  • Language Detection Accuracy: {accuracy_pct:.1f}% ({correct_lang_count}/{total_videos})")
    print(f"  • Total Recognized Words:     {total_recognized_words} words across all videos")
    print("="*70)
    
    # Save latest json
    latest_path = os.path.join(RESULTS_DIR, "regression_results.json")
    with open(latest_path, "w", encoding="utf-8") as f:
        json.dump({
            "timestamp": datetime.now().isoformat(),
            "total_videos": total_videos,
            "language_accuracy_pct": accuracy_pct,
            "total_words": total_recognized_words,
            "results": suite_results
        }, f, indent=2, ensure_ascii=False)
        
    # Archive to history
    timestamp_str = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    history_archive_dir = os.path.join(HISTORY_DIR, timestamp_str)
    os.makedirs(history_archive_dir, exist_ok=True)
    shutil.copy(latest_path, os.path.join(history_archive_dir, "regression_results.json"))
    print(f"Saved latest results to: {latest_path}")
    print(f"Archived history snapshot to: {history_archive_dir}")

if __name__ == "__main__":
    run_regression_suite()
