package fr.reunion.enregistreur.ui

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.reunion.enregistreur.EnregistreurApp
import fr.reunion.enregistreur.audio.RecordingService
import fr.reunion.enregistreur.data.ActionItem
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.data.Segment
import fr.reunion.enregistreur.data.Speaker
import fr.reunion.enregistreur.export.DocxExporter
import fr.reunion.enregistreur.export.ExportDocument
import fr.reunion.enregistreur.export.TxtExporter
import fr.reunion.enregistreur.transcription.TranscriptionPipeline
import fr.reunion.enregistreur.transcription.TranscriptionService
import fr.reunion.enregistreur.util.safeFileName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class ListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as EnregistreurApp).repository
    val query = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val meetings: StateFlow<List<Meeting>> = query.debounce(200)
        .flatMapLatest { repo.observeMeetings(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startRecording(onStarted: (Long) -> Unit) {
        viewModelScope.launch {
            val title = "Réunion du " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH))
            val (id, file) = repo.createMeeting(title, System.currentTimeMillis())
            RecordingService.start(getApplication(), id, file)
            onStarted(id)
        }
    }
}

class DetailViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val application = app as EnregistreurApp
    private val repo = application.repository
    val meetingId: Long = savedState.get<Long>("id") ?: savedState.get<String>("id")!!.toLong()

    val meeting = repo.observeMeeting(meetingId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val speakers = repo.observeSpeakers(meetingId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val segments = repo.observeSegments(meetingId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val actions = repo.observeActions(meetingId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------- Lecture audio
    data class Player(val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val visible: Boolean = false)

    val player = MutableStateFlow(Player())
    private var mediaPlayer: MediaPlayer? = null
    private var ticker: Job? = null

    private fun ensurePlayer(): MediaPlayer? {
        mediaPlayer?.let { return it }
        val path = meeting.value?.audioPath ?: return null
        if (!File(path).exists()) return null
        return try {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { player.value = player.value.copy(playing = false) }
            }.also { mediaPlayer = it }
        } catch (t: Throwable) { null }
    }

    fun playFrom(ms: Long) {
        val mp = ensurePlayer() ?: return
        mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        mp.start()
        player.value = Player(true, ms, mp.duration.toLong(), true)
        startTicker()
    }

    fun togglePlay() {
        val mp = ensurePlayer() ?: return
        if (mp.isPlaying) mp.pause() else { mp.start(); startTicker() }
        player.value = player.value.copy(playing = mp.isPlaying, durationMs = mp.duration.toLong(), visible = true)
    }

    fun seek(ms: Long) {
        val mp = ensurePlayer() ?: return
        mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        player.value = player.value.copy(positionMs = ms)
    }

    fun closePlayer() {
        ticker?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        player.value = Player()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val mp = mediaPlayer ?: break
                player.value = player.value.copy(positionMs = mp.currentPosition.toLong(), playing = mp.isPlaying)
                if (!mp.isPlaying) break
                delay(200)
            }
        }
    }

    override fun onCleared() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ------------------------------------------------------------- Édition
    fun rename(title: String) = viewModelScope.launch { repo.renameMeeting(meetingId, title) }
    fun renameSpeaker(speaker: Speaker, name: String) =
        viewModelScope.launch { repo.renameSpeaker(meetingId, speaker.id, name) }
    fun setSegmentSpeaker(segment: Segment, speaker: Speaker) =
        viewModelScope.launch { repo.setSegmentSpeaker(segment.id, speaker.id) }
    fun setSegmentNewSpeaker(segment: Segment) =
        viewModelScope.launch { repo.assignNewSpeaker(meetingId, segment.id) }
    fun setSegmentText(segment: Segment, text: String) =
        viewModelScope.launch { repo.setSegmentText(segment.id, text.trim()) }

    fun delete(onDone: () -> Unit) {
        closePlayer()
        application.appScope.launch {
            repo.deleteMeeting(meetingId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    // ------------------------------------------------------------- Transcription
    fun startTranscription(model: String, numSpeakers: Int) {
        val m = meeting.value ?: return
        application.appScope.launch {
            repo.clearTranscription(meetingId)
            TranscriptionPipeline.clearIntermediate(repo.meetingDir(meetingId))
            repo.updateMeeting(
                m.copy(model = model, numSpeakers = numSpeakers, stage = 0,
                    status = MeetingStatus.TRANSCRIBING, errorMessage = null)
            )
            withContext(Dispatchers.Main) { TranscriptionService.start(application, meetingId) }
        }
    }

    fun resumeTranscription() = TranscriptionService.start(application, meetingId)
    fun cancelTranscription() = TranscriptionService.cancel(application)

    // ------------------------------------------------------------- Actions
    fun addAction(task: String, owner: String, due: Long?) = viewModelScope.launch {
        if (task.isNotBlank()) repo.addAction(ActionItem(meetingId = meetingId, task = task.trim(), owner = owner.trim(), dueDate = due))
    }
    fun updateAction(item: ActionItem) = viewModelScope.launch { repo.updateAction(item) }
    fun deleteAction(item: ActionItem) = viewModelScope.launch { repo.deleteAction(item) }

    // ------------------------------------------------------------- Export
    /** Prépare le fichier et renvoie l'Intent de partage Android. */
    suspend fun buildShareIntent(docx: Boolean): Intent? = withContext(Dispatchers.IO) {
        val m = repo.getMeeting(meetingId) ?: return@withContext null
        val doc = ExportDocument.from(m, repo.speakers(meetingId), repo.segments(meetingId), repo.actions(meetingId))
        val dir = File(application.cacheDir, "exports")
        dir.deleteRecursively() // ne garde aucun ancien export en clair
        dir.mkdirs()
        val name = safeFileName(m.title) + if (docx) ".docx" else ".txt"
        val file = File(dir, name)
        if (docx) file.outputStream().use { DocxExporter.write(doc, it) }
        else file.writeText(TxtExporter.export(doc))
        val uri = FileProvider.getUriForFile(application, application.packageName + ".fileprovider", file)
        val mime = if (docx) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "text/plain"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, m.title)
            clipData = android.content.ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Envoyer la transcription").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
