import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { FileText, Sparkles, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Spinner } from '@/components/common'
import { templatesService } from '@/services/agreements'
import { errorMessage } from '@/lib/errors'

export type AiDraft = { title: string; suggested_name: string; body: string; notes: string[] }

const ACCEPT = '.pdf,.docx,.txt,.md,image/png,image/jpeg,image/webp,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain'
const MAX = 8 * 1024 * 1024

function toBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the file'))
    reader.readAsDataURL(file)
  })
}

/** Upload the lender's existing terms; the AI assistant (server) returns a template draft for the editor. */
export function AiImportDialog({ open, onOpenChange, onDraft }: {
  open: boolean
  onOpenChange: (o: boolean) => void
  onDraft: (d: AiDraft, fileName: string) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const run = useMutation({
    mutationFn: async (f: File) => templatesService.aiDraft({ file_name: f.name, mime_type: f.type, data_base64: await toBase64(f) }),
    onSuccess: (d, f) => { onDraft(d, f.name); setFile(null); onOpenChange(false) },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const pick = (f: File | undefined) => {
    if (!f) return
    if (f.size > MAX) return toast.error('The file is larger than 8 MB')
    if (/\.doc$/i.test(f.name)) return toast.error('Old .doc files are not supported. Save it as .docx or PDF first.')
    setFile(f)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !run.isPending && onOpenChange(o)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Sparkles className="size-5 text-violet-600" /> Turn your terms into a template</DialogTitle>
          <DialogDescription>Upload the loan agreement or terms you use today. The AI assistant keeps your wording and puts the borrower, amount, dates and rates in as fields. You review it before saving.</DialogDescription>
        </DialogHeader>

        {run.isPending ? (
          <div className="flex flex-col items-center gap-3 py-8 text-center">
            <Spinner />
            <p className="text-sm font-medium">Reading {file?.name}…</p>
            <p className="text-xs text-muted-foreground">This usually takes under a minute. Keep this window open.</p>
          </div>
        ) : (
          <label className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed bg-slate-50 px-4 py-8 text-center hover:border-violet-300">
            {file ? <FileText className="size-8 text-violet-600" /> : <Upload className="size-8 text-slate-400" />}
            <span className="text-sm font-medium">{file ? file.name : 'Tap to choose a file'}</span>
            <span className="text-xs text-muted-foreground">{file ? `${(file.size / 1024).toFixed(0)} KB · tap to change` : 'PDF, Word (.docx), photo or text · up to 8 MB'}</span>
            <input type="file" accept={ACCEPT} className="hidden" onChange={(e) => { pick(e.target.files?.[0]); e.target.value = '' }} />
          </label>
        )}

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
