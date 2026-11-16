package fr.reunion.enregistreur.transcription

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import fr.reunion.enregistreur.audio.Wav
import fr.reunion.enregistreur.audio.WavReader
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.Repository
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chaîne de transcription, entièrement locale et reprenable :
 *  1. diarisation (pyannote + empreintes vocales), par tranches de 30 min ;
 *  2. détection de parole (VAD Silero) ;
 *  3. plan des segments (un intervenant par segment) enregistré en base ;
 *  4. transcription Whisper segment par segment, chaque résultat étant sauvegardé.
 * Chaque étape terminée est persistée : une interruption reprend là où elle s'est arrêtée.
 */
class TranscriptionPipeline(
    private val context: Context,
    private val repo: Repository,
    private val meeting: Meeting,
    private val isCancelled: () -> Boolean,
    private val onProgress: (stage: String, percent: Int, etaSeconds: Long?) -> Unit,
) {
    class Cancelled : CancellationException("Transcription interrompue")

    private val dir = repo.meetingDir(meeting.id)
    private val audio = File(meeting.audioPath)
    private val stats = SpeedStats(context)
    private val model = Models.Whisper.of(meeting.model)
    private val asrKey = SpeedStats.asr(model.key)

    private var audioSec = 0.0
    private var speechSec = 0.0

    // Travail estimé par étape, en secondes de calcul.
    private fun wDiar() = audioSec * stats.get(SpeedStats.DIARIZATION)
    private fun wVad() = audioSec * stats.get(SpeedStats.VAD)
    private fun wAsr() = speechSec * stats.get(asrKey)

    private fun report(stage: String, stageIndex: Int, fraction: Double, stageElapsedSec: Double) {
        val weights = doubleArrayOf(wDiar(), wVad(), wAsr())
        val total = weights.sum().coerceAtLeast(1e-6)
        val f = fraction.coerceIn(0.0, 1.0)
        val done = weights.take(stageIndex).sum() + weights[stageIndex] * f
        val currentRemaining = if (stageElapsedSec > 5 && f > 0.02) stageElapsedSec * (1 - f) / f
        else weights[stageIndex] * (1 - f)
        val eta = currentRemaining + weights.drop(stageIndex + 1).sum()
        onProgress(stage, (100 * done / total).toInt().coerceIn(0, 99), eta.toLong())
    }

    private fun checkCancel() { if (isCancelled()) throw Cancelled() }

    /** Étape en cours, conservée sur disque pour expliquer un éventuel arrêt brutal. */
    private fun step(label: String) = setStep(context, "$label (réunion ${meeting.id})")

    suspend fun run() {
        check(audio.exists()) { "Fichier audio introuvable" }
        Wav.repairHeader(audio)
        WavReader(audio).use { reader ->
            audioSec = reader.numSamples.toDouble() / Wav.SAMPLE_RATE
            speechSec = audioSec * 0.85
            check(audioSec >= 0.5) { "Enregistrement trop court" }

            if (meeting.stage < STAGE_DIARIZED) {
                diarize(reader)
                repo.setStage(meeting.id, STAGE_DIARIZED)
            }
            checkCancel()
            if (meeting.stage < STAGE_VAD) {
                detectSpeech(reader)
                repo.setStage(meeting.id, STAGE_VAD)
            }
            checkCancel()
            if (meeting.stage < STAGE_PLANNED) {
                val regions = readRegions()
                val turns = readTurns()
                val plan = SegmentPlanner.plan(regions, turns)
                val count = (plan.maxOfOrNull { it.speaker } ?: -1) + 1
                repo.savePlan(meeting.id, max(count, if (plan.isEmpty()) 0 else 1),
                    plan.map { Triple(it.startMs, it.endMs, it.speaker) })
                repo.setStage(meeting.id, STAGE_PLANNED)
            }
            checkCancel()
            transcribe(reader)
            repo.setStage(meeting.id, STAGE_DONE)
            setStep(context, null)
        }
    }

    // ---------------------------------------------------------------- Diarisation

    private fun diarize(reader: WavReader) {
        val total = reader.numSamples
        val nChunks = max(1, ((total + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES).toInt())
        val single = nChunks == 1
        val n = meeting.numSpeakers
        val t0 = SystemClock.elapsedRealtime()
        report("Identification des intervenants", 0, 0.0, 0.0)

        if (n == 1 || audioSec < 3) { // un seul participant : inutile de calculer
            writeTurns(emptyList()); return
        }
        // Si une tentative précédente s'est arrêtée brutalement pendant cette étape,
        // on la saute pour garantir l'obtention du texte.
        val marker = File(dir, "diar_running")
        if (marker.exists()) {
            Log.w(TAG, "Plantage précédent pendant la diarisation : étape ignorée")
            writeTurns(emptyList()); marker.delete(); return
        }
        marker.writeText("1")
        try {
            step("chargement du modèle de diarisation")
            val sd = Models.diarization(context.assets, if (single && n > 0) n else -1, CLUSTER_THRESHOLD)
            val extractor = if (single) null else Models.embeddingExtractor(context.assets)
            val chunks = mutableListOf<ChunkResult>()
            try {
                for (c in 0 until nChunks) {
                    checkCancel()
                    val cache = File(dir, "diar_chunk_$c.txt")
                    val cached = if (cache.exists()) ChunkResult.read(cache) else null
                    if (cached != null) { chunks += cached; continue }
                    val start = c * CHUNK_SAMPLES
                    val len = min(CHUNK_SAMPLES, total - start).toInt()
                    val samples = reader.read(start, len)
                    val offsetMs = start * 1000 / Wav.SAMPLE_RATE
                    step("diarisation, tranche ${c + 1}/$nChunks")
                    val segs: Array<OfflineSpeakerDiarizationSegment> =
                        sd.processWithCallback(samples, { done, tot, _ ->
                            val f = (c + done.toDouble() / max(1, tot)) / nChunks
                            report("Identification des intervenants", 0, f * 0.95,
                                (SystemClock.elapsedRealtime() - t0) / 1000.0)
                            0
                        })
                    val turns = segs.map {
                        Turn(offsetMs + (it.start * 1000).toLong(), offsetMs + (it.end * 1000).toLong(), it.speaker)
                    }
                    val embeddings = HashMap<Int, FloatArray>()
                    if (extractor != null) {
                        for ((spk, list) in turns.groupBy { it.speaker }) {
                            embeddingFor(extractor, samples, offsetMs, list)?.let { embeddings[spk] = it }
                        }
                    }
                    val result = ChunkResult(c, turns, embeddings)
                    result.write(cache)
                    chunks += result
                }
            } finally {
                sd.release()
                extractor?.release()
            }
            // Relie les intervenants des différentes tranches.
            val global: List<Turn> = if (single) chunks.flatMap { it.turns } else {
                val locals = chunks.flatMap { ch ->
                    ch.embeddings.map { (spk, emb) -> SpeakerLinker.LocalSpeaker(ch.index, spk, emb) }
                }
                val map = SpeakerLinker.link(locals, n, CLUSTER_THRESHOLD)
                chunks.flatMap { ch ->
                    ch.turns.mapNotNull { t -> map[ch.index to t.speaker]?.let { t.copy(speaker = it) } }
                }
            }
            writeTurns(global)
            stats.record(SpeedStats.DIARIZATION, ((SystemClock.elapsedRealtime() - t0) / 1000.0 / audioSec).toFloat())
        } catch (c: Cancelled) {
            throw c
        } catch (t: Throwable) {
            // La diarisation ne doit jamais empêcher d'obtenir le texte.
            Log.e(TAG, "Diarisation impossible, un seul intervenant sera utilisé", t)
            writeTurns(emptyList())
        } finally {
            marker.delete()
        }
    }

    /** Empreinte vocale moyenne d'un intervenant à partir de ses plus longues interventions. */
    private fun embeddingFor(
        extractor: com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor,
        samples: FloatArray, offsetMs: Long, turns: List<Turn>,
    ): FloatArray? {
        val picked = turns.sortedByDescending { it.endMs - it.startMs }
        var used = 0L
        val pieces = mutableListOf<FloatArray>()
        for (t in picked) {
            if (used >= 30_000) break
            val s = ((t.startMs - offsetMs) * Wav.SAMPLE_RATE / 1000).toInt().coerceIn(0, samples.size)
            val e = ((t.endMs - offsetMs) * Wav.SAMPLE_RATE / 1000).toInt().coerceIn(0, samples.size)
            if (e - s < Wav.SAMPLE_RATE / 2) continue
            pieces += samples.copyOfRange(s, e)
            used += t.endMs - t.startMs
        }
        if (pieces.isEmpty()) return null
        val all = FloatArray(pieces.sumOf { it.size })
        var p = 0
        for (x in pieces) { x.copyInto(all, p); p += x.size }
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(all, Wav.SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    private class ChunkResult(val index: Int, val turns: List<Turn>, val embeddings: Map<Int, FloatArray>) {
        fun write(f: File) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for (t in turns) w.write("T ${t.startMs} ${t.endMs} ${t.speaker}\n")
                for ((k, v) in embeddings) w.write("E $k ${v.joinToString(" ")}\n")
            }
            tmp.renameTo(f)
        }

        companion object {
            fun read(f: File): ChunkResult? = try {
                val turns = mutableListOf<Turn>()
                val emb = HashMap<Int, FloatArray>()
                f.forEachLine { line ->
                    val p = line.trim().split(' ')
                    when (p.firstOrNull()) {
                        "T" -> turns += Turn(p[1].toLong(), p[2].toLong(), p[3].toInt())
                        "E" -> emb[p[1].toInt()] = FloatArray(p.size - 2) { p[it + 2].toFloat() }
                    }
                }
                val idx = f.name.removePrefix("diar_chunk_").removeSuffix(".txt").toInt()
                ChunkResult(idx, turns, emb)
            } catch (t: Throwable) { null }
        }
    }

    private fun writeTurns(turns: List<Turn>) = atomicWrite(File(dir, "diar.txt"),
        turns.joinToString("") { "${it.startMs} ${it.endMs} ${it.speaker}\n" })

    private fun readTurns(): List<Turn> {
        val f = File(dir, "diar.txt")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val p = l.trim().split(' ')
            if (p.size == 3) Turn(p[0].toLong(), p[1].toLong(), p[2].toInt()) else null
        }
    }

    // ---------------------------------------------------------------- VAD

    private fun detectSpeech(reader: WavReader) {
        val t0 = SystemClock.elapsedRealtime()
        step("détection de la parole (VAD)")
        val vad = Models.vad(context.assets)
        val regions = mutableListOf<SpeechRegion>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val s = seg.start.toLong() * 1000 / Wav.SAMPLE_RATE
                val e = (seg.start.toLong() + seg.samples.size) * 1000 / Wav.SAMPLE_RATE
                regions += SpeechRegion(s, e)
                vad.pop()
            }
        }
        try {
            val block = Wav.SAMPLE_RATE * 10
            var pos = 0L
            while (pos < reader.numSamples) {
                checkCancel()
                val samples = reader.read(pos, block)
                vad.acceptWaveform(samples)
                drain()
                pos += samples.size
                report("Détection de la parole", 1, pos.toDouble() / reader.numSamples,
                    (SystemClock.elapsedRealtime() - t0) / 1000.0)
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        atomicWrite(File(dir, "vad.txt"), regions.joinToString("") { "${it.startMs} ${it.endMs}\n" })
        stats.record(SpeedStats.VAD, ((SystemClock.elapsedRealtime() - t0) / 1000.0 / audioSec).toFloat())
    }

    private fun readRegions(): List<SpeechRegion> {
        val f = File(dir, "vad.txt")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val p = l.trim().split(' ')
            if (p.size == 2) SpeechRegion(p[0].toLong(), p[1].toLong()) else null
        }
    }

    // ---------------------------------------------------------------- Whisper

    private suspend fun transcribe(reader: WavReader) {
        val all = repo.segments(meeting.id)
        speechSec = all.sumOf { it.endMs - it.startMs } / 1000.0
        val pending = all.filter { it.text == null }
        if (pending.isEmpty()) return
        val doneSec0 = (all.sumOf { it.endMs - it.startMs } - pending.sumOf { it.endMs - it.startMs }) / 1000.0
        report("Chargement du modèle ${model.key}", 2, doneSec0 / speechSec.coerceAtLeast(1e-6), 0.0)
        step("chargement de Whisper ${model.key}")
        val recognizer = Models.recognizer(context.assets, model)
        val t0 = SystemClock.elapsedRealtime()
        var processedSec = 0.0
        try {
            for (seg in pending) {
                checkCancel()
                val pad = PAD_MS * Wav.SAMPLE_RATE / 1000
                val start = seg.startMs * Wav.SAMPLE_RATE / 1000 - pad
                val end = seg.endMs * Wav.SAMPLE_RATE / 1000 + pad
                val samples = normalize(reader.read(max(0, start), (end - max(0, start)).toInt()))
                step("transcription du segment à ${seg.startMs / 1000} s")
                val text = if (samples.size < Wav.SAMPLE_RATE / 5) "" else {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, Wav.SAMPLE_RATE)
                        recognizer.decode(stream)
                        TextCleaner.clean(recognizer.getResult(stream).text)
                    } finally {
                        stream.release()
                    }
                }
                repo.setSegmentText(seg.id, text)
                processedSec += (seg.endMs - seg.startMs) / 1000.0
                val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val fraction = (doneSec0 + processedSec) / speechSec.coerceAtLeast(1e-6)
                // Temps restant basé sur la vitesse réellement mesurée sur ce téléphone.
                val rate = if (processedSec > 10) elapsed / processedSec else stats.get(asrKey).toDouble()
                val remaining = (speechSec - doneSec0 - processedSec) * rate
                onProgress("Transcription", (100 * (wDiar() + wVad() + wAsr() * fraction) /
                    (wDiar() + wVad() + wAsr()).coerceAtLeast(1e-6)).toInt().coerceIn(0, 99), remaining.toLong())
            }
        } finally {
            recognizer.release()
            if (processedSec > 30) {
                stats.record(asrKey, ((SystemClock.elapsedRealtime() - t0) / 1000.0 / processedSec).toFloat())
            }
        }
    }

    /** Remonte le volume des passages faibles (intervenants éloignés du micro). */
    private fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) { val a = abs(v); if (a > peak) peak = a }
        if (peak < 1e-4f) return x
        val gain = (0.9f / peak).coerceIn(1f, 8f)
        if (gain <= 1.05f) return x
        for (i in x.indices) x[i] *= gain
        return x
    }

    private fun atomicWrite(f: File, content: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content)
        tmp.renameTo(f)
    }

    companion object {
        private const val TAG = "Transcription"
        const val STAGE_DIARIZED = 1
        const val STAGE_VAD = 2
        const val STAGE_PLANNED = 3
        const val STAGE_DONE = 4
        private const val CHUNK_SAMPLES = 30L * 60 * Wav.SAMPLE_RATE
        private const val CLUSTER_THRESHOLD = 0.5f
        private const val PAD_MS = 150L

        private fun stepFile(context: Context) = File(context.filesDir, "transcription_step.txt")

        fun setStep(context: Context, label: String?) {
            val f = stepFile(context)
            if (label == null) f.delete() else try { f.writeText(label) } catch (_: Throwable) { }
        }

        /** Dernière étape enregistrée (non nulle si le processus a été tué en cours de route). */
        fun lastStep(context: Context): String? = stepFile(context).takeIf { it.exists() }?.readText()

        /** Supprime les résultats intermédiaires (avant une nouvelle transcription). */
        fun clearIntermediate(dir: File) {
            dir.listFiles()?.filter { it.name.startsWith("diar") || it.name.startsWith("vad") }
                ?.forEach { it.delete() }
        }
    }
}
