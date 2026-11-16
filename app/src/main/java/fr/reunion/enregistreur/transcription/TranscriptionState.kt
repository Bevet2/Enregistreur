package fr.reunion.enregistreur.transcription

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Progression de la transcription en cours, observée par l'interface et la notification. */
object TranscriptionState {
    data class State(
        val meetingId: Long? = null,
        val stage: String = "",
        val percent: Int = 0,
        val etaSeconds: Long? = null,
        val queued: List<Long> = emptyList(),
    )

    internal val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
}

/**
 * Mémorise la vitesse mesurée de chaque étape (secondes de calcul par seconde d'audio)
 * pour estimer le temps restant dès le début des transcriptions suivantes.
 */
class SpeedStats(context: Context) {
    private val prefs = context.getSharedPreferences("speed_stats", Context.MODE_PRIVATE)

    fun get(key: String): Float = prefs.getFloat(key, DEFAULTS[key] ?: 0.3f)

    fun record(key: String, measured: Float) {
        if (measured.isNaN() || measured <= 0f) return
        val old = get(key)
        prefs.edit().putFloat(key, old * 0.5f + measured * 0.5f).apply()
    }

    companion object {
        const val DIARIZATION = "diar"
        const val VAD = "vad"
        fun asr(model: String) = "asr_$model"
        private val DEFAULTS = mapOf(
            DIARIZATION to 0.08f,
            VAD to 0.01f,
            "asr_small" to 0.45f,
            "asr_base" to 0.15f,
        )
    }
}
