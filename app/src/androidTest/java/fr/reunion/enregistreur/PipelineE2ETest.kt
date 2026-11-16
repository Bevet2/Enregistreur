package fr.reunion.enregistreur

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.reunion.enregistreur.audio.Wav
import fr.reunion.enregistreur.data.MeetingStatus
import fr.reunion.enregistreur.transcription.TranscriptionPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exécute la vraie chaîne (modèles intégrés, sherpa-onnx natif) sur un fichier audio de test. */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {

    private fun transcribe(asset: String, model: String, speakers: Int = 0) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EnregistreurApp
        val repo = app.repository
        val (id, file) = repo.createMeeting("Test $asset", System.currentTimeMillis())
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val m = repo.getMeeting(id)!!
        repo.updateMeeting(m.copy(model = model, numSpeakers = speakers, status = MeetingStatus.TRANSCRIBING,
            durationMs = Wav.durationMs(File(m.audioPath))))
        val t0 = System.currentTimeMillis()
        TranscriptionPipeline(app, repo, repo.getMeeting(id)!!, { false }) { stage, p, eta ->
            Log.i(TAG, "$asset/$model : $stage $p % eta=$eta")
        }.run()
        val segs = repo.segments(id)
        val speakersFound = repo.speakers(id)
        Log.i(TAG, "$asset/$model : ${segs.size} segments, ${speakersFound.size} intervenants, " +
            "${(System.currentTimeMillis() - t0) / 1000} s")
        for (s in segs) Log.i(TAG, "  [${s.startMs}-${s.endMs}] spk=${s.speakerId} : ${s.text}")
        assertTrue("aucun texte transcrit", segs.any { !it.text.isNullOrBlank() })
        repo.deleteMeeting(id)
    }

    @Test fun clipCourtBase() = transcribe("short.wav", "base")
    @Test fun deuxIntervenantsBase() = transcribe("two.wav", "base")
    @Test fun clipCourtSmall() = transcribe("short.wav", "small", speakers = 2)

    companion object { const val TAG = "E2E" }
}
