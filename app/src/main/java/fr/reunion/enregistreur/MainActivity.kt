package fr.reunion.enregistreur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import fr.reunion.enregistreur.ui.AppNavigation
import fr.reunion.enregistreur.ui.theme.EnregistreurTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openMeeting = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch { (application as EnregistreurApp).recoverInterrupted() }
        setContent {
            EnregistreurTheme {
                AppNavigation(openMeeting = openMeeting.value, onOpened = { openMeeting.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_MEETING, -1) ?: -1
        if (id >= 0) openMeeting.value = id
    }

    companion object {
        const val EXTRA_OPEN_MEETING = "openMeeting"
    }
}
