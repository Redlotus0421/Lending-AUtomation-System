import { useEffect, useState } from 'react'
import QRCode from 'qrcode'

export function QrCode({ value, size = 200, className }: { value: string; size?: number; className?: string }) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    QRCode.toDataURL(value, { width: size * 2, margin: 1, color: { dark: '#142B4A', light: '#ffffff' } })
      .then(setSrc)
      .catch(() => setSrc(null))
  }, [value, size])
  if (!src) return <div style={{ width: size, height: size }} className="animate-pulse rounded-lg bg-muted" />
  return <img src={src} width={size} height={size} alt="QR code" className={className} />
}
