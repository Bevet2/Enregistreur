package fr.reunion.enregistreur.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.reunion.enregistreur.data.Meeting
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.transcription.TranscriptionState
import fr.reunion.enregistreur.ui.ListViewModel
import fr.reunion.enregistreur.util.formatDateShort
import fr.reunion.enregistreur.util.formatDuration
import fr.reunion.enregistreur.util.formatRemaining
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingListScreen(
    onOpen: (Long) -> Unit,
    onRecording: (Long) -> Unit,
    activeRecording: Long?,
    vm: ListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val transcription by TranscriptionState.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var batteryOk by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) vm.startRecording(onRecording)
        else scope.launch {
            val r = snackbar.showSnackbar(
                "L'accès au micro est nécessaire pour enregistrer.", actionLabel = "Paramètres",
            )
            if (r == SnackbarResult.ActionPerformed) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            }
        }
    }

    fun record() {
        if (activeRecording != null) { onRecording(activeRecording); return }
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.startRecording(onRecording) else permissionLauncher.launch(missing.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réunions") },
                actions = {
                    Icon(
                        Icons.Default.Lock, contentDescription = "Hors ligne",
                        modifier = Modifier.padding(end = 16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { record() },
                icon = { Icon(if (activeRecording != null) Icons.Default.FiberManualRecord else Icons.Default.Mic, null) },
                text = { Text(if (activeRecording != null) "Enregistrement en cours" else "Enregistrer") },
                containerColor = if (activeRecording != null) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Rechercher dans les réunions et transcriptions") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) {
                            Icon(Icons.Default.Clear, "Effacer")
                        }
                    },
                    singleLine = true,
                )
            }
            if (!batteryOk) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryAlert, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Optimisation de la batterie active", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Nothing OS peut arrêter l'enregistrement ou la transcription en arrière-plan. " +
                                "Autorisez l'application à fonctionner sans restriction.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + context.packageName))
                                )
                            } catch (_: Throwable) {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }) { Text("Désactiver l'optimisation") }
                    }
                }
            }
            if (transcription.meetingId != null) item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(transcription.meetingId!!) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Transcription en cours — ${transcription.percent} %", fontWeight = FontWeight.Bold)
                        Text(
                            transcription.stage + (transcription.etaSeconds?.let { " · reste " + formatRemaining(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { transcription.percent / 100f }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (meetings.isEmpty()) item {
                Text(
                    if (query.isBlank()) "Aucune réunion pour l'instant.\nTouchez « Enregistrer » pour commencer."
                    else "Aucun résultat pour « $query ».",
                    modifier = Modifier.padding(top = 48.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(meetings, key = { it.id }) { m ->
                MeetingRow(m, onClick = {
                    if (m.status == MeetingStatus.RECORDING && activeRecording == m.id) onRecording(m.id) else onOpen(m.id)
                })
            }
        }
    }
}

@Composable
private fun MeetingRow(m: Meeting, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateShort(m.createdAt) + " · " + formatDuration(m.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusLabel(m.status), style = MaterialTheme.typography.labelMedium,
                    color = if (m.status == MeetingStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun statusLabel(status: String) = when (status) {
    MeetingStatus.RECORDING -> "Enregistrement…"
    MeetingStatus.RECORDED -> "À transcrire"
    MeetingStatus.TRANSCRIBING -> "Transcription…"
    MeetingStatus.INTERRUPTED -> "Interrompue"
    MeetingStatus.TRANSCRIBED -> "Transcrite"
    MeetingStatus.FAILED -> "Échec"
    else -> status
}
