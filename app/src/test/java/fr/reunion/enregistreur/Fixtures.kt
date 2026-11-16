package fr.reunion.enregistreur

import fr.reunion.enregistreur.data.ActionItem
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.data.Segment
import fr.reunion.enregistreur.data.Speaker
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

object Fixtures {
    val zone: ZoneId = ZoneId.of("Europe/Paris")

    // 16 novembre 2026 à 14h30, heure de Paris
    val meeting = Meeting(
        id = 1, title = "Comité <projet> & budget", createdAt = 1794835800000L,
        durationMs = 3_725_000, audioPath = "/x/audio.wav", status = MeetingStatus.TRANSCRIBED,
    )
    val speakers = listOf(
        Speaker(id = 10, meetingId = 1, name = "Personne 1", colorIndex = 0),
        Speaker(id = 11, meetingId = 1, name = "Personne 2", colorIndex = 1),
    )
    val segments = listOf(
        Segment(id = 100, meetingId = 1, speakerId = 10, startMs = 0, endMs = 4000, text = "Bonjour à tous."),
        Segment(id = 101, meetingId = 1, speakerId = 10, startMs = 4500, endMs = 8000, text = "Commençons."),
        Segment(id = 102, meetingId = 1, speakerId = 11, startMs = 65_000, endMs = 70_000, text = "Le budget est \"validé\" < 10 k€."),
        Segment(id = 103, meetingId = 1, speakerId = 10, startMs = 3_661_000, endMs = 3_665_000, text = "Merci."),
        Segment(id = 104, meetingId = 1, speakerId = 11, startMs = 3_670_000, endMs = 3_671_000, text = ""),
    )
    val actions = listOf(
        ActionItem(
            id = 1, meetingId = 1, task = "Envoyer le compte rendu", owner = "Alice",
            dueDate = LocalDate.of(2026, 11, 20).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        ),
    )
}
