package fr.reunion.enregistreur

import fr.reunion.enregistreur.transcription.PlannedSegment
import fr.reunion.enregistreur.transcription.SegmentPlanner
import fr.reunion.enregistreur.transcription.SpeakerLinker
import fr.reunion.enregistreur.transcription.SpeechRegion
import fr.reunion.enregistreur.transcription.TextCleaner
import fr.reunion.enregistreur.transcription.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningTest {
    @Test fun `sans diarisation tout est attribue a un intervenant`() {
        val plan = SegmentPlanner.plan(listOf(SpeechRegion(0, 5000), SpeechRegion(5500, 9000)), emptyList())
        assertEquals(listOf(PlannedSegment(0, 9000, 0)), plan)
    }

    @Test fun `une zone de parole est coupee au changement d'intervenant`() {
        val plan = SegmentPlanner.plan(
            listOf(SpeechRegion(0, 10_000)),
            listOf(Turn(0, 4000, 5), Turn(4000, 10_000, 2)),
        )
        assertEquals(listOf(PlannedSegment(0, 4000, 0), PlannedSegment(4000, 10_000, 1)), plan)
    }

    @Test fun `les morceaux trop courts sont rattaches au voisin`() {
        val plan = SegmentPlanner.plan(
            listOf(SpeechRegion(0, 10_000)),
            listOf(Turn(0, 5000, 0), Turn(5000, 5200, 1), Turn(5200, 10_000, 0)),
        )
        assertEquals(listOf(PlannedSegment(0, 10_000, 0)), plan)
    }

    @Test fun `les segments longs sont limites a 28 secondes`() {
        val plan = SegmentPlanner.plan(listOf(SpeechRegion(0, 20_000), SpeechRegion(20_500, 40_000)), emptyList())
        assertTrue(plan.all { it.durationMs <= SegmentPlanner.MAX_SEGMENT_MS })
        assertEquals(0L, plan.first().startMs)
        assertEquals(40_000L, plan.last().endMs)
    }

    @Test fun `les intervenants sont numerotes par ordre d'apparition`() {
        val plan = SegmentPlanner.plan(
            listOf(SpeechRegion(0, 3000), SpeechRegion(5000, 8000)),
            listOf(Turn(0, 3000, 7), Turn(5000, 8000, 3)),
        )
        assertEquals(listOf(0, 1), plan.map { it.speaker })
    }

    @Test fun `liaison des intervenants entre tranches`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val locals = listOf(
            SpeakerLinker.LocalSpeaker(0, 0, a), SpeakerLinker.LocalSpeaker(0, 1, b),
            SpeakerLinker.LocalSpeaker(1, 0, floatArrayOf(0.1f, 0.95f, 0f)),
            SpeakerLinker.LocalSpeaker(1, 1, floatArrayOf(0.97f, 0.05f, 0.1f)),
        )
        val map = SpeakerLinker.link(locals, 0)
        assertEquals(map[0 to 0], map[1 to 1])
        assertEquals(map[0 to 1], map[1 to 0])
        assertTrue(map[0 to 0] != map[0 to 1])
        // Nombre imposé : 1 seul intervenant
        assertEquals(1, SpeakerLinker.link(locals, 1).values.toSet().size)
    }

    @Test fun `nettoyage des hallucinations Whisper`() {
        assertEquals("", TextCleaner.clean("Sous-titres réalisés par la communauté d'Amara.org"))
        assertEquals("On valide le budget.", TextCleaner.clean(" On valide le budget. Merci d'avoir regardé cette vidéo !"))
        assertEquals("Oui. Oui.", TextCleaner.clean("Oui. Oui."))
        assertEquals("D'accord.", TextCleaner.clean("D'accord. D'accord. D'accord. D'accord."))
        assertEquals("", TextCleaner.clean(" ... "))
    }
}
