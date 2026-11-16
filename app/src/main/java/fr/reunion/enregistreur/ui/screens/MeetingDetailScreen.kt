package fr.reunion.enregistreur.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.reunion.enregistreur.data.ActionItem
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.data.Segment
import fr.reunion.enregistreur.data.Speaker
import fr.reunion.enregistreur.transcription.TranscriptionState
import fr.reunion.enregistreur.ui.DetailViewModel
import fr.reunion.enregistreur.ui.theme.speakerColor
import fr.reunion.enregistreur.util.formatDateLong
import fr.reunion.enregistreur.util.formatDay
import fr.reunion.enregistreur.util.formatDuration
import fr.reunion.enregistreur.util.formatRemaining
import fr.reunion.enregistreur.util.formatTimestamp
import kotlinx.coroutines.launch
import java.time.ZoneOffset

private sealed interface DialogState {
    data object RenameMeeting : DialogState
    data object Delete : DialogState
    data object Transcribe : DialogState
    data class EditText(val segment: Segment) : DialogState
    data class PickSpeaker(val segment: Segment) : DialogState
    data class RenameSpeaker(val speaker: Speaker) : DialogState
    data class EditAction(val item: ActionItem?) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(onBack: () -> Unit, vm: DetailViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val progress by TranscriptionState.state.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    val m = meeting ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val speakerById = remember(speakers) { speakers.associateBy { it.id } }
    val hasText = segments.any { !it.text.isNullOrBlank() }

    fun share(docx: Boolean) {
        scope.launch {
            val intent = vm.buildShareIntent(docx)
            if (intent != null) context.startActivity(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.closePlayer(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }, enabled = hasText) { Icon(Icons.Default.Share, "Exporter") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(text = { Text("Envoyer en Word (.docx)") }, onClick = { shareMenu = false; share(true) })
                            DropdownMenuItem(text = { Text("Envoyer en texte (.txt)") }, onClick = { shareMenu = false; share(false) })
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; dialog = DialogState.RenameMeeting })
                            if (m.status != MeetingStatus.RECORDING && m.status != MeetingStatus.TRANSCRIBING) {
                                DropdownMenuItem(
                                    text = { Text(if (segments.isEmpty()) "Transcrire" else "Retranscrire") },
                                    onClick = { menu = false; dialog = DialogState.Transcribe },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; dialog = DialogState.Delete },
                                enabled = m.status != MeetingStatus.RECORDING,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (player.visible) PlayerBar(
                player = player,
                onToggle = vm::togglePlay,
                onSeek = vm::seek,
                onClose = vm::closePlayer,
            )
        },
        floatingActionButton = {
            if (tab == 2) FloatingActionButton(onClick = { dialog = DialogState.EditAction(null) }) {
                Icon(Icons.Default.Add, "Ajouter une action")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(m, progress, isCurrent = progress.meetingId == m.id, queued = m.id in progress.queued,
                onTranscribe = { dialog = DialogState.Transcribe },
                onResume = vm::resumeTranscription,
                onCancel = vm::cancelTranscription,
                onPlay = { vm.playFrom(0) },
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcription") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Intervenants (${speakers.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Actions (${actions.size})") })
            }
            when (tab) {
                0 -> TranscriptTab(
                    segments, speakerById,
                    playingPositionMs = if (player.visible) player.positionMs else null,
                    onPlay = { vm.playFrom(it.startMs) },
                    onEdit = { dialog = DialogState.EditText(it) },
                    onSpeaker = { dialog = DialogState.PickSpeaker(it) },
                )
                1 -> SpeakersTab(speakers, segments, onRename = { dialog = DialogState.RenameSpeaker(it) })
                2 -> ActionsTab(
                    actions, m,
                    onEdit = { dialog = DialogState.EditAction(it) },
                    onToggle = { vm.updateAction(it.copy(done = !it.done)) },
                    onDelete = { vm.deleteAction(it) },
                    onCalendar = { addToCalendar(context, it, m) },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        DialogState.RenameMeeting -> TextInputDialog("Renommer la réunion", m.title, "Titre",
            onDismiss = { dialog = null }) { vm.rename(it); dialog = null }
        DialogState.Delete -> ConfirmDialog(
            "Supprimer la réunion ?",
            "L'enregistrement audio, la transcription et les actions seront définitivement effacés de ce téléphone.",
            "Supprimer", onDismiss = { dialog = null },
        ) { dialog = null; vm.delete(onBack) }
        DialogState.Transcribe -> TranscribeOptionsDialog(
            m.durationMs, m.model, m.numSpeakers, onDismiss = { dialog = null },
        ) { model, n -> dialog = null; vm.startTranscription(model, n) }
        is DialogState.EditText -> TextInputDialog("Modifier le texte", d.segment.text ?: "", "Texte",
            singleLine = false, onDismiss = { dialog = null }) { vm.setSegmentText(d.segment, it); dialog = null }
        is DialogState.PickSpeaker -> SpeakerPickerDialog(
            speakers, d.segment.speakerId, onDismiss = { dialog = null },
            onPick = { vm.setSegmentSpeaker(d.segment, it); dialog = null },
            onNew = { vm.setSegmentNewSpeaker(d.segment); dialog = null },
        )
        is DialogState.RenameSpeaker -> TextInputDialog(
            "Renommer l'intervenant", d.speaker.name, "Nom (appliqué partout)",
            onDismiss = { dialog = null },
        ) { vm.renameSpeaker(d.speaker, it); dialog = null }
        is DialogState.EditAction -> ActionDialog(d.item, onDismiss = { dialog = null }) { task, owner, due ->
            if (d.item == null) vm.addAction(task, owner, due)
            else vm.updateAction(d.item.copy(task = task.trim(), owner = owner.trim(), dueDate = due))
            dialog = null
        }
    }
}

@Composable
private fun Header(
    m: Meeting,
    progress: TranscriptionState.State,
    isCurrent: Boolean,
    queued: Boolean,
    onTranscribe: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDateLong(m.createdAt) + " · " + formatDuration(m.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (m.status != MeetingStatus.RECORDING) TextButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, null); Text("Écouter")
            }
        }
        when {
            isCurrent || (m.status == MeetingStatus.TRANSCRIBING && queued) -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp).fillMaxWidth()) {
                    if (isCurrent) {
                        Text("${progress.stage} — ${progress.percent} %", fontWeight = FontWeight.Bold)
                        progress.etaSeconds?.let { Text("Temps restant estimé : " + formatRemaining(it), style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Vous pouvez quitter l'application : la transcription continue en arrière-plan.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                        )
                    } else Text("En attente de transcription…")
                    TextButton(onClick = onCancel) { Text("Interrompre") }
                }
            }
            m.status == MeetingStatus.TRANSCRIBING -> InfoCard(
                "Transcription en attente de reprise.", "Reprendre", onResume,
            )
            m.status == MeetingStatus.RECORDED -> InfoCard(
                "Cette réunion n'est pas encore transcrite.", "Transcrire", onTranscribe,
            )
            m.status == MeetingStatus.INTERRUPTED -> InfoCard(
                "Transcription interrompue. Elle reprendra là où elle s'était arrêtée.", "Reprendre", onResume,
            )
            m.status == MeetingStatus.FAILED -> InfoCard(
                "La transcription a échoué : ${m.errorMessage ?: "erreur inconnue"}", "Réessayer", onTranscribe,
                error = true,
            )
        }
    }
}

@Composable
private fun InfoCard(text: String, button: String, onClick: () -> Unit, error: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptTab(
    segments: List<Segment>,
    speakers: Map<Long, Speaker>,
    playingPositionMs: Long?,
    onPlay: (Segment) -> Unit,
    onEdit: (Segment) -> Unit,
    onSpeaker: (Segment) -> Unit,
) {
    val visible = segments.filter { it.text == null || it.text.isNotBlank() }
    if (visible.isEmpty()) {
        Text(
            if (segments.isEmpty()) "Pas encore de transcription." else "Aucune parole reconnue.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(visible, key = { it.id }) { seg ->
            val sp = seg.speakerId?.let { speakers[it] }
            val color = speakerColor(sp?.colorIndex ?: 0)
            val playing = playingPositionMs != null && playingPositionMs in seg.startMs until seg.endMs
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (playing) color.copy(alpha = 0.22f) else color.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onPlay(seg) }, onLongClick = { if (seg.text != null) onEdit(seg) }),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = color.copy(alpha = 0.18f),
                            modifier = Modifier.clip(RoundedCornerShape(50)).combinedClickable(onClick = { onSpeaker(seg) }),
                        ) {
                            Text(
                                sp?.name ?: "?", color = color, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatTimestamp(seg.startMs), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        if (seg.text != null) IconButton(onClick = { onEdit(seg) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Edit, "Modifier le texte", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        seg.text ?: "…",
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = if (seg.text == null) FontStyle.Italic else FontStyle.Normal,
                        color = if (seg.text == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakersTab(speakers: List<Speaker>, segments: List<Segment>, onRename: (Speaker) -> Unit) {
    if (speakers.isEmpty()) {
        Text("Aucun intervenant détecté pour l'instant.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Renommer un intervenant met à jour toute la transcription. Donner le nom d'un intervenant " +
                    "existant fusionne les deux.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(speakers, key = { it.id }) { s ->
            val segs = segments.filter { it.speakerId == s.id && !it.text.isNullOrBlank() }
            val talk = segs.sumOf { it.endMs - it.startMs }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(speakerColor(s.colorIndex)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${segs.size} interventions · temps de parole ${formatDuration(talk)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { onRename(s) }) { Icon(Icons.Default.Edit, "Renommer") }
                }
            }
        }
    }
}

@Composable
private fun ActionsTab(
    actions: List<ActionItem>,
    meeting: Meeting,
    onEdit: (ActionItem) -> Unit,
    onToggle: (ActionItem) -> Unit,
    onDelete: (ActionItem) -> Unit,
    onCalendar: (ActionItem) -> Unit,
) {
    if (actions.isEmpty()) {
        Text(
            "Aucune action. Touchez + pour noter une tâche, son responsable et son échéance.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(actions, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickableCompat { onEdit(a) }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = a.done, onCheckedChange = { onToggle(a) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.task, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (a.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        val info = listOfNotNull(
                            a.owner.takeIf { it.isNotBlank() }?.let { "Responsable : $it" },
                            a.dueDate?.let { "Échéance : " + formatDay(it, ZoneOffset.UTC) },
                        ).joinToString(" · ")
                        if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onCalendar(a) }) { Icon(Icons.Default.Event, "Ajouter à l'agenda") }
                    IconButton(onClick = { onDelete(a) }) { Icon(Icons.Default.Delete, "Supprimer") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun PlayerBar(
    player: DetailViewModel.Player,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (player.playing) "Pause" else "Lecture")
            }
            Text(formatTimestamp(player.positionMs), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = player.positionMs.toFloat().coerceIn(0f, player.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatTimestamp(player.durationMs), style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer le lecteur") }
        }
    }
}

/** Bonus : ouvre l'agenda du téléphone pré-rempli (aucun accès réseau ni permission requis). */
private fun addToCalendar(context: Context, a: ActionItem, m: Meeting) {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, a.task)
        .putExtra(
            CalendarContract.Events.DESCRIPTION,
            buildString {
                if (a.owner.isNotBlank()) append("Responsable : ").append(a.owner).append('\n')
                append("Réunion : ").append(m.title)
            },
        )
    if (a.dueDate != null) {
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.dueDate)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, a.dueDate + 24 * 3600 * 1000L)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune application d'agenda trouvée", Toast.LENGTH_LONG).show()
    }
}
