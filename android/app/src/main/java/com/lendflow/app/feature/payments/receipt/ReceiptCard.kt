package com.lendflow.app.feature.payments.receipt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.EmptyRow
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ChipRow
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Loadable
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SectionLabel
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Receipt preview with Print receipt / Close (ReceiptCard.tsx). Used right after a payment is posted and
// for reprints from Records. It only reads the stored receipt, so printing any number of times never
// creates a payment or a receipt.

/** Receipt preview with print (system / Bluetooth thermal) and PDF actions, shown as a dialog. */
@Composable
fun ReceiptDialog(paymentId: String, reprint: Boolean = false, onClose: () -> Unit) {
    val q = rememberLoad(paymentId) { PaymentsService.receipt(paymentId) }
    val d = q.data?.obj("data")
    AppDialog(
        title = if (reprint) "Reprint receipt" else "Receipt",
        onDismiss = onClose,
        description = d?.let { "${q.data.str("receipt_number")} · ${it.str("payment_number")} · ${peso(it.str("amount"))}" },
        maxWidth = 448.dp,
    ) {
        ReceiptCardBody(q, reprint, onClose)
    }
}

/** The receipt preview and print buttons (embed inside a dialog or panel). */
@Composable
fun ReceiptCard(paymentId: String, reprint: Boolean = false, onClose: () -> Unit) {
    val q = rememberLoad(paymentId) { PaymentsService.receipt(paymentId) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { ReceiptCardBody(q, reprint, onClose) }
}

@Composable
private fun ColumnScope.ReceiptCardBody(q: Loadable<Row?>, reprint: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paper by remember { mutableIntStateOf(ReceiptPrefs.paper(ctx)) }
    var btBusy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(ReceiptPrefs.printer(ctx)) }
    val receipt = q.data
    val model = remember(receipt, reprint) { receipt?.let { buildReceiptModel(it.obj("data") ?: EmptyRow, it.bool("is_void"), reprint) } }
    val bt = remember { Bt.adapter(ctx) != null }
    val gate = rememberBluetoothGate()

    fun choosePaper(w: Int) { paper = w; ReceiptPrefs.savePaper(ctx, w) }
    fun systemPrint() {
        val m = model ?: return
        try { printReceiptSystem(ctx, m, paper) } catch (e: Exception) { Toasts.error(e.message ?: "The print dialog could not be opened") }
    }
    fun send(target: SavedPrinter) {
        val m = model ?: return
        btBusy = true
        scope.launch {
            try {
                val name = ThermalPrinter.print(ctx, target, escposBytes(m, paper))
                Toasts.success("Sent to $name")
            } catch (e: Exception) {
                Toasts.error(e.message?.ifBlank { null } ?: "The receipt could not be printed")
            } finally {
                btBusy = false
            }
        }
    }
    fun bluetoothPrint(choose: Boolean) {
        gate.ensure(scan = false) {
            val s = ReceiptPrefs.printer(ctx)
            if (s == null || choose) pickerOpen = true else send(s)
        }
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Slate100).padding(12.dp), contentAlignment = Alignment.TopCenter) {
        when {
            q.initialLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
            q.error != null -> ErrorState(q.error, onRetry = q.reload)
            model == null -> Text("No receipt was issued for this payment.", Modifier.fillMaxWidth().padding(vertical = 36.dp),
                fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
            else -> ReceiptPreview(model, paper)
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Paper width", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(2.dp)) {
            listOf(58, 80).forEach { w ->
                val sel = paper == w
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) LF.Navy else Color.Transparent)
                        .clickable { choosePaper(w) }.heightIn(min = 36.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$w mm", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (sel) Color.White else LF.Slate600) }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Close", onClose, Modifier.weight(1f).height(48.dp), variant = BtnVariant.Outline)
        Box(Modifier.weight(1f)) {
            if (bt) {
                Btn("Print receipt", { menuOpen = true }, Modifier.fillMaxWidth().height(48.dp), icon = Icons.Default.Print, loading = btBusy, enabled = model != null)
                DropdownMenu(menuOpen, { menuOpen = false }, Modifier.background(Color.White).width(256.dp)) {
                    Text("Print to", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = LF.MutedForeground)
                    DropdownMenuItem(
                        { Text(saved?.name ?: "Bluetooth thermal printer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        { menuOpen = false; bluetoothPrint(false) },
                        leadingIcon = { Icon(Icons.Default.Bluetooth, null) },
                    )
                    if (saved != null) {
                        DropdownMenuItem({ Text("Choose another printer") }, { menuOpen = false; bluetoothPrint(true) }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                    }
                    HorizontalDivider(color = LF.Border)
                    DropdownMenuItem({ Text("System print dialog") }, { menuOpen = false; systemPrint() }, leadingIcon = { Icon(Icons.Default.Print, null) })
                }
            } else {
                Btn("Print receipt", { systemPrint() }, Modifier.fillMaxWidth().height(48.dp), icon = Icons.Default.Print, enabled = model != null)
            }
        }
    }
    if (!bt) {
        Text("Prints through Android's print dialog (a print service or a thermal-printer app). This device has no Bluetooth for direct thermal printing.",
            Modifier.fillMaxWidth(), fontSize = 11.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
    }

    if (pickerOpen) {
        PrinterPickerDialog(
            gate = gate,
            current = saved,
            onDismiss = { pickerOpen = false },
            onPick = { p ->
                pickerOpen = false
                ThermalPrinter.disconnect()
                ReceiptPrefs.savePrinter(ctx, p)
                saved = p
                send(p)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// On-screen preview (receiptBodyHtml + RECEIPT_CSS)
// ---------------------------------------------------------------------------
@Composable
fun ReceiptPreview(m: ReceiptModel, paper: Int) {
    Column(
        Modifier.widthIn(max = if (paper == 58) 230.dp else 320.dp).fillMaxWidth()
            .shadow(1.dp).background(Color.White).padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        ProvideTextStyle(TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.5.sp, color = Color.Black)) {
            val c = m.company
            if (c.name.isNotEmpty()) RCenter(c.name, bold = true)
            c.address?.let { RCenter(it, small = true) }
            c.phone?.let { RCenter(it, small = true) }
            c.tin?.let { RCenter("TIN $it", small = true) }
            RRule()
            RCenter("PAYMENT RECEIPT", bold = true)
            if (m.isVoid) RCenter("*** VOID ***", bold = true, scale = 1.4f)
            m.reprintedAt?.let { RCenter("REPRINT $it", small = true) }
            RRule()
            RRow("Receipt No.", m.receiptNo, bold = true)
            RRow("Payment No.", m.paymentNo)
            RRow("Date", "${fmtDate(m.date)}${m.time?.let { " $it" } ?: ""}")
            RRow("Borrower", m.borrower, bold = true)
            m.borrowerCode?.let { RRow("Borrower code", it) }
            RRow("Loan", m.loanNo)
            m.dueDates.forEach { RRow("Due date #${it.no}", fmtDate(it.date)) }
            RRow("Method", m.method)
            m.reference?.let { RRow("Reference", it) }
            RRule()
            val atm = m.atm
            if (atm != null) {
                RRow("ATM Withdrawal", peso(atm.withdrawal))
                RRow("Loan Payment", peso(atm.payment))
                RRule()
                RRow("Cash Returned", peso(atm.cashReturned), bold = true, scale = 1.15f)
            } else {
                RRow("Amount Paid", peso(m.amount), bold = true, scale = 1.15f)
            }
            RRule()
            RRow("Remaining Balance", peso(m.remaining), bold = true)
            RRow("Status", m.status)
            m.receivedBy?.let { RRow("Received by", it) }
            m.footer?.let { Spacer(Modifier.height(7.dp)); RCenter(it, small = true) }
            Spacer(Modifier.height(7.dp))
            RCenter("Thank you!", small = true)
        }
    }
}

@Composable
private fun RCenter(text: String, bold: Boolean = false, small: Boolean = false, scale: Float = 1f) {
    Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = (11.5f * scale * if (small) 0.9f else 1f).sp, lineHeight = (15.5f * scale).sp)
}

@Composable
private fun RRow(label: String, value: String, bold: Boolean = false, scale: Float = 1f) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    val size = (11.5f * scale).sp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontWeight = w, fontSize = size, softWrap = false)
        Text(value, Modifier.weight(1f), fontWeight = w, fontSize = size, textAlign = TextAlign.End)
    }
}

@Composable
private fun RRule() {
    Canvas(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp)) {
        drawLine(Color.Black, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
    }
}

// ---------------------------------------------------------------------------
// Runtime permissions + "turn on Bluetooth"
// ---------------------------------------------------------------------------
class BluetoothGate(private val ctx: Context) {
    internal var requestPermissions: (Array<String>) -> Unit = {}
    internal var requestEnable: () -> Unit = {}
    private var pending: (() -> Unit)? = null
    private var scan = false

    /** Runs [onReady] once Bluetooth is available, permitted ([scan] adds the BLE scan permission) and turned on. */
    fun ensure(scan: Boolean, onReady: () -> Unit) {
        if (Bt.adapter(ctx) == null) { Toasts.error("This device has no Bluetooth. Use System print instead."); return }
        pending = onReady
        this.scan = scan
        step()
    }

    private fun step() {
        val missing = Bt.missing(ctx, scan)
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray()); return }
        if (!Bt.isEnabled(ctx)) {
            try { requestEnable() } catch (e: Exception) {
                pending = null
                Toasts.error("Turn on Bluetooth to print.")
            }
            return
        }
        val p = pending
        pending = null
        p?.invoke()
    }

    internal fun onPermissionResult() {
        if (Bt.missing(ctx, scan).isEmpty()) { step(); return }
        pending = null
        Toasts.error(
            if (scan && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) "Location permission is needed to find Bluetooth Low Energy printers on this Android version."
            else "Allow LendFlow to use Nearby devices (Bluetooth) to print. You can change this in Settings › Apps › LendFlow › Permissions.",
        )
    }

    internal fun onEnableResult() {
        if (Bt.isEnabled(ctx)) step() else { pending = null; Toasts.error("Turn on Bluetooth to print.") }
    }
}

@Composable
fun rememberBluetoothGate(): BluetoothGate {
    val ctx = LocalContext.current
    val gate = remember { BluetoothGate(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { gate.onPermissionResult() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { gate.onEnableResult() }
    gate.requestPermissions = { perms.launch(it) }
    gate.requestEnable = { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    return gate
}

// ---------------------------------------------------------------------------
// Printer picker: paired (Classic / BLE) devices + a BLE scan
// ---------------------------------------------------------------------------
private data class FoundDevice(val address: String, val name: String?, val type: PrinterType, val printer: Boolean, val rssi: Int? = null)

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<FoundDevice> = try {
    Bt.adapter(ctx)?.bondedDevices.orEmpty().map { d ->
        val name = d.name
        val uuids = d.uuids?.map { it.uuid }.orEmpty()
        FoundDevice(d.address, name, if (d.type == BluetoothDevice.DEVICE_TYPE_LE) PrinterType.Ble else PrinterType.Classic,
            looksLikePrinter(name, uuids) || SPP_UUID in uuids)
    }.sortedWith(compareBy<FoundDevice> { !it.printer }.thenBy { it.name ?: "~" })
} catch (_: SecurityException) { emptyList() }

@SuppressLint("MissingPermission")
@Composable
private fun PrinterPickerDialog(gate: BluetoothGate, current: SavedPrinter?, onDismiss: () -> Unit, onPick: (SavedPrinter) -> Unit) {
    val ctx = LocalContext.current
    val bonded = remember { mutableStateListOf<FoundDevice>().apply { addAll(bondedDevices(ctx)) } }
    val found = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf("printers") }
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                val name = result.scanRecord?.deviceName ?: try { d.name } catch (_: SecurityException) { null }
                val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val item = FoundDevice(d.address, name, PrinterType.Ble, looksLikePrinter(name, services), result.rssi)
                val i = found.indexOfFirst { it.address == item.address }
                if (i >= 0) found[i] = item.copy(name = item.name ?: found[i].name, printer = item.printer || found[i].printer) else found += item
            }
            override fun onScanFailed(errorCode: Int) {
                scanning = false
                Toasts.error("The Bluetooth scan failed (error $errorCode). Turn Bluetooth off and on, then try again.")
            }
        }
    }
    fun stop() {
        if (!scanning) return
        scanning = false
        try { Bt.adapter(ctx)?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
    }
    fun start() {
        gate.ensure(scan = true) {
            val scanner = Bt.adapter(ctx)?.bluetoothLeScanner
            if (scanner == null) { Toasts.error("Bluetooth Low Energy scanning is not available. Turn on Bluetooth and try again."); return@ensure }
            bonded.clear(); bonded.addAll(bondedDevices(ctx))
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                scanning = true
            } catch (e: SecurityException) {
                Toasts.error("Allow LendFlow to use Nearby devices (Bluetooth) to scan for printers.")
            }
        }
    }
    LaunchedEffect(scanning) { if (scanning) { delay(12_000); stop() } }
    LaunchedEffect(Unit) { if (Bt.missing(ctx, true).isEmpty() && Bt.isEnabled(ctx)) start() }
    DisposableEffect(Unit) { onDispose { stop() } }

    val nearby = found.filter { f -> bonded.none { it.address == f.address } && (showAll == "all" || f.printer) }
        .sortedWith(compareBy<FoundDevice> { !it.printer }.thenByDescending { it.rssi ?: -999 })

    AppDialog(
        title = "Choose printer",
        onDismiss = { stop(); onDismiss() },
        description = "Pick your thermal receipt printer. Most 58 mm printers connect over Bluetooth Classic once paired; newer ones use Bluetooth Low Energy.",
        maxWidth = 480.dp,
        footer = { Btn("Cancel", { stop(); onDismiss() }, variant = BtnVariant.Outline) },
    ) {
        Column {
            SectionLabel("Paired devices")
            if (bonded.isEmpty()) {
                Text("No paired devices. Pair your printer in Android's Bluetooth settings (the PIN is usually 0000 or 1234), then come back — or scan for a BLE printer below.",
                    fontSize = 13.sp, color = LF.MutedForeground)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bonded.forEach { d -> DeviceRow(d, d.address == current?.address) { onPick(SavedPrinter(d.address, d.type, d.name ?: "Bluetooth printer")) } }
                }
            }
            Btn("Open Bluetooth settings", {
                try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { Toasts.error("Bluetooth settings could not be opened.") }
            }, Modifier.padding(top = 8.dp), variant = BtnVariant.Ghost, icon = Icons.Default.Settings, small = true)
        }
        HorizontalDivider(color = LF.Border)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEARBY BLE PRINTERS", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LF.MutedForeground, letterSpacing = 0.6.sp)
                if (scanning) {
                    Spinner(14.dp)
                    Spacer(Modifier.width(8.dp))
                    Btn("Stop", { stop() }, variant = BtnVariant.Ghost, small = true)
                } else {
                    Btn("Scan", { start() }, variant = BtnVariant.Outline, icon = Icons.Default.BluetoothSearching, small = true)
                }
            }
            ChipRow(listOf(Opt("printers", "Printers"), Opt("all", "Any device")), showAll, { showAll = it })
            if (nearby.isEmpty()) {
                Text(
                    when {
                        scanning -> "Searching… keep the printer on and close to the phone."
                        found.isEmpty() -> "Tap Scan to look for Bluetooth Low Energy printers nearby." +
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " Location must be turned on for the scan on this Android version." else ""
                        else -> "No printers found. Choose “Any device” if your printer has an unusual name."
                    },
                    fontSize = 13.sp, color = LF.MutedForeground,
                )
            } else {
                nearby.forEach { d -> DeviceRow(d, d.address == current?.address) { stop(); onPick(SavedPrinter(d.address, d.type, d.name ?: "Bluetooth printer")) } }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) LF.Blue50 else Color.White)
            .border(1.dp, if (selected) LF.Blue200 else LF.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (d.printer) Icons.Default.Print else Icons.Default.Bluetooth, null, Modifier.size(20.dp), tint = if (d.printer) LF.Navy else LF.MutedForeground)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name ?: "Unknown device", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.address + (d.rssi?.let { " · $it dBm" } ?: ""), fontSize = 12.sp, color = LF.MutedForeground)
        }
        Pill(if (d.type == PrinterType.Ble) "BLE" else "Classic", if (d.type == PrinterType.Ble) Tone.violet else Tone.blue, dot = false)
    }
}
