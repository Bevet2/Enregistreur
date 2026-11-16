package fr.reunion.enregistreur.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.reunion.enregistreur.audio.RecorderState
import fr.reunion.enregistreur.ui.screens.MeetingDetailScreen
import fr.reunion.enregistreur.ui.screens.MeetingListScreen
import fr.reunion.enregistreur.ui.screens.RecordScreen

@Composable
fun AppNavigation(openMeeting: Long?, onOpened: () -> Unit) {
    val nav = rememberNavController()
    val recorder = RecorderState.state.collectAsStateWithLifecycle().value

    LaunchedEffect(openMeeting) {
        if (openMeeting != null) {
            nav.navigate("meeting/$openMeeting") { launchSingleTop = true }
            onOpened()
        }
    }

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            MeetingListScreen(
                onOpen = { nav.navigate("meeting/$it") },
                onRecording = { nav.navigate("record/$it") { launchSingleTop = true } },
                activeRecording = recorder.meetingId,
            )
        }
        composable("record/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            RecordScreen(
                meetingId = id,
                onFinished = {
                    nav.navigate("meeting/$id") { popUpTo("list") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("meeting/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            MeetingDetailScreen(onBack = { nav.popBackStack() })
        }
    }
}
