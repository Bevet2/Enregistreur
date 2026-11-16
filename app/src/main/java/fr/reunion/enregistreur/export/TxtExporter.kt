package fr.reunion.enregistreur.export

import fr.reunion.enregistreur.util.formatTimestamp

object TxtExporter {
    fun export(doc: ExportDocument): String = buildString {
        append(doc.title).append('\n')
        append("=".repeat(doc.title.length.coerceIn(3, 80))).append("\n\n")
        append("Date : ").append(doc.dateText).append('\n')
        append("Durée : ").append(doc.durationText).append('\n')
        if (doc.participants.isNotEmpty()) {
            append("Participants : ").append(doc.participants.joinToString(", ")).append('\n')
        }
        append("\nTRANSCRIPTION\n\n")
        if (doc.blocks.isEmpty()) append("(aucune transcription)\n")
        for (b in doc.blocks) {
            append('[').append(formatTimestamp(b.startMs)).append("] ").append(b.speaker).append(" :\n")
            append(b.text).append("\n\n")
        }
        if (doc.actions.isNotEmpty()) {
            append("ACTIONS\n\n")
            for (a in doc.actions) {
                append("- ").append(a.task)
                if (a.owner.isNotBlank()) append(" — responsable : ").append(a.owner)
                if (a.due != null) append(" — échéance : ").append(a.due)
                append('\n')
            }
        }
    }
}
