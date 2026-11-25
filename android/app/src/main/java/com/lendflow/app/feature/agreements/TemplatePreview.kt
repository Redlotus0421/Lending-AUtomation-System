package com.lendflow.app.feature.agreements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Notice
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.num

private val INK = Color(0xFF111827)
private val CELL_BORDER = Color(0xFFCBD5E1)
private val MISSING_PH = Regex("\\{\\{[a-z0-9_]+\\}\\}", RegexOption.IGNORE_CASE)

/** Runs with **bold** and unknown {{placeholders}} highlighted (RunsView on the web). */
private fun runsText(runs: List<Run>): AnnotatedString = buildAnnotatedString {
    runs.forEach { r ->
        val style = if (r.bold) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle()
        withStyle(style) {
            var last = 0
            for (m in MISSING_PH.findAll(r.text)) {
                append(r.text.substring(last, m.range.first))
                withStyle(SpanStyle(background = Color(0xFFFEF3C7), color = Color(0xFF92400E))) { append(m.value) }
                last = m.range.last + 1
            }
            append(r.text.substring(last))
        }
    }
}

/**
 * Preview of a template filled with sample data (the PDF uses the same parser on the server).
 * [maxHeight] caps the document with its own scroll (beside the editor on tablets); null = full height.
 */
@Composable
fun TemplatePreview(title: String, body: String, org: Row?, maxHeight: Dp? = null) {
    val data = remember(org) { sampleRenderData(org) }
    val (blocks, missing) = remember(body, data) {
        val values = buildPlaceholderValues(data)
        val filled = fillPlaceholders(body, values)
        parseTemplate(filled.text) to filled.missing
    }
    val o = data.obj("organization")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (missing.isNotEmpty()) Notice("Unknown placeholders: ${missing.joinToString(", ") { "{{$it}}" }}", Tone.amber)
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxWidth().card()
                .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight).verticalScroll(scroll) else Modifier)
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Text(o.strOrNull("legal_name")?.ifBlank { null } ?: o.str("name"), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy)
            Text(listOf("address", "city", "province").mapNotNull { o.strOrNull(it)?.ifBlank { null } }.joinToString(", "),
                Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = LF.Slate500)
            HorizontalDivider(color = CELL_BORDER, modifier = Modifier.padding(vertical = 8.dp))
            Text(title.uppercase(), Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), textAlign = TextAlign.Center,
                fontFamily = FontFamily.Serif, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = INK)
            Text("Agreement No. ${data.obj("agreement").str("agreement_number")} · Loan Ref. ${data.obj("loan").str("loan_number")} · Version 1",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp, color = LF.Slate500)
            blocks.forEach { b -> PreviewBlock(b, data) }
        }
    }
}

@Composable
private fun DocText(text: AnnotatedString, modifier: Modifier = Modifier, size: TextUnit = 14.sp, weight: FontWeight? = null, align: TextAlign = TextAlign.Justify) {
    Text(text, modifier.fillMaxWidth(), fontFamily = FontFamily.Serif, fontSize = size, lineHeight = size * 1.6f, fontWeight = weight, color = INK, textAlign = align)
}

@Composable
private fun PreviewBlock(b: Block, data: Row) {
    when (b) {
        is Block.Heading -> {
            val text = if (b.level <= 2) AnnotatedString(runsText(b.runs).text.uppercase()) else runsText(b.runs)
            DocText(text, Modifier.padding(top = if (b.level <= 2) 16.dp else 12.dp, bottom = 5.dp), size = if (b.level <= 2) 15.sp else 14.sp,
                weight = FontWeight.SemiBold, align = TextAlign.Start)
        }
        is Block.Paragraph -> DocText(runsText(b.runs), Modifier.padding(vertical = 6.dp))
        is Block.ListBlock -> Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, it ->
                Row {
                    Text(if (b.ordered) "${i + 1}." else "•", Modifier.width(22.dp), fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 22.sp, color = INK)
                    DocText(runsText(it), align = TextAlign.Start)
                }
            }
        }
        Block.Rule -> HorizontalDivider(color = CELL_BORDER, modifier = Modifier.padding(vertical = 14.dp))
        Block.Disclosure -> DocTable(
            header = null,
            rows = disclosureRows(data).map { (k, v) -> listOf(k, v) },
            weights = listOf(1f, 1f),
            aligns = listOf(TextAlign.Start, TextAlign.Start),
        )
        Block.Schedule -> DocTable(
            header = listOf("#", "Due date", "Principal", "Interest", "Total", "Balance"),
            rows = data.objs("schedule").map { r ->
                listOf(r.str("installment_no"), fmtDate(r.strOrNull("due_date")), num(r.num("principal"), 2), num(r.num("interest"), 2), num(r.num("total"), 2), num(r.num("closing_balance"), 2))
            },
            weights = listOf(0.5f, 1.3f, 1f, 1f, 1f, 1f),
            aligns = listOf(TextAlign.Center, TextAlign.Start, TextAlign.End, TextAlign.End, TextAlign.End, TextAlign.End),
            minWidth = 520.dp,
        )
        Block.Signatures -> {
            val signers = buildList {
                add(Triple("BORROWER", data.obj("borrower").str("full_name"), "Signature over printed name"))
                data.obj("co_maker")?.let { add(Triple("CO-MAKER", it.str("full_name"), "Signature over printed name")) }
                add(Triple("LENDER", data.obj("officer")?.strOrNull("full_name") ?: "", "Authorized representative"))
            }
            Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                signers.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        pair.forEach { (label, name, caption) ->
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy)
                                Spacer(Modifier.height(40.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))
                                Text(name, Modifier.padding(top = 4.dp), fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = INK)
                                Text(caption, fontSize = 12.sp, color = LF.Slate500)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DocTable(header: List<String>?, rows: List<List<String>>, weights: List<Float>, aligns: List<TextAlign>, minWidth: Dp = 0.dp) {
    val shape = RoundedCornerShape(2.dp)
    // wide tables (the schedule) scroll sideways on narrow screens with fixed column widths
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    val scroll = minWidth > maxWidth
    val unit = if (scroll) minWidth / weights.sum() else 0.dp
    Box(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
        Column(Modifier.then(if (scroll) Modifier else Modifier.fillMaxWidth()).clip(shape).border(1.dp, CELL_BORDER, shape)) {
            @Composable
            fun line(cells: List<String>, head: Boolean) {
                Row(Modifier.height(IntrinsicSize.Min).then(if (head) Modifier.background(LF.Slate100) else Modifier)) {
                    cells.forEachIndexed { i, c ->
                        val cell = if (scroll) Modifier.width(unit * weights[i]) else Modifier.weight(weights[i])
                        Box(cell.fillMaxHeight().border(0.5.dp, CELL_BORDER).padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(c, Modifier.fillMaxWidth(), fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = if (head) FontWeight.SemiBold else null,
                                color = INK, textAlign = aligns[i])
                        }
                    }
                }
            }
            if (header != null) line(header, true)
            rows.forEach { line(it, false) }
        }
    }
    }
}

@Suppress("unused") private fun keepAlign(a: Alignment) = a
