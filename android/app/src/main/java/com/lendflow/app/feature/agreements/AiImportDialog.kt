package com.lendflow.app.feature.agreements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.readable
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.Notice
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.rememberFilePicker
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACCEPT = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "text/*",
    "application/octet-stream",
)
private const val MAX = 15 * 1024 * 1024

/**
 * Upload the lender's existing terms (AiImportDialog.tsx); the built-in assistant converts them into
 * a template draft on this device. Nothing is sent to any server or AI service, and nothing is saved
 * until the user saves the template. Pasted text works too (for PDFs on devices that cannot read them).
 */
@Composable
fun AiImportDialog(onDismiss: () -> Unit, onDraft: (TermsDraft, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PickedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pasteMode by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pdfNote by remember { mutableStateOf<String?>(null) }

    fun pick(f: PickedFile) {
        if (f.size > MAX) { Toasts.error("The file is larger than 15 MB"); return }
        if (Regex("\\.doc$", RegexOption.IGNORE_CASE).containsMatchIn(f.name)) {
            Toasts.error("Old .doc files are not supported. Open it in Word and save it as .docx or PDF first."); return
        }
        if (fileKind(f.name, f.mime) == null) { Toasts.error("Use a PDF, Word (.docx) or text file. Photos and scans cannot be read."); return }
        if (fileKind(f.name, f.mime) == TextKind.pdf && !canReadPdfText) {
            pdfNote = PdfTextUnavailable().message
            pasteMode = true
        }
        file = f
    }
    val picker = rememberFilePicker { pick(it) }

    fun create() {
        val f = file
        busy = true
        scope.launch {
            try {
                val draft = if (pasteMode) {
                    val name = f?.name ?: "Pasted terms"
                    delay(30)
                    name to withContext(Dispatchers.Default) { convertTermsText(pasted, name, true) }
                } else {
                    val x = extractText(context, f!!)
                    // the screen updates before the conversion runs
                    delay(30)
                    f.name to withContext(Dispatchers.Default) { convertTermsText(x.text, f.name, x.kind != TextKind.docx) }
                }
                onDraft(draft.second, draft.first)
                file = null
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfTextUnavailable) {
                pdfNote = e.message
                pasteMode = true
            } catch (e: Throwable) {
                Toasts.error(e.readable())
            } finally {
                busy = false
            }
        }
    }

    AppDialog(
        title = "Turn your terms into a template",
        onDismiss = { if (!busy) onDismiss() },
        dismissible = !busy,
        maxWidth = 480.dp,
        description = "Upload the loan agreement or terms you use today. The built-in assistant keeps your wording and puts in the borrower, co-maker, amount, rate and dates as fields that fill in for every loan. You review it before saving.",
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline, enabled = !busy)
            Btn("Create template", { create() }, icon = Icons.Default.AutoAwesome, loading = busy,
                enabled = if (pasteMode) pasted.isNotBlank() else file != null)
        },
    ) {
        if (busy) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner(22.dp)
                Text("Converting ${file?.name ?: "your terms"}…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        } else if (!pasteMode) {
            val f = file
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(LF.Slate50).border(2.dp, LF.Slate200, shape)
                    .clickable { picker.launch(ACCEPT) }.padding(horizontal = 16.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (f != null) Icon(Icons.Default.Description, null, Modifier.size(32.dp), tint = LF.Violet700)
                else Icon(Icons.Default.Upload, null, Modifier.size(32.dp), tint = LF.Slate400)
                Text(f?.name ?: "Tap to choose a file", fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Text(if (f != null) "${f.size / 1024} KB · tap to change" else "PDF, Word (.docx) or text file", fontSize = 12.sp, color = LF.MutedForeground)
            }
            LinkText("Or paste the text of your terms", { pasteMode = true }, fontSize = 13.sp)
        } else {
            pdfNote?.let { Notice(it, Tone.amber) }
            Field("Text of your terms", hint = "Paste the whole agreement, from the title to the signature lines.") {
                TextInput(pasted, { pasted = it }, singleLine = false, minLines = 8, placeholder = "LOAN AGREEMENT\n\nThis Loan Agreement is made …")
            }
            LinkText("Choose a file instead", { pasteMode = false; pdfNote = null }, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.WifiOff, null, Modifier.size(14.dp).padding(top = 2.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(6.dp))
            Text("Runs on this device. Your file is not uploaded or sent to any AI service.", fontSize = 11.sp, color = LF.MutedForeground)
        }
    }
}
