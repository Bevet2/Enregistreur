package fr.reunion.enregistreur.transcription

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Zone de parole détectée par le VAD (millisecondes). */
data class SpeechRegion(val startMs: Long, val endMs: Long)

/** Tour de parole issu de la diarisation (millisecondes, intervenant global). */
data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

/** Segment à transcrire : parole continue d'un seul intervenant. */
data class PlannedSegment(val startMs: Long, val endMs: Long, val speaker: Int) {
    val durationMs get() = endMs - startMs
}

/**
 * Combine les zones de parole (VAD) et les tours de parole (diarisation) en segments
 * homogènes (un seul intervenant), d'une durée adaptée à Whisper (≤ 28 s).
 * Logique pure, testée unitairement.
 */
object SegmentPlanner {
    const val MAX_SEGMENT_MS = 28_000L
    const val TARGET_MERGE_MS = 25_000L
    const val MERGE_GAP_MS = 1_000L
    const val MIN_PIECE_MS = 400L

    fun plan(regions: List<SpeechRegion>, turns: List<Turn>): List<PlannedSegment> {
        val sortedTurns = turns.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val pieces = mutableListOf<PlannedSegment>()
        for (r in regions.sortedBy { it.startMs }) {
            if (r.endMs <= r.startMs) continue
            pieces += splitRegion(r, sortedTurns)
        }
        val merged = mergeAcross(pieces)
        return renumber(merged.flatMap { splitLong(it) })
    }

    private fun speakerAt(startMs: Long, endMs: Long, turns: List<Turn>): Int {
        if (turns.isEmpty()) return 0
        var best = -1
        var bestOverlap = 0L
        for (t in turns) {
            if (t.startMs >= endMs) break
            val ov = min(endMs, t.endMs) - max(startMs, t.startMs)
            if (ov > bestOverlap) { bestOverlap = ov; best = t.speaker }
        }
        if (best >= 0) return best
        // Aucun tour ne couvre ce morceau : intervenant du tour le plus proche.
        val mid = (startMs + endMs) / 2
        return turns.minBy { t ->
            when {
                mid < t.startMs -> t.startMs - mid
                mid > t.endMs -> mid - t.endMs
                else -> 0
            }
        }.speaker
    }

    private fun splitRegion(r: SpeechRegion, turns: List<Turn>): List<PlannedSegment> {
        val cuts = sortedSetOf(r.startMs, r.endMs)
        for (t in turns) {
            if (t.startMs > r.startMs && t.startMs < r.endMs) cuts += t.startMs
            if (t.endMs > r.startMs && t.endMs < r.endMs) cuts += t.endMs
        }
        val bounds = cuts.toList()
        val raw = mutableListOf<PlannedSegment>()
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]; val e = bounds[i + 1]
            if (e <= s) continue
            val spk = speakerAt(s, e, turns)
            val last = raw.lastOrNull()
            if (last != null && last.speaker == spk) raw[raw.lastIndex] = last.copy(endMs = e)
            else raw += PlannedSegment(s, e, spk)
        }
        // Les morceaux trop courts (chevauchements, bruit) sont rattachés au voisin le plus long.
        val out = raw.toMutableList()
        var changed = true
        while (changed && out.size > 1) {
            changed = false
            val idx = out.indices.firstOrNull { out[it].durationMs < MIN_PIECE_MS } ?: break
            val p = out[idx]
            val left = out.getOrNull(idx - 1)
            val right = out.getOrNull(idx + 1)
            val target = when {
                left == null -> idx + 1
                right == null -> idx - 1
                left.durationMs >= right.durationMs -> idx - 1
                else -> idx + 1
            }
            val t = out[target]
            out[target] = t.copy(startMs = min(t.startMs, p.startMs), endMs = max(t.endMs, p.endMs))
            out.removeAt(idx)
            // Fusionne les voisins devenus identiques.
            var i = 1
            while (i < out.size) {
                if (out[i].speaker == out[i - 1].speaker) {
                    out[i - 1] = out[i - 1].copy(endMs = out[i].endMs); out.removeAt(i)
                } else i++
            }
            changed = true
        }
        return out
    }

    private fun mergeAcross(pieces: List<PlannedSegment>): List<PlannedSegment> {
        val out = mutableListOf<PlannedSegment>()
        for (p in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == p.speaker &&
                p.startMs - last.endMs <= MERGE_GAP_MS &&
                p.endMs - last.startMs <= TARGET_MERGE_MS
            ) out[out.lastIndex] = last.copy(endMs = p.endMs)
            else out += p
        }
        return out
    }

    private fun splitLong(p: PlannedSegment): List<PlannedSegment> {
        if (p.durationMs <= MAX_SEGMENT_MS) return listOf(p)
        val n = ((p.durationMs + MAX_SEGMENT_MS - 1) / MAX_SEGMENT_MS).toInt()
        val len = p.durationMs / n
        return (0 until n).map { i ->
            PlannedSegment(p.startMs + i * len, if (i == n - 1) p.endMs else p.startMs + (i + 1) * len, p.speaker)
        }
    }

    /** Numérote les intervenants dans l'ordre de première prise de parole (0, 1, 2…). */
    fun renumber(segments: List<PlannedSegment>): List<PlannedSegment> {
        val map = LinkedHashMap<Int, Int>()
        for (s in segments) if (s.speaker !in map) map[s.speaker] = map.size
        return segments.map { it.copy(speaker = map.getValue(it.speaker)) }
    }
}

/**
 * Regroupement des intervenants détectés dans des tranches successives d'une longue
 * réunion (clustering agglomératif sur la similarité cosinus des empreintes vocales).
 */
object SpeakerLinker {
    data class LocalSpeaker(val chunk: Int, val local: Int, val embedding: FloatArray)

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /**
     * @param numSpeakers nombre d'intervenants connu (>0) ou 0 pour une détection automatique.
     * @return pour chaque intervenant local, l'identifiant global.
     */
    fun link(
        speakers: List<LocalSpeaker>,
        numSpeakers: Int,
        threshold: Float = 0.5f,
    ): Map<Pair<Int, Int>, Int> {
        if (speakers.isEmpty()) return emptyMap()
        val clusters = speakers.map { mutableListOf(it) }.toMutableList()
        fun sim(a: List<LocalSpeaker>, b: List<LocalSpeaker>): Float {
            var s = 0f
            for (x in a) for (y in b) s += cosine(x.embedding, y.embedding)
            return s / (a.size * b.size)
        }
        fun conflict(a: List<LocalSpeaker>, b: List<LocalSpeaker>) =
            a.any { x -> b.any { y -> x.chunk == y.chunk } }

        while (clusters.size > 1) {
            if (numSpeakers > 0 && clusters.size <= numSpeakers) break
            var bi = -1; var bj = -1; var best = -2f
            var fallbackI = -1; var fallbackJ = -1; var fallbackBest = -2f
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val s = sim(clusters[i], clusters[j])
                if (!conflict(clusters[i], clusters[j])) {
                    if (s > best) { best = s; bi = i; bj = j }
                } else if (s > fallbackBest) { fallbackBest = s; fallbackI = i; fallbackJ = j }
            }
            if (numSpeakers > 0) {
                // Nombre imposé : on fusionne, en dernier recours même au sein d'une tranche.
                if (bi < 0) { bi = fallbackI; bj = fallbackJ }
            } else if (bi < 0 || best < threshold) break
            clusters[bi].addAll(clusters[bj])
            clusters.removeAt(bj)
        }
        val result = HashMap<Pair<Int, Int>, Int>()
        clusters.forEachIndexed { g, c -> for (s in c) result[s.chunk to s.local] = g }
        return result
    }
}

/** Supprime les « hallucinations » classiques de Whisper en français. */
object TextCleaner {
    private val junk = listOf(
        Regex("(?i)[^.!?]*amara\\.org[.!?]?"),
        Regex("(?i)sous-titr(es|age)[^.!?]*(radio-canada|st'? ?501|société|communauté)[^.!?]*[.!?]?"),
        Regex("(?i)merci d'avoir regardé( cette vidéo)?[ .!]*"),
        Regex("(?i)abonnez-vous[^.!?]*[.!?]?"),
    )

    fun clean(raw: String): String {
        var t = raw.trim()
        for (r in junk) t = r.replace(t, " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = collapseRepetitions(t)
        return if (t.all { !it.isLetterOrDigit() }) "" else t
    }

    /** « bla. bla. bla. bla. » (3 fois ou plus d'affilée) -> conservé une seule fois. */
    private fun collapseRepetitions(t: String): String {
        val parts = t.split(Regex("(?<=[.!?…])\\s+"))
        if (parts.size < 3) return t
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            var j = i
            while (j + 1 < parts.size && parts[j + 1].equals(parts[i], ignoreCase = true)) j++
            val run = j - i + 1
            if (run >= 3) out += parts[i] else for (k in i..j) out += parts[k]
            i = j + 1
        }
        return out.joinToString(" ")
    }
}
