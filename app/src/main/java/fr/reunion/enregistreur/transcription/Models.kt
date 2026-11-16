package fr.reunion.enregistreur.transcription

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Modèles intégrés à l'APK (dossier assets/models, rempli pendant le build CI).
 * Ils sont chargés directement depuis l'APK : aucun téléchargement à l'exécution.
 */
object Models {
    const val NUM_THREADS = 4

    enum class Whisper(val key: String, val label: String, val dir: String, val prefix: String) {
        SMALL("small", "Qualité (Whisper small)", "models/whisper-small", "small"),
        BASE("base", "Rapide (Whisper base)", "models/whisper-base", "base");

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: SMALL
        }
    }

    private const val VAD = "models/vad/silero_vad.onnx"
    private const val SEGMENTATION = "models/diarization/segmentation.onnx"
    private const val EMBEDDING = "models/diarization/embedding.onnx"

    fun recognizer(assets: AssetManager, model: Whisper): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "${model.dir}/${model.prefix}-encoder.int8.onnx",
                    decoder = "${model.dir}/${model.prefix}-decoder.int8.onnx",
                    language = "fr", // français forcé : pas de détection de langue
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = "${model.dir}/${model.prefix}-tokens.txt",
                modelType = "whisper",
                numThreads = NUM_THREADS,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assets, config)
    }

    fun vad(assets: AssetManager): Vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = VAD,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    fun diarization(assets: AssetManager, numClusters: Int, threshold: Float): OfflineSpeakerDiarization =
        OfflineSpeakerDiarization(
            assets,
            OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SEGMENTATION),
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = EMBEDDING,
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )

    fun embeddingExtractor(assets: AssetManager): SpeakerEmbeddingExtractor =
        SpeakerEmbeddingExtractor(
            assets,
            SpeakerEmbeddingExtractorConfig(model = EMBEDDING, numThreads = NUM_THREADS, provider = "cpu"),
        )
}
