package com.lendflow.app.feature.loans.simplified

// Borrower steps of SimplifiedLoanPage.tsx: register a new borrower (with photo, ID, ATM card and
// co-maker), search an existing borrower, and the balance check of a renewal.

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.bool
import com.lendflow.app.feature.borrowers.BorrowerAvatar
import com.lendflow.app.feature.loans.ActionBar
import com.lendflow.app.feature.loans.MainAside
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Avatar
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectText
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberCameraCapture
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberFilePicker
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.PH_ID_TYPES
import com.lendflow.app.util.RELATIONSHIPS
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.initials
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import kotlinx.coroutines.launch

internal fun PickedFile.isImage(): Boolean =
    mime?.startsWith("image") == true || name.substringAfterLast('.', "").lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic", "gif")

/** Downscaled preview of a picked image (URL.createObjectURL on the web). */
@Composable
internal fun rememberThumb(file: PickedFile?, maxPx: Int = 900): ImageBitmap? = remember(file) {
    if (file == null || !file.isImage()) null else try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(file.bytes, 0, file.size, o)
        var sample = 1
        while (o.outWidth / sample > maxPx || o.outHeight / sample > maxPx) sample *= 2
        BitmapFactory.decodeByteArray(file.bytes, 0, file.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp = 12.dp): Modifier = drawBehind {
    drawRoundRect(
        color = color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
    )
}

/** Text input that reports when it loses focus (react-hook-form mode "onTouched"). */
@Composable
private fun TouchedInput(
    value: String,
    onValueChange: (String) -> Unit,
    onBlur: () -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    isError: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    TextInput(
        value, onValueChange,
        Modifier.onFocusChanged { st -> if (focused && !st.isFocused) onBlur(); focused = st.isFocused },
        placeholder = placeholder, keyboardType = keyboardType, singleLine = singleLine, minLines = if (singleLine) 1 else 2, isError = isError,
    )
}

// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewBorrowerStep(
    s: SimplifiedState,
    setFiles: (BorrowerFiles) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onRenewInstead: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val v = s.borrower
    val errors = validateBorrower(v)
    fun err(k: String) = if (s.submitted || k in s.touched) errors[k] else null
    fun touch(k: String) { s.touched = s.touched + k }
    @Suppress("UNUSED_PARAMETER") fun upd(k: String, f: (BorrowerDraft) -> BorrowerDraft) { s.borrower = f(s.borrower) }

    var dups by remember { mutableStateOf<List<Row>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("photo") }

    fun pick(k: String, f: PickedFile) {
        if (f.size > 10 * 1024 * 1024) { Toasts.error("Files must be 10 MB or smaller"); return }
        if (k == "photo" && !f.isImage()) { Toasts.error("The borrower photo must be an image"); return }
        val cur = s.files
        setFiles(when (k) { "photo" -> cur.copy(photo = f); "id" -> cur.copy(id = f); else -> cur.copy(atm = f) })
        s.fileErrors = s.fileErrors - k
    }
    val picker = rememberFilePicker(maxBytes = 60 * 1024 * 1024) { pick(target, it) }
    val camera = rememberCameraCapture { pick(target, it) }
    fun choose(k: String) { target = k; picker.launch(if (k == "photo") arrayOf("image/*") else arrayOf("image/*", "application/pdf")) }
    fun shoot(k: String) { target = k; camera.launch() }

    fun next() {
        s.submitted = true
        val fe = linkedMapOf<String, String>()
        if (s.files.photo == null) fe["photo"] = "Add the borrower's photo"
        if (s.files.id == null) fe["id"] = "Upload the ID"
        if (s.files.atm == null) fe["atm"] = "Upload the ATM card"
        s.fileErrors = fe
        if (errors.isNotEmpty() || fe.isNotEmpty()) { Toasts.error("Please complete the highlighted fields"); return }
        checking = true
        scope.launch {
            try {
                val found = BorrowersService.checkDuplicates(
                    firstName = v.first_name, lastName = v.last_name, dateOfBirth = null, mobile = normalizePhMobile(v.mobile),
                    idType = v.id_type, idNumber = v.id_number, exclude = null,
                )
                if (found.isNotEmpty()) dups = found else onNext()
            } catch (e: Exception) {
                Toasts.error(e.readable())
            } finally {
                checking = false
            }
        }
    }

    val compact = isCompact()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Borrower information", description = "Registered once and reused for every future renewal.") {
            val photo: @Composable () -> Unit = {
                Column(Modifier.widthIn(max = 180.dp)) {
                    Row {
                        Text("Borrower's photo", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(" *", fontSize = 14.sp, color = LF.Red500)
                    }
                    var menu by remember { mutableStateOf(false) }
                    val thumb = rememberThumb(s.files.photo)
                    Box(Modifier.padding(top = 6.dp)) {
                        Box(
                            Modifier.width(180.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(LF.Slate50)
                                .dashedBorder(if (s.fileErrors["photo"] != null) LF.Red200 else LF.Slate200)
                                .clickable { menu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) Image(thumb, "Borrower", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Smartphone, null, Modifier.size(24.dp), tint = LF.Slate400)
                                Text("Take or upload photo", fontSize = 12.sp, color = LF.Slate400)
                            }
                        }
                        DropdownMenu(menu, { menu = false }, Modifier.background(Color.White)) {
                            DropdownMenuItem({ Text("Take photo") }, { menu = false; shoot("photo") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) })
                            DropdownMenuItem({ Text("Choose from gallery") }, { menu = false; choose("photo") }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) })
                        }
                    }
                    s.fileErrors["photo"]?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = LF.Red600) }
                }
            }
            val fields: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val name: List<@Composable () -> Unit> = listOf(
                        { Field("First name", required = true, error = err("first_name")) { TouchedInput(v.first_name, { x -> upd("first_name") { it.copy(first_name = x) } }, { touch("first_name") }, isError = err("first_name") != null) } },
                        { Field("Middle name") { TouchedInput(v.middle_name, { x -> upd("middle_name") { it.copy(middle_name = x) } }, { touch("middle_name") }) } },
                        { Field("Last name", required = true, error = err("last_name")) { TouchedInput(v.last_name, { x -> upd("last_name") { it.copy(last_name = x) } }, { touch("last_name") }, isError = err("last_name") != null) } },
                    )
                    Cols(name)
                    Field("Complete address", required = true, error = err("address")) {
                        TouchedInput(v.address, { x -> upd("address") { it.copy(address = x) } }, { touch("address") }, placeholder = "House no., street, barangay, city / municipality, province", singleLine = false, isError = err("address") != null)
                    }
                    Cols(
                        listOf(
                            { Field("Contact number", required = true, error = err("mobile")) { TouchedInput(v.mobile, { x -> upd("mobile") { it.copy(mobile = x) } }, { touch("mobile") }, placeholder = "0917 123 4567", keyboardType = KeyboardType.Phone, isError = err("mobile") != null) } },
                            { Field("Company / employer", required = true, error = err("employer")) { TouchedInput(v.employer, { x -> upd("employer") { it.copy(employer = x) } }, { touch("employer") }, isError = err("employer") != null) } },
                        ),
                        weights = listOf(1f, 2f),
                    )
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { photo(); fields() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.width(180.dp)) { photo() }
                    Box(Modifier.weight(1f)) { fields() }
                }
            }
        }

        Panel(title = "Identification & ATM card", description = "Stored privately; only staff allowed to view identity documents can open them.") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Cols(
                    listOf(
                        {
                            Field("ID type", required = true, error = err("id_type")) {
                                SelectText(v.id_type.ifEmpty { null }, PH_ID_TYPES, { x -> touch("id_type"); upd("id_type") { it.copy(id_type = x) } }, placeholder = "Select ID…")
                            }
                        },
                        { Field("ID number", required = true, error = err("id_number")) { TouchedInput(v.id_number, { x -> upd("id_number") { it.copy(id_number = x) } }, { touch("id_number") }, isError = err("id_number") != null) } },
                    ),
                )
                Cols(
                    listOf(
                        { FileDrop(s.files.id, s.files.id?.name ?: "Upload ID", "Photo or PDF of the ID · max 10 MB", s.fileErrors["id"], { shoot("id") }, { choose("id") }) },
                        { FileDrop(s.files.atm, s.files.atm?.name ?: "Upload ATM card", "Front of the card · max 10 MB", s.fileErrors["atm"], { shoot("atm") }, { choose("atm") }) },
                    ),
                )
            }
        }

        Panel(title = "Co-maker information") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Cols(
                    listOf(
                        { Field("Co-maker full name", required = true, error = err("co_name")) { TouchedInput(v.co_name, { x -> upd("co_name") { it.copy(co_name = x) } }, { touch("co_name") }, isError = err("co_name") != null) } },
                        {
                            Field("Relationship to borrower", required = true, error = err("co_relationship")) {
                                SelectText(v.co_relationship.ifEmpty { null }, RELATIONSHIPS, { x -> touch("co_relationship"); upd("co_relationship") { it.copy(co_relationship = x) } })
                            }
                        },
                    ),
                )
                Cols(
                    listOf(
                        { Field("Co-maker contact number", required = true, error = err("co_mobile")) { TouchedInput(v.co_mobile, { x -> upd("co_mobile") { it.copy(co_mobile = x) } }, { touch("co_mobile") }, placeholder = "0918 765 4321", keyboardType = KeyboardType.Phone, isError = err("co_mobile") != null) } },
                        { Field("Co-maker address") { TouchedInput(v.co_address, { x -> upd("co_address") { it.copy(co_address = x) } }, { touch("co_address") }) } },
                    ),
                )
            }
        }

        ActionBar {
            Btn("Back", onBack, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
            Btn("Continue to loan details", ::next, Modifier.weight(1f).height(48.dp), loading = checking, icon = Icons.AutoMirrored.Filled.ArrowForward)
        }
    }

    val found = dups
    if (found != null) {
        AppDialog(
            "Possible existing borrower", { dups = null },
            description = "Borrower records must not be duplicated. If this person is already registered, use Reloan / Renew instead.",
            footer = {
                Btn("Edit details", { dups = null }, variant = BtnVariant.Outline)
                if (found.none { Regex("id", RegexOption.IGNORE_CASE).containsMatchIn(it.str("match_reason")) }) {
                    Btn("It's a different person — continue", { dups = null; onNext() }, variant = BtnVariant.Ghost)
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = LF.Warning)
                Text("Check the matches below", Modifier.padding(start = 8.dp), fontSize = 13.sp, color = LF.MutedForeground)
            }
            val shape = RoundedCornerShape(8.dp)
            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape)) {
                found.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = LF.Border)
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(d.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${d.str("borrower_code")} · ${phMobile(d.strOrNull("mobile_number"))} · matched on ${d.str("match_reason").replace("_", " ")}",
                                fontSize = 12.sp, color = LF.MutedForeground,
                            )
                        }
                        Btn("Reloan", { dups = null; onRenewInstead(d.str("borrower_id")) }, small = true, icon = Icons.Default.Refresh)
                    }
                }
            }
        }
    }
}

/** Equal (or weighted) columns on tablets, stacked on phones (`sm:grid-cols-n`). */
@Composable
internal fun Cols(cells: List<@Composable () -> Unit>, weights: List<Float>? = null) {
    if (isCompact()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { cells.forEach { it() } }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cells.forEachIndexed { i, c -> Box(Modifier.weight(weights?.getOrNull(i) ?: 1f)) { c() } }
        }
    }
}

/** Upload box (FileDrop on the web): preview / file name, and camera or file buttons. */
@Composable
private fun FileDrop(file: PickedFile?, label: String, hint: String, error: String?, onCamera: () -> Unit, onFile: () -> Unit) {
    val thumb = rememberThumb(file)
    Column {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Slate50)
                .dashedBorder(if (error != null) LF.Red200 else LF.Slate200).clickable(onClick = onFile).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (thumb != null) {
                Image(thumb, label, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            } else {
                Icon(if (file != null) Icons.Default.Description else Icons.Default.UploadFile, null, Modifier.size(28.dp), tint = LF.Slate400)
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, fontSize = 12.sp, color = LF.MutedForeground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Take photo", onCamera, variant = BtnVariant.Outline, small = true, icon = Icons.Default.CameraAlt)
                Btn("Choose file", onFile, variant = BtnVariant.Outline, small = true, icon = Icons.Default.UploadFile)
            }
        }
        if (error != null) Text(error, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = LF.Red600)
    }
}

// ---------------------------------------------------------------------------
@Composable
internal fun SearchStep(selected: String?, onBack: () -> Unit, onSelect: (Row) -> Unit) {
    var term by remember { mutableStateOf("") }
    val debounced = rememberDebounced(term, 250)
    val results = rememberLoad(debounced) { BorrowersService.search(debounced, 12) }
    var last by remember { mutableStateOf<List<Row>?>(null) }
    results.data?.let { last = it }
    val list = results.data ?: last
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }

    Panel(title = "Search existing borrower", description = "Search by name (or borrower code / mobile number). The borrower is not registered again.") {
        SearchField(term, { term = it }, Modifier.padding(bottom = 12.dp).focusRequester(focus), placeholder = "e.g. Dela Cruz")
        when {
            list == null -> PageLoader()
            list.isEmpty() -> EmptyState("No matching borrowers", description = "Check the spelling, or go back and choose New loan for a first-time borrower.", icon = Icons.Default.People)
            else -> {
                val shape = RoundedCornerShape(8.dp)
                Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape)) {
                    list.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(color = LF.Border)
                        Row(
                            Modifier.fillMaxWidth().background(if (selected == b.str("id")) LF.Blue50 else Color.White).clickable { onSelect(b) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(initials(b.str("full_name")), 40.dp, bg = LF.Slate100, fg = LF.Slate700)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))}${b.strOrNull("employer_name")?.let { " · $it" } ?: ""}",
                                    fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                        }
                    }
                }
            }
        }
        Btn("Back", onBack, Modifier.padding(top = 16.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
    }
}

// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BalanceStep(data: Row, onBack: () -> Unit, onNext: () -> Unit) {
    val router = LocalRouter.current
    val b = data.obj("borrower")
    val openLoans = data.objs("open_loans")
    val blocked = openLoans.firstOrNull { !it.strOrNull("pending_renewal_loan").isNullOrBlank() }
    val coMaker = data.obj("co_maker")
    val hasOutstanding = data.bool("has_outstanding")
    MainAside(
        asideWidth = 360.dp,
        main = {
            Panel(title = "Borrower") {
                Row(verticalAlignment = Alignment.Top) {
                    BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 56.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(b.str("full_name"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))}${b.strOrNull("email")?.let { " · $it" } ?: ""}", fontSize = 14.sp, color = LF.MutedForeground)
                        Text((b.strOrNull("address")?.ifBlank { null } ?: "No address on file") + (b.strOrNull("employer_name")?.let { " · $it" } ?: ""), fontSize = 14.sp, color = LF.MutedForeground)
                        if (coMaker != null) {
                            Text(
                                "Co-maker: ${coMaker.str("full_name")}${coMaker.strOrNull("relationship")?.let { " ($it)" } ?: ""}",
                                Modifier.padding(top = 4.dp), fontSize = 12.sp, color = LF.MutedForeground,
                            )
                        }
                    }
                    Btn("Profile", { router.go("/borrowers/${b.str("id")}") }, variant = BtnVariant.Ghost, small = true)
                }
            }

            Panel(title = "Current active loan", description = "Balances read from the ledger as of ${fmtDate(data.strOrNull("as_of"))}.") {
                if (openLoans.isEmpty()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Green50).padding(16.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.padding(top = 2.dp).size(20.dp), tint = Color(0xFF14532D))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("No outstanding balance", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14532D))
                            Text("This borrower has no active loan. Continue with a regular renewal — nothing will be deducted.", fontSize = 14.sp, color = Color(0xFF14532D))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        openLoans.forEach { l ->
                            val shape = RoundedCornerShape(12.dp)
                            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape).padding(16.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinkText(l.str("loan_number"), { router.go("/loans/${l.str("loan_id")}") }, fontSize = 14.sp)
                                    LoanStatusBadge(l.str("status"), Modifier.align(Alignment.CenterVertically))
                                    l.strOrNull("pending_renewal_loan")?.let { Pill("Already being renewed by $it", Tone.amber, modifier = Modifier.align(Alignment.CenterVertically)) }
                                }
                                KeyValue(
                                    listOf(
                                        KV("Original loan amount", peso(l.num("principal"))),
                                        KV("Released", fmtDate(l.strOrNull("release_date"))),
                                        KV("Maturity", fmtDate(l.strOrNull("maturity_date"))),
                                        KV("Installment", peso(l.num("installment_amount"))),
                                        KV("Remaining on contract", peso(l.num("contract_balance"))),
                                        KV("Unearned interest rebated", peso(l.num("interest_rebate"))),
                                    ),
                                    columns = 3, modifier = Modifier.padding(top = 12.dp),
                                )
                                Row(
                                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Amber50).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Remaining outstanding balance (payoff today)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF451A03))
                                    Text(peso(l.num("payoff")), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF451A03))
                                }
                                Text(
                                    "Principal ${peso(l.num("payoff_principal"))} · interest earned ${peso(l.num("payoff_interest"))}" +
                                        (if (l.num("payoff_charges") > 0) " · charges ${peso(l.num("payoff_charges"))}" else "") +
                                        (if (l.num("payoff_penalties") > 0) " · penalties ${peso(l.num("payoff_penalties"))}" else ""),
                                    Modifier.padding(top = 6.dp), fontSize = 11.sp, color = LF.MutedForeground,
                                )
                            }
                        }
                    }
                }
                val pipeline = data.objs("pipeline_loans")
                if (pipeline.isNotEmpty()) {
                    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Blue50).padding(12.dp)) {
                        Icon(Icons.Default.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = Color(0xFF1E3A8A))
                        Text(
                            "Not yet released: ${pipeline.joinToString(", ") { "${it.str("loan_number")} (${it.str("status").replace("_", " ")})" }}. Make sure you are not creating a duplicate loan.",
                            Modifier.padding(start = 8.dp), fontSize = 12.sp, color = Color(0xFF1E3A8A),
                        )
                    }
                }
            }

            Panel(title = "Previous loan details", actions = { Icon(Icons.Default.History, null, Modifier.size(16.dp), tint = LF.MutedForeground) }) {
                val history = data.objs("history")
                if (history.isEmpty()) {
                    Text("No previous loans.", fontSize = 14.sp, color = LF.MutedForeground)
                } else {
                    Column {
                        history.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(color = LF.Border)
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        l.str("loan_number"), Modifier.clickable { router.go("/loans/${l.str("id")}") },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${peso(l.num("principal"), 0)} · released ${fmtDate(l.strOrNull("release_date"))} · paid ${peso(l.num("total_paid"), 0)}" +
                                            (if (l.num("renewal_deduction") > 0) " · offset ${peso(l.num("renewal_deduction"), 0)}" else ""),
                                        fontSize = 12.sp, color = LF.MutedForeground,
                                    )
                                }
                                LoanStatusBadge(l.str("status"))
                            }
                        }
                    }
                }
            }
        },
        aside = {
            Panel(title = "Balance check") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hasOutstanding) Color(0xFFF59E0B) else LF.Green600).padding(16.dp)) {
                        Text("EXISTING OUTSTANDING BALANCE", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), letterSpacing = 0.5.sp)
                        Text(peso(data.num("total_payoff")), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(if (hasOutstanding) "Will be deducted from the new loan" else "Nothing to deduct", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                    if (hasOutstanding) {
                        Text(
                            "The payoff follows your early-settlement rule (${data.str("settlement_rule").replaceFirst("_", " ")}). It is recomputed on the release date, when the previous loan is settled in full.",
                            fontSize = 12.sp, color = LF.MutedForeground,
                        )
                    }
                    if (blocked != null) {
                        RedNote("${blocked.str("loan_number")} is already being renewed by ${blocked.str("pending_renewal_loan")}. Release or cancel that loan first.")
                    }
                    val active = b.str("status") == "active"
                    if (!active) RedNote("This borrower is ${b.str("status")}; loans can only be created for active borrowers.")
                    Btn(
                        if (hasOutstanding) "Create renewal loan" else "Continue with renewal", onNext, Modifier.fillMaxWidth().height(48.dp),
                        enabled = blocked == null && active, icon = Icons.AutoMirrored.Filled.ArrowForward,
                    )
                    Btn("Choose another borrower", onBack, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
                }
            }
        },
    )
}

@Composable
internal fun RedNote(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Red50).padding(12.dp), fontSize = 12.sp, color = LF.Red700)
}
