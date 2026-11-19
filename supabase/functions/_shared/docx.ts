// Minimal .docx text extraction without dependencies: read the ZIP central directory,
// inflate word/document.xml and turn paragraphs into lines (bold runs, headings and list items kept).

function u16(b: Uint8Array, o: number) { return b[o] | (b[o + 1] << 8) }
function u32(b: Uint8Array, o: number) { return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0 }

async function inflateRaw(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([new Uint8Array(data)]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

async function readZipEntry(zip: Uint8Array, name: string): Promise<Uint8Array | null> {
  // end of central directory record: last 22..65557 bytes
  let eocd = -1
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (u32(zip, i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('Not a valid .docx file')
  const count = u16(zip, eocd + 10)
  let p = u32(zip, eocd + 16)
  const dec = new TextDecoder()
  for (let n = 0; n < count; n++) {
    if (u32(zip, p) !== 0x02014b50) break
    const method = u16(zip, p + 10)
    const compSize = u32(zip, p + 20)
    const nameLen = u16(zip, p + 28)
    const extraLen = u16(zip, p + 30)
    const commentLen = u16(zip, p + 32)
    const localOffset = u32(zip, p + 42)
    const entryName = dec.decode(zip.subarray(p + 46, p + 46 + nameLen))
    if (entryName === name) {
      const lNameLen = u16(zip, localOffset + 26)
      const lExtraLen = u16(zip, localOffset + 28)
      const start = localOffset + 30 + lNameLen + lExtraLen
      const raw = zip.subarray(start, start + compSize)
      if (method === 0) return raw
      if (method === 8) return await inflateRaw(raw)
      throw new Error('Unsupported compression in .docx file')
    }
    p += 46 + nameLen + extraLen + commentLen
  }
  return null
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }
const unescape = (s: string) => s.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (m, e: string) => {
  if (e[0] === '#') return String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10))
  return ENTITIES[e] ?? m
})

/** Plain text of a .docx: one line per paragraph, bold runs kept as **bold**, headings as "## ". */
export async function docxToText(bytes: Uint8Array): Promise<string> {
  const xmlBytes = await readZipEntry(bytes, 'word/document.xml')
  if (!xmlBytes) throw new Error('The Word file has no document body')
  const xml = new TextDecoder().decode(xmlBytes)
  const out: string[] = []
  const paragraphs = xml.match(/<w:p[\s>][\s\S]*?<\/w:p>/g) ?? []
  for (const p of paragraphs) {
    const style = p.match(/<w:pStyle w:val="([^"]+)"/)?.[1] ?? ''
    const runs = p.match(/<w:r[\s>][\s\S]*?<\/w:r>/g) ?? []
    let line = ''
    for (const r of runs) {
      const text = (r.match(/<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>/g) ?? []).map((t) => unescape(t.replace(/<[^>]+>/g, ''))).join('')
      const tab = /<w:tab\/>/.test(r) ? ' ' : ''
      if (!text) { line += tab; continue }
      const bold = /<w:b(?:\s+w:val="(?:1|true)")?\s*\/>/.test(r)
      line += tab + (bold && text.trim() ? `**${text}**` : text)
    }
    line = line.replace(/\*\*\*\*/g, '').trim()
    if (!line) { out.push(''); continue }
    const isList = /<w:numPr>/.test(p)
    if (/^Heading|^Title/i.test(style)) out.push(`## ${line.replace(/\*\*/g, '')}`)
    else out.push(isList ? `- ${line}` : line)
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}
