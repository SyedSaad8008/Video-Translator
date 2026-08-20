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

def test():
    for f in ["telugu_Telugu_1.wav", "telugu_Telugu_2.wav", "telugu_Telugu_3.wav"]:
        p = os.path.join("test-data/audio", f)
        with wave.open(p, "rb") as wf:
            n = wf.getnframes()
            pcm = wf.readframes(n)
        
        rec = KaldiRecognizer(te_model, 16000)
        rec.SetWords(True)
        chunk_size = 4000
        words = []
        for i in range(0, len(pcm), chunk_size):
            if rec.AcceptWaveform(pcm[i:i+chunk_size]):
                r = json.loads(rec.Result())
                if r.get("text"):
                    words.extend(r["text"].split())
        r_fin = json.loads(rec.FinalResult())
        if r_fin.get("text"):
            words.extend(r_fin["text"].split())
        print(f"{f}: {len(words)} words -> \"{' '.join(words)}\"")

if __name__ == "__main__":
    test()
