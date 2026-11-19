// POST { file_name, mime_type, data_base64 } — read a lender's existing loan terms (PDF, Word,
// photo/scan or plain text) with Claude and return a draft agreement template that uses the
// system's placeholders. Nothing is saved here: staff review the draft in the editor and save it.
// Requires the ANTHROPIC_API_KEY secret (npx supabase secrets set ANTHROPIC_API_KEY=...).
import Anthropic from 'npm:@anthropic-ai/sdk'
import { HttpError, json, readJson, requirePermission, requireUser, serve } from '../_shared/http.ts'
import { docxToText } from '../_shared/docx.ts'
import { PLACEHOLDERS } from '../_shared/template.ts'

const MAX_BYTES = 8 * 1024 * 1024
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'] as const
type ImageType = typeof IMAGE_TYPES[number]

const SYSTEM = `You convert a lending company's existing loan agreement / terms and conditions into a reusable template for a Philippine loan management system.

TEMPLATE SYNTAX (the only formatting the system understands)
- "## " starts a section heading, "### " a sub-heading. Do not use "# ".
- **double asterisks** make text bold. No italics, no tables, no HTML, no links.
- Numbered list items start with "1. ", "2. " ...; bullet items start with "- ".
- Separate paragraphs, headings and lists with one blank line.
- These three block placeholders must each stand alone on their own line:
  {{disclosure_table}}   the Truth-in-Lending disclosure statement table
  {{repayment_schedule}} the installment schedule table
  {{signature_block}}    all signature lines (borrower, co-maker, lender, witnesses)

PLACEHOLDERS — the system fills these for every loan. Use ONLY these exact keys inside double braces:
${PLACEHOLDERS.map((p) => `- {{${p.key}}} — ${p.label}`).join('\n')}

HOW TO CONVERT
1. Keep the lender's own clauses, order and legal wording faithfully. Do not invent new obligations, rates, fees or penalties, and do not drop clauses. You may fix obvious typos and OCR errors.
2. Replace every loan-specific value with the matching placeholder: names and addresses of the borrower, co-maker and lender, ID numbers, amounts (and amounts in words), interest rates, terms/durations, dates, number and amount of installments, fees, penalties, loan and agreement numbers. Blanks to fill in ("________") for such values also become placeholders.
3. A specific value that is the lender's fixed policy rather than per-loan (for example the venue city of courts written as a fixed city, or a fixed notice period) stays as written.
4. A per-loan value with no matching placeholder becomes "________" and you mention it in the notes.
5. Replace any written signature area, signature lines, "Signed in the presence of" witness lines and notary acknowledgment signature lines with a single {{signature_block}} at the end. Keep the body of a notarial acknowledgment only if the source has one, without signature lines.
6. If the source has a disclosure statement or a payment schedule/table, put {{disclosure_table}} or {{repayment_schedule}} where it was. If it has none, add a short "## Disclosure Statement" section with {{disclosure_table}} and a "## Repayment Schedule" section with {{repayment_schedule}} just before the signatures, and say so in the notes.
7. Do not put the document title or the lender's letterhead (name, address, logo) at the top of the body; the system prints those. Put the document title in "title" instead (e.g. "Loan Agreement", "Promissory Note").
8. If the file is not a loan agreement or terms document, still return your best attempt and explain the problem in the notes.

Write "notes" as short, plain sentences for a non-technical lender: what you replaced with blanks, sections you added, anything unclear in the source they should check before using the template.`

const OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    title: { type: 'string', description: 'Printed agreement title, e.g. "Loan Agreement"' },
    suggested_name: { type: 'string', description: 'Short template name for the template list, e.g. "Standard Loan Agreement"' },
    body: { type: 'string', description: 'The template body in the template syntax' },
    notes: { type: 'array', items: { type: 'string' }, description: 'Points the lender should review' },
  },
  required: ['title', 'suggested_name', 'body', 'notes'],
  additionalProperties: false,
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function kindOf(name: string, mime: string): 'pdf' | 'docx' | 'image' | 'text' | null {
  const ext = name.toLowerCase().split('.').pop() ?? ''
  if (mime === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (ext === 'docx' || mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx'
  if ((IMAGE_TYPES as readonly string[]).includes(mime) || ['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(ext)) return 'image'
  if (mime.startsWith('text/') || ['txt', 'md', 'markdown'].includes(ext)) return 'text'
  return null
}

const imageType = (name: string, mime: string): ImageType => {
  if ((IMAGE_TYPES as readonly string[]).includes(mime)) return mime as ImageType
  const ext = name.toLowerCase().split('.').pop()
  return ext === 'png' ? 'image/png' : ext === 'webp' ? 'image/webp' : ext === 'gif' ? 'image/gif' : 'image/jpeg'
}

serve(async (req) => {
  const { access } = await requireUser(req)
  requirePermission(access, 'templates.manage')
  if (!Deno.env.get('ANTHROPIC_API_KEY')) {
    throw new HttpError(503, 'The AI assistant is not set up yet. Ask the platform administrator to add the ANTHROPIC_API_KEY secret.')
  }

  const body = await readJson<{ file_name?: string; mime_type?: string; data_base64?: string }>(req)
  const name = (body.file_name ?? 'terms').slice(0, 200)
  const mime = (body.mime_type ?? '').toLowerCase()
  if (!body.data_base64) throw new HttpError(400, 'Attach the terms file')
  const bytes = b64ToBytes(body.data_base64)
  if (bytes.length > MAX_BYTES) throw new HttpError(413, 'The file is larger than 8 MB')
  const kind = kindOf(name, mime)
  if (!kind) throw new HttpError(415, 'Use a PDF, Word (.docx), photo (JPG/PNG) or text file')
  if (name.toLowerCase().endsWith('.doc')) throw new HttpError(415, 'Old .doc files are not supported. Save it as .docx or PDF first.')

  let source: Anthropic.Beta.BetaContentBlockParam
  if (kind === 'pdf') {
    source = { type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: body.data_base64 } }
  } else if (kind === 'image') {
    source = { type: 'image', source: { type: 'base64', media_type: imageType(name, mime), data: body.data_base64 } }
  } else {
    let text: string
    try {
      text = kind === 'docx' ? await docxToText(bytes) : new TextDecoder().decode(bytes)
    } catch (e) {
      throw new HttpError(422, `Could not read the file: ${(e as Error).message}`)
    }
    if (!text.trim()) throw new HttpError(422, 'The file has no readable text')
    source = { type: 'text', text: `<terms_document file="${name.replace(/"/g, '')}">\n${text}\n</terms_document>` }
  }

  const client = new Anthropic()
  let message: Anthropic.Beta.BetaMessage
  try {
    const stream = client.beta.messages.stream({
      model: 'claude-opus-5-5',
      max_tokens: 32000,
      // on a policy decline, the API retries on a fallback model inside the same call
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
      output_config: { effort: 'medium', format: { type: 'json_schema', schema: OUTPUT_SCHEMA } },
      system: SYSTEM,
      messages: [{
        role: 'user',
        content: [source, { type: 'text', text: 'Convert this document into a template. Return the JSON only.' }],
      }],
    })
    message = await stream.finalMessage()
  } catch (e) {
    if (e instanceof Anthropic.RateLimitError) throw new HttpError(429, 'The AI assistant is busy. Try again in a minute.')
    if (e instanceof Anthropic.AuthenticationError) throw new HttpError(503, 'The AI assistant key is invalid. Ask the platform administrator to check ANTHROPIC_API_KEY.')
    if (e instanceof Anthropic.BadRequestError) throw new HttpError(422, `The AI assistant could not read this file: ${e.message}`)
    if (e instanceof Anthropic.APIError) throw new HttpError(502, `The AI assistant failed (${e.status ?? 'network'}). Try again.`)
    throw e
  }

  if (message.stop_reason === 'refusal') throw new HttpError(422, 'The AI assistant declined to process this file.')
  if (message.stop_reason === 'max_tokens') throw new HttpError(422, 'The document is too long to convert in one go. Split it and try again.')
  const text = message.content.flatMap((b) => (b.type === 'text' ? [b.text] : [])).join('')
  let out: { title: string; suggested_name: string; body: string; notes: string[] }
  try {
    out = JSON.parse(text)
  } catch {
    throw new HttpError(502, 'The AI assistant returned an unreadable answer. Try again.')
  }

  // only known placeholders survive; anything else becomes a blank the lender fills in
  const known = new Set(PLACEHOLDERS.map((p) => p.key))
  const unknown = new Set<string>()
  const cleaned = out.body.replace(/\{\{\s*([a-z0-9_]+)\s*\}\}/gi, (m, k: string) => {
    if (known.has(k.toLowerCase())) return `{{${k.toLowerCase()}}}`
    unknown.add(k)
    return '________'
  })
  const notes = [...(out.notes ?? [])]
  if (unknown.size) notes.push(`Replaced unsupported fields with blanks: ${[...unknown].join(', ')}.`)

  return json({
    title: out.title?.trim() || 'Loan Agreement',
    suggested_name: out.suggested_name?.trim() || 'Imported terms',
    body: cleaned.trim(),
    notes,
    model: message.model,
  })
})
