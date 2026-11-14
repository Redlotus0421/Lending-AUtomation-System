import { useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Spinner } from './index'
import { errorMessage } from '@/lib/errors'

/** Confirmation dialog with optional required reason (for audited actions). */
export function ConfirmDialog({
  open, onOpenChange, title, description, confirmLabel = 'Confirm', destructive, requireReason, reasonLabel = 'Reason',
  onConfirm, children,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  title: string
  description?: ReactNode
  confirmLabel?: string
  destructive?: boolean
  requireReason?: boolean
  reasonLabel?: string
  onConfirm: (reason: string) => Promise<unknown> | unknown
  children?: ReactNode
}) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (requireReason && !reason.trim()) return
    setBusy(true)
    try {
      await onConfirm(reason.trim())
      setReason('')
      onOpenChange(false)
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription asChild><div>{description}</div></DialogDescription>}
        </DialogHeader>
        {children}
        {requireReason && (
          <div className="space-y-1.5">
            <label className="text-sm font-medium">{reasonLabel} <span className="text-red-500">*</span></label>
            <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} placeholder="This will be recorded in the audit trail" />
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button variant={destructive ? 'destructive' : 'default'} onClick={submit} disabled={busy || (requireReason && !reason.trim())}>
            {busy && <Spinner />} {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
