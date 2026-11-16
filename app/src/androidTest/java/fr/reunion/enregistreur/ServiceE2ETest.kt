package fr.reunion.enregistreur

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.reunion.enregistreur.audio.Wav
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.transcription.TranscriptionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Même parcours que l'utilisateur : application au premier plan, puis lancement de la
 * transcription par le service de premier plan (comme le bouton « Transcrire / Reprendre »).
 */
@RunWith(AndroidJUnit4::class)
class ServiceE2ETest {
    @Test fun transcriptionViaLeService() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EnregistreurApp
        val repo = app.repository
        val (id, file) = repo.createMeeting("Test service", System.currentTimeMillis())
        InstrumentationRegistry.getInstrumentation().context.assets.open("two.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val m = repo.getMeeting(id)!!
        repo.updateMeeting(m.copy(model = "small", numSpeakers = 0, status = MeetingStatus.TRANSCRIBING,
            durationMs = Wav.durationMs(File(m.audioPath))))
        TranscriptionService.start(app, id)
        var status = MeetingStatus.TRANSCRIBING
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 600_000) {
            delay(2000)
            val cur = repo.getMeeting(id)!!
            status = cur.status
            Log.i("E2E", "service : statut=$status étape=${cur.stage} erreur=${cur.errorMessage}")
            if (status == MeetingStatus.TRANSCRIBED || status == MeetingStatus.FAILED ||
                status == MeetingStatus.INTERRUPTED) break
        }
        val segs = repo.segments(id)
        for (s in segs) Log.i("E2E", "  service [${s.startMs}-${s.endMs}] spk=${s.speakerId} : ${s.text}")
        assertEquals(MeetingStatus.TRANSCRIBED, status)
        assertTrue(segs.any { !it.text.isNullOrBlank() })
        repo.deleteMeeting(id)
        scenario.close()
    }
}
