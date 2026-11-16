package fr.reunion.enregistreur

import fr.reunion.enregistreur.export.DocxExporter
import fr.reunion.enregistreur.export.ExportDocument
import fr.reunion.enregistreur.export.TxtExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private val doc = ExportDocument.from(
        Fixtures.meeting, Fixtures.speakers, Fixtures.segments, Fixtures.actions, Fixtures.zone,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test fun `le document regroupe les prises de parole consecutives`() {
        assertEquals(3, doc.blocks.size)
        assertEquals("Bonjour à tous. Commençons.", doc.blocks[0].text)
        assertEquals("Personne 2", doc.blocks[1].speaker)
        assertEquals(listOf("Personne 1", "Personne 2"), doc.participants)
        assertEquals("1:02:05", doc.durationText)
        assertTrue(doc.dateText, doc.dateText.startsWith("Lundi 16 novembre 2026 à 14h30"))
    }

    @Test fun `docx contient toutes les parties OOXML et un XML valide`() {
        val files = unzip(DocxExporter.export(doc))
        for (name in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml",
            "word/styles.xml", "word/_rels/document.xml.rels", "docProps/core.xml")) {
            assertTrue("partie manquante : $name", name in files)
            assertWellFormed(files.getValue(name))
        }
        val body = files.getValue("word/document.xml")
        assertTrue(body.contains("Comité &lt;projet&gt; &amp; budget"))
        assertTrue(body.contains("Participants : "))
        assertTrue(body.contains("Personne 1, Personne 2"))
        assertTrue(body.contains("00:01:05"))
        assertTrue(body.contains("01:01:01"))
        assertTrue(body.contains("Le budget est &quot;validé&quot; &lt; 10 k€."))
        assertTrue(body.contains("Envoyer le compte rendu"))
        assertTrue(body.contains("20 novembre 2026"))
        assertFalse(body.contains("Intervenant inconnu"))
    }

    @Test fun `docx supprime les caracteres interdits en XML`() {
        val d = doc.copy(blocks = listOf(ExportDocument.Block("A", "000000", 0, "x\u0001y\u000Bz")))
        val body = unzip(DocxExporter.export(d)).getValue("word/document.xml")
        assertWellFormed(body)
        assertTrue(body.contains("xyz"))
    }

    @Test fun `export texte`() {
        val txt = TxtExporter.export(doc)
        val expected = """
            Comité <projet> & budget
            ========================

            Date : Lundi 16 novembre 2026 à 14h30
            Durée : 1:02:05
            Participants : Personne 1, Personne 2

            TRANSCRIPTION

            [00:00:00] Personne 1 :
            Bonjour à tous. Commençons.

            [00:01:05] Personne 2 :
            Le budget est "validé" < 10 k€.

            [01:01:01] Personne 1 :
            Merci.

            ACTIONS

            - Envoyer le compte rendu — responsable : Alice — échéance : 20 novembre 2026

        """.trimIndent()
        assertEquals(expected, txt)
    }

    @Test fun `segment sans intervenant`() {
        val segs = listOf(Fixtures.segments[0].copy(speakerId = null))
        val d = ExportDocument.from(Fixtures.meeting, Fixtures.speakers, segs, emptyList(), Fixtures.zone)
        assertEquals(ExportDocument.UNKNOWN_SPEAKER, d.blocks.single().speaker)
        assertTrue(d.participants.isEmpty())
        assertFalse(TxtExporter.export(d).contains("Participants"))
    }
}
