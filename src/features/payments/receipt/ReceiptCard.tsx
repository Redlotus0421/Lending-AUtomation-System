// Receipt preview with Print receipt / Close. Used right after a payment is posted and for reprints
// from Records. It only reads the stored receipt, so printing any number of times never creates a
// payment or a receipt.
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Bluetooth, ChevronDown, Printer, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ErrorState, Spinner } from '@/components/common'
import { paymentsService } from '@/services/payments'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import { buildReceiptModel, type PaperWidth } from './model'
import { receiptBodyHtml, RECEIPT_CSS } from './html'
import { bluetoothSupported, connectedPrinterName, printReceipt, printReceiptBluetooth, savedPaper, savePaper } from './print'

export function ReceiptCard({ paymentId, reprint = false, onClose, className }: {
  paymentId: string
  reprint?: boolean
  onClose: () => void
  className?: string
}) {
  const q = useQuery({ queryKey: ['payments', 'receipt', paymentId], queryFn: () => paymentsService.receipt(paymentId) })
  const [paper, setPaper] = useState<PaperWidth>(savedPaper)
  const [btBusy, setBtBusy] = useState(false)
  const model = useMemo(() => q.data ? buildReceiptModel(q.data.data, { isVoid: q.data.is_void, reprint }) : null, [q.data, reprint])
  const bt = bluetoothSupported()

  const choosePaper = (w: PaperWidth) => { setPaper(w); savePaper(w) }
  const systemPrint = () => {
    if (!model) return
    try { printReceipt(model, paper) } catch (e) { toast.error(errorMessage(e)) }
  }
  const bluetoothPrint = async (choose: boolean) => {
    if (!model) return
    setBtBusy(true)
    try {
      const name = await printReceiptBluetooth(model, paper, choose)
      toast.success(`Sent to ${name}`)
    } catch (e) {
      // closing the device picker is not an error
      if ((e as { name?: string })?.name !== 'NotFoundError') toast.error(errorMessage(e, 'The receipt could not be printed'))
    } finally {
      setBtBusy(false)
    }
  }
  const connected = connectedPrinterName()

  return (
    <div className={cn('space-y-4', className)}>
      <style>{RECEIPT_CSS}</style>
      <div className="max-h-[52dvh] overflow-y-auto rounded-xl bg-slate-100 p-3 sm:p-4">
        {q.isLoading ? <div className="grid place-items-center py-16"><Spinner /></div>
          : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          : !model ? <p className="py-10 text-center text-sm text-muted-foreground">No receipt was issued for this payment.</p>
          : (
            <div className={cn('mx-auto bg-white px-3 py-4 text-[11.5px] shadow-sm ring-1 ring-black/5 transition-[width]', paper === 58 ? 'w-[230px]' : 'w-[320px]')}
              // receiptBodyHtml escapes every value it inserts
              dangerouslySetInnerHTML={{ __html: receiptBodyHtml(model) }} />
          )}
      </div>

      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-medium text-muted-foreground">Paper width</span>
        <div role="radiogroup" aria-label="Paper width" className="inline-flex rounded-lg border bg-white p-0.5">
          {([58, 80] as const).map((w) => (
            <button key={w} type="button" role="radio" aria-checked={paper === w} onClick={() => choosePaper(w)}
              className={cn('h-9 rounded-md px-4 text-sm font-medium transition', paper === w ? 'bg-[#142B4A] text-white' : 'text-slate-600 hover:bg-slate-100')}>
              {w} mm
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" className="h-12 text-base" onClick={onClose}>Close</Button>
        {bt ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button className="h-12 text-base" disabled={!model || btBusy}>
                {btBusy ? <Spinner /> : <Printer />} Print receipt <ChevronDown className="opacity-70" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-64">
              <DropdownMenuLabel className="text-xs text-muted-foreground">Print to</DropdownMenuLabel>
              <DropdownMenuItem onClick={() => void bluetoothPrint(false)}>
                <Bluetooth /> {connected ? connected : 'Bluetooth thermal printer'}
              </DropdownMenuItem>
              {connected && <DropdownMenuItem onClick={() => void bluetoothPrint(true)}><RefreshCw /> Choose another printer</DropdownMenuItem>}
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={systemPrint}><Printer /> System print dialog</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ) : (
          <Button className="h-12 text-base" disabled={!model} onClick={systemPrint}><Printer /> Print receipt</Button>
        )}
      </div>
      {!bt && (
        <p className="text-center text-[11px] text-muted-foreground">
          Prints through your device's print dialog (AirPrint or a thermal-printer app). Direct Bluetooth printing works in Chrome on Android, Windows and Mac.
        </p>
      )}
    </div>
  )
}
