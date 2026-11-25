package com.lendflow.app.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintManager
import com.lendflow.app.data.Row
import com.lendflow.app.data.primitiveString
import com.lendflow.app.data.toNum
import com.lendflow.app.ui.components.saveToDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Port of src/lib/export.ts: CSV, Excel (.xlsx) and PDF exports of tables and reports, saved to the
// phone's Downloads folder. PDFs use "PHP" like the web exports.

/** A report column (ReportColumn on the web). type: money | date | number | percent | status | null */
data class ExportCol(val key: String, val label: String, val type: String? = null, val total: Boolean = false)

fun exportColsFrom(columns: List<Row>): List<ExportCol> = columns.map {
    ExportCol(it["key"].primitiveString() ?: "", it["label"].primitiveString() ?: "", it["type"].primitiveString(), it["total"].primitiveString() == "true")
}

private fun cellOf(r: Row, key: String): Any? = r[key]?.primitiveString()
private fun isBlank(v: Any?) = v == null || v.toString().isEmpty()

private fun slug(name: String) = name.replace(Regex("[^\\w.-]+"), "-").replace(Regex("-+"), "-").trim('-').ifEmpty { "export" }
private fun stamp() = LocalDateTime.now(MANILA).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
private fun fileName(base: String, ext: String) = "${slug(base.removeSuffix(".$ext"))}-${stamp()}.$ext"

private fun dateText(v: Any?, long: Boolean = false): String {
    if (isBlank(v)) return ""
    val s = v.toString()
    if (!long) {
        return if (s.length <= 10) s.take(10) else parseInstant(s)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: s
    }
    return if (s.length <= 10) parseDate(s)?.format(DateTimeFormatter.ofPattern("MMM dd, yyyy", java.util.Locale.US)) ?: s
    else parseInstant(s)?.format(DateTimeFormatter.ofPattern("MMM dd, yyyy", java.util.Locale.US)) ?: s
}

/** Human-readable value for PDFs / print. */
fun displayValue(col: ExportCol, v: Any?): String {
    if (isBlank(v)) return if (col.type == "money") "PHP 0.00" else ""
    return when (col.type) {
        "money" -> money(v)
        "number" -> trimNumber(v, 2)
        "percent" -> trimNumber(v, 2) + "%"
        "date" -> dateText(v, true)
        "status" -> titleCase(v.toString())
        else -> v.toString()
    }
}

private fun csvValue(col: ExportCol, v: Any?): String {
    if (isBlank(v)) return ""
    return when (col.type) {
        "money" -> String.format(java.util.Locale.US, "%.2f", toNumber(v))
        "number", "percent" -> toNumber(v).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        "date" -> dateText(v)
        "status" -> titleCase(v.toString())
        else -> v.toString()
    }
}

private fun csvEscape(s: String) = if (Regex("[\",\r\n]").containsMatchIn(s)) "\"${s.replace("\"", "\"\"")}\"" else s

fun computeTotals(columns: List<ExportCol>, rows: List<Row>): Map<String, Double> =
    columns.filter { it.total }.associate { c -> c.key to round2(rows.sumOf { it[c.key].toNum() }) }

private fun hasTotals(columns: List<ExportCol>) = columns.any { it.total }

// ---------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------
fun exportCsv(context: Context, filename: String, columns: List<ExportCol>, rows: List<Row>, totals: Map<String, Double>? = null) {
    val lines = mutableListOf(columns.joinToString(",") { csvEscape(it.label) })
    rows.forEach { r -> lines += columns.joinToString(",") { c -> csvEscape(csvValue(c, cellOf(r, c.key))) } }
    if (hasTotals(columns) && rows.isNotEmpty()) {
        val t = totals ?: computeTotals(columns, rows)
        lines += columns.mapIndexed { i, c -> if (c.total) csvEscape(csvValue(c, t[c.key])) else if (i == 0) "TOTAL" else "" }.joinToString(",")
    }
    // BOM so Excel opens UTF-8 (₱, ñ) correctly
    val bytes = ("﻿" + lines.joinToString("\r\n")).toByteArray(Charsets.UTF_8)
    saveToDownloads(context, fileName(filename, "csv"), bytes, "text/csv")
}

// ---------------------------------------------------------------------------
// XLSX (SpreadsheetML written directly: numbers stay numbers, dates are real dates)
// ---------------------------------------------------------------------------
data class XlsxSheet(val name: String, val columns: List<ExportCol>, val rows: List<Row>, val totals: Map<String, Double>? = null)

private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

private fun colName(i: Int): String {
    var n = i + 1
    val sb = StringBuilder()
    while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
    return sb.toString()
}

/** Excel serial date (1900 system). */
private fun excelDate(s: String): Double? {
    if (s.length <= 10) {
        val d = parseDate(s) ?: return null
        return (d.toEpochDay() + 25569).toDouble()
    }
    val z = parseInstant(s) ?: return null
    val ldt = z.toLocalDateTime()
    return ldt.toLocalDate().toEpochDay() + 25569 + (ldt.toLocalTime().toSecondOfDay() / 86400.0)
}

// style ids: 0 default, 1 header (bold + fill), 2 money, 3 number, 4 percent, 5 date, 6 datetime, 7 bold, 8 bold money, 9 bold number
private fun xlsxCellXml(ref: String, col: ExportCol, v: Any?, bold: Boolean): String {
    if (isBlank(v)) return if (bold) "<c r=\"$ref\" s=\"7\"/>" else ""
    val s = v.toString()
    fun str(text: String, style: Int = if (bold) 7 else 0) = "<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xmlEsc(text)}</t></is></c>"
    fun numCell(n: Double, style: Int) = "<c r=\"$ref\" s=\"$style\"><v>$n</v></c>"
    return when (col.type) {
        "money" -> numCell(toNumber(v), if (bold) 8 else 2)
        "number" -> numCell(toNumber(v), if (bold) 9 else 3)
        "percent" -> numCell(toNumber(v) / 100, 4)
        "date" -> excelDate(s)?.let { numCell(it, if (s.length <= 10) 5 else 6) } ?: str(s)
        "status" -> str(titleCase(s))
        else -> str(s)
    }
}

private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="4"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="#,##0.##"/><numFmt numFmtId="166" formatCode="yyyy-mm-dd"/><numFmt numFmtId="167" formatCode="yyyy-mm-dd hh:mm"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF7"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="10">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="10" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>
</cellXfs></styleSheet>"""

fun xlsxBytes(sheets: List<XlsxSheet>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        val names = sheets.mapIndexed { i, s -> s.name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Sheet${i + 1}" } }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
        }</Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${
            names.mapIndexed { i, n -> "<sheet name=\"${xmlEsc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")
        }</sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }
        }<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", STYLES)
        sheets.forEachIndexed { si, s ->
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            sb.append("<cols>")
            s.columns.forEachIndexed { i, c ->
                val longest = s.rows.take(200).fold(c.label.length) { m, r -> max(m, displayValue(c, cellOf(r, c.key)).length) }
                sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${min(max(longest + 2, 10), 48)}\" customWidth=\"1\"/>")
            }
            sb.append("</cols><sheetData>")
            sb.append("<row r=\"1\">")
            s.columns.forEachIndexed { i, c -> sb.append("<c r=\"${colName(i)}1\" t=\"inlineStr\" s=\"1\"><is><t>${xmlEsc(c.label)}</t></is></c>") }
            sb.append("</row>")
            s.rows.forEachIndexed { ri, r ->
                val rn = ri + 2
                sb.append("<row r=\"$rn\">")
                s.columns.forEachIndexed { i, c -> sb.append(xlsxCellXml("${colName(i)}$rn", c, cellOf(r, c.key), false)) }
                sb.append("</row>")
            }
            if (hasTotals(s.columns) && s.rows.isNotEmpty()) {
                val t = s.totals ?: computeTotals(s.columns, s.rows)
                val rn = s.rows.size + 2
                sb.append("<row r=\"$rn\">")
                s.columns.forEachIndexed { i, c ->
                    val ref = "${colName(i)}$rn"
                    sb.append(
                        if (c.total) xlsxCellXml(ref, c, t[c.key] ?: 0.0, true)
                        else if (i == 0) "<c r=\"$ref\" t=\"inlineStr\" s=\"7\"><is><t>TOTAL</t></is></c>" else "",
                    )
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
        }
    }
    return out.toByteArray()
}

fun exportXlsxSheets(context: Context, filename: String, sheets: List<XlsxSheet>) {
    saveToDownloads(context, fileName(filename, "xlsx"), xlsxBytes(sheets), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

fun exportXlsx(context: Context, filename: String, sheetName: String, columns: List<ExportCol>, rows: List<Row>, totals: Map<String, Double>? = null) =
    exportXlsxSheets(context, filename, listOf(XlsxSheet(sheetName, columns, rows, totals)))

// ---------------------------------------------------------------------------
// PDF (android.graphics.pdf.PdfDocument): A4, navy header row, striped body, totals, page numbers
// ---------------------------------------------------------------------------
data class PdfSection(val columns: List<ExportCol>, val rows: List<Row>, val title: String? = null, val totals: Map<String, Double>? = null, val note: String? = null)

/** Replace characters that look wrong in simple report text. */
private fun clean(s: String) = s.replace(Regex("₱\\s?"), "PHP ").replace(Regex("[–—]"), "-").replace(Regex("[“”]"), "\"").replace(Regex("[‘’]"), "'").replace("…", "...")

private fun wrapText(s: String, p: Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    s.split('\n').forEach { para ->
        var line = ""
        para.split(' ').forEach { w ->
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) <= width) line = t
            else {
                if (line.isNotEmpty()) out += line
                var word = w
                while (p.measureText(word) > width && word.length > 1) {
                    var n = word.length
                    while (n > 1 && p.measureText(word.take(n)) > width) n--
                    out += word.take(n); word = word.drop(n)
                }
                line = word
            }
        }
        out += line
    }
    return out
}

/** Draws the report; returns the bytes and the number of pages. [totalPages] = 0 on the counting pass. */
private fun renderReport(
    title: String, sections: List<PdfSection>, subtitle: String?, organization: String?, landscape: Boolean?, footerNote: String, totalPages: Int,
): Pair<ByteArray, Int> {
    val land = landscape ?: ((sections.maxOfOrNull { it.columns.size } ?: 0) > 6)
    val pageW = if (land) 842 else 595
    val pageH = if (land) 595 else 842
    val margin = 40f
    val doc = PdfDocument()
    val navy = android.graphics.Color.rgb(20, 43, 74)
    val muted = android.graphics.Color.rgb(91, 107, 130)
    val ink = android.graphics.Color.rgb(17, 24, 39)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 8f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    val bold = Paint(text).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val footer = Paint(text).apply { textSize = 7f; color = android.graphics.Color.rgb(140, 150, 165) }
    val fill = Paint().apply { style = Paint.Style.FILL }
    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f

    fun finish() {
        val p = page ?: return
        p.canvas.drawText(clean(footerNote), margin, pageH - 20f, footer)
        val label = if (totalPages > 0) "Page $pageNo of $totalPages" else "Page $pageNo"
        p.canvas.drawText(label, pageW - margin - footer.measureText(label), pageH - 20f, footer)
        doc.finishPage(p)
        page = null
    }
    fun newPage() {
        finish()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        y = 40f
        if (pageNo == 1) { fill.color = navy; page!!.canvas.drawRect(0f, 0f, pageW.toFloat(), 6f, fill) }
    }

    newPage()
    y = 36f
    if (!organization.isNullOrBlank()) { page!!.canvas.drawText(clean(organization), margin, y, Paint(text).apply { color = muted; textSize = 9f }); y += 16f }
    page!!.canvas.drawText(clean(title), margin, y, Paint(bold).apply { textSize = 15f; color = android.graphics.Color.rgb(15, 27, 45) }); y += 15f
    val generated = "Generated ${LocalDateTime.now(MANILA).format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))} (PHT)"
    page!!.canvas.drawText(clean(if (subtitle != null) "$subtitle  ·  $generated" else generated), margin, y, Paint(text).apply { color = muted; textSize = 8.5f }); y += 16f

    val usable = pageW - margin * 2
    for (s in sections) {
        if (s.columns.isEmpty()) continue
        val fontSize = if (s.columns.size > 9) 6.8f else 7.8f
        text.textSize = fontSize; bold.textSize = fontSize
        if (s.title != null) {
            if (y > pageH - 90) newPage()
            page!!.canvas.drawText(clean(s.title), margin, y + 10, Paint(bold).apply { textSize = 10.5f; color = navy }); y += 18f
        }
        val weights = s.columns.map { col ->
            val longest = s.rows.take(100).fold(col.label.length) { m, r -> max(m, displayValue(col, cellOf(r, col.key)).length) }
            longest.coerceIn(4, 40).toFloat()
        }
        val total = weights.sum().coerceAtLeast(1f)
        val widths = weights.map { it / total * usable }
        val pad = 3.5f
        val lineH = fontSize + 2.5f
        fun right(col: ExportCol) = col.type == "money" || col.type == "number" || col.type == "percent"
        fun drawRow(cells: List<String>, p: Paint, bg: Int?, fg: Int) {
            val wrapped = cells.mapIndexed { i, t -> wrapText(clean(t), p, widths[i] - pad * 2) }
            val h = (wrapped.maxOfOrNull { it.size } ?: 1) * lineH + pad * 2
            if (y + h > pageH - 40) newPage()
            val c = page!!.canvas
            if (bg != null) { fill.color = bg; c.drawRect(margin, y, margin + usable, y + h, fill) }
            var x = margin
            val pp = Paint(p).apply { color = fg }
            wrapped.forEachIndexed { i, lines ->
                lines.forEachIndexed { li, ln ->
                    val tx = if (right(s.columns[i])) x + widths[i] - pad - pp.measureText(ln) else x + pad
                    c.drawText(ln, tx, y + pad + fontSize + li * lineH, pp)
                }
                x += widths[i]
            }
            y += h
        }
        drawRow(s.columns.map { it.label }, bold, navy, android.graphics.Color.WHITE)
        s.rows.forEachIndexed { i, r ->
            drawRow(s.columns.map { col -> displayValue(col, cellOf(r, col.key)) }, text, if (i % 2 == 1) android.graphics.Color.rgb(247, 249, 252) else null, ink)
        }
        if (hasTotals(s.columns) && s.rows.isNotEmpty()) {
            val t = s.totals ?: computeTotals(s.columns, s.rows)
            drawRow(s.columns.mapIndexed { i, col -> if (col.total) displayValue(col, t[col.key]) else if (i == 0) "TOTAL" else "" }, bold,
                android.graphics.Color.rgb(232, 238, 247), android.graphics.Color.rgb(15, 27, 45))
        }
        y += 14f
        if (s.note != null) {
            val np = Paint(text).apply { textSize = 7.5f; color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC) }
            wrapText(clean(s.note), np, usable).forEach { ln -> if (y > pageH - 40) newPage(); page!!.canvas.drawText(ln, margin, y, np); y += 10f }
            y += 8f
        }
    }
    finish()
    val out = ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return out.toByteArray() to pageNo
}

/** PDF bytes of a report (two passes so every footer reads "Page i of n"). */
suspend fun pdfReportBytes(
    title: String,
    sections: List<PdfSection>,
    subtitle: String? = null,
    organization: String? = null,
    landscape: Boolean? = null,
    footerNote: String? = null,
): ByteArray = withContext(Dispatchers.Default) {
    val note = footerNote ?: "LendFlow · Amounts in Philippine Pesos (PHP)"
    val (_, pages) = renderReport(title, sections, subtitle, organization, landscape, note, 0)
    renderReport(title, sections, subtitle, organization, landscape, note, pages).first
}

/** Builds the PDF report and saves it to Downloads. */
suspend fun exportPdfReport(
    context: Context,
    filename: String,
    title: String,
    sections: List<PdfSection>,
    subtitle: String? = null,
    organization: String? = null,
    landscape: Boolean? = null,
    footerNote: String? = null,
) {
    val bytes = pdfReportBytes(title, sections, subtitle, organization, landscape, footerNote)
    withContext(Dispatchers.Main) { saveToDownloads(context, fileName(filename, "pdf"), bytes, "application/pdf") }
}

suspend fun exportPdfTable(
    context: Context, filename: String, title: String, columns: List<ExportCol>, rows: List<Row>,
    subtitle: String? = null, organization: String? = null, totals: Map<String, Double>? = null, note: String? = null, landscape: Boolean? = null,
) = exportPdfReport(context, filename, title, listOf(PdfSection(columns, rows, totals = totals, note = note)), subtitle, organization, landscape)

/** Sends a PDF to Android's print framework (any installed print service, or Save as PDF). */
fun printPdf(context: Context, jobName: String, bytes: ByteArray) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(jobName, PdfBytesPrintAdapter(bytes, jobName), PrintAttributes.Builder().build())
}

/** PrintDocumentAdapter that streams an existing PDF. */
class PdfBytesPrintAdapter(private val bytes: ByteArray, private val name: String) : android.print.PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: android.os.CancellationSignal?,
        callback: LayoutResultCallback, extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        callback.onLayoutFinished(
            android.print.PrintDocumentInfo.Builder("$name.pdf").setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true,
        )
    }

    override fun onWrite(
        pages: Array<out android.print.PageRange>?, destination: android.os.ParcelFileDescriptor, cancellationSignal: android.os.CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            java.io.FileOutputStream(destination.fileDescriptor).use { it.write(bytes) }
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

@Suppress("unused") private fun keepAbs(v: Double) = abs(v)
