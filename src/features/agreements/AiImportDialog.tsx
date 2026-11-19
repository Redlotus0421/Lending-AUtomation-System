import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FileText, Sparkles, Upload, WifiOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Spinner } from '@/components/common'
import { errorMessage } from '@/lib/errors'
import { convertTermsText, type TermsDraft } from './converter/convertTerms'
import { extractText, fileKind } from './converter/extractText'

export type AiDraft = TermsDraft

const ACCEPT = '.pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain'
const MAX = 15 * 1024 * 1024

/**
 * Upload the lender's existing terms; the built-in assistant converts them into a template draft
 * on this device. Nothing is sent to any server or AI service, and nothing is saved until the
 * user saves the template.
 */
export function AiImportDialog({ open, onOpenChange, onDraft }: {
  open: boolean
  onOpenChange: (o: boolean) => void
  onDraft: (d: AiDraft, fileName: string) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const run = useMutation({
    mutationFn: async (f: File) => {
      const { text, kind } = await extractText(f)
      // the screen updates before the (synchronous) conversion runs
      await new Promise((r) => setTimeout(r, 30))
      return convertTermsText(text, f.name, kind !== 'docx')
    },
    onSuccess: (d, f) => { onDraft(d, f.name); setFile(null); onOpenChange(false) },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const pick = (f: File | undefined) => {
    if (!f) return
    if (f.size > MAX) return toast.error('The file is larger than 15 MB')
    if (/\.doc$/i.test(f.name)) return toast.error('Old .doc files are not supported. Open it in Word and save it as .docx or PDF first.')
    if (!fileKind(f)) return toast.error('Use a PDF, Word (.docx) or text file. Photos and scans cannot be read.')
    setFile(f)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !run.isPending && onOpenChange(o)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Sparkles className="size-5 text-violet-600" /> Turn your terms into a template</DialogTitle>
          <DialogDescription>Upload the loan agreement or terms you use today. The built-in assistant keeps your wording and puts in the borrower, co-maker, amount, rate and dates as fields that fill in for every loan. You review it before saving.</DialogDescription>
        </DialogHeader>

        {run.isPending ? (
          <div className="flex flex-col items-center gap-3 py-8 text-center">
            <Spinner />
            <p className="text-sm font-medium">Converting {file?.name}…</p>
          </div>
        ) : (
          <label className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed bg-slate-50 px-4 py-8 text-center hover:border-violet-300">
            {file ? <FileText className="size-8 text-violet-600" /> : <Upload className="size-8 text-slate-400" />}
            <span className="text-sm font-medium">{file ? file.name : 'Tap to choose a file'}</span>
            <span className="text-xs text-muted-foreground">{file ? `${(file.size / 1024).toFixed(0)} KB · tap to change` : 'PDF, Word (.docx) or text file'}</span>
            <input type="file" accept={ACCEPT} className="hidden" onChange={(e) => { pick(e.target.files?.[0]); e.target.value = '' }} />
          </label>
        )}
        <p className="flex items-start gap-1.5 text-[11px] text-muted-foreground">
          <WifiOff className="mt-0.5 size-3.5 shrink-0" /> Runs on this device. Your file is not uploaded or sent to any AI service.
        </p>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={run.isPending}>Cancel</Button>
          <Button onClick={() => file && run.mutate(file)} disabled={!file || run.isPending}>
            {run.isPending ? <Spinner /> : <Sparkles />} Create template
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
