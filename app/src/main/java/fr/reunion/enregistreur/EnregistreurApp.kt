package fr.reunion.enregistreur

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import fr.reunion.enregistreur.audio.RecorderState
import fr.reunion.enregistreur.audio.Wav
import fr.reunion.enregistreur.data.AppDatabase
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.data.Repository
import fr.reunion.enregistreur.transcription.TranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class EnregistreurApp : Application() {
    lateinit var repository: Repository
        private set

    /** Portée applicative pour les écritures qui doivent survivre à un écran. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = Repository(AppDatabase.create(this), filesDir)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Enregistrement", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Enregistrement de réunion en cours"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSCRIPTION, "Transcription", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progression de la transcription"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Transcription terminée", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * Remet en état les réunions laissées « en cours » par un arrêt brutal de l'app :
     * l'audio déjà écrit est conservé et son en-tête réparé.
     */
    suspend fun recoverInterrupted() {
        if (!RecorderState.isActive()) {
            for (m in repository.meetingsWithStatus(MeetingStatus.RECORDING)) {
                val f = File(m.audioPath)
                Wav.repairHeader(f)
                repository.setDuration(m.id, Wav.durationMs(f))
                repository.setStatus(m.id, MeetingStatus.RECORDED)
            }
        }
        if (TranscriptionState.state.value.meetingId == null) {
            for (m in repository.meetingsWithStatus(MeetingStatus.TRANSCRIBING)) {
                repository.setStatus(m.id, MeetingStatus.INTERRUPTED)
            }
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIPTION = "transcription"
        const val CHANNEL_DONE = "transcription_done"
        lateinit var instance: EnregistreurApp
            private set
    }
}
