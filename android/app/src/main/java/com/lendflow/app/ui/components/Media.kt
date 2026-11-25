package com.lendflow.app.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Supa
import com.lendflow.app.data.readable
import com.lendflow.app.ui.theme.LF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------
// Picking files and photos
// ---------------------------------------------------------------------------
fun readPickedFile(context: Context, uri: Uri): PickedFile? = try {
    val cr = context.contentResolver
    var name = "file"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    val mime = cr.getType(uri)
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    if (!name.contains('.') && mime != null) {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (ext != null) name = "$name.$ext"
    }
    PickedFile(name, mime, bytes)
} catch (e: Exception) {
    Toasts.error("Could not read the file: ${e.message}")
    null
}

/** Opens the system document picker (gallery, files, Drive…). Call `launch(arrayOf("image/\*", "application/pdf"))`. */
class FilePicker(val launch: (Array<String>) -> Unit)

@Composable
fun rememberFilePicker(maxBytes: Int = 15 * 1024 * 1024, onPicked: (PickedFile) -> Unit): FilePicker {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readPickedFile(context, uri)?.let {
                if (it.size > maxBytes) Toasts.error("The file is too large (max ${maxBytes / 1024 / 1024} MB).") else onPicked(it)
            }
        }
    }
    return remember { FilePicker { types -> launcher.launch(types) } }
}

/** Takes a photo with the camera app and returns it as a JPEG. */
class CameraCapture(val launch: () -> Unit)

@Composable
fun rememberCameraCapture(onPicked: (PickedFile) -> Unit): CameraCapture {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (ok && f != null && f.exists()) onPicked(PickedFile("photo-${System.currentTimeMillis()}.jpg", "image/jpeg", f.readBytes()))
        f?.delete()
        pending = null
    }
    return remember {
        CameraCapture {
            try {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                pending = f
                launcher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
            } catch (e: Exception) {
                Toasts.error("The camera could not be opened.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Opening, sharing, downloading
// ---------------------------------------------------------------------------
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toasts.error("No app can open this link.")
    }
}

fun shareText(context: Context, text: String, subject: String? = null) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (subject != null) i.putExtra(Intent.EXTRA_SUBJECT, subject)
    context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyToClipboard(context: Context, text: String, label: String = "LendFlow") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toasts.success("Copied to clipboard")
}

/** Writes bytes to the app's shared cache and returns a content:// Uri other apps can read. */
fun cacheFile(context: Context, fileName: String, bytes: ByteArray): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    f.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
}

fun shareFile(context: Context, fileName: String, bytes: ByteArray, mime: String) {
    val uri = cacheFile(context, fileName, bytes)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(i, "Share $fileName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openFile(context: Context, fileName: String, bytes: ByteArray, mime: String) {
    val uri = cacheFile(context, fileName, bytes)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        shareFile(context, fileName, bytes, mime)
    }
}

/** Saves a file to the public Downloads folder (Android 10+) or shares it on older versions. */
fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray, mime: String) {
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no uri")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toasts.success("Saved to Downloads: $fileName")
        } else {
            shareFile(context, fileName, bytes, mime)
        }
    } catch (e: Exception) {
        shareFile(context, fileName, bytes, mime)
    }
}

// ---------------------------------------------------------------------------
// Images from private storage buckets (signed URLs)
// ---------------------------------------------------------------------------
@Composable
fun StorageImage(bucket: String, path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, contentDescription: String? = null) {
    var url by remember(bucket, path) { mutableStateOf<String?>(null) }
    var failed by remember(bucket, path) { mutableStateOf(false) }
    LaunchedEffect(bucket, path) {
        if (path.isNullOrBlank()) return@LaunchedEffect
        url = try { Supa.signedUrl(bucket, path, 3600) } catch (_: Exception) { failed = true; null }
    }
    if (url != null) {
        AsyncImage(model = url, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(LF.Muted), contentAlignment = Alignment.Center) {
            if (failed || path.isNullOrBlank()) Icon(Icons.Default.Image, null, tint = LF.Slate400) else Spinner()
        }
    }
}

// ---------------------------------------------------------------------------
// Native PDF viewer (android.graphics.pdf.PdfRenderer)
// ---------------------------------------------------------------------------
/** Renders every page of a PDF to bitmaps (2x for crisp text). */
suspend fun renderPdf(context: Context, bytes: ByteArray, maxWidthPx: Int = 1600): List<Bitmap> = withContext(Dispatchers.IO) {
    val f = File.createTempFile("view", ".pdf", context.cacheDir)
    f.writeBytes(bytes)
    val out = mutableListOf<Bitmap>()
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { r ->
            for (i in 0 until r.pageCount) {
                r.openPage(i).use { p ->
                    val scale = (maxWidthPx.toFloat() / p.width).coerceAtMost(3f)
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AColor.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out += bmp
                }
            }
        }
    }
    f.delete()
    out
}

/** Pages of a PDF in a scrolling column with pinch-to-zoom. */
@Composable
fun PdfPages(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pages by remember(bytes) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(bytes) { mutableStateOf<String?>(null) }
    LaunchedEffect(bytes) {
        try { pages = renderPdf(context, bytes) } catch (e: Exception) { error = "This PDF could not be displayed." }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    when {
        error != null -> ErrorState(error!!)
        pages == null -> PageLoader("Rendering document…")
        else -> LazyColumn(
            modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX = if (scale == 1f) 0f else (offsetX + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                }
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(pages!!) { i, bmp ->
                Image(
                    bmp.asImageBitmap(), "Page ${i + 1}",
                    Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX)
                        .border(1.dp, LF.Border).background(Color.White),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/**
 * Full-screen PDF viewer. [load] returns the PDF bytes (e.g. download a signed URL); the toolbar can
 * share the file or save it to Downloads.
 */
@Composable
fun PdfViewerDialog(title: String, fileName: String, onDismiss: () -> Unit, load: suspend () -> ByteArray) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { bytes = load() } catch (e: Exception) { error = e.readable() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(LF.Slate100)) {
            Row(Modifier.fillMaxWidth().background(LF.Navy).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                Text(title, Modifier.weight(1f), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val b = bytes
                if (b != null) {
                    IconButton(onClick = { shareFile(context, fileName, b, "application/pdf") }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
                    IconButton(onClick = { saveToDownloads(context, fileName, b, "application/pdf") }) { Icon(Icons.Default.Download, "Download", tint = Color.White) }
                }
            }
            Box(Modifier.fillMaxSize().padding(10.dp)) {
                when {
                    error != null -> ErrorState(error!!)
                    bytes == null -> PageLoader("Loading document…")
                    else -> PdfPages(bytes!!)
                }
            }
        }
    }
}

/** Helper state to open the viewer from a button: `pdf.open("Title", "file.pdf") { bytes }`. */
class PdfViewerState {
    var request by mutableStateOf<Triple<String, String, suspend () -> ByteArray>?>(null)
    fun open(title: String, fileName: String, load: suspend () -> ByteArray) { request = Triple(title, fileName, load) }
    /** Opens a signed URL (downloads it). */
    fun openUrl(title: String, fileName: String, url: suspend () -> String) = open(title, fileName) { Supa.download(url()) }
}

@Composable
fun rememberPdfViewer(): PdfViewerState {
    val s = remember { PdfViewerState() }
    s.request?.let { (title, name, load) -> PdfViewerDialog(title, name, { s.request = null }, load) }
    return s
}

/** Downloads a URL and hands it to another app (open / share). */
@Composable
fun rememberDownloader(): (url: String, fileName: String, mime: String, share: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        { url, name, mime, share ->
            scope.launch {
                try {
                    val b = Supa.download(url)
                    if (share) shareFile(context, name, b, mime) else openFile(context, name, b, mime)
                } catch (e: Exception) { Toasts.error(e.readable()) }
            }
        }
    }
}

