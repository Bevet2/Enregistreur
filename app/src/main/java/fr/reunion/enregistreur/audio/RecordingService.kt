package fr.reunion.enregistreur.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.reunion.enregistreur.EnregistreurApp
import fr.reunion.enregistreur.MainActivity
import fr.reunion.enregistreur.R
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.util.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/** État partagé de l'enregistrement, observé par l'interface. */
object RecorderState {
    data class State(
        val meetingId: Long? = null,
        val paused: Boolean = false,
        val elapsedMs: Long = 0,
        val level: Float = 0f,
    )

    internal val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
    fun isActive() = mutable.value.meetingId != null
}

/**
 * Service de premier plan (type micro) : l'enregistrement continue écran verrouillé
 * et application en arrière-plan, sans limite de durée.
 */
class RecordingService : Service() {

    @Volatile private var paused = false
    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var meetingId: Long = -1
    private var chronoBase = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_MEETING_ID, -1)
                val path = intent.getStringExtra(EXTRA_PATH)
                if (!running && id >= 0 && path != null) start(id, File(path)) else if (!running) stopSelf()
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopRecording()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long, file: File) {
        meetingId = id
        paused = false
        chronoBase = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Enregistreur:recording").apply {
            setReferenceCounted(false); acquire()
        }
        running = true
        RecorderState.mutable.value = RecorderState.State(meetingId = id)
        thread = Thread({ recordLoop(file) }, "audio-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(file: File) {
        val minBuf = AudioRecord.getMinBufferSize(
            Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, Wav.SAMPLE_RATE) // >= 0,5 s de tampon
        var record: AudioRecord? = null
        var writer: WavWriter? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, Wav.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2,
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Micro indisponible" }
            writer = WavWriter(file)
            record.startRecording()
            val block = ShortArray(Wav.SAMPLE_RATE / 10) // 100 ms
            var lastSync = SystemClock.elapsedRealtime()
            while (running) {
                val n = record.read(block, 0, block.size)
                if (n <= 0) {
                    if (n < 0) Thread.sleep(20)
                    continue
                }
                var peak = 0
                for (i in 0 until n) { val a = abs(block[i].toInt()); if (a > peak) peak = a }
                if (!paused) writer.write(block, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastSync > 2000) { writer.sync(); lastSync = now }
                RecorderState.mutable.value = RecorderState.mutable.value.copy(
                    elapsedMs = writer.samplesWritten * 1000 / Wav.SAMPLE_RATE,
                    level = if (paused) 0f else peak / 32768f,
                    paused = paused,
                )
            }
        } catch (t: Throwable) {
            android.util.Log.e("RecordingService", "Erreur d'enregistrement", t)
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            try { writer?.close() } catch (_: Throwable) {}
            finishInDatabase(file)
        }
    }

    private fun finishInDatabase(file: File) {
        val app = EnregistreurApp.instance
        val id = meetingId
        runBlocking {
            Wav.repairHeader(file)
            app.repository.setDuration(id, Wav.durationMs(file))
            app.repository.setStatus(id, MeetingStatus.RECORDED)
        }
        RecorderState.mutable.value = RecorderState.State()
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setPaused(p: Boolean) {
        if (!running) return
        paused = p
        val elapsed = RecorderState.mutable.value.elapsedMs
        RecorderState.mutable.value = RecorderState.mutable.value.copy(paused = p)
        chronoBase = System.currentTimeMillis() - elapsed
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(elapsed))
    }

    private fun stopRecording() {
        if (!running) { stopSelf(); return }
        running = false // la boucle se termine et finalise le fichier
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, EnregistreurApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Enregistrement en pause" else "Enregistrement en cours")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (paused) {
            b.setContentText("Durée enregistrée : ${formatDuration(elapsedMs)}")
            b.addAction(0, "Reprendre", pending(ACTION_RESUME, 1))
        } else {
            b.setUsesChronometer(true).setWhen(chronoBase).setShowWhen(true)
            b.setContentText("Touchez pour ouvrir l'application")
            b.addAction(0, "Pause", pending(ACTION_PAUSE, 2))
        }
        b.addAction(0, "Arrêter", pending(ACTION_STOP, 3))
        return b.build()
    }

    private fun pending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        running = false
        thread?.join(3000)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val EXTRA_MEETING_ID = "meetingId"
        const val EXTRA_PATH = "path"

        fun start(context: Context, meetingId: Long, file: File) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId).putExtra(EXTRA_PATH, file.absolutePath)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
