package fr.reunion.enregistreur.export

import fr.reunion.enregistreur.util.formatTimestamp
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Générateur .docx minimal (Office Open XML) sans dépendance :
 * un zip contenant [Content_Types].xml, les relations, styles.xml et document.xml.
 */
object DocxExporter {

    fun export(doc: ExportDocument): ByteArray =
        ByteArrayOutputStream().also { write(doc, it) }.toByteArray()

    fun write(doc: ExportDocument, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("docProps/core.xml", coreXml(doc.title))
            zip.entry("word/_rels/document.xml.rels", DOC_RELS)
            zip.entry("word/styles.xml", STYLES)
            zip.entry("word/document.xml", documentXml(doc))
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Échappe le XML et supprime les caractères interdits en XML 1.0. */
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            c == '\t' || c == '\n' || c == '\r' -> append(' ')
            c < ' ' || c == '￾' || c == '￿' -> Unit
            else -> append(c)
        }
    }

    private fun run(text: String, bold: Boolean = false, color: String? = null, size: Int? = null) = buildString {
        append("<w:r>")
        if (bold || color != null || size != null) {
            append("<w:rPr>")
            if (bold) append("<w:b/>")
            if (color != null) append("<w:color w:val=\"").append(color).append("\"/>")
            if (size != null) append("<w:sz w:val=\"").append(size).append("\"/>")
            append("</w:rPr>")
        }
        append("<w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r>")
    }

    private fun para(style: String? = null, content: String) = buildString {
        append("<w:p>")
        if (style != null) append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        append(content)
        append("</w:p>")
    }

    fun documentXml(doc: ExportDocument): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
        append(para("Title", run(doc.title)))
        append(para(content = run("Date : ", bold = true) + run(doc.dateText)))
        append(para(content = run("Durée : ", bold = true) + run(doc.durationText)))
        if (doc.participants.isNotEmpty()) {
            append(para(content = run("Participants : ", bold = true) + run(doc.participants.joinToString(", "))))
        }
        append(para("Heading1", run("Transcription")))
        if (doc.blocks.isEmpty()) append(para(content = run("(aucune transcription)")))
        for (b in doc.blocks) {
            append(
                para(
                    "Speaker",
                    run(b.speaker, bold = true, color = b.colorHex) +
                        run("  " + formatTimestamp(b.startMs), color = "808080", size = 18),
                )
            )
            append(para(content = run(b.text)))
        }
        if (doc.actions.isNotEmpty()) {
            append(para("Heading1", run("Actions")))
            for (a in doc.actions) {
                val parts = StringBuilder(run("• " + a.task, bold = true))
                if (a.owner.isNotBlank()) parts.append(run(" — responsable : " + a.owner))
                if (a.due != null) parts.append(run(" — échéance : " + a.due))
                append(para(content = parts.toString()))
            }
        }
        append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        append("</w:body></w:document>")
    }

    private fun coreXml(title: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${escape(title)}</dc:title>" +
            "<dc:language>fr-FR</dc:language></cp:coreProperties>"

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "</Relationships>"

    private const val DOC_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"40\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>" +
            "<w:rPr><w:b/><w:color w:val=\"2E4A7D\"/><w:sz w:val=\"30\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Speaker\"><w:name w:val=\"Intervenant\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"40\"/></w:pPr></w:style>" +
            "</w:styles>"
}
