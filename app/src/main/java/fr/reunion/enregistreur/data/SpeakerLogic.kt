package fr.reunion.enregistreur.data

/**
 * Règles métier (pures, sans Android) pour le renommage et la correction des intervenants.
 * Les intervenants sont référencés par identifiant dans les segments : renommer un
 * intervenant change donc son nom partout dans la transcription.
 */
object SpeakerLogic {

    sealed interface RenamePlan {
        /** Nom vide : rien à faire. */
        data object Invalid : RenamePlan
        /** Le nom est inchangé. */
        data object Unchanged : RenamePlan
        /** Renommage simple. */
        data class Rename(val speakerId: Long, val newName: String) : RenamePlan
        /** Le nom existe déjà pour un autre intervenant : on fusionne les deux. */
        data class Merge(val fromId: Long, val intoId: Long) : RenamePlan
    }

    fun defaultName(index: Int): String = "Personne ${index + 1}"

    fun normalize(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    fun planRename(speakers: List<Speaker>, speakerId: Long, newName: String): RenamePlan {
        val clean = normalize(newName)
        if (clean.isEmpty()) return RenamePlan.Invalid
        val current = speakers.firstOrNull { it.id == speakerId } ?: return RenamePlan.Invalid
        if (current.name == clean) return RenamePlan.Unchanged
        val existing = speakers.firstOrNull { it.id != speakerId && it.name.equals(clean, ignoreCase = true) }
        return if (existing != null) RenamePlan.Merge(fromId = speakerId, intoId = existing.id)
        else RenamePlan.Rename(speakerId, clean)
    }

    /** Applique un plan en mémoire (utilisé par les tests et pour l'aperçu). */
    fun apply(
        plan: RenamePlan,
        speakers: List<Speaker>,
        segments: List<Segment>,
    ): Pair<List<Speaker>, List<Segment>> = when (plan) {
        is RenamePlan.Rename -> speakers.map {
            if (it.id == plan.speakerId) it.copy(name = plan.newName) else it
        } to segments
        is RenamePlan.Merge -> speakers.filter { it.id != plan.fromId } to segments.map {
            if (it.speakerId == plan.fromId) it.copy(speakerId = plan.intoId) else it
        }
        else -> speakers to segments
    }

    /** Index de couleur pour un nouvel intervenant ajouté manuellement. */
    fun nextColorIndex(speakers: List<Speaker>): Int = (speakers.maxOfOrNull { it.colorIndex } ?: -1) + 1

    /** Nom par défaut pour un nouvel intervenant ajouté manuellement (évite les doublons). */
    fun nextDefaultName(speakers: List<Speaker>): String {
        var i = speakers.size
        while (speakers.any { it.name.equals(defaultName(i), ignoreCase = true) }) i++
        return defaultName(i)
    }
}
