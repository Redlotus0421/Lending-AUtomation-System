package com.lendflow.app.feature.payments.receipt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.ContextCompat
import com.lendflow.app.util.PdfBytesPrintAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

// Native replacement for print.ts: receipts go to a thermal printer over Bluetooth Classic (SPP / RFCOMM,
// most cheap 58mm printers) or Bluetooth Low Energy (GATT, same service UUIDs as the web), or through
// Android's print framework as a PDF sized to the roll width.

class PrintError(message: String) : Exception(message)

enum class PrinterType { Classic, Ble }

data class SavedPrinter(val address: String, val type: PrinterType, val name: String)

/** Paper width and the last printer, kept on the device (localStorage on the web). */
object ReceiptPrefs {
    private const val FILE = "lendflow.receipt"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun paper(ctx: Context): Int = if (prefs(ctx).getString("paper", "58") == "80") 80 else 58
    fun savePaper(ctx: Context, w: Int) = prefs(ctx).edit().putString("paper", w.toString()).apply()

    fun printer(ctx: Context): SavedPrinter? {
        val p = prefs(ctx)
        val address = p.getString("printer_address", null) ?: return null
        val type = if (p.getString("printer_type", "classic") == "ble") PrinterType.Ble else PrinterType.Classic
        return SavedPrinter(address, type, p.getString("printer_name", null) ?: "Bluetooth printer")
    }

    fun savePrinter(ctx: Context, s: SavedPrinter) = prefs(ctx).edit()
        .putString("printer_address", s.address)
        .putString("printer_type", if (s.type == PrinterType.Ble) "ble" else "classic")
        .putString("printer_name", s.name)
        .apply()

    fun forgetPrinter(ctx: Context) = prefs(ctx).edit().remove("printer_address").remove("printer_type").remove("printer_name").apply()
}

/** Service UUIDs used by common BLE thermal printers (generic 58/80mm, Goojprt, Xprinter, MTP, PT-210, etc.). */
val PRINTER_SERVICES: List<UUID> = listOf(
    "000018f0-0000-1000-8000-00805f9b34fb",
    "0000ff00-0000-1000-8000-00805f9b34fb",
    "0000ffe0-0000-1000-8000-00805f9b34fb",
    "0000fee7-0000-1000-8000-00805f9b34fb",
    "0000ae30-0000-1000-8000-00805f9b34fb",
    "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
    "49535343-fe7d-4ae5-8fa9-9fafd205e455",
).map(UUID::fromString)

/** Serial Port Profile — Bluetooth Classic printers. */
val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

private val PRINTER_NAME = Regex("print|pos|thermal|receipt|pt-?\\d|mtp|xp-|xprinter|gooj|rpp|mpt|zjiang|zj-|bluetooth printer|inner|peripage|phomemo|hprt|sunmi|bixolon|epson|star", RegexOption.IGNORE_CASE)

/** Looks like a receipt printer by its name or advertised services. */
fun looksLikePrinter(name: String?, services: List<UUID> = emptyList()): Boolean =
    services.any { it in PRINTER_SERVICES } || (name != null && PRINTER_NAME.containsMatchIn(name))

object Bt {
    fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Runtime permissions needed to connect (and to scan for BLE printers when [scan]). */
    fun permissions(scan: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (scan) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        scan -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    fun missing(ctx: Context, scan: Boolean): List<String> =
        permissions(scan).filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    fun isEnabled(ctx: Context): Boolean = try { adapter(ctx)?.isEnabled == true } catch (_: SecurityException) { false }
}

/** Sends ESC/POS bytes to a Bluetooth printer. All I/O runs on Dispatchers.IO. */
object ThermalPrinter {
    private val lock = Mutex()
    private var ble: BleSession? = null

    /** Prints [data] and returns the printer's name. */
    suspend fun print(ctx: Context, target: SavedPrinter, data: ByteArray): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val adapter = Bt.adapter(ctx) ?: throw PrintError("This device has no Bluetooth.")
            if (!Bt.isEnabled(ctx)) throw PrintError("Bluetooth is turned off. Turn it on and try again.")
            if (Bt.missing(ctx, false).isNotEmpty()) throw PrintError("Allow LendFlow to use Nearby devices (Bluetooth) to print.")
            try {
                when (target.type) {
                    PrinterType.Classic -> printClassic(adapter, target, data)
                    PrinterType.Ble -> printBle(ctx, adapter, target, data)
                }
            } catch (e: SecurityException) {
                throw PrintError("Bluetooth permission was denied. Allow Nearby devices for LendFlow in Settings.")
            }
            target.name
        }
    }

    /** Drops the cached BLE connection (e.g. when switching printers). */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        ble?.let { s -> try { s.gatt.disconnect(); s.gatt.close() } catch (_: Exception) {} }
        ble = null
    }

    // -----------------------------------------------------------------------
    // Bluetooth Classic (SPP over RFCOMM)
    // -----------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun printClassic(adapter: BluetoothAdapter, target: SavedPrinter, data: ByteArray) {
        val device = try { adapter.getRemoteDevice(target.address) } catch (_: IllegalArgumentException) {
            throw PrintError("The saved printer address is not valid. Choose your printer again.")
        }
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val socket = openSocket(device, target.name)
        try {
            val out = socket.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(256, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
                Thread.sleep(15)
            }
            // give the printer time to take the buffer before the link closes
            Thread.sleep(600L + data.size / 8)
        } catch (e: IOException) {
            throw PrintError("The connection to ${target.name} was lost while printing. Try again.")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, name: String): BluetoothSocket {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // some printers only answer on channel 1 without an SDP record
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                try { s?.close() } catch (_: IOException) {}
            }
        }
        throw PrintError("Could not connect to $name. Make sure the printer is on, paired, nearby and not connected to another phone.")
    }

    // -----------------------------------------------------------------------
    // Bluetooth Low Energy (GATT)
    // -----------------------------------------------------------------------
    private class GattEvents : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val discovered = CompletableDeferred<Boolean>()
        val writes = Channel<Int>(Channel.UNLIMITED)
        @Volatile var isConnected = false

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                connected.complete(false)
                discovered.complete(false)
                writes.trySend(-1)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writes.trySend(status)
        }
    }

    private class BleSession(val address: String, val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic, val noResponse: Boolean, val events: GattEvents)

    private fun BluetoothGattCharacteristic.writable(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    @SuppressLint("MissingPermission")
    private suspend fun connectBle(ctx: Context, adapter: BluetoothAdapter, target: SavedPrinter): BleSession {
        ble?.let { s -> if (s.address == target.address && s.events.isConnected) return s }
        disconnect()
        val device = try { adapter.getRemoteDevice(target.address) } catch (_: IllegalArgumentException) {
            throw PrintError("The saved printer address is not valid. Choose your printer again.")
        }
        val events = GattEvents()
        val gatt = device.connectGatt(ctx, false, events, BluetoothDevice.TRANSPORT_LE)
            ?: throw PrintError("Could not connect to ${target.name}.")
        try {
            val ok = withTimeoutOrNull(15_000) { events.connected.await() } ?: false
            if (!ok) throw PrintError("Could not connect to ${target.name}. Make sure the printer is on and nearby.")
            gatt.discoverServices()
            val found = withTimeoutOrNull(15_000) { events.discovered.await() } ?: false
            if (!found) throw PrintError("${target.name} did not respond. Turn the printer off and on, then try again.")
            val services = gatt.services.orEmpty()
            // the printer services first (like the web's optionalServices), then any other writable channel
            val preferred = services.filter { it.uuid in PRINTER_SERVICES }
            val others = services.filter { it.uuid !in PRINTER_SERVICES && !it.uuid.toString().matches(Regex("0000180[01a]-.*")) }
            val ch = (preferred + others).asSequence().flatMap { it.characteristics.orEmpty().asSequence() }.firstOrNull { it.writable() }
                ?: throw PrintError("This device has no printable channel. Choose your thermal printer from the list.")
            val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            return BleSession(target.address, gatt, ch, noResponse, events).also { ble = it }
        } catch (e: Exception) {
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeChunk(s: BleSession, part: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.gatt.writeCharacteristic(s.ch, part, type) == BluetoothStatusCodes.SUCCESS
        } else {
            s.ch.writeType = type
            s.ch.value = part
            s.gatt.writeCharacteristic(s.ch)
        }

    private suspend fun printBle(ctx: Context, adapter: BluetoothAdapter, target: SavedPrinter, data: ByteArray) {
        val s = connectBle(ctx, adapter, target)
        try {
            while (s.events.writes.tryReceive().isSuccess) { /* drain stale callbacks */ }
            val type = if (s.noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // small chunks: many printers have a 20-byte BLE MTU and drop data on larger writes
            val chunk = 20
            var i = 0
            while (i < data.size) {
                val part = data.copyOfRange(i, minOf(i + chunk, data.size))
                var tries = 0
                while (!writeChunk(s, part, type)) {
                    if (!s.events.isConnected) throw PrintError("${target.name} disconnected while printing. Try again.")
                    if (++tries > 200) throw PrintError("${target.name} is not accepting data. Try again.")
                    delay(10)
                }
                if (s.noResponse) {
                    withTimeoutOrNull(1_000) { s.events.writes.receive() }
                    delay(8)
                } else {
                    val status = withTimeoutOrNull(5_000) { s.events.writes.receive() }
                        ?: throw PrintError("${target.name} stopped responding while printing.")
                    if (status != BluetoothGatt.GATT_SUCCESS) throw PrintError("${target.name} rejected the data (error $status).")
                }
                i += chunk
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }
}

// ---------------------------------------------------------------------------
// System print: a PDF page exactly the roll width, through Android's print framework
// (any installed print service, a thermal-printer app such as RawBT, or Save as PDF).
// ---------------------------------------------------------------------------
private const val PT_PER_MM = 72f / 25.4f

/** One page as wide as the roll, as tall as the receipt, in a monospace face like the thermal printout. */
fun receiptPdfBytes(m: ReceiptModel, paper: Int): Pair<ByteArray, Float> {
    val lines = receiptLines(m, paper)
    val cols = columnsFor(paper)
    val pageW = paper * PT_PER_MM
    // printable width: ~48mm on 58mm rolls, ~72mm on 80mm rolls
    val bodyW = (if (paper == 58) 48f else 72f) * PT_PER_MM
    val left = (pageW - bodyW) / 2f
    val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Color.BLACK; textSize = 10f }
    val advance = regular.measureText("0") / 10f // width of one character per point of text size
    val size = bodyW / (cols * advance)
    regular.textSize = size
    val bold = Paint(regular).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isFakeBoldText = true }
    val lineH = size * 1.35f
    val top = 3f * PT_PER_MM
    val height = top * 2 + lines.sumOf { (if (it.big) lineH * 2 else lineH).toDouble() }.toFloat() + lineH * 2

    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW.toInt().coerceAtLeast(1), height.toInt() + 1, 1).create())
    val canvas = page.canvas
    var y = top
    for (l in lines) {
        val h = if (l.big) lineH * 2 else lineH
        y += h
        val p = Paint(if (l.bold) bold else regular).apply { if (l.big) textSize = size * 2 }
        val w = p.measureText(l.text)
        val x = if (l.center) left + (bodyW - w) / 2f else left
        canvas.drawText(l.text, x, y - h * 0.25f, p)
    }
    doc.finishPage(page)
    val out = ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return out.toByteArray() to height
}

/** Opens Android's print dialog with the receipt on a roll-sized page. */
fun printReceiptSystem(ctx: Context, m: ReceiptModel, paper: Int) {
    val (bytes, heightPt) = receiptPdfBytes(m, paper)
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: throw PrintError("Printing is not available on this device.")
    val widthMils = (paper / 25.4 * 1000).toInt()
    val media = PrintAttributes.MediaSize("lendflow_roll_$paper", "$paper mm receipt roll", widthMils, (heightPt / 72f * 1000f).toInt().coerceAtLeast(widthMils))
    val attrs = PrintAttributes.Builder()
        .setMediaSize(media)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
        .build()
    val name = "Receipt ${m.receiptNo}"
    pm.print(name, PdfBytesPrintAdapter(bytes, name), attrs)
}
