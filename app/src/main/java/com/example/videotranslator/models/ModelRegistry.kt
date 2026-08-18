package com.example.videotranslator.models

import com.example.videotranslator.model.ModelCategory
import com.example.videotranslator.model.ModelInfo

/**
 * Static registry defining all on-device AI models required for the fully offline pipeline.
 * Contains metadata, category, file size, download endpoints, and storage file names.
 */
object ModelRegistry {

    val STT_HI_MODEL = ModelInfo(
        id = "stt_hi_vosk",
        name = "Hindi Speech Recognition Engine (Vosk)",
        description = "On-device acoustic speech-to-text model for timestamped Hindi and Hindustani/Urdu speech recognition.",
        category = ModelCategory.SPEECH_RECOGNITION,
        sizeBytes = 42_000_000L, // ~42 MB
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        fileName = "vosk-model-small-hi-0.22.zip",
        isBundledInAssets = false
    )

    val STT_EN_MODEL = ModelInfo(
        id = "stt_en_vosk",
        name = "English Speech Recognition Engine (Vosk)",
        description = "On-device acoustic speech-to-text model for timestamped English speech recognition.",
        category = ModelCategory.SPEECH_RECOGNITION,
        sizeBytes = 40_000_000L, // ~40 MB
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        fileName = "vosk-model-small-en-us-0.15.zip",
        isBundledInAssets = false
    )

    val STT_TE_MODEL = ModelInfo(
        id = "stt_te_vosk",
        name = "Telugu Speech Recognition Engine (Vosk)",
        description = "On-device acoustic speech-to-text model for timestamped Telugu speech recognition.",
        category = ModelCategory.SPEECH_RECOGNITION,
        sizeBytes = 45_000_000L, // ~45 MB
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip",
        fileName = "vosk-model-small-te-0.42.zip",
        isBundledInAssets = false
    )

    val NLLB_200_INT8 = ModelInfo(
        id = "nllb_200_int8",
        name = "NLLB-200 / IndicTrans2 Multilingual Translation Engine",
        description = "High-accuracy on-device neural machine translation model supporting Hindi (hin_Deva), English (eng_Latn), and Telugu (tel_Telu) in all 6 directions.",
        category = ModelCategory.TRANSLATION,
        sizeBytes = 295_000_000L, // ~295 MB INT8
        downloadUrl = "https://huggingface.co/Hosstia/nllb-200-distilled-600m-onnx/resolve/main/model_int8.onnx",
        fileName = "nllb_200_distilled_600m_int8.onnx",
        isBundledInAssets = false
    )

    val NLLB_TOKENIZER = ModelInfo(
        id = "nllb_tokenizer",
        name = "NLLB / IndicTrans2 SentencePiece Tokenizer",
        description = "SentencePiece BPE vocabulary and tokenizer dictionary for language token conversion.",
        category = ModelCategory.TRANSLATION,
        sizeBytes = 4_800_000L, // ~4.8 MB
        downloadUrl = "https://huggingface.co/facebook/nllb-200-distilled-600M/resolve/main/sentencepiece.bpe.model",
        fileName = "sentencepiece.bpe.model",
        isBundledInAssets = false
    )

    val GENDER_CLASSIFIER = ModelInfo(
        id = "voice_gender_classifier",
        name = "Voice Gender Classifier",
        description = "Multi-signal ensemble model for YIN F0 pitch, spectral centroid, and HNR voice classification.",
        category = ModelCategory.VOICE_CLASSIFICATION,
        sizeBytes = 12_000_000L, // ~12 MB
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/gender_classifier.onnx",
        fileName = "gender_classifier.onnx",
        isBundledInAssets = true
    )

    val TTS_EN_MALE = ModelInfo(
        id = "tts_en_male",
        name = "English Male Voice (en-us-male)",
        description = "Deep natural male voice model for English dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 28_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts_r/medium/en_US-libritts_r-medium.onnx",
        fileName = "tts_en_male.onnx",
        isBundledInAssets = false
    )

    val TTS_EN_FEMALE = ModelInfo(
        id = "tts_en_female",
        name = "English Female Voice (en-us-female)",
        description = "Clear natural female voice model for English dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 28_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/hfc_female/medium/en_US-hfc_female-medium.onnx",
        fileName = "tts_en_female.onnx",
        isBundledInAssets = false
    )

    val TTS_HI_MALE = ModelInfo(
        id = "tts_hi_male",
        name = "Hindi Male Voice (hi-in-male)",
        description = "Authentic Devanagari Hindi male voice model for Hindi dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 29_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/male/medium/hi_IN-male-medium.onnx",
        fileName = "tts_hi_male.onnx",
        isBundledInAssets = false
    )

    val TTS_HI_FEMALE = ModelInfo(
        id = "tts_hi_female",
        name = "Hindi Female Voice (hi-in-female)",
        description = "Clear Devanagari Hindi female voice model for Hindi dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 29_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/female/medium/hi_IN-female-medium.onnx",
        fileName = "tts_hi_female.onnx",
        isBundledInAssets = false
    )

    val TTS_TE_MALE = ModelInfo(
        id = "tts_te_male",
        name = "Telugu Male Voice (te-in-male)",
        description = "Natural Telugu male voice model for Telugu dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 29_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/te/te_IN/male/medium/te_IN-male-medium.onnx",
        fileName = "tts_te_male.onnx",
        isBundledInAssets = false
    )

    val TTS_TE_FEMALE = ModelInfo(
        id = "tts_te_female",
        name = "Telugu Female Voice (te-in-female)",
        description = "Natural Telugu female voice model for Telugu dubbed playback.",
        category = ModelCategory.TEXT_TO_SPEECH,
        sizeBytes = 29_000_000L,
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/te/te_IN/female/medium/te_IN-female-medium.onnx",
        fileName = "tts_te_female.onnx",
        isBundledInAssets = false
    )

    val ALL_MODELS = listOf(
        STT_HI_MODEL,
        STT_EN_MODEL,
        STT_TE_MODEL,
        NLLB_200_INT8,
        NLLB_TOKENIZER,
        GENDER_CLASSIFIER,
        TTS_EN_MALE,
        TTS_EN_FEMALE,
        TTS_HI_MALE,
        TTS_HI_FEMALE,
        TTS_TE_MALE,
        TTS_TE_FEMALE
    )

    fun getModelById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
}
