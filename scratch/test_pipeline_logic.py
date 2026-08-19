import json
import re

def test_vosk_json_parsing():
    # Case 1: Vosk returns both "result" and "text"
    json_with_result = json.dumps({
        "result": [
            {"conf": 1.0, "end": 1.1, "start": 0.3, "word": "నేను"},
            {"conf": 1.0, "end": 1.9, "start": 1.2, "word": "ఈరోజు"},
            {"conf": 1.0, "end": 2.4, "start": 2.0, "word": "మా"},
            {"conf": 1.0, "end": 3.1, "start": 2.5, "word": "కాలేజీకి"},
            {"conf": 1.0, "end": 4.2, "start": 3.2, "word": "వెళ్తున్నాను"}
        ],
        "text": "నేను ఈరోజు మా కాలేజీకి వెళ్తున్నాను"
    })

    # Case 2: Vosk returns ONLY "text" without "result" array (common in small Indian models)
    json_only_text = json.dumps({
        "text": "నేను ఈరోజు మా కాలేజీకి వెళ్తున్నాను"
    })

    def parse_words(json_str, chunk_start_sec=0.0, chunk_end_sec=5.0):
        data = json.loads(json_str)
        words = []
        if "result" in data and len(data["result"]) > 0:
            for item in data["result"]:
                w = item.get("word", "").strip()
                if w:
                    words.append({
                        "word": w,
                        "start": item.get("start", 0.0),
                        "end": item.get("end", 0.0),
                        "conf": item.get("conf", 1.0)
                    })
        elif "text" in data and data["text"].strip():
            raw_text = data["text"].strip()
            tokens = raw_text.split()
            if tokens:
                dt = (chunk_end_sec - chunk_start_sec) / len(tokens)
                for i, token in enumerate(tokens):
                    words.append({
                        "word": token,
                        "start": round(chunk_start_sec + i * dt, 2),
                        "end": round(chunk_start_sec + (i + 1) * dt, 2),
                        "conf": 0.95
                    })
        return words

    words1 = parse_words(json_with_result)
    words2 = parse_words(json_only_text, 0.0, 4.5)

    print(f"Words parsed with result array: {len(words1)} words -> {[w['word'] for w in words1]}")
    print(f"Words parsed with text-only fallback: {len(words2)} words -> {[w['word'] for w in words2]}")

    assert len(words1) == 5
    assert len(words2) == 5
    assert words1[0]["word"] == "నేను"
    assert words2[4]["word"] == "వెళ్తున్నాను"
    print("✓ Vosk JSON parsing test passed completely.")

if __name__ == "__main__":
    test_vosk_json_parsing()
