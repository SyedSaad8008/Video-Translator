# Video Translator — Android App

An Android application built in Kotlin that plays a Hindi video and allows seamless real-time switching of its spoken audio track between **Hindi (original)**, **English**, and **Telugu**. 

The app utilizes fully on-device speech-to-text (Vosk STT), offline machine translation (Google ML Kit), DSP pitch-based voice gender detection, pre-rendered duration-matched audio synthesis, and Android's native TextToSpeech / MediaPlayer engine.

---

## 1. How to Build and Run

### Prerequisites
| Tool / Environment | Requirement |
|-------------------|-------------|
| **Android Studio** | Ladybug / Hedgehog (2023.1.1+) |
| **JDK** | Java 17 (bundled with Android Studio) |
| **Android Gradle Plugin** | 9.0.1 |
| **Kotlin** | 2.3.20 |
| **Minimum SDK** | API 26 (Android 8.0 Oreo) |
| **Target SDK** | API 36 (Android 16) |

### Steps to Build & Run

1. **Open Project**:
   - Launch Android Studio $\rightarrow$ **File** $\rightarrow$ **Open** $\rightarrow$ Select the `VideoTranslator/` folder.

2. **Sync Gradle**:
   - Click **Sync Now** when prompted to resolve dependencies.

3. **Verify Pre-bundled Binary Assets**:
   - `app/src/main/res/raw/sample_video.mp4` — Bundled sample Hindi video.
   - `app/src/main/assets/model-hi-small.zip` — Bundled Vosk Hindi offline speech recognition model (`vosk-model-small-hi-0.22`).

4. **Run on Device or Emulator**:
   - Select a physical device or emulator (API 26+) and press **▶ Run** (`Shift + F10`).
   - Alternatively, build a debug APK via command line:
     ```bash
     ./gradlew assembleDebug
     ```
     Output APK location: `app/build/outputs/apk/debug/app-debug.apk`

5. **First-Run Processing**:
   - On initial video selection, the app:
     1. Pre-warms Vosk STT & ML Kit models in the background.
     2. Extracts audio from video via `MediaCodec` (~3–5s).
     3. Runs DSP pitch estimation ($F_0$ median autocorrelation) to classify speaker gender (**Male** vs **Female**).
     4. Transcribes Hindi speech offline with Vosk.
     5. Downloads Google ML Kit translation models on first run (~50 MB, one-time).
     6. Translates Hindi speech into English and Telugu.
     7. Pre-renders gender-matched TTS speech files and computes duration-matched playback speed ratios (`0.75x`–`1.5x`).
     8. Caches processed segment results locally for instant playback on repeat runs.

---

## 2. Libraries, APIs, & AI Services Used

| Component / Task | Library / Technology | Version | Purpose & Notes |
|------------------|---------------------|---------|-----------------|
| **UI Framework** | Jetpack Compose + Material 3 | BOM 2026.06.01 | Declarative dark mode UI (`#080810` theme with gold accents) |
| **Video Playback** | AndroidX Media3 ExoPlayer | 1.10.1 | Modern video playback and volume control |
| **Audio Decoding** | Android `MediaCodec` + `MediaExtractor` | Native API | High-performance audio stream demuxing and decoding |
| **Speech-to-Text (STT)** | Vosk Android SDK (`com.alphacephei:vosk-android`) | 0.3.75 | 100% offline, on-device Hindi speech recognition |
| **STT Model** | `vosk-model-small-hi-0.22` | — | Lightweight offline Hindi acoustic model (~44 MB, bundled in assets) |
| **Gender Detection** | Custom DSP Autocorrelation | Native Kotlin | Estimates fundamental pitch ($F_0$) to classify Male vs Female voice |
| **Translation Engine** | Google ML Kit Translation | 17.0.3 | On-device neural machine translation (Hindi $\rightarrow$ English / Telugu) |
| **Pre-Rendered TTS** | Android `TextToSpeech` (`synthesizeToFile`) | Native API | Pre-renders gender-matched WAV speech files for duration matching |
| **Pitch-Preserved Audio Sync** | Android `MediaPlayer` (`PlaybackParams`) | Native API | Plays pre-rendered segment files with pitch-preserved speed scaling |
| **Caching** | `kotlinx-serialization-json` | 1.8.1 | Local JSON serialization of processed speech segments and speed ratios |

---

## 3. Architecture & Sync Pipeline Overview

```
VideoPlayerViewModel
  │
  ├── AudioExtractor       → MediaCodec decodes 5.1/stereo audio → 16kHz anti-aliased mono PCM
  ├── GenderDetector       → DSP autocorrelation estimates median F0 pitch → Male (<165Hz) / Female (>=165Hz)
  ├── VoskSpeechRecognizer → Vosk transcribes PCM → timed Hindi full-sentence segments
  ├── TranslationManager   → ML Kit translates Hindi sentences → English & Telugu
  ├── TtsManager           → Pre-renders gender-matched TTS WAV files; computes speedRatio = T_render / T_target
  ├── SegmentCache         → Serializes segment metadata & pre-rendered WAV paths to JSON in filesDir
  ├── SegmentAudioPlayer   → Plays segment audio via MediaPlayer with PlaybackParams.setSpeed(speedRatio)
  └── ExoPlayer            → Plays video (mutes original audio track in English/Telugu mode)
```

---

## 4. Speaker Gender Detection & Verified Voice Lookup Table

### Speaker Gender Detection Approach
- **DSP Pitch ($F_0$) Estimation**: Analyzes 30ms frames (480 samples @ 16kHz) with a 15ms hop.
- **Energy Filtering**: Filters out unvoiced speech and silence frames ($\text{RMS} < 120$).
- **Autocorrelation**: Computes autocorrelation over lag range $40..200$ (corresponding to $80\text{ Hz} - 400\text{ Hz}$).
- **Median Pitch & Classification**: Computes the median $F_0$ across all voiced frames in the video:
  - Median $F_0 < 165\text{ Hz} \rightarrow$ **Male Speaker**
  - Median $F_0 \ge 165\text{ Hz} \rightarrow$ **Female Speaker**
- **Single Speaker Assumption**: Assumes a single dominant speaker per video (matching standard dubbed sample videos).

### Verified Google TTS Voice Lookup Table
The app maps detected speaker gender to verified Google TTS engine voice names for target locales:

| Language | Locale | Target Gender | Preferred Google TTS Voice Names | Fallback Strategy |
|----------|--------|---------------|----------------------------------|-------------------|
| **English** | `en-US` | **Male** | `en-us-x-iom-network`, `en-us-x-iom-local`, `en-us-x-tpf-network`, `en-us-x-sfg-network` | Dynamic name tag inspection (`male`, `-m-`), then default `en-US` voice |
| **English** | `en-US` | **Female** | `en-us-x-iob-network`, `en-us-x-iob-local`, `en-us-x-tpc-network`, `en-us-x-sfg-local` | Dynamic name tag inspection (`female`, `-f-`), then default `en-US` voice |
| **Telugu** | `te-IN` | **Male** | `te-in-x-tem-network`, `te-in-x-tem-local` | Dynamic name tag inspection (`tem`, `male`), then default `te-IN` voice |
| **Telugu** | `te-IN` | **Female** | `te-in-x-tef-network`, `te-in-x-tef-local` | Dynamic name tag inspection (`tef`, `female`), then default `te-IN` voice |

---

## 5. Audio-Timing ("Lip Sync") Duration Matching

> [!NOTE]
> **Audio Timing Sync vs. Visual Lip Resynthesis**:
> True visual lip-sync (repainting the speaker's mouth in the video using deep generative neural models like Wav2Lip) is a separate video synthesis technology stack.
> What is implemented here is **duration-matched audio sync**: adjusting the rendered speech playback tempo to fit within each segment's original timing window ($T_{\text{target}} = \text{endMs} - \text{startMs}$), ensuring translated audio starts and stops precisely when the speaker's mouth is moving.

### Pre-Render & Speed-Scaling Algorithm
1. **Pre-Rendering**: Each translated sentence is synthesized to a `.wav` file on disk via `TextToSpeech.synthesizeToFile()`.
2. **Duration Measurement**: Actual rendered audio duration ($T_{\text{render}}$) is measured in milliseconds.
3. **Speed Ratio Calculation & Clamping**:
   $$\text{speedRatio} = \text{clamp}\left(\frac{T_{\text{render}}}{T_{\text{target}}}, 0.75, 1.50\right)$$
   Clamping to `[0.75x, 1.50x]` ensures speech tempo remains natural, clear, and fully intelligible without distortion.
4. **Pitch-Preserved Playback**: Pre-rendered audio files are played using `MediaPlayer` configured with `PlaybackParams().setSpeed(speedRatio)`, which modifies tempo while preserving natural voice pitch.

---

## 6. Assumptions & Limitations

### Assumptions
- **Video Encodings**: Input video contains an audio track in standard container formats (MP4, MKV, WEBM, 3GP) with AAC, MP3, Opus, Vorbis, or AC3 audio.
- **Multi-Speaker Diarization**: Performs sentence-level PCM pitch analysis ($F_0$) to assign speaker gender on a per-segment basis (alternating Male and Female voices for multi-speaker dialogues).
- **First-Run Connectivity**: Device has internet access during initial launch to download ML Kit translation models (~50 MB, downloaded once). Subsequent uses are 100% offline.
- **Android Version**: Device runs API level 26 (Android 8.0) or higher.

### Implemented Architectural Solutions
| Area / Requirement | Diagnostic Finding & Implemented Solution |
|-------------------|------------------------------------------|
| **Multi-Speaker Voice Gender Matching** | Performs subharmonic pitch period analysis ($F_0$) on each sentence's PCM slice $\rightarrow$ dynamically selects Male/Female Google TTS voices per segment (`en-us-x-tpd` Male, `te-in-x-teg` Male, `en-us-x-iob` Female, `te-in-x-tee` Female). |
| **Lip Sync / Audio Duration Match** | Replaced live TTS streaming with pre-rendered `.wav` files and pitch-preserved speed scaling (`0.75x`–`1.5x`), synchronizing speech start/end with mouth movements. |
| **5.1 Surround Sound Speech Extraction** | Re-engineered `AudioExtractor.kt` to downmix 5.1 surround sound audio with Center channel prioritization: `mono = FC * 0.50 + (FL + FR) * 0.25`. |
| **Resampling Aliasing Noise** | Applied a 31-tap Blackman-windowed FIR low-pass filter ($f_c = 7200\text{ Hz}$) prior to decimation down to 16 kHz. |
| **Audio Volume Normalization** | Added peak/RMS gain normalization to scale speech volume to optimal recognition levels (-2 dB peak, ~26,000). |
| **TTS System Voice Availability** | Displays an inline warning card if the system lacks an installed TTS voice package for English or Telugu. |

---

*Built for an Android internship assessment. All speech-to-text, gender detection, translation, and audio pre-rendering are executed 100% on-device after initial setup.*
