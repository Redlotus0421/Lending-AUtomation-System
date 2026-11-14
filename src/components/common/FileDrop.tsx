import { useRef, useState, type ReactNode } from 'react'
import { FileUp, ImageIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

/** Tap-to-pick / drag-and-drop file input. On phones `capture` opens the camera. */
export function FileDrop({ onFile, accept = 'image/*,application/pdf', label = 'Upload a file', hint, preview, capture, disabled, className }: {
  onFile: (f: File) => void
  accept?: string
  label?: ReactNode
  hint?: ReactNode
  preview?: string | null
  capture?: boolean
  disabled?: boolean
  className?: string
}) {
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  return (
    <div
      onClick={() => !disabled && input.current?.click()}
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault()
        setOver(false)
        const f = e.dataTransfer.files?.[0]
        if (f && !disabled) onFile(f)
      }}
      className={cn(
        'flex cursor-pointer items-center gap-3 rounded-lg border-2 border-dashed border-slate-200 bg-slate-50/60 p-3 transition hover:border-blue-300 hover:bg-blue-50/40',
        over && 'border-blue-400 bg-blue-50', disabled && 'cursor-not-allowed opacity-60', className,
      )}
    >
      {preview ? (
        <img src={preview} alt="" className="size-14 shrink-0 rounded-md object-cover ring-1 ring-black/5" />
      ) : (
        <div className="grid size-14 shrink-0 place-items-center rounded-md bg-white text-slate-400 ring-1 ring-slate-200">
          {accept.startsWith('image') ? <ImageIcon className="size-5" /> : <FileUp className="size-5" />}
        </div>
      )}
      <div className="min-w-0 text-left">
        <p className="text-sm font-medium">{label}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </div>
      <input
        ref={input}
        type="file"
        accept={accept}
        className="hidden"
        {...(capture ? { capture: 'environment' as const } : {})}
        onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) onFile(f)
          e.target.value = ''
        }}
      />
    </div>
  )
}
