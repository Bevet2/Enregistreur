package fr.reunion.enregistreur.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** États d'une réunion. Stockés en texte dans la base. */
object MeetingStatus {
    const val RECORDING = "RECORDING"
    const val RECORDED = "RECORDED"
    const val TRANSCRIBING = "TRANSCRIBING"
    const val INTERRUPTED = "INTERRUPTED"
    const val TRANSCRIBED = "TRANSCRIBED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "meetings")
data class Meeting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val audioPath: String,
    val status: String = MeetingStatus.RECORDING,
    /** Modèle choisi pour la transcription : "small" (qualité) ou "base" (rapide). */
    val model: String = "small",
    /** Nombre de participants indiqué par l'utilisateur (0 = détection automatique). */
    val numSpeakers: Int = 0,
    /** Étape interne de la transcription (pour la reprise). */
    val stage: Int = 0,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "speakers",
    foreignKeys = [ForeignKey(
        entity = Meeting::class, parentColumns = ["id"], childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("meetingId")],
)
data class Speaker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val name: String,
    /** Index de couleur stable (0, 1, 2…). */
    val colorIndex: Int,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Meeting::class, parentColumns = ["id"], childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("meetingId"), Index("speakerId")],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val speakerId: Long?,
    val startMs: Long,
    val endMs: Long,
    /** null = pas encore transcrit (permet la reprise). */
    val text: String?,
)

@Entity(
    tableName = "actions",
    foreignKeys = [ForeignKey(
        entity = Meeting::class, parentColumns = ["id"], childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("meetingId")],
)
data class ActionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val task: String,
    val owner: String,
    /** Échéance en millisecondes (minuit, heure locale), ou null. */
    val dueDate: Long?,
    val done: Boolean = false,
)
