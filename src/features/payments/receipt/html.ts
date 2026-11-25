// Stand-alone print page for a thermal roll: the page is exactly the paper width with no margins,
// in a monospace face so it looks like (and wraps like) a thermal printout.
import { fmtDate, peso } from '@/lib/format'
import type { PaperWidth, ReceiptModel } from './model'

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

export function receiptBodyHtml(m: ReceiptModel): string {
  const row = (l: string, v: string, cls = '') => `<div class="row ${cls}"><span>${esc(l)}</span><span>${esc(v)}</span></div>`
  const c = m.company
  return `
  <div class="r">
    ${c.name ? `<div class="c b">${esc(c.name)}</div>` : ''}
    ${c.address ? `<div class="c s">${esc(c.address)}</div>` : ''}
    ${c.phone ? `<div class="c s">${esc(c.phone)}</div>` : ''}
    ${c.tin ? `<div class="c s">TIN ${esc(c.tin)}</div>` : ''}
    <hr>
    <div class="c b">PAYMENT RECEIPT</div>
    ${m.isVoid ? '<div class="c b big">*** VOID ***</div>' : ''}
    ${m.reprintedAt ? `<div class="c s">REPRINT ${esc(m.reprintedAt)}</div>` : ''}
    <hr>
    ${row('Receipt No.', m.receiptNo, 'b')}
    ${row('Payment No.', m.paymentNo)}
    ${row('Date', `${fmtDate(m.date)}${m.time ? ` ${m.time}` : ''}`)}
    ${row('Borrower', m.borrower, 'b')}
    ${m.borrowerCode ? row('Borrower code', m.borrowerCode) : ''}
    ${row('Loan', m.loanNo)}
    ${m.dueDates.map((d) => row(`Due date #${d.no}`, fmtDate(d.date))).join('')}
    ${row('Method', m.method)}
    ${m.reference ? row('Reference', m.reference) : ''}
    <hr>
    ${m.atm
      ? `${row('ATM Withdrawal', peso(m.atm.withdrawal))}${row('Loan Payment', peso(m.atm.payment))}<hr>${row('Cash Returned', peso(m.atm.cashReturned), 'b big2')}`
      : row('Amount Paid', peso(m.amount), 'b big2')}
    <hr>
    ${row('Remaining Balance', peso(m.remaining), 'b')}
    ${row('Status', m.status)}
    ${m.receivedBy ? row('Received by', m.receivedBy) : ''}
    ${m.footer ? `<div class="c s note">${esc(m.footer)}</div>` : ''}
    <div class="c s note">Thank you!</div>
  </div>`
}

/** Shared styles for the on-screen preview and the print page. */
export const RECEIPT_CSS = `
  .r{font-family:ui-monospace,"SF Mono",Menlo,Consolas,"Courier New",monospace;color:#000;line-height:1.35}
  .r .c{text-align:center}.r .b{font-weight:700}.r .s{font-size:.9em}
  .r .big{font-size:1.4em}.r .big2{font-size:1.15em}
  .r hr{border:0;border-top:1px dashed #000;margin:.45em 0}
  .r .row{display:flex;justify-content:space-between;gap:.6em}
  .r .row span:first-child{flex-shrink:0}.r .row span:last-child{text-align:right;word-break:break-word}
  .r .note{margin-top:.6em}
`

export function receiptHtml(m: ReceiptModel, paper: PaperWidth, opts: { autoPrint?: boolean } = {}): string {
  // printable width: ~48mm on 58mm rolls, ~72mm on 80mm rolls
  const body = paper === 58 ? '48mm' : '72mm'
  const font = paper === 58 ? '10.5px' : '12.5px'
  return `<!doctype html><html><head><meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Receipt ${esc(m.receiptNo)}</title>
  <style>
    @page{size:${paper}mm auto;margin:0}
    html,body{margin:0;padding:0;background:#fff}
    body{width:${body};padding:3mm ${paper === 58 ? '5mm' : '4mm'};font-size:${font};-webkit-print-color-adjust:exact;print-color-adjust:exact}
    ${RECEIPT_CSS}
    .bar{font:14px -apple-system,system-ui,sans-serif;display:flex;gap:8px;margin:0 0 12px}
    .bar button{font:inherit;padding:10px 14px;border-radius:8px;border:1px solid #ccc;background:#f6f6f6}
    @media print{.bar{display:none}}
  </style></head><body>
  <div class="bar"><button onclick="window.print()">Print</button><button onclick="window.close()">Close</button></div>
  ${receiptBodyHtml(m)}
  ${opts.autoPrint ? '<script>window.addEventListener("load",function(){setTimeout(function(){window.print()},250)})</script>' : ''}
  </body></html>`
}
