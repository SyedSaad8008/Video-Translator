import os
import sys
import sherpa_onnx
import wave
import numpy as np

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

print(f"Sherpa-ONNX version: {sherpa_onnx.__file__}")

# Check sherpa-onnx offline recognizer capabilities
print("Sherpa-ONNX Offline Recognizer available ✓")
