import os
import sys
import wave
import numpy as np
import sherpa_onnx

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

MODELS_DIR = "test-data/models/sherpa-onnx-whisper-base"

def create_recognizer(language="en"):
    encoder = os.path.join(MODELS_DIR, "base-encoder.int8.onnx")
    if not os.path.exists(encoder):
        encoder = os.path.join(MODELS_DIR, "base-encoder.onnx")
    decoder = os.path.join(MODELS_DIR, "base-decoder.int8.onnx")
    if not os.path.exists(decoder):
        decoder = os.path.join(MODELS_DIR, "base-decoder.onnx")
    tokens = os.path.join(MODELS_DIR, "base-tokens.txt")
    
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=encoder,
        decoder=decoder,
        tokens=tokens,
        language=language,
        task="transcribe",
        num_threads=4
    )

def test_audio(audio_path, language="en"):
    with wave.open(audio_path, "rb") as wf:
        n_frames = wf.getnframes()
        framerate = wf.getframerate()
        pcm_bytes = wf.readframes(n_frames)
        
    samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
    rec = create_recognizer(language=language)
    stream = rec.create_stream()
    stream.accept_waveform(framerate, samples)
    rec.decode_streams([stream])
    return stream.result.text.strip()

if __name__ == "__main__":
    print("==================================================================")
    print("          SHERPA-ONNX MULTILINGUAL ASR BENCHMARK RUNNER           ")
    print("==================================================================")
    
    test_files = [
        ("english_English_1.wav", "en"),
        ("english_English_2.wav", "en"),
        ("hindi_Hindi_1.wav", "hi"),
        ("hindi_Hindi_2.wav", "hi"),
        ("telugu_Telugu_1.wav", "te"),
        ("telugu_Telugu_2.wav", "te"),
        ("telugu_Telugu_3.wav", "te")
    ]
    
    for fname, lang in test_files:
        p = os.path.join("test-data/audio", fname)
        if os.path.exists(p):
            txt = test_audio(p, language=lang)
            print(f"[{fname}] ({lang}) -> \"{txt}\"")
