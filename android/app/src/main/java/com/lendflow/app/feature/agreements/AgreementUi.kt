package com.lendflow.app.feature.agreements

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.readable
import com.lendflow.app.ui.components.PdfPages
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.saveToDownloads
import com.lendflow.app.ui.components.shareFile
import com.lendflow.app.ui.theme.LF

/** Copies text and shows the given toast (the web's clipboard + toast). */
internal fun copyText(context: Context, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LendFlow", text))
    Toasts.success(toast)
}

/** Decodes a "data:image/png;base64,…" signature to a bitmap for the preview. */
internal fun dataUrlBitmap(url: String?): Bitmap? = try {
    if (url == null) null else {
        val b = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    }
} catch (_: Exception) { null }

@Composable
internal fun SignatureImage(dataUrl: String?, modifier: Modifier = Modifier, maxHeight: Dp = 150.dp) {
    val bmp = remember(dataUrl) { dataUrlBitmap(dataUrl) } ?: return
    Image(bmp.asImageBitmap(), "Signature preview", modifier.fillMaxWidth().heightIn(max = maxHeight), contentScale = ContentScale.Fit)
}

/**
 * The web PdfViewer: the pages of a PDF inline (native PdfRenderer), with full screen, share and
 * download. [key] changes reload the document; null shows an empty frame. [onInteract] fires on
 * any touch inside the viewer (the signing page requires the borrower to open the document).
 */
@Composable
internal fun InlinePdf(
    key: Any?,
    fileName: String,
    title: String,
    height: Dp,
    load: suspend () -> ByteArray,
    onInteract: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewer = rememberPdfViewer()
    var bytes by remember(key) { mutableStateOf<ByteArray?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        try { bytes = load() } catch (e: Exception) { error = e.readable() }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(LF.Slate100).border(1.dp, LF.Border, shape)
            .then(if (onInteract != null) Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); onInteract() } }
            } else Modifier),
    ) {
        val b = bytes
        Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fileName, Modifier.weight(1f), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1)
            IconButton(onClick = { if (b != null) { onInteract?.invoke(); viewer.open(title, fileName) { b } } }, enabled = b != null) {
                Icon(Icons.Default.OpenInFull, "Full screen", Modifier.size(18.dp))
            }
            IconButton(onClick = { if (b != null) shareFile(context, fileName, b, "application/pdf") }, enabled = b != null) {
                Icon(Icons.Default.Share, "Share", Modifier.size(18.dp))
            }
            IconButton(onClick = { if (b != null) saveToDownloads(context, fileName, b, "application/pdf") }, enabled = b != null) {
                Icon(Icons.Default.Download, "Download", Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(height).padding(8.dp), contentAlignment = Alignment.Center) {
            when {
                key == null -> Text("No document", fontSize = 13.sp, color = LF.MutedForeground)
                error != null -> Text(error!!, fontSize = 13.sp, color = LF.Red700)
                b == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(22.dp)
                    Text("Loading document…", fontSize = 13.sp, color = LF.MutedForeground)
                }
                else -> PdfPages(b)
            }
        }
    }
}
