package com.lendflow.app.feature.borrowers

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Supa
import com.lendflow.app.data.readable
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.PdfViewerState
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.StorageImage
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.openFile
import com.lendflow.app.ui.components.rememberCameraCapture
import com.lendflow.app.ui.components.rememberFilePicker
import com.lendflow.app.ui.theme.LF
import kotlinx.coroutines.launch

/** Same limit and message as the web form (useLocalPreview / DocumentsTab). */
const val MAX_BORROWER_FILE = 10 * 1024 * 1024
const val FILE_TOO_LARGE = "Files must be 10 MB or smaller"

private val DropShape = RoundedCornerShape(8.dp)

/** Image preview of a picked (not yet uploaded) file. */
@Composable
fun LocalImage(file: PickedFile, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp = remember(file) {
        try { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)?.asImageBitmap() } catch (_: Throwable) { null }
    }
    if (bmp != null) Image(bmp, null, modifier, contentScale = contentScale)
    else Box(modifier.background(LF.Muted), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = LF.Slate400) }
}

fun PickedFile.isImage(): Boolean = (mime ?: "").startsWith("image/")

/**
 * Port of components/common/FileDrop.tsx: tap to pick a file. On phones the web lets the OS offer
 * "Take photo" or "Choose file"; here the tap opens the same two choices (camera or document picker).
 * [existingPath] is an already-uploaded file in the private borrower-files bucket (shown as preview).
 */
@Composable
fun FileDrop(
    onFile: (PickedFile) -> Unit,
    accept: Array<String> = arrayOf("image/*", "application/pdf"),
    label: String = "Upload a file",
    hint: String? = null,
    picked: PickedFile? = null,
    existingPath: String? = null,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val accepted: (PickedFile) -> Unit = { f -> if (f.size > MAX_BORROWER_FILE) Toasts.error(FILE_TOO_LARGE) else onFile(f) }
    val picker = rememberFilePicker(maxBytes = Int.MAX_VALUE, onPicked = accepted)
    val camera = rememberCameraCapture(accepted)
    val imageOnly = accept.all { it.startsWith("image") }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(DropShape)
                .background(LF.Slate50.copy(alpha = 0.6f))
                .border(2.dp, LF.Slate200, DropShape)
                .clickable(enabled = !disabled) { menu = true }
                .alpha(if (disabled) 0.6f else 1f)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previewMod = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0x0D000000), RoundedCornerShape(6.dp))
            when {
                picked != null && picked.isImage() -> LocalImage(picked, previewMod)
                picked == null && !existingPath.isNullOrBlank() && !existingPath.lowercase().endsWith(".pdf") ->
                    StorageImage("borrower-files", existingPath, previewMod)
                else -> Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, LF.Slate200, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (imageOnly) Icons.Default.Image else Icons.Default.UploadFile, null, Modifier.size(20.dp), tint = LF.Slate400)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (hint != null) Text(hint, fontSize = 12.sp, color = LF.MutedForeground)
            }
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Color.White)) {
            DropdownMenuItem(
                text = { Text("Take photo", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)) },
                onClick = { menu = false; camera.launch() },
            )
            DropdownMenuItem(
                text = { Text(if (imageOnly) "Choose photo" else "Choose file", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)) },
                onClick = { menu = false; picker.launch(accept) },
            )
        }
    }
}

/** ID image tile on the profile (IdImage on the web): tap opens the full file. */
@Composable
fun IdImage(path: String?, label: String, viewer: BorrowerFileViewer, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    if (path.isNullOrBlank()) {
        Box(modifier.fillMaxWidth().aspectRatio(1.6f).clip(shape).border(1.dp, LF.Border, shape), contentAlignment = Alignment.Center) {
            Text("$label: not uploaded", fontSize = 12.sp, color = LF.MutedForeground)
        }
        return
    }
    val isPdf = path.lowercase().endsWith(".pdf")
    Box(
        modifier.fillMaxWidth().aspectRatio(1.6f).clip(shape).background(LF.Slate50).border(1.dp, LF.Border, shape)
            .clickable { viewer.open(path, "$label of ID") },
    ) {
        if (!isPdf) StorageImage("borrower-files", path, Modifier.fillMaxWidth().aspectRatio(1.6f), contentDescription = label)
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(20.dp), tint = LF.MutedForeground)
            Text("$label (PDF)", fontSize = 12.sp, color = LF.MutedForeground)
        }
        Text(
            label, Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 11.sp,
        )
    }
}

/**
 * Opens a private borrower file (the web opens the signed URL in a new tab): PDFs in the native PDF
 * viewer, images in a full-size preview dialog, anything else in another app.
 */
@Stable
class BorrowerFileViewer(val pdf: PdfViewerState, private val external: (String, String?) -> Unit) {
    var image by mutableStateOf<Pair<String, String>?>(null)
    fun open(path: String, title: String, mime: String? = null) {
        val name = path.substringAfterLast('/')
        val type = mime?.ifBlank { null } ?: guessMime(name)
        when {
            type == "application/pdf" -> pdf.openUrl(title, name) { BorrowersService.fileUrl(path) }
            type.startsWith("image/") -> image = path to title
            else -> external(path, type)
        }
    }
}

@Composable
fun rememberBorrowerFileViewer(): BorrowerFileViewer {
    val pdf = rememberPdfViewer()
    val external = rememberBorrowerFileOpener()
    val viewer = remember { BorrowerFileViewer(pdf, external) }
    viewer.image?.let { (path, title) ->
        AppDialog(
            title = title, onDismiss = { viewer.image = null }, maxWidth = 900.dp,
            footer = {
                Btn("Open in another app", { external(path, null) }, variant = BtnVariant.Outline, icon = Icons.Default.OpenInNew)
                Btn("Close", { viewer.image = null })
            },
        ) {
            StorageImage("borrower-files", path, Modifier.fillMaxWidth().heightIn(min = 200.dp), contentScale = ContentScale.Fit, contentDescription = title)
        }
    }
    return viewer
}

/** Downloads a private borrower file (signed URL) and opens it in another app (image viewer, PDF reader…). */
@Composable
fun rememberBorrowerFileOpener(): (path: String, mime: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        { path, mime ->
            scope.launch {
                try {
                    val bytes = Supa.download(BorrowersService.fileUrl(path))
                    val name = path.substringAfterLast('/')
                    val type = mime?.ifBlank { null } ?: guessMime(name)
                    openFile(context, name, bytes, type)
                } catch (e: Exception) {
                    Toasts.error(e.readable())
                }
            }
        }
    }
}

fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    else -> "application/octet-stream"
}
