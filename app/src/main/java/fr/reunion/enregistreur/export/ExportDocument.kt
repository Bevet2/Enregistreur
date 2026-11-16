package fr.reunion.enregistreur.export

import fr.reunion.enregistreur.data.ActionItem
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.Segment
import fr.reunion.enregistreur.data.Speaker
import fr.reunion.enregistreur.util.formatDateLong
import fr.reunion.enregistreur.util.formatDay
import fr.reunion.enregistreur.util.formatDuration
import java.time.ZoneId
import java.time.ZoneOffset

/** Palette de couleurs des intervenants (partagée entre l'écran et le .docx). */
val SPEAKER_COLORS = listOf(
    "1E63B5", "C2410C", "15803D", "9333EA", "B91C1C",
    "0E7490", "A16207", "BE185D", "4D7C0F", "4338CA",
)

fun speakerColorHex(index: Int) = SPEAKER_COLORS[((index % SPEAKER_COLORS.size) + SPEAKER_COLORS.size) % SPEAKER_COLORS.size]

/** Représentation neutre d'une réunion à exporter, indépendante du format. */
data class ExportDocument(
    val title: String,
    val dateText: String,
    val durationText: String,
    val participants: List<String>,
    val blocks: List<Block>,
    val actions: List<Action>,
) {
    /** Prise de parole : segments consécutifs d'un même intervenant regroupés. */
    data class Block(val speaker: String, val colorHex: String, val startMs: Long, val text: String)
    data class Action(val task: String, val owner: String, val due: String?)

    companion object {
        const val UNKNOWN_SPEAKER = "Intervenant inconnu"

        fun from(
            meeting: Meeting,
            speakers: List<Speaker>,
            segments: List<Segment>,
            actions: List<ActionItem> = emptyList(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): ExportDocument {
            val byId = speakers.associateBy { it.id }
            val blocks = mutableListOf<Block>()
            var lastSpeakerId: Long? = Long.MIN_VALUE
            for (s in segments.sortedBy { it.startMs }) {
                val text = s.text?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sp = s.speakerId?.let { byId[it] }
                if (blocks.isNotEmpty() && s.speakerId == lastSpeakerId) {
                    val last = blocks.removeAt(blocks.lastIndex)
                    blocks += last.copy(text = last.text + " " + text)
                } else {
                    blocks += Block(
                        speaker = sp?.name ?: UNKNOWN_SPEAKER,
                        colorHex = sp?.let { speakerColorHex(it.colorIndex) } ?: "555555",
                        startMs = s.startMs,
                        text = text,
                    )
                }
                lastSpeakerId = s.speakerId
            }
            val usedIds = segments.filter { !it.text.isNullOrBlank() }.mapNotNull { it.speakerId }.toSet()
            val participants = speakers.sortedBy { it.colorIndex }.filter { it.id in usedIds }.map { it.name }
            return ExportDocument(
                title = meeting.title,
                dateText = formatDateLong(meeting.createdAt, zone),
                durationText = formatDuration(meeting.durationMs),
                participants = participants,
                blocks = blocks,
                actions = actions.map { Action(it.task, it.owner, it.dueDate?.let { d -> formatDay(d, ZoneOffset.UTC) }) },
            )
        }
    }
}
