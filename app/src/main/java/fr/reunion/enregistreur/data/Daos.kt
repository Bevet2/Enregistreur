package fr.reunion.enregistreur.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Meeting>>

    @Query(
        """SELECT DISTINCT m.* FROM meetings m
           LEFT JOIN segments s ON s.meetingId = m.id
           LEFT JOIN speakers p ON p.meetingId = m.id
           WHERE m.title LIKE :pattern OR s.text LIKE :pattern OR p.name LIKE :pattern
           ORDER BY m.createdAt DESC"""
    )
    fun search(pattern: String): Flow<List<Meeting>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    fun observe(id: Long): Flow<Meeting?>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun get(id: Long): Meeting?

    @Query("SELECT * FROM meetings WHERE status = :status")
    suspend fun byStatus(status: String): List<Meeting>

    @Insert
    suspend fun insert(meeting: Meeting): Long

    @Update
    suspend fun update(meeting: Meeting)

    @Query("UPDATE meetings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE meetings SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String? = null)

    @Query("UPDATE meetings SET stage = :stage WHERE id = :id")
    suspend fun setStage(id: Long, stage: Int)

    @Query("UPDATE meetings SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long)

    @Delete
    suspend fun delete(meeting: Meeting)
}

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers WHERE meetingId = :meetingId ORDER BY colorIndex")
    fun observe(meetingId: Long): Flow<List<Speaker>>

    @Query("SELECT * FROM speakers WHERE meetingId = :meetingId ORDER BY colorIndex")
    suspend fun list(meetingId: Long): List<Speaker>

    @Insert
    suspend fun insert(speaker: Speaker): Long

    @Query("UPDATE speakers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM speakers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM speakers WHERE meetingId = :meetingId")
    suspend fun deleteForMeeting(meetingId: Long)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE meetingId = :meetingId ORDER BY startMs")
    fun observe(meetingId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE meetingId = :meetingId ORDER BY startMs")
    suspend fun list(meetingId: Long): List<Segment>

    @Insert
    suspend fun insertAll(segments: List<Segment>)

    @Query("UPDATE segments SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("UPDATE segments SET speakerId = :speakerId WHERE id = :id")
    suspend fun setSpeaker(id: Long, speakerId: Long?)

    @Query("UPDATE segments SET speakerId = :to WHERE speakerId = :from")
    suspend fun moveSpeaker(from: Long, to: Long)

    @Query("SELECT COUNT(*) FROM segments WHERE speakerId = :speakerId")
    suspend fun countForSpeaker(speakerId: Long): Int

    @Query("DELETE FROM segments WHERE meetingId = :meetingId")
    suspend fun deleteForMeeting(meetingId: Long)
}

@Dao
interface ActionDao {
    @Query("SELECT * FROM actions WHERE meetingId = :meetingId ORDER BY id")
    fun observe(meetingId: Long): Flow<List<ActionItem>>

    @Query("SELECT * FROM actions WHERE meetingId = :meetingId ORDER BY id")
    suspend fun list(meetingId: Long): List<ActionItem>

    @Insert
    suspend fun insert(item: ActionItem): Long

    @Update
    suspend fun update(item: ActionItem)

    @Delete
    suspend fun delete(item: ActionItem)
}
