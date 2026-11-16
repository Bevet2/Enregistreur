package fr.reunion.enregistreur.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.reunion.enregistreur.data.ActionItem
import fr.reunion.enregistreur.data.Speaker
import fr.reunion.enregistreur.transcription.Models
import fr.reunion.enregistreur.transcription.SpeedStats
import fr.reunion.enregistreur.ui.theme.speakerColor
import fr.reunion.enregistreur.util.formatDay
import fr.reunion.enregistreur.util.formatRemaining
import java.time.ZoneOffset

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String = "Enregistrer",
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) },
                singleLine = singleLine, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank() || !singleLine) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Choix du modèle et du nombre de participants avant de lancer la transcription. */
@Composable
fun TranscribeOptionsDialog(
    durationMs: Long,
    initialModel: String,
    initialSpeakers: Int,
    onDismiss: () -> Unit,
    onStart: (model: String, numSpeakers: Int) -> Unit,
) {
    val context = LocalContext.current
    val stats = remember { SpeedStats(context) }
    var model by remember { mutableStateOf(initialModel) }
    var auto by remember { mutableStateOf(initialSpeakers <= 0) }
    var count by remember { mutableIntStateOf(if (initialSpeakers > 0) initialSpeakers else 2) }
    val sec = durationMs / 1000.0
    fun estimate(key: String) = (sec * (stats.get(SpeedStats.DIARIZATION) + stats.get(SpeedStats.VAD) +
        0.85f * stats.get(SpeedStats.asr(key)))).toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transcrire la réunion") },
        text = {
            Column {
                Text("Modèle", fontWeight = FontWeight.Bold)
                for (m in Models.Whisper.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(model == m.key) { model = m.key }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == m.key, onClick = { model = m.key })
                        Column {
                            Text(m.label)
                            Text(
                                (if (m == Models.Whisper.SMALL) "Meilleure qualité en français" else "Pour les longues réunions") +
                                    " · durée estimée : " + formatRemaining(estimate(m.key)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nombre de participants", fontWeight = FontWeight.Bold)
                        Text(
                            if (auto) "Détection automatique" else "Indiqué : $count",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Auto", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                if (!auto) Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = { if (count > 1) count-- }) { Icon(Icons.Default.Remove, "Moins") }
                    Text("$count", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                    FilledTonalIconButton(onClick = { if (count < 20) count++ }) { Icon(Icons.Default.Add, "Plus") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(model, if (auto) 0 else count) }) { Text("Lancer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Correction de l'intervenant d'un segment. */
@Composable
fun SpeakerPickerDialog(
    speakers: List<Speaker>,
    currentId: Long?,
    onDismiss: () -> Unit,
    onPick: (Speaker) -> Unit,
    onNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qui parle ?") },
        text = {
            LazyColumn {
                items(speakers, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(s) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.size(14.dp).clip(CircleShape).background(speakerColor(s.colorIndex)))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            s.name, modifier = Modifier.weight(1f),
                            fontWeight = if (s.id == currentId) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (s.id == currentId) Text("actuel", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    TextButton(onClick = onNew, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Nouvel intervenant")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Ajout / modification d'une action de suivi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDialog(
    initial: ActionItem?,
    onDismiss: () -> Unit,
    onSave: (task: String, owner: String, due: Long?) -> Unit,
) {
    var task by remember { mutableStateOf(initial?.task ?: "") }
    var owner by remember { mutableStateOf(initial?.owner ?: "") }
    var due by remember { mutableStateOf(initial?.dueDate) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = due ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { due = state.selectedDateMillis; picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Annuler") } },
        ) { DatePicker(state = state) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouvelle action" else "Modifier l'action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(task, { task = it }, label = { Text("Tâche") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(owner, { owner = it }, label = { Text("Responsable") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }) {
                        Icon(Icons.Default.Event, null); Spacer(Modifier.width(8.dp))
                        Text(due?.let { formatDay(it, ZoneOffset.UTC) } ?: "Échéance")
                    }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Retirer") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(task, owner, due) }, enabled = task.isNotBlank()) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
