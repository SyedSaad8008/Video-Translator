import os
import sys
import json
import wave
from vosk import Model, KaldiRecognizer, SetLogLevel

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

SetLogLevel(-1)

MODELS_DIR = "test-data/models"
te_model = Model(os.path.join(MODELS_DIR, "vosk-model-small-te-0.42"))

def test_telugu_tuning():
    audio_dir = "test-data/audio"
    telugu_files = ["telugu_Telugu_1.wav", "telugu_Telugu_2.wav", "telugu_Telugu_3.wav"]

    print("=================================================================")
    print("           TELUGU ASR DEEP DIVE & PARAMETER TUNING               ")
    print("=================================================================")

    for fname in telugu_files:
        wav_path = os.path.join(audio_dir, fname)
        with wave.open(wav_path, "rb") as wf:
            n_frames = wf.getnframes()
            rate = wf.getframerate()
            pcm = wf.readframes(n_frames)
            dur = n_frames / rate

        print(f"\nEvaluating {fname} ({dur:.2f}s):")
        
        # Test 1: Standard
        rec1 = KaldiRecognizer(te_model, rate)
        rec1.SetWords(True)
        rec1.AcceptWaveform(pcm)
        res1 = json.loads(rec1.FinalResult())
        print(f"  • Standard Decode: \"{res1.get('text', '')}\"")

        # Test 2: Chunked streaming with intermediate results
        rec2 = KaldiRecognizer(te_model, rate)
        rec2.SetWords(True)
        chunk_size = 3200 # 100ms
        chunks_text = []
        for i in range(0, len(pcm), chunk_size):
            chunk = pcm[i:i+chunk_size]
            if rec2.AcceptWaveform(chunk):
                r = json.loads(rec2.Result())
                if r.get("text"):
                    chunks_text.append(r["text"])
        r_final = json.loads(rec2.FinalResult())
        if r_final.get("text"):
            chunks_text.append(r_final["text"])
        print(f"  • Streaming 100ms: \"{' '.join(chunks_text)}\"")

if __name__ == "__main__":
    test_telugu_tuning()
