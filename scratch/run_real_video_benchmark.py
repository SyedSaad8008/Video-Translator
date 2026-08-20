import os
import sys
import json
import wave
import time

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1) # Suppress Kaldi verbose log spam

MODELS_DIR = "test-data/models"

def load_models():
    print("Loading on-device ASR models...")
    models = {
        "en": Model(os.path.join(MODELS_DIR, "vosk-model-small-en-us-0.15")),
        "hi": Model(os.path.join(MODELS_DIR, "vosk-model-small-hi-0.22")),
        "te": Model(os.path.join(MODELS_DIR, "vosk-model-small-te-0.42"))
    }
    print("All 3 models loaded successfully.")
    return models

def decode_audio(model, pcm_data, framerate=16000):
    rec = KaldiRecognizer(model, framerate)
    rec.SetWords(True)
    
    chunk_size = 4000
    all_results = []
    text_chunks = []
    
    for i in range(0, len(pcm_data), chunk_size):
        chunk = pcm_data[i:i+chunk_size]
        if rec.AcceptWaveform(chunk):
            res = json.loads(rec.Result())
            txt = res.get("text", "").strip()
            if txt:
                text_chunks.append(txt)
                all_results.append(res)
                
    final_res = json.loads(rec.FinalResult())
    final_txt = final_res.get("text", "").strip()
    if final_txt:
        text_chunks.append(final_txt)
        all_results.append(final_res)
        
    full_transcript = " ".join(text_chunks)
    return {
        "transcript": full_transcript,
        "word_count": len(full_transcript.split()) if full_transcript else 0,
        "results": all_results
    }

def run_evaluation():
    models = load_models()
    audio_dir = "test-data/audio"
    results_dir = "test-data/results/latest"
    os.makedirs(results_dir, exist_ok=True)
    
    wav_files = sorted([f for f in os.listdir(audio_dir) if f.endswith(".wav")])
    summary_report = []

    print("\n" + "="*70)
    print("      REAL VIDEO AUDIO ASR & MULTILINGUAL BENCHMARK REPORT")
    print("="*70)

    for wav_file in wav_files:
        wav_path = os.path.join(audio_dir, wav_file)
        expected_lang = wav_file.split("_")[0].lower() # 'english', 'hindi', 'telugu'
        
        with wave.open(wav_path, "rb") as wf:
            n_frames = wf.getnframes()
            rate = wf.getframerate()
            pcm = wf.readframes(n_frames)
            dur_sec = n_frames / rate

        print(f"\n▶ TEST AUDIO: {wav_file} (Duration: {dur_sec:.2f}s | Expected: {expected_lang.upper()})")
        
        # Test against all 3 models to observe cross-language performance & detection
        model_results = {}
        for l_code, l_name in [("en", "English"), ("hi", "Hindi"), ("te", "Telugu")]:
            t0 = time.time()
            res = decode_audio(models[l_code], pcm, rate)
            elapsed = time.time() - t0
            model_results[l_name] = {
                "transcript": res["transcript"],
                "word_count": res["word_count"],
                "elapsed_sec": round(elapsed, 2)
            }
            print(f"  [{l_name:7s} ASR]: {res['word_count']:2d} words in {elapsed:4.2f}s -> \"{res['transcript']}\"")
        
        # Detect language based on word count & confidence across the 3 models
        detected_lang = max(model_results, key=lambda k: model_results[k]["word_count"])
        if model_results[detected_lang]["word_count"] == 0:
            detected_lang = "UNKNOWN"
            
        print(f"  => AUTOMATIC LANGUAGE IDENTIFIED: {detected_lang.upper()} (Expected: {expected_lang.upper()})")
        if detected_lang.lower() == expected_lang:
            print("  => STATUS: ACCURATE DETECTION ✓")
        else:
            print("  => STATUS: MISMATCH ✗")

        summary_report.append({
            "audio": wav_file,
            "duration_sec": dur_sec,
            "expected_language": expected_lang,
            "detected_language": detected_lang,
            "models": model_results
        })

    out_file = os.path.join(results_dir, "real_video_asr_benchmark.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(summary_report, f, indent=2, ensure_ascii=False)
    print("\n" + "="*70)
    print(f"Benchmark summary saved to {out_file}")
    print("="*70)

if __name__ == "__main__":
    run_evaluation()
