import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react'
import SignaturePadLib from 'signature_pad'
import { Eraser, Undo2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type SignaturePadHandle = {
  isEmpty: () => boolean
  clear: () => void
  /** Transparent, trimmed, high-resolution PNG data URL */
  toPng: () => string | null
}

/** Large touch-friendly signature canvas (finger, mouse or stylus). */
export const SignaturePad = forwardRef<SignaturePadHandle, { className?: string; onChange?: (empty: boolean) => void; height?: number }>(
  function SignaturePad({ className, onChange, height = 220 }, ref) {
    const canvasRef = useRef<HTMLCanvasElement>(null)
    const padRef = useRef<SignaturePadLib | null>(null)
    const [empty, setEmpty] = useState(true)

    const notify = useCallback(() => {
      const e = padRef.current?.isEmpty() ?? true
      setEmpty(e)
      onChange?.(e)
    }, [onChange])

    const resize = useCallback(() => {
      const canvas = canvasRef.current
      const pad = padRef.current
      if (!canvas || !pad) return
      const data = pad.toData()
      const ratio = Math.max(window.devicePixelRatio || 1, 2)
      canvas.width = canvas.offsetWidth * ratio
      canvas.height = canvas.offsetHeight * ratio
      canvas.getContext('2d')!.scale(ratio, ratio)
      pad.clear()
      if (data.length) pad.fromData(data)
    }, [])

    useEffect(() => {
      const canvas = canvasRef.current!
      const pad = new SignaturePadLib(canvas, {
        penColor: '#0b1f44',
        minWidth: 1.1,
        maxWidth: 3.2,
        velocityFilterWeight: 0.6,
        backgroundColor: 'rgba(0,0,0,0)',
      })
      padRef.current = pad
      pad.addEventListener('endStroke', notify)
      resize()
      window.addEventListener('resize', resize)
      return () => {
        window.removeEventListener('resize', resize)
        pad.removeEventListener('endStroke', notify)
        pad.off()
      }
    }, [notify, resize])

    const undo = () => {
      const pad = padRef.current
      if (!pad) return
      const data = pad.toData()
      data.pop()
      pad.fromData(data)
      notify()
    }
    const clear = () => {
      padRef.current?.clear()
      notify()
    }

    useImperativeHandle(ref, () => ({
      isEmpty: () => padRef.current?.isEmpty() ?? true,
      clear,
      toPng: () => {
        const canvas = canvasRef.current
        const pad = padRef.current
        if (!canvas || !pad || pad.isEmpty()) return null
        // trim transparent borders so the signature fills the PDF field
        const ctx = canvas.getContext('2d')!
        const { width, height: h } = canvas
        const img = ctx.getImageData(0, 0, width, h).data
        let minX = width, minY = h, maxX = 0, maxY = 0
        for (let y = 0; y < h; y++) {
          for (let x = 0; x < width; x++) {
            if (img[(y * width + x) * 4 + 3] > 8) {
              if (x < minX) minX = x
              if (y < minY) minY = y
              if (x > maxX) maxX = x
              if (y > maxY) maxY = y
            }
          }
        }
        const pad2 = 12
        minX = Math.max(0, minX - pad2); minY = Math.max(0, minY - pad2)
        maxX = Math.min(width - 1, maxX + pad2); maxY = Math.min(h - 1, maxY + pad2)
        const out = document.createElement('canvas')
        out.width = maxX - minX + 1
        out.height = maxY - minY + 1
        out.getContext('2d')!.drawImage(canvas, minX, minY, out.width, out.height, 0, 0, out.width, out.height)
        return out.toDataURL('image/png')
      },
    }))

    return (
      <div className={cn('space-y-2', className)}>
        <div className="relative rounded-xl border-2 border-dashed border-slate-300 bg-white">
          <canvas ref={canvasRef} className="block w-full touch-none rounded-xl" style={{ height }} aria-label="Signature area" />
          <div className="pointer-events-none absolute inset-x-6 bottom-12 border-b border-slate-300" />
          <span className="pointer-events-none absolute bottom-4 left-6 text-xs text-slate-400">✕ Sign above the line</span>
          {empty && (
            <span className="pointer-events-none absolute inset-0 grid place-items-center text-sm text-slate-400">Draw your signature here</span>
          )}
        </div>
        <div className="flex gap-2">
          <Button type="button" variant="outline" className="h-11 flex-1" onClick={undo} disabled={empty}><Undo2 /> Undo</Button>
          <Button type="button" variant="outline" className="h-11 flex-1" onClick={clear} disabled={empty}><Eraser /> Clear</Button>
        </div>
      </div>
    )
  },
)
