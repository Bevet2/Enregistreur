package fr.reunion.enregistreur

import fr.reunion.enregistreur.data.SpeakerLogic
import fr.reunion.enregistreur.data.SpeakerLogic.RenamePlan
import fr.reunion.enregistreur.export.ExportDocument
import fr.reunion.enregistreur.export.TxtExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerLogicTest {
    private val speakers = Fixtures.speakers
    private val segments = Fixtures.segments

    @Test fun `renommage simple applique partout`() {
        val plan = SpeakerLogic.planRename(speakers, 10, "  Alice   Martin ")
        assertEquals(RenamePlan.Rename(10, "Alice Martin"), plan)
        val (sp, segs) = SpeakerLogic.apply(plan, speakers, segments)
        val doc = ExportDocument.from(Fixtures.meeting, sp, segs, emptyList(), Fixtures.zone)
        assertEquals(listOf("Alice Martin", "Personne 2", "Alice Martin"), doc.blocks.map { it.speaker })
        val txt = TxtExporter.export(doc)
        assertFalse(txt.contains("Personne 1"))
        assertTrue(txt.contains("Participants : Alice Martin, Personne 2"))
    }

    @Test fun `nom vide refuse`() {
        assertEquals(RenamePlan.Invalid, SpeakerLogic.planRename(speakers, 10, "   "))
        assertEquals(RenamePlan.Invalid, SpeakerLogic.planRename(speakers, 999, "Bob"))
    }

    @Test fun `nom inchange`() {
        assertEquals(RenamePlan.Unchanged, SpeakerLogic.planRename(speakers, 10, "Personne 1"))
    }

    @Test fun `nom deja pris fusionne les intervenants`() {
        val plan = SpeakerLogic.planRename(speakers, 11, "personne 1")
        assertEquals(RenamePlan.Merge(fromId = 11, intoId = 10), plan)
        val (sp, segs) = SpeakerLogic.apply(plan, speakers, segments)
        assertEquals(listOf(10L), sp.map { it.id })
        assertTrue(segs.all { it.speakerId == 10L })
        val doc = ExportDocument.from(Fixtures.meeting, sp, segs, emptyList(), Fixtures.zone)
        assertEquals(1, doc.blocks.size) // tout est désormais une seule prise de parole
    }

    @Test fun `correction de l'intervenant d'un segment`() {
        val segs = segments.map { if (it.id == 102L) it.copy(speakerId = 10) else it }
        val doc = ExportDocument.from(Fixtures.meeting, speakers, segs, emptyList(), Fixtures.zone)
        assertEquals(listOf("Personne 1"), doc.participants)
    }

    @Test fun `nouvel intervenant`() {
        assertEquals("Personne 3", SpeakerLogic.nextDefaultName(speakers))
        assertEquals(2, SpeakerLogic.nextColorIndex(speakers))
        val renamed = speakers.map { if (it.id == 10L) it.copy(name = "Personne 3") else it }
        assertEquals("Personne 4", SpeakerLogic.nextDefaultName(renamed))
    }
}
