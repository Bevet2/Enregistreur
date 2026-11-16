package fr.reunion.enregistreur.transcription

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.reunion.enregistreur.EnregistreurApp
import fr.reunion.enregistreur.MainActivity
import fr.reunion.enregistreur.R
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.util.formatRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Service de transcription en arrière-plan, avec progression dans la notification.
 * Les réunions demandées sont traitées une par une.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ConcurrentLinkedDeque<Long>()
    private var worker: Job? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private var currentTitle = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_MEETING_ID, -1)
                goForeground()
                if (id >= 0 && id !in queue && TranscriptionState.state.value.meetingId != id) queue.add(id)
                publishQueue()
                if (worker?.isActive != true) worker = scope.launch { work() }
            }
            ACTION_CANCEL -> {
                queue.clear()
                cancelled = true
                publishQueue()
                if (worker?.isActive != true) stopSelf()
            }
            else -> if (worker?.isActive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, progressNotification("Préparation…", 0, null), type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Enregistreur:transcription")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun publishQueue() {
        TranscriptionState.mutable.value = TranscriptionState.mutable.value.copy(queued = queue.toList())
    }

    private suspend fun work() {
        val app = EnregistreurApp.instance
        val repo = app.repository
        while (true) {
            val id = queue.pollFirst() ?: break
            cancelled = false
            publishQueue()
            val meeting = repo.getMeeting(id) ?: continue
            currentTitle = meeting.title
            TranscriptionState.mutable.value = TranscriptionState.mutable.value.copy(
                meetingId = id, stage = "Préparation…", percent = 0, etaSeconds = null,
            )
            repo.setStatus(id, MeetingStatus.TRANSCRIBING)
            try {
                TranscriptionPipeline(this, repo, meeting, { cancelled }) { stage, percent, eta ->
                    TranscriptionState.mutable.value = TranscriptionState.mutable.value.copy(
                        stage = stage, percent = percent, etaSeconds = eta,
                    )
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 1000) {
                        lastNotify = now
                        notifyProgress(stage, percent, eta)
                    }
                }.run()
                repo.setStatus(id, MeetingStatus.TRANSCRIBED)
                notifyDone(id, meeting.title, null)
            } catch (c: TranscriptionPipeline.Cancelled) {
                repo.setStatus(id, MeetingStatus.INTERRUPTED)
            } catch (t: Throwable) {
                Log.e("TranscriptionService", "Échec de la transcription", t)
                repo.setStatus(id, MeetingStatus.FAILED, t.message ?: t.javaClass.simpleName)
                notifyDone(id, meeting.title, t.message ?: "erreur inconnue")
            }
            TranscriptionPipeline.setStep(this, null)
        }
        TranscriptionState.mutable.value = TranscriptionState.State()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progressNotification(stage: String, percent: Int, eta: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 11, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append(stage).append(" — ").append(percent).append(" %")
            if (eta != null) append(" — reste ").append(formatRemaining(eta))
        }
        return NotificationCompat.Builder(this, EnregistreurApp.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (currentTitle.isBlank()) "Transcription" else "Transcription : $currentTitle")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Interrompre", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyProgress(stage: String, percent: Int, eta: Long?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, progressNotification(stage, percent, eta))
    }

    private fun notifyDone(meetingId: Long, title: String, error: String?) {
        val open = PendingIntent.getActivity(
            this, meetingId.toInt(),
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MEETING, meetingId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, EnregistreurApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (error == null) "Transcription terminée" else "Échec de la transcription")
            .setContentText(if (error == null) title else "$title : $error")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(DONE_NOTIF_BASE + meetingId.toInt(), n)
        } catch (_: SecurityException) { }
    }

    /** Android 15 : délai maximal d'un service de traitement atteint -> on s'arrête proprement. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.clear()
        cancelled = true
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val DONE_NOTIF_BASE = 3000
        const val ACTION_START = "start"
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_MEETING_ID = "meetingId"

        fun start(context: Context, meetingId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_MEETING_ID, meetingId),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
