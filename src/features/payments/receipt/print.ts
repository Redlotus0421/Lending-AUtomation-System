// Printing a receipt on 58mm / 80mm thermal paper, two ways:
//  1. System print (every browser, incl. iPhone/iPad): a page sized to the roll width, sent through
//     the OS print dialog — AirPrint, Android's print service, or a printer app such as RawBT.
//  2. Bluetooth (Chrome / Edge on Android, Windows, macOS, ChromeOS): ESC/POS bytes written straight
//     to a BLE thermal printer through Web Bluetooth. Safari on iOS does not offer Web Bluetooth.
import { ascii, receiptLines, type PaperWidth, type ReceiptModel } from './model'
import { receiptHtml } from './html'

const PAPER_KEY = 'lendflow.receipt.paper'
export const savedPaper = (): PaperWidth => (localStorage.getItem(PAPER_KEY) === '80' ? 80 : 58)
export const savePaper = (w: PaperWidth) => localStorage.setItem(PAPER_KEY, String(w))

// ---------------------------------------------------------------------------
// 1. System print
// ---------------------------------------------------------------------------
/** Must run inside the tap handler: the print window is opened synchronously (pop-up rules). */
export function printReceipt(m: ReceiptModel, paper: PaperWidth) {
  const w = window.open('', '_blank')
  if (!w) throw new Error('The print window was blocked. Allow pop-ups for this site and try again.')
  w.document.write(receiptHtml(m, paper, { autoPrint: true }))
  w.document.close()
  w.focus()
}

// ---------------------------------------------------------------------------
// 2. Bluetooth ESC/POS
// ---------------------------------------------------------------------------
type BtCharacteristic = {
  properties: { write: boolean; writeWithoutResponse: boolean }
  writeValue?: (d: BufferSource) => Promise<void>
  writeValueWithResponse?: (d: BufferSource) => Promise<void>
  writeValueWithoutResponse?: (d: BufferSource) => Promise<void>
}
type BtDevice = {
  name?: string
  gatt?: {
    connected: boolean
    connect: () => Promise<{ getPrimaryServices: () => Promise<{ getCharacteristics: () => Promise<BtCharacteristic[]> }[]> }>
  }
}
type BtNavigator = Navigator & { bluetooth?: { requestDevice: (o: unknown) => Promise<BtDevice> } }

/** Service UUIDs used by common BLE thermal printers (generic 58/80mm, Goojprt, Xprinter, MTP, PT-210, etc.). */
const PRINTER_SERVICES = [
  '000018f0-0000-1000-8000-00805f9b34fb',
  '0000ff00-0000-1000-8000-00805f9b34fb',
  '0000ffe0-0000-1000-8000-00805f9b34fb',
  '0000fee7-0000-1000-8000-00805f9b34fb',
  '0000ae30-0000-1000-8000-00805f9b34fb',
  'e7810a71-73ae-499d-8c15-faa9aef0c3f2',
  '49535343-fe7d-4ae5-8fa9-9fafd205e455',
]

export const bluetoothSupported = () => typeof navigator !== 'undefined' && !!(navigator as BtNavigator).bluetooth

let printer: { device: BtDevice; ch: BtCharacteristic } | null = null
export const connectedPrinterName = () => (printer?.device.gatt?.connected ? printer.device.name ?? 'Bluetooth printer' : null)

async function writableCharacteristic(device: BtDevice): Promise<BtCharacteristic> {
  const server = await device.gatt!.connect()
  for (const s of await server.getPrimaryServices()) {
    for (const c of await s.getCharacteristics()) {
      if (c.properties.write || c.properties.writeWithoutResponse) return c
    }
  }
  throw new Error('This device has no printable channel. Choose your thermal printer from the list.')
}

async function getPrinter(forcePick: boolean) {
  if (printer && !forcePick) {
    if (!printer.device.gatt?.connected) printer.ch = await writableCharacteristic(printer.device)
    return printer
  }
  const bt = (navigator as BtNavigator).bluetooth
  if (!bt) throw new Error('Bluetooth printing is not available in this browser. Use Print instead.')
  // the printer is chosen by the user; many printers do not advertise a service, so list every device
  const device = await bt.requestDevice({ acceptAllDevices: true, optionalServices: PRINTER_SERVICES })
  printer = { device, ch: await writableCharacteristic(device) }
  return printer
}

const ESC = 0x1b, GS = 0x1d
const cmd = (...b: number[]) => b

export function escposBytes(m: ReceiptModel, paper: PaperWidth): Uint8Array {
  const enc = new TextEncoder()
  const out: number[] = [...cmd(ESC, 0x40)] // initialise
  for (const l of receiptLines(m, paper)) {
    out.push(...cmd(ESC, 0x61, l.align === 'center' ? 1 : 0))
    out.push(...cmd(ESC, 0x45, l.bold ? 1 : 0))
    out.push(...cmd(GS, 0x21, l.big ? 0x11 : 0x00))
    out.push(...enc.encode(ascii(l.text)), 0x0a)
  }
  out.push(...cmd(ESC, 0x61, 0), ...cmd(GS, 0x21, 0), ...cmd(ESC, 0x45, 0))
  out.push(...cmd(ESC, 0x64, 4)) // feed 4 lines so the tear-off clears the text
  out.push(...cmd(GS, 0x56, 0x42, 0x00)) // partial cut (ignored by printers without a cutter)
  return new Uint8Array(out)
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Prints over Bluetooth; `choose` opens the device picker even when a printer is already connected. */
export async function printReceiptBluetooth(m: ReceiptModel, paper: PaperWidth, choose = false): Promise<string> {
  const p = await getPrinter(choose)
  const data = escposBytes(m, paper)
  // small chunks: many printers have a 20-byte BLE MTU and drop data on larger writes
  const CHUNK = 20
  for (let i = 0; i < data.length; i += CHUNK) {
    const part = data.slice(i, i + CHUNK)
    if (p.ch.properties.writeWithoutResponse && p.ch.writeValueWithoutResponse) {
      await p.ch.writeValueWithoutResponse(part)
      await sleep(8)
    } else if (p.ch.writeValueWithResponse) {
      await p.ch.writeValueWithResponse(part)
    } else {
      await p.ch.writeValue!(part)
    }
  }
  return p.device.name ?? 'Bluetooth printer'
}
