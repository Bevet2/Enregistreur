package fr.reunion.enregistreur.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.io.File

class Repository(private val db: AppDatabase, private val filesDir: File) {
    private val meetings = db.meetings()
    private val speakers = db.speakers()
    private val segments = db.segments()
    private val actions = db.actions()

    fun observeMeetings(query: String): Flow<List<Meeting>> {
        val q = query.trim()
        return if (q.isEmpty()) meetings.observeAll()
        else meetings.search("%" + q.replace("%", "").replace("_", "") + "%")
    }

    fun observeMeeting(id: Long) = meetings.observe(id)
    fun observeSpeakers(meetingId: Long) = speakers.observe(meetingId)
    fun observeSegments(meetingId: Long) = segments.observe(meetingId)
    fun observeActions(meetingId: Long) = actions.observe(meetingId)

    suspend fun getMeeting(id: Long) = meetings.get(id)
    suspend fun speakers(meetingId: Long) = speakers.list(meetingId)
    suspend fun segments(meetingId: Long) = segments.list(meetingId)
    suspend fun actions(meetingId: Long) = actions.list(meetingId)
    suspend fun meetingsWithStatus(status: String) = meetings.byStatus(status)

    fun meetingDir(id: Long): File = File(filesDir, "meetings/$id").apply { mkdirs() }

    /** Crée une réunion et son dossier ; renvoie (id, fichier audio). */
    suspend fun createMeeting(title: String, createdAt: Long): Pair<Long, File> {
        val id = meetings.insert(Meeting(title = title, createdAt = createdAt, audioPath = ""))
        val audio = File(meetingDir(id), "audio.wav")
        meetings.update(meetings.get(id)!!.copy(audioPath = audio.absolutePath))
        return id to audio
    }

    suspend fun updateMeeting(m: Meeting) = meetings.update(m)
    suspend fun setStatus(id: Long, status: String, error: String? = null) = meetings.setStatus(id, status, error)
    suspend fun setStage(id: Long, stage: Int) = meetings.setStage(id, stage)
    suspend fun setDuration(id: Long, ms: Long) = meetings.setDuration(id, ms)

    suspend fun renameMeeting(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) meetings.rename(id, clean)
    }

    /** Supprime la réunion, sa transcription et son audio. */
    suspend fun deleteMeeting(id: Long) {
        val m = meetings.get(id) ?: return
        meetings.delete(m)
        File(filesDir, "meetings/$id").deleteRecursively()
    }

    // ----- Intervenants -----

    suspend fun renameSpeaker(meetingId: Long, speakerId: Long, newName: String) {
        db.withTransaction {
            val list = speakers.list(meetingId)
            when (val plan = SpeakerLogic.planRename(list, speakerId, newName)) {
                is SpeakerLogic.RenamePlan.Rename -> speakers.rename(plan.speakerId, plan.newName)
                is SpeakerLogic.RenamePlan.Merge -> {
                    segments.moveSpeaker(plan.fromId, plan.intoId)
                    speakers.delete(plan.fromId)
                }
                else -> Unit
            }
        }
    }

    suspend fun setSegmentSpeaker(segmentId: Long, speakerId: Long) = segments.setSpeaker(segmentId, speakerId)

    /** Ajoute un intervenant (correction manuelle) et l'affecte au segment. */
    suspend fun assignNewSpeaker(meetingId: Long, segmentId: Long): Long = db.withTransaction {
        val list = speakers.list(meetingId)
        val id = speakers.insert(
            Speaker(
                meetingId = meetingId,
                name = SpeakerLogic.nextDefaultName(list),
                colorIndex = SpeakerLogic.nextColorIndex(list),
            )
        )
        segments.setSpeaker(segmentId, id)
        id
    }

    /** Supprime les intervenants qui n'ont plus aucun segment. */
    suspend fun pruneSpeakers(meetingId: Long) {
        for (s in speakers.list(meetingId)) if (segments.countForSpeaker(s.id) == 0) speakers.delete(s.id)
    }

    suspend fun setSegmentText(segmentId: Long, text: String) = segments.setText(segmentId, text)

    // ----- Transcription -----

    suspend fun clearTranscription(meetingId: Long) = db.withTransaction {
        segments.deleteForMeeting(meetingId)
        speakers.deleteForMeeting(meetingId)
    }

    /** Enregistre le plan de segments (sans texte) et les intervenants détectés. */
    suspend fun savePlan(meetingId: Long, speakerCount: Int, plan: List<Triple<Long, Long, Int>>) =
        db.withTransaction {
            segments.deleteForMeeting(meetingId)
            speakers.deleteForMeeting(meetingId)
            val ids = (0 until speakerCount).map { i ->
                speakers.insert(Speaker(meetingId = meetingId, name = SpeakerLogic.defaultName(i), colorIndex = i))
            }
            segments.insertAll(plan.map { (start, end, spk) ->
                Segment(
                    meetingId = meetingId,
                    speakerId = ids.getOrNull(spk),
                    startMs = start, endMs = end, text = null,
                )
            })
        }

    // ----- Actions -----

    suspend fun addAction(item: ActionItem) = actions.insert(item)
    suspend fun updateAction(item: ActionItem) = actions.update(item)
    suspend fun deleteAction(item: ActionItem) = actions.delete(item)
}
