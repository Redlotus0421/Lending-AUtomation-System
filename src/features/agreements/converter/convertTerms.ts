// Built-in terms converter: turns the text of a lender's existing loan agreement into a
// LendFlow template, entirely on the device (no internet service, no AI API). It recognises
// the usual parts of Philippine loan agreements with rules:
//   - the title and letterhead, section headings, numbered clauses
//   - the parties ("..., hereinafter referred to as the BORROWER") and their addresses
//   - labelled fields ("Loan Amount: ...") and fill-in blanks ("the sum of ______")
//   - amounts, interest rates and dates in context
//   - the signature area, disclosure statement and payment schedule
// Everything it is unsure about stays as written or becomes "________", and is listed in notes.

export type TermsDraft = { title: string; suggested_name: string; body: string; notes: string[] }

const BLANK = '________'
const ph = (k: string) => `{{${k}}}`

// ----------------------------------------------------------------------------- helpers
const MONTHS = 'January|February|March|April|May|June|July|August|September|October|November|December|Jan\\.?|Feb\\.?|Mar\\.?|Apr\\.?|Jun\\.?|Jul\\.?|Aug\\.?|Sept?\\.?|Oct\\.?|Nov\\.?|Dec\\.?'
const DATE_RE = new RegExp(
  `(?:\\b(?:${MONTHS})\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}\\b|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:day\\s+of\\s+)?(?:${MONTHS}),?\\s+\\d{4}\\b|\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b)`, 'gi')
const MONEY_RE = /(?:\b(?:PHP|Php|php)\s?|₱\s?|\bP\s?(?=\d))\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\b\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?(?=\s*(?:pesos|php)\b)/g
const WORDS_AMOUNT_RE = /\b(?:(?:ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|ELEVEN|TWELVE|THIRTEEN|FOURTEEN|FIFTEEN|SIXTEEN|SEVENTEEN|EIGHTEEN|NINETEEN|TWENTY|THIRTY|FORTY|FIFTY|SIXTY|SEVENTY|EIGHTY|NINETY|HUNDRED|THOUSAND|MILLION|AND)[\s-]+){1,14}PESOS(?:\s+(?:AND\s+\d{1,2}\/100|ONLY))?\b/gi
const PCT_RE = /\b\d{1,2}(?:\.\d{1,4})?\s?(?:%|percent\b|per\s?cent\b)(?:\s*\((?:\d{1,2}(?:\.\d+)?%)\))?(?:\s+(?:per|a|every)\s+(?:month|annum|year|week|day)|\s+monthly|\s+annually)?/gi

const isBlank = (s: string) => /_{3,}/.test(s)
const letters = (s: string) => s.replace(/[^A-Za-z]/g, '')
const isAllCaps = (s: string) => { const l = letters(s); return l.length >= 4 && l === l.toUpperCase() }
const titleCase = (s: string) => s.toLowerCase().replace(/\b([a-z])/g, (m) => m.toUpperCase()).replace(/\b(Of|And|The|To|For|In|On|By|With|A|An)\b/g, (m) => m.toLowerCase()).replace(/^./, (m) => m.toUpperCase())

/** Section heading such as "ARTICLE I – THE LOAN", "1. INTEREST", "PAYMENT TERMS" or "## Penalties". */
function headingText(line: string): string | null {
  const t = line.trim()
  if (/^#{1,3}\s+/.test(t)) return t.replace(/^#{1,3}\s+/, '').replace(/\*\*/g, '').trim()
  const plain = t.replace(/\*\*/g, '').trim()
  if (plain.length < 3 || plain.length > 80 || /[.;,]$/.test(plain) || /_{3,}/.test(plain)) return null
  if (/^(article|section|part)\s+([0-9]+|[ivxlc]+)\b/i.test(plain)) return plain
  const numbered = plain.match(/^(\d{1,2}|[IVX]{1,4})[.)]\s+(.{3,70})$/)
  if (numbered && (isAllCaps(numbered[2]) || (/^[A-Z][a-z]+(?:\s+(?:[A-Z][a-z]+|of|and|the|to|for|&))*$/.test(numbered[2]) && numbered[2].split(/\s+/).length <= 6))) return plain
  if (isAllCaps(plain) && plain.split(/\s+/).length <= 8) return plain
  if (/^\*\*[^*]{3,70}\*\*$/.test(t) && !/[.;,]$/.test(plain) && plain.split(/\s+/).length <= 8) return plain
  return null
}

const SIGNATURE_RE = /(signature\s+over\s+printed\s+name|signed\s+in\s+the\s+presence\s+of|^\s*conforme\b|^\s*(?:borrower|co-?maker|lender|creditor|debtor|guarantor|surety|witness(?:es)?|spouse)(?:'s)?\s*(?:signature)?\s*:?\s*_*\s*$|^\s*_{6,}\s*$|^\s*_{6,}\s+_{6,}\s*$|^\s*\(?\s*(?:borrower|co-?maker|lender|witness)\s*\)?\s*$)/i
const ACK_RE = /^\s*(?:#+\s*)?acknowledge?ment\b/i

// ----------------------------------------------------------------------------- parties
type Party = 'borrower' | 'co_maker' | 'lender'
const PARTY_WORD: Record<string, Party> = {
  BORROWER: 'borrower', DEBTOR: 'borrower', OBLIGOR: 'borrower', MAKER: 'borrower', PROMISSOR: 'borrower',
  'CO-MAKER': 'co_maker', COMAKER: 'co_maker', 'CO MAKER': 'co_maker', GUARANTOR: 'co_maker', SURETY: 'co_maker',
  LENDER: 'lender', CREDITOR: 'lender', OBLIGEE: 'lender', PAYEE: 'lender', COMPANY: 'lender',
}
const NAME_PH: Record<Party, string> = { borrower: 'borrower_name', co_maker: 'co_maker_name', lender: 'lender_name' }
const ADDR_PH: Record<Party, string> = { borrower: 'borrower_address', co_maker: 'co_maker_address', lender: 'lender_address' }
const ADDRESS_INTRO = /(residing\s+at|resident\s+of|with\s+(?:postal\s+|residence\s+|business\s+|principal\s+(?:office\s+)?)?address\s+at|with\s+(?:principal\s+)?office(?:s)?\s+at|located\s+at|address\s*:)\s*/i

function partyOf(word: string): Party | undefined {
  const w = word.toUpperCase().replace(/\s+/g, '')
  if (w === 'CO-MAKER' || w === 'COMAKER' || w === 'CO-MAKERS') return 'co_maker'
  return PARTY_WORD[w]
}

/**
 * "JUAN DELA CRUZ, of legal age, ..., residing at 12 Rizal St., Pasig City, hereinafter referred to as the "BORROWER""
 * -> "{{borrower_name}}, of legal age, ..., residing at {{borrower_address}}, hereinafter referred to as the "BORROWER""
 */
function replaceParties(text: string, found: Set<string>): string {
  const re = /(?:hereinafter|hereafter)\s+(?:to\s+be\s+)?(?:referred\s+to|called|known)\s+(?:as\s+)?(?:the\s+)?["“'‘(]*\s*(CO-?\s?MAKER|COMAKER|BORROWER|DEBTOR|OBLIGOR|MAKER|GUARANTOR|SURETY|LENDER|CREDITOR|OBLIGEE|PAYEE|COMPANY)\b/gi
  let result = ''
  let cursor = 0
  for (const m of text.matchAll(re)) {
    const party = partyOf(m[1])
    const start = m.index!
    const clause = text.slice(cursor, start)
    // where this party's description begins: after "between", "and", ";" or ":" followed by a name or a blank
    let begin = 0
    for (const b of clause.matchAll(/(?:\bby\s+and\s+between\b|\bbetween\b|\band\b|[;:])\s*:?\s*/gi)) {
      const next = clause.slice(b.index! + b[0].length)
      if (/^(?:Mr\.|Mrs\.|Ms\.|Sps\.)?\s*[A-Z_]/.test(next)) begin = b.index! + b[0].length
    }
    let desc = clause.slice(begin)
    if (party) {
      const nm = desc.match(/^(\s*(?:Mr\.|Mrs\.|Ms\.|Sps\.)?\s*)([A-Z][A-Za-z.'ñÑ&-]*(?:\s+[A-Z][A-Za-z.'ñÑ&-]*){0,7}|_{3,})(?=\s*(?:,|\(|of\s+legal|a\s+(?:corporation|lending|domestic)|with\b|residing|filipino))/)
      if (nm && !/^(?:The|A|An|This|That)$/.test(nm[2])) {
        desc = nm[1] + ph(NAME_PH[party]) + desc.slice(nm[0].length)
        found.add(NAME_PH[party])
      }
      const intro = desc.match(ADDRESS_INTRO)
      if (intro) {
        const aStart = intro.index! + intro[0].length
        const tail = desc.slice(aStart).match(/[\s,;(“"']*$/)![0]
        const addr = desc.slice(aStart, desc.length - tail.length)
        if (addr.trim() && !addr.includes('{{')) {
          desc = desc.slice(0, aStart) + ph(ADDR_PH[party]) + tail
          found.add(ADDR_PH[party])
        }
      }
    }
    result += clause.slice(0, begin) + desc + m[0]
    cursor = start + m[0].length
  }
  return result + text.slice(cursor)
}

/** Promissory-note style: "I, MARIA SANTOS, of legal age, residing at 45 Mabini St., Marikina City, promise to pay ..." */
function replaceFirstPerson(text: string, found: Set<string>): string {
  return text.replace(/\b(I|We),\s+((?:Mr\.|Mrs\.|Ms\.)?\s*[A-Z][A-Za-z.'ñÑ-]*(?:\s+[A-Z][A-Za-z.'ñÑ-]*){1,6}|_{3,})(\s*,)([^]*?)(?=,?\s*(?:do\s+hereby|hereby|promise|jointly|bind|agree|acknowledge|declare|for\s+value)\b)/,
    (all, who: string, name: string, comma: string, rest: string) => {
      if (name.includes('{{')) return all
      found.add('borrower_name')
      let r = rest
      const intro = r.match(ADDRESS_INTRO)
      if (intro) {
        const aStart = intro.index! + intro[0].length
        const addr = r.slice(aStart)
        if (addr.trim() && !addr.includes('{{')) { r = r.slice(0, aStart) + ph('borrower_address'); found.add('borrower_address') }
      }
      return `${who}, ${ph('borrower_name')}${comma}${r}`
    })
}

// ----------------------------------------------------------------------------- labelled fields
const LABELS: [RegExp, string][] = [
  [/name\s+of\s+(?:the\s+)?borrower|borrower(?:'s)?\s+name|borrower|debtor(?:'s)?\s+name|name\s+of\s+debtor|pangalan/i, 'borrower_name'],
  [/borrower(?:'s)?\s+address|address\s+of\s+(?:the\s+)?borrower|home\s+address|present\s+address|address|tirahan/i, 'borrower_address'],
  [/co-?maker(?:'s)?\s+name|name\s+of\s+(?:the\s+)?co-?maker|co-?maker|guarantor/i, 'co_maker_name'],
  [/co-?maker(?:'s)?\s+address/i, 'co_maker_address'],
  [/relationship(?:\s+to\s+(?:the\s+)?borrower)?/i, 'co_maker_relationship'],
  [/co-?maker(?:'s)?\s+(?:contact|mobile|cellphone|phone)(?:\s+(?:no\.?|number))?/i, 'co_maker_mobile'],
  [/(?:contact|mobile|cellphone|cell|phone|tel\.?)\s*(?:no\.?|number|#)?/i, 'borrower_mobile'],
  [/e-?mail(?:\s+address)?/i, 'borrower_email'],
  [/(?:type\s+of\s+)?(?:valid\s+)?id\s+(?:type|presented)|type\s+of\s+id/i, 'borrower_id_type'],
  [/id\s+(?:no\.?|number|#)/i, 'borrower_id_number'],
  [/lender|creditor|name\s+of\s+lender/i, 'lender_name'],
  [/(?:amount\s+of\s+(?:the\s+)?loan|loan\s+amount|principal(?:\s+amount)?(?:\s+of\s+loan)?|amount\s+(?:borrowed|financed|granted)|halaga\s+ng\s+utang)/i, 'loan_amount'],
  [/amount\s+in\s+words/i, 'loan_amount_words'],
  [/(?:interest\s+rate|rate\s+of\s+interest|interest|tubo)/i, 'interest_rate'],
  [/(?:loan\s+)?(?:term|duration|period)(?:\s+of\s+(?:the\s+)?loan)?|terms?\s+of\s+payment|no\.\s+of\s+months/i, 'loan_duration'],
  [/(?:mode|frequency)\s+of\s+payment|payment\s+(?:mode|frequency|schedule)/i, 'payment_frequency'],
  [/(?:number|no\.)\s+of\s+(?:installments|payments|amortizations)/i, 'installment_count'],
  [/(?:monthly|semi-?monthly|weekly|daily)?\s*(?:installment|amortization)(?:\s+amount)?|amount\s+(?:per|of\s+each)\s+(?:installment|payment)/i, 'installment_amount'],
  [/total\s+interest/i, 'total_interest'],
  [/processing\s+fee|service\s+(?:fee|charge)/i, 'processing_fee'],
  [/(?:total\s+)?(?:fees\s+and\s+charges|charges)/i, 'total_charges'],
  [/net\s+(?:proceeds|amount\s+(?:released|received))|amount\s+released/i, 'net_proceeds'],
  [/total\s+(?:amount\s+(?:to\s+be\s+paid|payable|due)|repayment|payable)/i, 'total_repayment'],
  [/(?:date\s+(?:of\s+)?(?:release|released)|release\s+date|date\s+granted)/i, 'release_date'],
  [/(?:first\s+(?:payment|due|installment)(?:\s+date)?|start\s+of\s+payment)/i, 'first_payment_date'],
  [/(?:maturity(?:\s+date)?|due\s+date|last\s+payment(?:\s+date)?)/i, 'last_payment_date'],
  [/(?:loan|account|reference|ref\.?)\s*(?:no\.?|number|#)/i, 'loan_number'],
  [/(?:agreement|contract)\s*(?:no\.?|number|#)/i, 'agreement_number'],
  [/^(?:date|petsa)$/i, 'agreement_date'],
]

function labelPlaceholder(label: string): string | null {
  const l = label.replace(/\*\*/g, '').trim()
  if (l.length > 40 || l.split(/\s+/).length > 6) return null
  for (const [re, key] of LABELS) {
    const m = l.match(re)
    if (m && m[0].length >= l.length - 3) return key
  }
  return null
}

/** "Loan Amount: PHP 20,000.00    Term: 6 months" -> "Loan Amount: {{loan_amount}}    Term: {{loan_duration}}" */
function replaceLabelled(line: string, found: Set<string>): string {
  return line.replace(/(^|\s{2,}|\t|\|\s*|;\s*)([A-Za-z*][A-Za-z .'’()/#*-]{1,40}?)\s*[:：]\s*([^:\t|]*?)(?=\s{2,}[A-Za-z*][A-Za-z .'’()/#*-]{1,40}?\s*[:：]|\t|\s*\||$)/g,
    (all, pre: string, label: string, value: string) => {
      const key = labelPlaceholder(label)
      if (!key || value.includes('{{')) return all
      const v = value.trim()
      // keep sentences that merely start with a word and a colon (e.g. "Interest: the borrower shall ...")
      if (v.length > 80 || (v.split(/\s+/).length > 10 && !isBlank(v))) return all
      found.add(key)
      return `${pre}${label.trim()}: ${ph(key)}`
    })
}

// ----------------------------------------------------------------------------- blanks & values in sentences
function blankFor(before: string, after: string, ctx: string): string | null {
  const b = before.toLowerCase().slice(-90)
  const a = after.toLowerCase().slice(0, 40)
  const c = ctx.toLowerCase()
  if (/^\s*\(\s*(?:php|p|₱)/.test(a) || /^\s*pesos/.test(a)) return 'loan_amount_words'
  if (/(?:php|₱|\bp)\s*\.?\s*$/.test(b) || /\(\s*(?:php|p|₱)?\s*$/.test(b)) {
    if (/install|amortiz|each\s+payment|monthly\s+payment/.test(b)) return 'installment_amount'
    if (/processing|service\s+fee/.test(b)) return 'processing_fee'
    if (/net\s+(?:proceeds|amount)/.test(b)) return 'net_proceeds'
    if (/penalt|late|overdue|default/.test(c)) return null
    return 'loan_amount'
  }
  if (/(?:sum|amount|principal|loan)\s+of\s*(?:php|₱|p)?\s*$/.test(b)) return 'loan_amount'
  if (/^\s*(?:%|percent|per\s?cent)/.test(a)) return /penalt|late|overdue|default|surcharge/.test(c) ? null : 'interest_rate'
  if (/^\s*(?:\(\s*\d+\s*\)\s*)?(?:equal\s+)?(?:monthly|semi-?monthly|weekly|daily)?\s*(?:installments|amortizations|payments)/.test(a)) return 'installment_count'
  if (/^\s*(?:\(\s*\d+\s*\)\s*)?(?:months?|weeks?|days?|years?)\b/.test(a) && /(?:within|for|period|term|payable\s+in|duration|of)\s*$/.test(b)) return 'loan_duration'
  if (/(?:day\s+of|dated|executed\s+(?:on|this)|this|on)\s*$/.test(b) && /^\s*(?:,?\s*\d{4}|,?\s*20\s*_*|day)/.test(a)) return 'agreement_date'
  if (/(?:first\s+(?:payment|installment|due))[^.]{0,30}$/.test(b)) return 'first_payment_date'
  if (/(?:maturity|last\s+(?:payment|installment))[^.]{0,30}$/.test(b)) return 'last_payment_date'
  if (/(?:release|released)[^.]{0,20}$/.test(b) && /date|on\s*$/.test(b)) return 'release_date'
  if (/(?:residing\s+at|resident\s+of|address\s+(?:at|is)|with\s+address\s+at)\s*$/.test(b)) return /co-?maker|guarantor/.test(b) ? 'co_maker_address' : 'borrower_address'
  if (/\bi\s*,\s*$/.test(b) || /(?:^|\s)i,\s*$/.test(b)) return 'borrower_name'
  if (/(?:co-?maker|guarantor)(?:'s)?\s*(?:name)?\s*[:,]?\s*$/.test(b)) return 'co_maker_name'
  if (/(?:borrower|debtor)(?:'s)?\s*(?:name)?\s*[:,]?\s*$/.test(b)) return 'borrower_name'
  if (/(?:loan|account|reference)\s*(?:no\.?|number)\s*\.?\s*$/.test(b)) return 'loan_number'
  return null
}

const MONEY_KEYS = new Set(['loan_amount', 'installment_amount', 'processing_fee', 'total_charges', 'net_proceeds', 'total_repayment', 'total_interest'])

function replaceBlanks(text: string, found: Set<string>, counter: { left: number }): string {
  // "this ____ day of ________, 20___" is one date: the agreement date
  text = text.replace(/\b(?:this|on\s+the)\s+_{2,}\s*(?:st|nd|rd|th)?\s+day\s+of\s+_{2,}\s*,?\s*(?:(?:20|19)?_{2,}|\d{4})?/gi, () => {
    found.add('agreement_date')
    return `on ${ph('agreement_date')}`
  })
  let out = ''
  let last = 0
  for (const m of text.matchAll(/_{3,}/g)) {
    const start = m.index!
    const before = text.slice(0, start)
    const after = text.slice(start + m[0].length)
    const sentence = text.slice(Math.max(0, text.lastIndexOf('.', start - 1) + 1), start + 60)
    let key = blankFor(before, after, sentence)
    // "______ months" -> {{loan_duration}} already says "6 months"; drop the unit word
    let skip = 0
    if (key === 'loan_duration') {
      const unit = after.match(/^\s*(?:\(\s*\d+\s*\)\s*)?(?:months?|weeks?|days?|years?)\b/i)
      if (unit) skip = unit[0].length
    }
    if (key === 'interest_rate') {
      const unit = after.match(/^\s*(?:%|percent\b|per\s?cent\b)(?:\s+(?:per|a|every)\s+(?:month|annum|year|week|day)|\s+monthly)?/i)
      if (unit) skip = unit[0].length
    }
    // the amount in words already ends with "Pesos"
    if (key === 'loan_amount_words') {
      const unit = after.match(/^\s*pesos\b/i)
      if (unit) skip = unit[0].length
    }
    let lead = text.slice(last, start)
    // money fields already print "PHP 20,000.00": drop the currency sign written before the blank
    if (key && MONEY_KEYS.has(key)) lead = lead.replace(/(?:\bPHP|\bPhp|\bP|₱)\s*\.?\s*$/, '')
    out += lead + (key ? ph(key) : BLANK)
    if (key) found.add(key)
    else counter.left++
    last = start + m[0].length + skip
  }
  return out + text.slice(last)
}

/** Filled-in samples: replace concrete amounts / rates / dates whose meaning is clear from the sentence. */
function replaceValues(text: string, found: Set<string>, replaced: string[]): string {
  // sentence by sentence so the context stays local
  return text.split(/(?<=[.;])\s+(?=[A-Z(])/).map((sentence) => {
    const s = sentence.toLowerCase()
    const penalty = /penalt|late\s+payment|overdue|in\s+default|surcharge|liquidated|attorney/.test(s)
    let out = sentence
    // amount in words (uppercase words ending in PESOS)
    out = out.replace(WORDS_AMOUNT_RE, (m) => {
      if (penalty || /install|amortiz/.test(s)) return m
      found.add('loan_amount_words'); replaced.push(`"${m.trim()}" → amount in words`)
      return ph('loan_amount_words')
    })
    let usedAmount = false
    out = out.replace(MONEY_RE, (m, offset: number) => {
      const before = out.slice(Math.max(0, offset - 70), offset).toLowerCase()
      let key: string | null = null
      if (/install|amortiz|each\s+payment|per\s+(?:month|payment)/.test(before)) key = 'installment_amount'
      else if (/processing|service\s+fee/.test(before)) key = 'processing_fee'
      else if (/net\s+(?:proceeds|amount)|amount\s+released/.test(before)) key = 'net_proceeds'
      else if (/total\s+(?:amount|repayment|payable)/.test(before)) key = 'total_repayment'
      else if (!penalty && !usedAmount && /(?:sum|amount|principal|loan|borrow|obtain|lend|the\s+amount\s+of)/.test(before + s.slice(0, 200))) { key = 'loan_amount'; usedAmount = true }
      if (!key) return m
      found.add(key); replaced.push(`${m.trim()} → ${key.replace(/_/g, ' ')}`)
      return ph(key)
    })
    out = out.replace(PCT_RE, (m) => {
      if (penalty || !/interest|tubo/.test(s) || /effective|annual\s+percentage/.test(s)) return m
      if (found.has('interest_rate') && !/interest\s+rate|rate\s+of\s+interest|interest\s+of|interest\s+at/.test(s)) return m
      found.add('interest_rate'); replaced.push(`${m.trim()} → interest rate`)
      return ph('interest_rate')
    })
    out = out.replace(DATE_RE, (m, offset: number) => {
      const before = out.slice(Math.max(0, offset - 60), offset).toLowerCase()
      let key: string | null = null
      if (/first\s+(?:payment|installment|due)/.test(before)) key = 'first_payment_date'
      else if (/maturity|last\s+(?:payment|installment)|fully\s+paid\s+(?:on|by)/.test(before)) key = 'last_payment_date'
      else if (/releas/.test(before)) key = 'release_date'
      else if (/(?:executed|signed|entered\s+into|made|dated|this|day\s+of|on)\s*(?:this\s*)?$/.test(before) || /^(?:this|made|executed)/.test(s)) key = 'agreement_date'
      if (!key) return m
      found.add(key); replaced.push(`${m.trim()} → ${key.replace(/_/g, ' ')}`)
      return ph(key)
    })
    return out
  }).join(' ')
}

// ----------------------------------------------------------------------------- main
const FIELD_LINE = /^(?:\*\*)?[A-Za-z][A-Za-z .'’()/#-]{1,40}(?:\*\*)?\s*[:：]\s*\S/

/**
 * @param soft  lines may be soft-wrapped (PDF / plain text): join them into paragraphs.
 *              Word files give one line per paragraph already.
 */
export function convertTermsText(input: string, fileName = 'terms', soft = true): TermsDraft {
  const notes: string[] = []
  const found = new Set<string>()
  const replaced: string[] = []
  const counter = { left: 0 }

  let lines = input.replace(/\r\n?/g, '\n').replace(/ /g, ' ').replace(/[ \t]+$/gm, '').split('\n')
  // page furniture repeated by PDFs
  lines = lines.filter((l) => !/^\s*(?:page\s+\d+(?:\s+of\s+\d+)?|\d{1,3}|-\s*\d+\s*-)\s*$/i.test(l))

  // ---- title & letterhead
  let title = 'Loan Agreement'
  const firstLines = lines.map((l, i) => ({ l: l.trim(), i })).filter((x) => x.l).slice(0, 14)
  const t = firstLines.find((x) => x.l.replace(/[#*]/g, '').trim().length <= 80
    && /\b(?:loan\s+agreement|promissory\s+note|agreement|contract|terms\s+and\s+conditions|disclosure|kasunduan|kontrata)\b/i.test(x.l)
    && !/hereinafter|between|this\s+\w+\s+(?:is|made)/i.test(x.l))
  if (t) {
    title = titleCase(t.l.replace(/^#+\s*/, '').replace(/\*\*/g, '').trim())
    const before = lines.slice(0, t.i).filter((l) => l.trim())
    if (before.length && before.every((l) => l.length < 120)) notes.push('Removed the letterhead above the title — LendFlow prints your business name and address at the top.')
    lines = before.every((l) => l.length < 120) ? lines.slice(t.i + 1) : [...lines.slice(0, t.i), ...lines.slice(t.i + 1)]
  }

  // ---- signature area & notarial acknowledgment
  const n = lines.length
  let sigStart = -1
  for (let i = Math.floor(n * 0.4); i < n; i++) {
    if (SIGNATURE_RE.test(lines[i])) {
      // the signature area: nothing but short lines (names, labels, blanks) after it, or an acknowledgment
      const after = lines.slice(i)
      const longClauses = after.filter((l) => l.length > 160 && !ACK_RE.test(l))
      const ackAt = after.findIndex((l) => ACK_RE.test(l))
      const clausesBeforeAck = ackAt === -1 ? longClauses.length : after.slice(0, ackAt).filter((l) => l.length > 160).length
      if (clausesBeforeAck === 0) { sigStart = i; break }
    }
  }
  if (sigStart !== -1) {
    // keep the witness clause ("IN WITNESS WHEREOF, ...") that often sits on the line right above
    const tail = lines.slice(sigStart)
    if (tail.some((l) => ACK_RE.test(l))) notes.push('Removed the notarial acknowledgment with the signature area. Add the acknowledgment text back at the end if you have agreements notarized.')
    lines = lines.slice(0, sigStart)
    notes.push('Replaced the signature lines with LendFlow\'s signature section (borrower, co-maker, lender and witnesses are printed automatically).')
  } else {
    notes.push('Could not find a signature area — LendFlow adds its signature section at the end.')
  }

  // ---- join soft-wrapped lines into paragraphs (PDF), classify headings and lists
  const blocks: string[] = []
  let para = ''
  const flush = () => { if (para.trim()) blocks.push(para.trim()); para = '' }
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) { flush(); blocks.push(''); continue }
    // "- and -" between the parties
    if (/^[-–—\s]*and[-–—\s]*$/i.test(line)) { flush(); blocks.push('and'); continue }
    // an all-caps preamble such as "KNOW ALL MEN BY THESE PRESENTS:" stays text, in bold
    if (isAllCaps(line) && /:$/.test(line) && line.length <= 80) { flush(); blocks.push(`**${line.replace(/\*\*/g, '')}**`); continue }
    const h = headingText(line)
    if (h) { flush(); blocks.push('', `## ${isAllCaps(h) ? titleCase(h) : h}`, ''); continue }
    const list = line.match(/^(?:(\d{1,2})[.)]|\(?([a-z]|[ivx]{1,4})\)|[-•*▪●]\s)\s*(.*)$/i)
    if (list && !/^\d{1,2}[.)]\d/.test(line)) {
      flush()
      const body = (list[3] ?? '').trim()
      para = list[1] ? `${list[1]}. ${body}` : `- ${body}`
      continue
    }
    const joinable = soft && para && !FIELD_LINE.test(line) && !FIELD_LINE.test(para) && !/\s{3,}|\t/.test(line) && !/\s{3,}|\t/.test(para)
    if (joinable && (!/[.:;!?]["”)]?$/.test(para) || /^[a-z(]/.test(line))) para += ` ${line}`
    else { flush(); para = line }
  }
  flush()

  // ---- disclosure statement / payment schedule sections
  let out: string[] = []
  let skipping: null | 'disclosure' | 'schedule' = null
  let hasDisclosure = false
  let hasSchedule = false
  for (const b of blocks) {
    if (b.startsWith('## ')) {
      skipping = null
      const h = b.toLowerCase()
      out.push(b)
      if (/disclosure/.test(h)) { skipping = 'disclosure'; hasDisclosure = true; out.push('', ph('disclosure_table')) }
      else if (/(?:schedule|amortization|amortisation)\b/.test(h) && !/penalt/.test(h)) { skipping = 'schedule'; hasSchedule = true; out.push('', ph('repayment_schedule')) }
      continue
    }
    if (skipping) {
      // keep explanatory sentences, drop the table rows and filled-in figures
      const digits = (b.match(/\d/g) ?? []).length
      if (b && b.length > 60 && digits < b.length * 0.08 && !isBlank(b)) out.push(b)
      continue
    }
    out.push(b)
  }

  // ---- placeholders
  out = out.map((b) => {
    if (!b || b.startsWith('## ') || b.startsWith('{{')) return b
    let x = replaceParties(b, found)
    x = replaceFirstPerson(x, found)
    // promissory notes: "promise to pay ABC Lending Inc." names the lender
    x = x.replace(/(promise\s+to\s+pay\s+(?:to\s+)?(?:the\s+order\s+of\s+)?)([A-Z][A-Za-z.&'-]*(?:\s+[A-Z][A-Za-z.&'-]*){0,6}?(?:,?\s+(?:Inc|Corp|Co|Corporation|Company)\.?)?)(?=\s+(?:the|or|at|on|in)\b|[,.;(])/, (all, pre: string, name: string) => {
      if (/^(?:The|Said)\b/.test(name)) return all
      found.add('lender_name')
      return pre + ph('lender_name')
    })
    x = replaceLabelled(x, found)
    x = replaceBlanks(x, found, counter)
    x = replaceValues(x, found, replaced)
    return x
  })

  if (!hasDisclosure) out.push('', '## Disclosure Statement', '', ph('disclosure_table'))
  if (!hasSchedule) out.push('', '## Repayment Schedule', '', ph('repayment_schedule'))
  if (!hasDisclosure || !hasSchedule) {
    notes.push(`Added ${[!hasDisclosure && 'a Disclosure Statement', !hasSchedule && 'a Repayment Schedule'].filter(Boolean).join(' and ')} section — LendFlow fills ${!hasDisclosure && !hasSchedule ? 'them' : 'it'} in for every loan.`)
  }
  out.push('', ph('signature_block'))

  const body = out.join('\n').replace(/\n{3,}/g, '\n\n').replace(/^\n+/, '').trim()

  // ---- notes for the lender
  const labels: Record<string, string> = {
    borrower_name: 'borrower name', borrower_address: 'borrower address', co_maker_name: 'co-maker name', co_maker_address: 'co-maker address',
    lender_name: 'lender name', lender_address: 'lender address', loan_amount: 'loan amount', loan_amount_words: 'amount in words',
    interest_rate: 'interest rate', loan_duration: 'loan term', installment_count: 'number of installments', installment_amount: 'installment amount',
    agreement_date: 'agreement date', first_payment_date: 'first payment date', last_payment_date: 'last payment date', release_date: 'release date',
  }
  const got = [...found].map((k) => labels[k] ?? k.replace(/_/g, ' '))
  if (got.length) notes.unshift(`Filled in automatically for every loan: ${got.join(', ')}.`)
  else notes.unshift('No loan details were recognised. Use the field buttons above the text to insert them where needed.')
  if (counter.left) notes.push(`${counter.left} blank${counter.left === 1 ? '' : 's'} (________) could not be matched to a loan detail. Replace each with a field from the buttons above the text, or type the wording you want.`)
  if (replaced.length) notes.push(`Values replaced from your sample: ${replaced.slice(0, 8).join('; ')}${replaced.length > 8 ? '; …' : ''}. Check that each one is right.`)
  if (/penalt|late\s+payment|overdue/i.test(body) && /\d\s?%/.test(body)) notes.push('Penalty percentages were kept exactly as written. They should match your Late payments settings (or replace them with {{penalty_terms}}).')

  return {
    title,
    suggested_name: title === 'Loan Agreement' ? fileName.replace(/\.[a-z0-9]+$/i, '').replace(/[_-]+/g, ' ').trim().slice(0, 60) || 'Imported terms' : title,
    body,
    notes,
  }
}
