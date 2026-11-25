import { useCallback, useEffect, useRef, useState } from 'react'
// The legacy build: the default (modern) build of pdf.js 6 relies on very recent JavaScript
// (Map.prototype.getOrInsertComputed, Math.sumPrecise, ...) that Safari on iOS/iPadOS 18 lacks,
// so pages never rendered there. The legacy build ships the fallbacks.
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import workerSrc from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import { Download, Maximize2, Minimize2, Minus, Plus, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { Spinner } from './index'

pdfjs.GlobalWorkerOptions.workerSrc = workerSrc

/**
 * Canvas-based PDF viewer (pdf.js). Works on mobile browsers, which cannot
 * display PDFs inline in an iframe. Supports zoom, page scrolling and full screen.
 */
export function PdfViewer({ url, className, fileName, height = '70vh', allowFullscreen = true }: {
  url: string | null | undefined
  className?: string
  fileName?: string
  height?: string
  allowFullscreen?: boolean
}) {
  const container = useRef<HTMLDivElement>(null)
  const pagesRef = useRef<HTMLDivElement>(null)
  const [doc, setDoc] = useState<pdfjs.PDFDocumentProxy | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)
  const [fitWidth, setFitWidth] = useState(0)
  const [full, setFull] = useState(false)
  const [rendering, setRendering] = useState(false)

  useEffect(() => {
    if (!url) return
    let cancelled = false
    setDoc(null)
    setError(null)
    const task = pdfjs.getDocument({ url })
    task.promise.then((d) => !cancelled && setDoc(d)).catch((e) => !cancelled && setError(e?.message ?? 'Could not load document'))
    return () => {
      cancelled = true
      void task.destroy()
    }
  }, [url])

  // track available width for "fit to width"
  useEffect(() => {
    const el = container.current
    if (!el) return
    const ro = new ResizeObserver(() => setFitWidth(el.clientWidth - 24))
    ro.observe(el)
    return () => ro.disconnect()
  }, [full])

  const render = useCallback(async () => {
    const host = pagesRef.current
    if (!doc || !host || fitWidth <= 0) return
    setRendering(true)
    host.innerHTML = ''
    const dpr = Math.min(window.devicePixelRatio || 1, 2.5)
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i)
      const base = page.getViewport({ scale: 1 })
      const scale = (fitWidth / base.width) * zoom
      const vp = page.getViewport({ scale: scale * dpr })
      const canvas = document.createElement('canvas')
      canvas.width = Math.floor(vp.width)
      canvas.height = Math.floor(vp.height)
      canvas.style.width = `${Math.floor(vp.width / dpr)}px`
      canvas.style.height = `${Math.floor(vp.height / dpr)}px`
      canvas.className = 'mx-auto mb-3 block bg-white shadow-md ring-1 ring-black/5'
      host.appendChild(canvas)
      await page.render({ canvas, canvasContext: canvas.getContext('2d')!, viewport: vp }).promise
    }
    setRendering(false)
  }, [doc, fitWidth, zoom])

  useEffect(() => {
    void render()
  }, [render])

  return (
    <div className={cn('flex flex-col overflow-hidden rounded-xl border bg-slate-100', full && 'fixed inset-0 z-50 rounded-none border-0', className)}>
      <div className="flex items-center justify-between gap-2 border-b bg-white px-2 py-1.5">
        <div className="flex items-center gap-1">
          <Button size="icon" variant="ghost" className="size-9" onClick={() => setZoom((z) => Math.max(0.5, +(z - 0.25).toFixed(2)))} aria-label="Zoom out"><Minus /></Button>
          <span className="w-12 text-center text-xs tabular text-muted-foreground">{Math.round(zoom * 100)}%</span>
          <Button size="icon" variant="ghost" className="size-9" onClick={() => setZoom((z) => Math.min(3, +(z + 0.25).toFixed(2)))} aria-label="Zoom in"><Plus /></Button>
          <Button size="icon" variant="ghost" className="size-9" onClick={() => setZoom(1)} aria-label="Reset zoom"><RotateCcw /></Button>
        </div>
        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          {rendering && <Spinner className="size-3.5" />}
          {doc && <span>{doc.numPages} page{doc.numPages === 1 ? '' : 's'}</span>}
          {url && (
            <Button size="icon" variant="ghost" className="size-9" asChild aria-label="Download PDF">
              <a href={url} download={fileName} target="_blank" rel="noreferrer"><Download /></a>
            </Button>
          )}
          {allowFullscreen && (
            <Button size="icon" variant="ghost" className="size-9" onClick={() => setFull((f) => !f)} aria-label="Toggle full screen">
              {full ? <Minimize2 /> : <Maximize2 />}
            </Button>
          )}
        </div>
      </div>
      <div ref={container} className="flex-1 overflow-auto p-3" style={{ height: full ? undefined : height }}>
        {!url && <p className="py-20 text-center text-sm text-muted-foreground">No document</p>}
        {error && <p className="py-20 text-center text-sm text-red-600">{error}</p>}
        {url && !doc && !error && (
          <div className="flex items-center justify-center gap-2 py-20 text-sm text-muted-foreground"><Spinner /> Loading document…</div>
        )}
        <div ref={pagesRef} style={{ minWidth: zoom > 1 ? `${fitWidth * zoom}px` : undefined }} />
      </div>
    </div>
  )
}
