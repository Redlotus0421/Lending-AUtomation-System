package com.lendflow.app.feature.agreements

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lendflow.app.data.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.max

// Port of src/features/agreements/converter/extractText.ts (+ supabase/functions/_shared/docx.ts):
// read the text of an uploaded terms file on the device (no upload, no internet service).
// Word .docx via java.util.zip, plain text as is, PDF with the system PDF text API when the
// device has it (Android 15+); otherwise the lender pastes the text.

enum class TextKind { pdf, docx, text }

data class ExtractedText(val text: String, val kind: TextKind, val pages: Int? = null)

class UnsupportedFileError(message: String) : Exception(message)

/** The PDF text API is missing on this device: ask the lender to paste the text instead. */
class PdfTextUnavailable : Exception(
    "This device cannot read the text of PDF files. Open the PDF, copy all of its text and paste it below — or upload the original Word (.docx) file.",
)

fun fileKind(name: String, mime: String?): TextKind? {
    val ext = name.lowercase().substringAfterLast('.', "")
    val type = mime ?: ""
    if (type == "application/pdf" || ext == "pdf") return TextKind.pdf
    if (ext == "docx" || type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return TextKind.docx
    if (type.startsWith("text/") || ext in listOf("txt", "md", "markdown")) return TextKind.text
    return null
}

/** True when this Android version can extract text from PDFs (PdfRenderer.Page.getTextContents). */
val canReadPdfText: Boolean get() = Build.VERSION.SDK_INT >= 35

suspend fun extractText(context: Context, file: PickedFile): ExtractedText = withContext(Dispatchers.IO) {
    val kind = fileKind(file.name, file.mime) ?: throw UnsupportedFileError("Use a PDF, Word (.docx) or text file.")
    when (kind) {
        TextKind.pdf -> {
            val (text, pages) = pdfText(context, file.bytes)
            if (text.replace(Regex("\\s"), "").length < 80) {
                throw UnsupportedFileError("This PDF has no readable text — it looks like a scanned image or photo. Use the original Word file or a PDF saved from Word.")
            }
            ExtractedText(text, kind, pages)
        }
        TextKind.docx -> ExtractedText(docxToText(file.bytes), kind)
        TextKind.text -> ExtractedText(String(file.bytes, Charsets.UTF_8).removePrefix("﻿"), kind)
    }
}

// ----------------------------------------------------------------------------- PDF
private fun pdfText(context: Context, bytes: ByteArray): Pair<String, Int> {
    if (!canReadPdfText) throw PdfTextUnavailable()
    val f = File.createTempFile("terms", ".pdf", context.cacheDir)
    try {
        f.writeBytes(bytes)
        val pages = mutableListOf<List<String>>()
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { p -> pages += pageText(p) }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw UnsupportedFileError("This PDF is password-protected. Remove the password or use the original Word file.")
        } catch (e: NoSuchMethodError) {
            throw PdfTextUnavailable()
        } catch (e: UnsupportedOperationException) {
            throw PdfTextUnavailable()
        }
        return dropRunningHeaders(pages).joinToString("\n\n") { it.joinToString("\n") } to pages.size
    } finally {
        f.delete()
    }
}

@androidx.annotation.RequiresApi(35)
private fun pageText(p: PdfRenderer.Page): List<String> =
    p.textContents.joinToString("\n") { it.text ?: "" }
        .replace(Regex("\\r\\n?"), "\n")
        .split("\n")
        .map { it.replace(Regex("[ \\t]+$"), "") }

/** drop running headers / footers that repeat on most pages (same rule as the web extractor) */
private fun dropRunningHeaders(input: List<List<String>>): List<List<String>> {
    if (input.size < 2) return input
    val norm = { l: String -> l.trim().replace(Regex("\\d+"), "#") }
    val count = HashMap<String, Int>()
    for (lines in input) {
        val ne = lines.filter { it.isNotEmpty() }
        val edge = ne.take(2) + ne.takeLast(2)
        for (l in edge.map(norm).toSet()) count[l] = (count[l] ?: 0) + 1
    }
    val need = max(2, ceil(input.size * 0.6).toInt())
    val repeated = count.filter { it.value >= need }.keys
    return input.map { lines ->
        val nonEmpty = lines.withIndex().filter { it.value.trim().isNotEmpty() }
        val edges = (nonEmpty.take(2) + nonEmpty.takeLast(2)).map { it.index }.toSet()
        lines.filterIndexed { j, l -> !(j in edges && norm(l) in repeated) }
    }
}

// ----------------------------------------------------------------------------- Word (.docx)
private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
private fun unescapeXml(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
    val e = m.groupValues[1]
    if (e.startsWith("#")) {
        val cp = if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
        if (cp != null) String(Character.toChars(cp)) else m.value
    } else ENTITIES[e] ?: m.value
}

private fun zipEntry(bytes: ByteArray, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: return null
            if (e.name == name) return zip.readBytes()
        }
    }
}

private val PARAGRAPH = Regex("<w:p[\\s>][\\s\\S]*?</w:p>")
private val RUN = Regex("<w:r[\\s>][\\s\\S]*?</w:r>")
private val TEXT_NODE = Regex("<w:t(?:\\s[^>]*)?>([\\s\\S]*?)</w:t>")
private val P_STYLE = Regex("<w:pStyle w:val=\"([^\"]+)\"")
private val BOLD = Regex("<w:b(?:\\s+w:val=\"(?:1|true)\")?\\s*/>")

/** Plain text of a .docx: one line per paragraph, bold runs kept as **bold**, headings as "## ". */
fun docxToText(bytes: ByteArray): String {
    val xmlBytes = try {
        zipEntry(bytes, "word/document.xml")
    } catch (e: Exception) {
        throw UnsupportedFileError("Not a valid .docx file")
    } ?: throw UnsupportedFileError("The Word file has no document body")
    val xml = String(xmlBytes, Charsets.UTF_8)
    val out = mutableListOf<String>()
    for (pm in PARAGRAPH.findAll(xml)) {
        val p = pm.value
        val style = P_STYLE.find(p)?.groupValues?.get(1) ?: ""
        var line = ""
        for (rm in RUN.findAll(p)) {
            val r = rm.value
            val text = TEXT_NODE.findAll(r).joinToString("") { unescapeXml(it.value.replace(Regex("<[^>]+>"), "")) }
            val tab = if (r.contains("<w:tab/>")) " " else ""
            if (text.isEmpty()) { line += tab; continue }
            val bold = BOLD.containsMatchIn(r)
            line += tab + if (bold && text.isNotBlank()) "**$text**" else text
        }
        line = line.replace("****", "").trim()
        if (line.isEmpty()) { out += ""; continue }
        val isList = p.contains("<w:numPr>")
        if (Regex("^Heading|^Title", RegexOption.IGNORE_CASE).containsMatchIn(style)) out += "## ${line.replace("**", "")}"
        else out += if (isList) "- $line" else line
    }
    return out.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}
