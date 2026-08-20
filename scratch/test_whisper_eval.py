import os
import sys
import urllib.request
import subprocess

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

MODELS_DIR = "test-data/models"
os.makedirs(MODELS_DIR, exist_ok=True)

WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
whisper_model_path = os.path.join(MODELS_DIR, "ggml-base.bin")

def ensure_whisper():
    if not os.path.exists(whisper_model_path):
        print(f"Downloading whisper ggml-base.bin from {WHISPER_URL}...")
        urllib.request.urlretrieve(WHISPER_URL, whisper_model_path)
        print(f"Downloaded {whisper_model_path} ({os.path.getsize(whisper_model_path)} bytes) ✓")
    else:
        print(f"Whisper model exists: {whisper_model_path}")

def test_whisper_on_audio(audio_path, language="te"):
    # Run ffmpeg whisper filter
    out_txt_path = audio_path.replace(".wav", f"_whisper_{language}.txt")
    # ffmpeg -i <audio> -af "whisper=model=...:language=te:destination=out.txt:format=text" -f null -
    cmd = [
        "ffmpeg", "-y", "-i", audio_path,
        "-af", f"whisper=model={whisper_model_path.replace(os.sep, '/')}:language={language}:destination={out_txt_path.replace(os.sep, '/')}:format=text",
        "-f", "null", "-"
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if os.path.exists(out_txt_path):
        with open(out_txt_path, "r", encoding="utf-8", errors="ignore") as f:
            txt = f.read().strip()
        return txt
    else:
        return f"[Error or no output: {res.stderr[:200]}]"

if __name__ == "__main__":
    ensure_whisper()
    for f in ["telugu_Telugu_1.wav", "telugu_Telugu_2.wav", "telugu_Telugu_3.wav", "english_English_1.wav", "hindi_Hindi_1.wav"]:
        p = os.path.join("test-data/audio", f)
        lang = "te" if "telugu" in f else ("en" if "english" in f else "hi")
        print(f"\n--- Running Whisper on {f} (lang={lang}) ---")
        transcript = test_whisper_on_audio(p, language=lang)
        print(f"Whisper Output:\n\"{transcript}\"")
