# Video Translator — Android App

An Android app that plays a Hindi video and lets the user switch its audio between **Hindi (original)**, **English**, and **Telugu** using fully offline speech-to-text, on-device ML Kit translation, and Android's built-in TTS.

---

## How to Build and Run

### Prerequisites
| Tool | Required version |
|------|-----------------|
| Android Studio | Hedgehog (2023.1.1) or later |
| JDK | 17 (bundled with Android Studio) |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.3.20 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 (Android 16) |

### Steps

1. **Clone / open the project**
   ```
   Open Android Studio → File → Open → select the `VideoTranslator/` folder
   ```

2. **Sync Gradle**  
   Android Studio will prompt you. Click **Sync Now** and wait for dependencies to resolve.

3. **The two binary assets are already in place:**
   - `app/src/main/res/raw/sample_video.mp4` — the sample Hindi video
   - `app/src/main/assets/model-hi-small.zip` — Vosk `vosk-model-small-hi-0.22`

4. **Build / Run**
   - Press **▶ Run** (or `Shift+F10`) with an emulator or device running API 26+.
   - For a debug APK: `./gradlew assembleDebug`  
     Output: `app/build/outputs/apk/debug/app-debug.apk`

5. **First-run note**  
   On first launch the app will:
   1. Decode the video's audio track (MediaCodec, ~5 s).
   2. Unzip and load the Vosk Hindi model (~10 s).
   3. Transcribe the audio offline (time depends on video length).
   4. Download ML Kit translation models (requires internet, ~once only, ~50 MB).
   5. Translate all segments.
   6. Cache the results to internal storage.  
   Subsequent launches skip all of the above and start immediately.

---

## Libraries, APIs & AI Services Used

| Component | Library / Service | Version | Notes |
|-----------|------------------|---------|-------|
| **UI** | Jetpack Compose + Material 3 | BOM 2026.06.01 | Fully declarative Compose UI |
| **Video playback** | AndroidX Media3 ExoPlayer | 1.10.1 | Replaces legacy ExoPlayer2 |
| **Audio decoding** | Android `MediaCodec` + `MediaExtractor` | Built-in | No FFmpeg, no NDK |
| **Speech-to-text (Hindi)** | Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | Offline, on-device, no API key |
| **Hindi model** | `vosk-model-small-hi-0.22` | — | ~44 MB; bundled in APK assets |
| **Translation** | Google ML Kit Translation | 17.0.3 | On-device after first download; no API key |
| **Text-to-speech** | Android `TextToSpeech` | Built-in | System voices; warns if locale missing |
| **Coroutines bridge** | `kotlinx-coroutines-play-services` | 1.10.2 | `.await()` on ML Kit Tasks |
| **JSON cache** | `kotlinx-serialization-json` | 1.8.1 | Caches segment list across launches |

---

## Architecture

```
VideoPlayerViewModel
  │
  ├── AudioExtractor      → MediaCodec decodes video → 16kHz mono PCM
  ├── VoskSpeechRecognizer→ Vosk transcribes PCM → timed Hindi segments
  ├── TranslationManager  → ML Kit translates Hindi → English / Telugu
  ├── SegmentCache        → JSON-serialises results to filesDir
  ├── TtsManager          → Android TextToSpeech speaks translated text
  └── ExoPlayer           → plays the original video (audio muted for non-Hindi)

VideoPlayerScreen (Compose)
  ├── PlayerView (AndroidView, Media3 UI)
  ├── LoadingCard / ErrorCard
  ├── LanguageSelector (Hindi / English / Telugu chips)
  └── MissingVoiceWarning
```

**Sync strategy:** After processing, a polling coroutine queries `exoPlayer.currentPosition` every 100 ms. When the position enters a segment's time window (`startMs – 150 ms` tolerance), the translated text for the active language is spoken via TTS. This is intentionally segment-level, not audio-level — good enough to demonstrate "on the fly" translation.

---

## Assumptions & Limitations

### Assumptions
- The video file contains an audio track in a supported container (MP4, MKV, WEBM, 3GP) with standard audio encodings (AAC, MP3, Opus, Vorbis, AC3, PCM).
- The device has a working internet connection on first launch to download Google ML Kit translation models (~50 MB for Hindi↔English + Hindi↔Telugu).
- The target device runs Android 8.0 (API 26) or later for `MediaCodec` audio extraction.

### Diagnostic Findings & Architectural Solutions
| Component / Limitation | Empirical Diagnosis | Implemented Solution |
|-------------------|-------------------|---------------------|
| **5.1 Surround Sound Speech Extraction** | In 5.1 surround sound video tracks (e.g. `sample_video.mp4` AAC 5.1), human speech is mixed into **Channel 2 (Center Channel / FC)**. Naive channel downmix discarded Channel 2, stripping out human speech. | Updated `AudioExtractor.kt` to downmix 5.1 surround with Center channel prioritization: `mono = FC * 0.50 + (FL + FR) * 0.25`. |
| **Resampling Quality** | Linear interpolation when downsampling 48 kHz $\rightarrow$ 16 kHz introduced high-frequency aliasing noise into the speech band. | Added a 31-tap Blackman-windowed FIR low-pass filter ($f_c = 7200\text{ Hz}$) prior to decimation. |
| **Audio Level & Gain** | Quiet audio recordings resulted in low STT recognition due to low RMS energy. | Added peak / RMS gain normalization to scale speech volume to optimal recognition level (-2 dB peak, ~26,000). |
| **Sentence-Level Translation** | Short 600ms / 10-word fragmenting broke sentences mid-thought, causing broken, literal ML Kit translations. | Configured `VoskSpeechRecognizer.kt` for full-sentence boundary grouping (1.2s silence threshold, up to 12s / 30 words per segment) and direct sentence translation in `TranslationManager.kt`. |
| **TTS Voice Availability** | If device lacks an English or Telugu system TTS voice, an inline warning is shown. | Handled gracefully in `TtsManager.kt` with user warning. |
| **Speech Sync Rate** | Over-accelerating speech rate (2x–3x) created unnatural robotic speech. | Enforced natural human speech rates (1.0x – 1.15x max). |

---

## What Changed from the Original Plan

| Item | Change |
|------|--------|
| **Audio Downmixing** | Refactored `AudioExtractor` to support 5.1 surround sound (Center channel prioritization), 32-bit Float PCM, 8-bit PCM, anti-aliased FIR resampling, and RIFF `.wav` file export (`extracted_speech.wav` in `filesDir`). |
| **Sentence Segmentation** | Upgraded Vosk word grouping from fragment-level to complete full-sentence clauses (1.2s pause threshold) to preserve ML Kit translation context. |
| **Model Unpacking** | The app unzips `model-hi-small.zip` from assets into `filesDir` at runtime and passes the path directly to `Model()`. |
| **UI Design System** | Redesigned with a luxury dark mode theme (`#080810` deep navy, metallic gold accents, status bar padding, ambient glow, custom launcher icon). |

---

*Built for an Android internship take-home assessment. All AI services used are fully offline (Vosk STT, ML Kit Translation) after the one-time model download.*

