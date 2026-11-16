package fr.reunion.enregistreur.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.reunion.enregistreur.audio.RecorderState
import fr.reunion.enregistreur.audio.RecordingService
import fr.reunion.enregistreur.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(meetingId: Long, onFinished: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by RecorderState.state.collectAsStateWithLifecycle()
    var seenActive by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    val active = state.meetingId == meetingId

    LaunchedEffect(active) {
        if (active) seenActive = true
        else if (seenActive) onFinished()
        else { delay(4000); if (!seenActive) onFinished() }
    }

    BackHandler { onBack() } // l'enregistrement continue en arrière-plan

    val level by animateFloatAsState(sqrt(state.level.coerceIn(0f, 1f)), label = "niveau")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enregistrement") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                when {
                    stopping -> "Finalisation…"
                    state.paused -> "En pause"
                    else -> "● Enregistrement en cours"
                },
                color = if (state.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                formatDuration(state.elapsedMs),
                fontSize = 64.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { level }, modifier = Modifier.fillMaxWidth(0.7f).height(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "L'enregistrement continue écran verrouillé.\nAudio stocké uniquement sur ce téléphone.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = {
                        RecordingService.send(
                            context, if (state.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE,
                        )
                    },
                    enabled = active && !stopping,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (state.paused) "Reprendre" else "Pause", modifier = Modifier.size(36.dp),
                    )
                }
                FilledIconButton(
                    onClick = { stopping = true; RecordingService.send(context, RecordingService.ACTION_STOP) },
                    enabled = active && !stopping,
                    modifier = Modifier.size(96.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Icon(Icons.Default.Stop, "Arrêter", modifier = Modifier.size(48.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text(if (state.paused) "Reprendre" else "Pause", Modifier.width(72.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(32.dp))
                Text("Arrêter", Modifier.width(96.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
