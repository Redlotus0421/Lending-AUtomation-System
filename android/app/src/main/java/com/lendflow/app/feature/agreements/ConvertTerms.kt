package com.lendflow.app.feature.agreements

// Port of src/features/agreements/converter/convertTerms.ts — the built-in terms converter: turns the
// text of a lender's existing loan agreement into a LendFlow template, entirely on the device (no
// internet service, no AI API). It recognises the usual parts of Philippine loan agreements with rules:
//   - the title and letterhead, section headings, numbered clauses
//   - the parties ("..., hereinafter referred to as the BORROWER") and their addresses
//   - labelled fields ("Loan Amount: ...") and fill-in blanks ("the sum of ______")
//   - amounts, interest rates and dates in context
//   - the signature area, disclosure statement and payment schedule
// Everything it is unsure about stays as written or becomes "________", and is listed in notes.
// Pure Kotlin (no Android classes) so it runs in JVM unit tests.

data class TermsDraft(val title: String, val suggestedName: String, val body: String, val notes: List<String>)

private const val BLANK = "________"
private fun ph(k: String) = "{{$k}}"

private val I = RegexOption.IGNORE_CASE
private fun re(p: String) = Regex(p)
private fun rei(p: String) = Regex(p, I)

/** JS `str.replace(regexWithoutG, fn)`: replaces the first match only. */
private fun Regex.replaceFirstWith(input: String, transform: (MatchResult) -> String): String {
    val m = find(input) ?: return input
    return input.substring(0, m.range.first) + transform(m) + input.substring(m.range.last + 1)
}

private fun MatchResult.g(i: Int): String = groupValues.getOrElse(i) { "" }
private fun MatchResult.has(i: Int): Boolean = groups[i] != null

// ----------------------------------------------------------------------------- helpers
private const val MONTHS = """January|February|March|April|May|June|July|August|September|October|November|December|Jan\.?|Feb\.?|Mar\.?|Apr\.?|Jun\.?|Jul\.?|Aug\.?|Sept?\.?|Oct\.?|Nov\.?|Dec\.?"""
private val DATE_RE = rei(
    """(?:\b(?:$MONTHS)\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}\b|\b\d{1,2}(?:st|nd|rd|th)?\s+(?:day\s+of\s+)?(?:$MONTHS),?\s+\d{4}\b|\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b)""",
)
private val MONEY_RE = re("""(?:\b(?:PHP|Php|php)\s?|₱\s?|\bP\s?(?=\d))\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\b\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?(?=\s*(?:pesos|php)\b)""")
private val WORDS_AMOUNT_RE = rei("""\b(?:(?:ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|ELEVEN|TWELVE|THIRTEEN|FOURTEEN|FIFTEEN|SIXTEEN|SEVENTEEN|EIGHTEEN|NINETEEN|TWENTY|THIRTY|FORTY|FIFTY|SIXTY|SEVENTY|EIGHTY|NINETY|HUNDRED|THOUSAND|MILLION|AND)[\s-]+){1,14}PESOS(?:\s+(?:AND\s+\d{1,2}/100|ONLY))?\b""")
private val PCT_RE = rei("""\b\d{1,2}(?:\.\d{1,4})?\s?(?:%|percent\b|per\s?cent\b)(?:\s*\((?:\d{1,2}(?:\.\d+)?%)\))?(?:\s+(?:per|a|every)\s+(?:month|annum|year|week|day)|\s+monthly|\s+annually)?""")

private val BLANK3 = re("_{3,}")
private fun isBlank(s: String) = BLANK3.containsMatchIn(s)
private fun letters(s: String) = s.replace(Regex("[^A-Za-z]"), "")
private fun isAllCaps(s: String): Boolean { val l = letters(s); return l.length >= 4 && l == l.uppercase() }
private fun words(s: String) = s.split(Regex("\\s+")).size

private val SMALL_WORDS = re("""\b(Of|And|The|To|For|In|On|By|With|A|An)\b""")
private fun titleCase(s: String): String {
    var t = re("""\b([a-z])""").replace(s.lowercase()) { it.value.uppercase() }
    t = SMALL_WORDS.replace(t) { it.value.lowercase() }
    return if (t.isEmpty()) t else t.substring(0, 1).uppercase() + t.substring(1)
}

/** Section heading such as "ARTICLE I – THE LOAN", "1. INTEREST", "PAYMENT TERMS" or "## Penalties". */
private fun headingText(line: String): String? {
    val t = line.trim()
    if (re("^#{1,3}\\s+").containsMatchIn(t)) return t.replaceFirst(re("^#{1,3}\\s+"), "").replace("**", "").trim()
    val plain = t.replace("**", "").trim()
    if (plain.length < 3 || plain.length > 80 || re("[.;,]$").containsMatchIn(plain) || BLANK3.containsMatchIn(plain)) return null
    if (rei("""^(article|section|part)\s+([0-9]+|[ivxlc]+)\b""").containsMatchIn(plain)) return plain
    val numbered = re("""^(\d{1,2}|[IVX]{1,4})[.)]\s+(.{3,70})$""").find(plain)
    if (numbered != null) {
        val n2 = numbered.g(2)
        if (isAllCaps(n2) || (re("""^[A-Z][a-z]+(?:\s+(?:[A-Z][a-z]+|of|and|the|to|for|&))*$""").containsMatchIn(n2) && words(n2) <= 6)) return plain
    }
    if (isAllCaps(plain) && words(plain) <= 8) return plain
    if (re("""^\*\*[^*]{3,70}\*\*$""").containsMatchIn(t) && !re("[.;,]$").containsMatchIn(plain) && words(plain) <= 8) return plain
    return null
}

private val SIGNATURE_RE = rei("""(signature\s+over\s+printed\s+name|signed\s+in\s+the\s+presence\s+of|^\s*conforme\b|^\s*(?:borrower|co-?maker|lender|creditor|debtor|guarantor|surety|witness(?:es)?|spouse)(?:'s)?\s*(?:signature)?\s*:?\s*_*\s*$|^\s*_{6,}\s*$|^\s*_{6,}\s+_{6,}\s*$|^\s*\(?\s*(?:borrower|co-?maker|lender|witness)\s*\)?\s*$)""")
private val ACK_RE = rei("""^\s*(?:#+\s*)?acknowledge?ment\b""")

// ----------------------------------------------------------------------------- parties
private enum class Party { borrower, co_maker, lender }

private val PARTY_WORD = mapOf(
    "BORROWER" to Party.borrower, "DEBTOR" to Party.borrower, "OBLIGOR" to Party.borrower, "MAKER" to Party.borrower, "PROMISSOR" to Party.borrower,
    "CO-MAKER" to Party.co_maker, "COMAKER" to Party.co_maker, "CO MAKER" to Party.co_maker, "GUARANTOR" to Party.co_maker, "SURETY" to Party.co_maker,
    "LENDER" to Party.lender, "CREDITOR" to Party.lender, "OBLIGEE" to Party.lender, "PAYEE" to Party.lender, "COMPANY" to Party.lender,
)
private val NAME_PH = mapOf(Party.borrower to "borrower_name", Party.co_maker to "co_maker_name", Party.lender to "lender_name")
private val ADDR_PH = mapOf(Party.borrower to "borrower_address", Party.co_maker to "co_maker_address", Party.lender to "lender_address")
private val ADDRESS_INTRO = rei("""(residing\s+at|resident\s+of|with\s+(?:postal\s+|residence\s+|business\s+|principal\s+(?:office\s+)?)?address\s+at|with\s+(?:principal\s+)?office(?:s)?\s+at|located\s+at|address\s*:)\s*""")

private fun partyOf(word: String): Party? {
    val w = word.uppercase().replace(Regex("\\s+"), "")
    if (w == "CO-MAKER" || w == "COMAKER" || w == "CO-MAKERS") return Party.co_maker
    return PARTY_WORD[w]
}

private val PARTY_RE = rei("""(?:hereinafter|hereafter)\s+(?:to\s+be\s+)?(?:referred\s+to|called|known)\s+(?:as\s+)?(?:the\s+)?["“'‘(]*\s*(CO-?\s?MAKER|COMAKER|BORROWER|DEBTOR|OBLIGOR|MAKER|GUARANTOR|SURETY|LENDER|CREDITOR|OBLIGEE|PAYEE|COMPANY)\b""")
private val PARTY_BEGIN = rei("""(?:\bby\s+and\s+between\b|\bbetween\b|\band\b|[;:])\s*:?\s*""")
private val NAME_START = re("""^(?:Mr\.|Mrs\.|Ms\.|Sps\.)?\s*[A-Z_]""")
private val PARTY_NAME = re("""^(\s*(?:Mr\.|Mrs\.|Ms\.|Sps\.)?\s*)([A-Z][A-Za-z.'ñÑ&-]*(?:\s+[A-Z][A-Za-z.'ñÑ&-]*){0,7}|_{3,})(?=\s*(?:,|\(|of\s+legal|a\s+(?:corporation|lending|domestic)|with\b|residing|filipino))""")
private val ARTICLES = re("^(?:The|A|An|This|That)$")
private val TRAILING_PUNCT = re("""[\s,;(“"']*$""")

/**
 * "JUAN DELA CRUZ, of legal age, ..., residing at 12 Rizal St., Pasig City, hereinafter referred to as the "BORROWER""
 * -> "{{borrower_name}}, of legal age, ..., residing at {{borrower_address}}, hereinafter referred to as the "BORROWER""
 */
private fun replaceParties(text: String, found: MutableSet<String>): String {
    val result = StringBuilder()
    var cursor = 0
    for (m in PARTY_RE.findAll(text)) {
        val party = partyOf(m.g(1))
        val start = m.range.first
        val clause = text.substring(cursor, start)
        // where this party's description begins: after "between", "and", ";" or ":" followed by a name or a blank
        var begin = 0
        for (b in PARTY_BEGIN.findAll(clause)) {
            val end = b.range.last + 1
            val next = clause.substring(end)
            if (NAME_START.containsMatchIn(next)) begin = end
        }
        var desc = clause.substring(begin)
        if (party != null) {
            val nm = PARTY_NAME.find(desc)
            if (nm != null && !ARTICLES.matches(nm.g(2))) {
                desc = nm.g(1) + ph(NAME_PH.getValue(party)) + desc.substring(nm.value.length)
                found += NAME_PH.getValue(party)
            }
            val intro = ADDRESS_INTRO.find(desc)
            if (intro != null) {
                val aStart = intro.range.last + 1
                val tail = TRAILING_PUNCT.find(desc.substring(aStart))!!.value
                val addr = desc.substring(aStart, desc.length - tail.length)
                if (addr.isNotBlank() && !addr.contains("{{")) {
                    desc = desc.substring(0, aStart) + ph(ADDR_PH.getValue(party)) + tail
                    found += ADDR_PH.getValue(party)
                }
            }
        }
        result.append(clause.substring(0, begin)).append(desc).append(m.value)
        cursor = start + m.value.length
    }
    return result.append(text.substring(cursor)).toString()
}

private val FIRST_PERSON = re("""\b(I|We),\s+((?:Mr\.|Mrs\.|Ms\.)?\s*[A-Z][A-Za-z.'ñÑ-]*(?:\s+[A-Z][A-Za-z.'ñÑ-]*){1,6}|_{3,})(\s*,)([\s\S]*?)(?=,?\s*(?:do\s+hereby|hereby|promise|jointly|bind|agree|acknowledge|declare|for\s+value)\b)""")

/** Promissory-note style: "I, MARIA SANTOS, of legal age, residing at 45 Mabini St., Marikina City, promise to pay ..." */
private fun replaceFirstPerson(text: String, found: MutableSet<String>): String =
    FIRST_PERSON.replaceFirstWith(text) { m ->
        val who = m.g(1); val name = m.g(2); val comma = m.g(3); val rest = m.g(4)
        if (name.contains("{{")) return@replaceFirstWith m.value
        found += "borrower_name"
        var r = rest
        val intro = ADDRESS_INTRO.find(r)
        if (intro != null) {
            val aStart = intro.range.last + 1
            val addr = r.substring(aStart)
            if (addr.isNotBlank() && !addr.contains("{{")) { r = r.substring(0, aStart) + ph("borrower_address"); found += "borrower_address" }
        }
        "$who, ${ph("borrower_name")}$comma$r"
    }

// ----------------------------------------------------------------------------- labelled fields
private val LABELS: List<Pair<Regex, String>> = listOf(
    rei("""name\s+of\s+(?:the\s+)?borrower|borrower(?:'s)?\s+name|borrower|debtor(?:'s)?\s+name|name\s+of\s+debtor|pangalan""") to "borrower_name",
    rei("""borrower(?:'s)?\s+address|address\s+of\s+(?:the\s+)?borrower|home\s+address|present\s+address|address|tirahan""") to "borrower_address",
    rei("""co-?maker(?:'s)?\s+name|name\s+of\s+(?:the\s+)?co-?maker|co-?maker|guarantor""") to "co_maker_name",
    rei("""co-?maker(?:'s)?\s+address""") to "co_maker_address",
    rei("""relationship(?:\s+to\s+(?:the\s+)?borrower)?""") to "co_maker_relationship",
    rei("""co-?maker(?:'s)?\s+(?:contact|mobile|cellphone|phone)(?:\s+(?:no\.?|number))?""") to "co_maker_mobile",
    rei("""(?:contact|mobile|cellphone|cell|phone|tel\.?)\s*(?:no\.?|number|#)?""") to "borrower_mobile",
    rei("""e-?mail(?:\s+address)?""") to "borrower_email",
    rei("""(?:type\s+of\s+)?(?:valid\s+)?id\s+(?:type|presented)|type\s+of\s+id""") to "borrower_id_type",
    rei("""id\s+(?:no\.?|number|#)""") to "borrower_id_number",
    rei("""lender|creditor|name\s+of\s+lender""") to "lender_name",
    rei("""(?:amount\s+of\s+(?:the\s+)?loan|loan\s+amount|principal(?:\s+amount)?(?:\s+of\s+loan)?|amount\s+(?:borrowed|financed|granted)|halaga\s+ng\s+utang)""") to "loan_amount",
    rei("""amount\s+in\s+words""") to "loan_amount_words",
    rei("""(?:interest\s+rate|rate\s+of\s+interest|interest|tubo)""") to "interest_rate",
    rei("""(?:loan\s+)?(?:term|duration|period)(?:\s+of\s+(?:the\s+)?loan)?|terms?\s+of\s+payment|no\.\s+of\s+months""") to "loan_duration",
    rei("""(?:mode|frequency)\s+of\s+payment|payment\s+(?:mode|frequency|schedule)""") to "payment_frequency",
    rei("""(?:number|no\.)\s+of\s+(?:installments|payments|amortizations)""") to "installment_count",
    rei("""(?:monthly|semi-?monthly|weekly|daily)?\s*(?:installment|amortization)(?:\s+amount)?|amount\s+(?:per|of\s+each)\s+(?:installment|payment)""") to "installment_amount",
    rei("""total\s+interest""") to "total_interest",
    rei("""processing\s+fee|service\s+(?:fee|charge)""") to "processing_fee",
    rei("""(?:total\s+)?(?:fees\s+and\s+charges|charges)""") to "total_charges",
    rei("""net\s+(?:proceeds|amount\s+(?:released|received))|amount\s+released""") to "net_proceeds",
    rei("""total\s+(?:amount\s+(?:to\s+be\s+paid|payable|due)|repayment|payable)""") to "total_repayment",
    rei("""(?:date\s+(?:of\s+)?(?:release|released)|release\s+date|date\s+granted)""") to "release_date",
    rei("""(?:first\s+(?:payment|due|installment)(?:\s+date)?|start\s+of\s+payment)""") to "first_payment_date",
    rei("""(?:maturity(?:\s+date)?|due\s+date|last\s+payment(?:\s+date)?)""") to "last_payment_date",
    rei("""(?:loan|account|reference|ref\.?)\s*(?:no\.?|number|#)""") to "loan_number",
    rei("""(?:agreement|contract)\s*(?:no\.?|number|#)""") to "agreement_number",
    rei("""^(?:date|petsa)$""") to "agreement_date",
)

private fun labelPlaceholder(label: String): String? {
    val l = label.replace("**", "").trim()
    if (l.length > 40 || words(l) > 6) return null
    for ((r, key) in LABELS) {
        val m = r.find(l)
        if (m != null && m.value.length >= l.length - 3) return key
    }
    return null
}

private val LABELLED = re("""(^|\s{2,}|\t|\|\s*|;\s*)([A-Za-z*][A-Za-z .'’()/#*-]{1,40}?)\s*[:：]\s*([^:\t|]*?)(?=\s{2,}[A-Za-z*][A-Za-z .'’()/#*-]{1,40}?\s*[:：]|\t|\s*\||$)""")

/** "Loan Amount: PHP 20,000.00    Term: 6 months" -> "Loan Amount: {{loan_amount}}    Term: {{loan_duration}}" */
private fun replaceLabelled(line: String, found: MutableSet<String>): String =
    LABELLED.replace(line) { m ->
        val pre = m.g(1); val label = m.g(2); val value = m.g(3)
        val key = labelPlaceholder(label)
        if (key == null || value.contains("{{")) return@replace m.value
        val v = value.trim()
        // keep sentences that merely start with a word and a colon (e.g. "Interest: the borrower shall ...")
        if (v.length > 80 || (words(v) > 10 && !isBlank(v))) return@replace m.value
        found += key
        "$pre${label.trim()}: ${ph(key)}"
    }

// ----------------------------------------------------------------------------- blanks & values in sentences
private val CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
/** JS `/p/.test(s)` (case-sensitive; the inputs are lower-cased like the web code). */
private fun t(p: String, s: String) = CACHE.getOrPut(p) { Regex(p) }.containsMatchIn(s)

private fun blankFor(before: String, after: String, ctx: String): String? {
    val bl = before.lowercase()
    val b = bl.substring(maxOf(0, bl.length - 90))
    val al = after.lowercase()
    val a = al.substring(0, minOf(40, al.length))
    val c = ctx.lowercase()
    if (t("""^\s*\(\s*(?:php|p|₱)""", a) || t("""^\s*pesos""", a)) return "loan_amount_words"
    if (t("""(?:php|₱|\bp)\s*\.?\s*$""", b) || t("""\(\s*(?:php|p|₱)?\s*$""", b)) {
        if (t("""install|amortiz|each\s+payment|monthly\s+payment""", b)) return "installment_amount"
        if (t("""processing|service\s+fee""", b)) return "processing_fee"
        if (t("""net\s+(?:proceeds|amount)""", b)) return "net_proceeds"
        if (t("""penalt|late|overdue|default""", c)) return null
        return "loan_amount"
    }
    if (t("""(?:sum|amount|principal|loan)\s+of\s*(?:php|₱|p)?\s*$""", b)) return "loan_amount"
    if (t("""^\s*(?:%|percent|per\s?cent)""", a)) return if (t("""penalt|late|overdue|default|surcharge""", c)) null else "interest_rate"
    if (t("""^\s*(?:\(\s*\d+\s*\)\s*)?(?:equal\s+)?(?:monthly|semi-?monthly|weekly|daily)?\s*(?:installments|amortizations|payments)""", a)) return "installment_count"
    if (t("""^\s*(?:\(\s*\d+\s*\)\s*)?(?:months?|weeks?|days?|years?)\b""", a) && t("""(?:within|for|period|term|payable\s+in|duration|of)\s*$""", b)) return "loan_duration"
    if (t("""(?:day\s+of|dated|executed\s+(?:on|this)|this|on)\s*$""", b) && t("""^\s*(?:,?\s*\d{4}|,?\s*20\s*_*|day)""", a)) return "agreement_date"
    if (t("""(?:first\s+(?:payment|installment|due))[^.]{0,30}$""", b)) return "first_payment_date"
    if (t("""(?:maturity|last\s+(?:payment|installment))[^.]{0,30}$""", b)) return "last_payment_date"
    if (t("""(?:release|released)[^.]{0,20}$""", b) && t("""date|on\s*$""", b)) return "release_date"
    if (t("""(?:residing\s+at|resident\s+of|address\s+(?:at|is)|with\s+address\s+at)\s*$""", b)) return if (t("""co-?maker|guarantor""", b)) "co_maker_address" else "borrower_address"
    if (t("""\bi\s*,\s*$""", b) || t("""(?:^|\s)i,\s*$""", b)) return "borrower_name"
    if (t("""(?:co-?maker|guarantor)(?:'s)?\s*(?:name)?\s*[:,]?\s*$""", b)) return "co_maker_name"
    if (t("""(?:borrower|debtor)(?:'s)?\s*(?:name)?\s*[:,]?\s*$""", b)) return "borrower_name"
    if (t("""(?:loan|account|reference)\s*(?:no\.?|number)\s*\.?\s*$""", b)) return "loan_number"
    return null
}

private val MONEY_KEYS = setOf("loan_amount", "installment_amount", "processing_fee", "total_charges", "net_proceeds", "total_repayment", "total_interest")
private val DATE_BLANKS = rei("""\b(?:this|on\s+the)\s+_{2,}\s*(?:st|nd|rd|th)?\s+day\s+of\s+_{2,}\s*,?\s*(?:(?:20|19)?_{2,}|\d{4})?""")
private val DURATION_UNIT = rei("""^\s*(?:\(\s*\d+\s*\)\s*)?(?:months?|weeks?|days?|years?)\b""")
private val PCT_UNIT = rei("""^\s*(?:%|percent\b|per\s?cent\b)(?:\s+(?:per|a|every)\s+(?:month|annum|year|week|day)|\s+monthly)?""")
private val PESOS_UNIT = rei("""^\s*pesos\b""")
private val CURRENCY_BEFORE = re("""(?:\bPHP|\bPhp|\bP|₱)\s*\.?\s*$""")

private fun replaceBlanks(input: String, found: MutableSet<String>, counter: IntArray): String {
    // "this ____ day of ________, 20___" is one date: the agreement date
    val text = DATE_BLANKS.replace(input) {
        found += "agreement_date"
        "on ${ph("agreement_date")}"
    }
    val out = StringBuilder()
    var last = 0
    for (m in BLANK3.findAll(text)) {
        val start = m.range.first
        val before = text.substring(0, start)
        val after = text.substring(start + m.value.length)
        val dot = if (start - 1 < 0) (if (text.startsWith(".")) 0 else -1) else text.lastIndexOf('.', start - 1)
        val sentence = text.substring(maxOf(0, dot + 1), minOf(text.length, start + 60))
        val key = blankFor(before, after, sentence)
        // "______ months" -> {{loan_duration}} already says "6 months"; drop the unit word
        var skip = 0
        if (key == "loan_duration") DURATION_UNIT.find(after)?.let { skip = it.value.length }
        if (key == "interest_rate") PCT_UNIT.find(after)?.let { skip = it.value.length }
        // the amount in words already ends with "Pesos"
        if (key == "loan_amount_words") PESOS_UNIT.find(after)?.let { skip = it.value.length }
        var lead = if (last <= start) text.substring(last, start) else ""
        // money fields already print "PHP 20,000.00": drop the currency sign written before the blank
        if (key != null && key in MONEY_KEYS) lead = CURRENCY_BEFORE.replaceFirst(lead, "")
        out.append(lead).append(if (key != null) ph(key) else BLANK)
        if (key != null) found += key else counter[0]++
        last = start + m.value.length + skip
    }
    if (last < text.length) out.append(text.substring(last))
    return out.toString()
}

private val SENTENCE_SPLIT = re("""(?<=[.;])\s+(?=[A-Z(])""")

/** Filled-in samples: replace concrete amounts / rates / dates whose meaning is clear from the sentence. */
private fun replaceValues(text: String, found: MutableSet<String>, replaced: MutableList<String>): String =
    // sentence by sentence so the context stays local
    text.split(SENTENCE_SPLIT).joinToString(" ") { sentence ->
        val s = sentence.lowercase()
        val penalty = t("""penalt|late\s+payment|overdue|in\s+default|surcharge|liquidated|attorney""", s)
        var out = sentence
        // amount in words (uppercase words ending in PESOS)
        out = WORDS_AMOUNT_RE.replace(out) { m ->
            if (penalty || t("install|amortiz", s)) return@replace m.value
            found += "loan_amount_words"; replaced += "\"${m.value.trim()}\" → amount in words"
            ph("loan_amount_words")
        }
        var usedAmount = false
        val src1 = out
        out = MONEY_RE.replace(src1) { m ->
            val offset = m.range.first
            val before = src1.substring(maxOf(0, offset - 70), offset).lowercase()
            var key: String? = null
            if (t("""install|amortiz|each\s+payment|per\s+(?:month|payment)""", before)) key = "installment_amount"
            else if (t("""processing|service\s+fee""", before)) key = "processing_fee"
            else if (t("""net\s+(?:proceeds|amount)|amount\s+released""", before)) key = "net_proceeds"
            else if (t("""total\s+(?:amount|repayment|payable)""", before)) key = "total_repayment"
            else if (!penalty && !usedAmount && t("""(?:sum|amount|principal|loan|borrow|obtain|lend|the\s+amount\s+of)""", before + s.substring(0, minOf(200, s.length)))) {
                key = "loan_amount"; usedAmount = true
            }
            if (key == null) return@replace m.value
            found += key; replaced += "${m.value.trim()} → ${key.replace('_', ' ')}"
            ph(key)
        }
        out = PCT_RE.replace(out) { m ->
            if (penalty || !t("interest|tubo", s) || t("""effective|annual\s+percentage""", s)) return@replace m.value
            if ("interest_rate" in found && !t("""interest\s+rate|rate\s+of\s+interest|interest\s+of|interest\s+at""", s)) return@replace m.value
            found += "interest_rate"; replaced += "${m.value.trim()} → interest rate"
            ph("interest_rate")
        }
        val src2 = out
        out = DATE_RE.replace(src2) { m ->
            val offset = m.range.first
            val before = src2.substring(maxOf(0, offset - 60), offset).lowercase()
            var key: String? = null
            if (t("""first\s+(?:payment|installment|due)""", before)) key = "first_payment_date"
            else if (t("""maturity|last\s+(?:payment|installment)|fully\s+paid\s+(?:on|by)""", before)) key = "last_payment_date"
            else if (t("releas", before)) key = "release_date"
            else if (t("""(?:executed|signed|entered\s+into|made|dated|this|day\s+of|on)\s*(?:this\s*)?$""", before) || t("^(?:this|made|executed)", s)) key = "agreement_date"
            if (key == null) return@replace m.value
            found += key; replaced += "${m.value.trim()} → ${key.replace('_', ' ')}"
            ph(key)
        }
        out
    }

// ----------------------------------------------------------------------------- main
private val FIELD_LINE = re("""^(?:\*\*)?[A-Za-z][A-Za-z .'’()/#-]{1,40}(?:\*\*)?\s*[:：]\s*\S""")
private val PAGE_FURNITURE = rei("""^\s*(?:page\s+\d+(?:\s+of\s+\d+)?|\d{1,3}|-\s*\d+\s*-)\s*$""")
private val TITLE_WORDS = rei("""\b(?:loan\s+agreement|promissory\s+note|agreement|contract|terms\s+and\s+conditions|disclosure|kasunduan|kontrata)\b""")
private val NOT_TITLE = rei("""hereinafter|between|this\s+\w+\s+(?:is|made)""")
private val AND_LINE = rei("""^[-–—\s]*and[-–—\s]*$""")
private val LIST_LINE = rei("""^(?:(\d{1,2})[.)]|\(?([a-z]|[ivx]{1,4})\)|[-•*▪●]\s)\s*(.*)$""")
private val PROMISE_TO_PAY = re("""(promise\s+to\s+pay\s+(?:to\s+)?(?:the\s+order\s+of\s+)?)([A-Z][A-Za-z.&'-]*(?:\s+[A-Z][A-Za-z.&'-]*){0,6}?(?:,?\s+(?:Inc|Corp|Co|Corporation|Company)\.?)?)(?=\s+(?:the|or|at|on|in)\b|[,.;(])""")

/**
 * @param soft  lines may be soft-wrapped (PDF / plain text): join them into paragraphs.
 *              Word files give one line per paragraph already.
 */
fun convertTermsText(input: String, fileName: String = "terms", soft: Boolean = true): TermsDraft {
    val notes = mutableListOf<String>()
    val found = LinkedHashSet<String>()
    val replaced = mutableListOf<String>()
    val counter = intArrayOf(0)

    var lines = input.replace(Regex("\\r\\n?"), "\n").replace(' ', ' ').replace(Regex("(?m)[ \\t]+$"), "").split("\n")
    // page furniture repeated by PDFs
    lines = lines.filter { !PAGE_FURNITURE.containsMatchIn(it) }

    // ---- title & letterhead
    var title = "Loan Agreement"
    val firstLines = lines.mapIndexed { i, l -> l.trim() to i }.filter { it.first.isNotEmpty() }.take(14)
    val tl = firstLines.firstOrNull { (l, _) ->
        l.replace(Regex("[#*]"), "").trim().length <= 80 && TITLE_WORDS.containsMatchIn(l) && !NOT_TITLE.containsMatchIn(l)
    }
    if (tl != null) {
        val (l, ti) = tl
        title = titleCase(l.replaceFirst(Regex("^#+\\s*"), "").replace("**", "").trim())
        val before = lines.subList(0, ti).filter { it.isNotBlank() }
        val letterhead = before.all { it.length < 120 }
        if (before.isNotEmpty() && letterhead) notes += "Removed the letterhead above the title — LendFlow prints your business name and address at the top."
        lines = if (letterhead) lines.subList(ti + 1, lines.size).toList() else lines.subList(0, ti) + lines.subList(ti + 1, lines.size)
    }

    // ---- signature area & notarial acknowledgment
    val n = lines.size
    var sigStart = -1
    for (i in (n * 0.4).toInt() until n) {
        if (SIGNATURE_RE.containsMatchIn(lines[i])) {
            // the signature area: nothing but short lines (names, labels, blanks) after it, or an acknowledgment
            val after = lines.subList(i, n)
            val longClauses = after.filter { it.length > 160 && !ACK_RE.containsMatchIn(it) }
            val ackAt = after.indexOfFirst { ACK_RE.containsMatchIn(it) }
            val clausesBeforeAck = if (ackAt == -1) longClauses.size else after.subList(0, ackAt).count { it.length > 160 }
            if (clausesBeforeAck == 0) { sigStart = i; break }
        }
    }
    if (sigStart != -1) {
        // keep the witness clause ("IN WITNESS WHEREOF, ...") that often sits on the line right above
        val tail = lines.subList(sigStart, lines.size)
        if (tail.any { ACK_RE.containsMatchIn(it) }) notes += "Removed the notarial acknowledgment with the signature area. Add the acknowledgment text back at the end if you have agreements notarized."
        lines = lines.subList(0, sigStart).toList()
        notes += "Replaced the signature lines with LendFlow's signature section (borrower, co-maker, lender and witnesses are printed automatically)."
    } else {
        notes += "Could not find a signature area — LendFlow adds its signature section at the end."
    }

    // ---- join soft-wrapped lines into paragraphs (PDF), classify headings and lists
    val blocks = mutableListOf<String>()
    var para = ""
    fun flush() { if (para.isNotBlank()) blocks += para.trim(); para = "" }
    val wide = re("""\s{3,}|\t""")
    val endsSentence = re("""[.:;!?]["”)]?$""")
    val startsLower = re("^[a-z(]")
    val numberedDecimal = re("""^\d{1,2}[.)]\d""")
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) { flush(); blocks += ""; continue }
        // "- and -" between the parties
        if (AND_LINE.containsMatchIn(line)) { flush(); blocks += "and"; continue }
        // an all-caps preamble such as "KNOW ALL MEN BY THESE PRESENTS:" stays text, in bold
        if (isAllCaps(line) && line.endsWith(":") && line.length <= 80) { flush(); blocks += "**${line.replace("**", "")}**"; continue }
        val h = headingText(line)
        if (h != null) { flush(); blocks += listOf("", "## ${if (isAllCaps(h)) titleCase(h) else h}", ""); continue }
        val list = LIST_LINE.find(line)
        if (list != null && !numberedDecimal.containsMatchIn(line)) {
            flush()
            val body = list.g(3).trim()
            para = if (list.has(1) && list.g(1).isNotEmpty()) "${list.g(1)}. $body" else "- $body"
            continue
        }
        val joinable = soft && para.isNotEmpty() && !FIELD_LINE.containsMatchIn(line) && !FIELD_LINE.containsMatchIn(para) &&
            !wide.containsMatchIn(line) && !wide.containsMatchIn(para)
        if (joinable && (!endsSentence.containsMatchIn(para) || startsLower.containsMatchIn(line))) para += " $line"
        else { flush(); para = line }
    }
    flush()

    // ---- disclosure statement / payment schedule sections
    var out = mutableListOf<String>()
    var skipping: String? = null
    var hasDisclosure = false
    var hasSchedule = false
    for (b in blocks) {
        if (b.startsWith("## ")) {
            skipping = null
            val h = b.lowercase()
            out += b
            if (t("disclosure", h)) { skipping = "disclosure"; hasDisclosure = true; out += listOf("", ph("disclosure_table")) }
            else if (t("""(?:schedule|amortization|amortisation)\b""", h) && !t("penalt", h)) { skipping = "schedule"; hasSchedule = true; out += listOf("", ph("repayment_schedule")) }
            continue
        }
        if (skipping != null) {
            // keep explanatory sentences, drop the table rows and filled-in figures
            val digits = b.count { it.isDigit() && it in '0'..'9' }
            if (b.isNotEmpty() && b.length > 60 && digits < b.length * 0.08 && !isBlank(b)) out += b
            continue
        }
        out += b
    }

    // ---- placeholders
    out = out.map { b ->
        if (b.isEmpty() || b.startsWith("## ") || b.startsWith("{{")) return@map b
        var x = replaceParties(b, found)
        x = replaceFirstPerson(x, found)
        // promissory notes: "promise to pay ABC Lending Inc." names the lender
        x = PROMISE_TO_PAY.replaceFirstWith(x) { m ->
            if (re("""^(?:The|Said)\b""").containsMatchIn(m.g(2))) return@replaceFirstWith m.value
            found += "lender_name"
            m.g(1) + ph("lender_name")
        }
        x = replaceLabelled(x, found)
        x = replaceBlanks(x, found, counter)
        x = replaceValues(x, found, replaced)
        x
    }.toMutableList()

    if (!hasDisclosure) out += listOf("", "## Disclosure Statement", "", ph("disclosure_table"))
    if (!hasSchedule) out += listOf("", "## Repayment Schedule", "", ph("repayment_schedule"))
    if (!hasDisclosure || !hasSchedule) {
        val parts = listOfNotNull(if (!hasDisclosure) "a Disclosure Statement" else null, if (!hasSchedule) "a Repayment Schedule" else null)
        notes += "Added ${parts.joinToString(" and ")} section — LendFlow fills ${if (!hasDisclosure && !hasSchedule) "them" else "it"} in for every loan."
    }
    out += listOf("", ph("signature_block"))

    val body = out.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").replaceFirst(Regex("^\\n+"), "").trim()

    // ---- notes for the lender
    val labels = mapOf(
        "borrower_name" to "borrower name", "borrower_address" to "borrower address", "co_maker_name" to "co-maker name", "co_maker_address" to "co-maker address",
        "lender_name" to "lender name", "lender_address" to "lender address", "loan_amount" to "loan amount", "loan_amount_words" to "amount in words",
        "interest_rate" to "interest rate", "loan_duration" to "loan term", "installment_count" to "number of installments", "installment_amount" to "installment amount",
        "agreement_date" to "agreement date", "first_payment_date" to "first payment date", "last_payment_date" to "last payment date", "release_date" to "release date",
    )
    val got = found.map { labels[it] ?: it.replace('_', ' ') }
    if (got.isNotEmpty()) notes.add(0, "Filled in automatically for every loan: ${got.joinToString(", ")}.")
    else notes.add(0, "No loan details were recognised. Use the field buttons above the text to insert them where needed.")
    val left = counter[0]
    if (left > 0) notes += "$left blank${if (left == 1) "" else "s"} (________) could not be matched to a loan detail. Replace each with a field from the buttons above the text, or type the wording you want."
    if (replaced.isNotEmpty()) notes += "Values replaced from your sample: ${replaced.take(8).joinToString("; ")}${if (replaced.size > 8) "; …" else ""}. Check that each one is right."
    if (rei("""penalt|late\s+payment|overdue""").containsMatchIn(body) && re("""\d\s?%""").containsMatchIn(body)) {
        notes += "Penalty percentages were kept exactly as written. They should match your Late payments settings (or replace them with {{penalty_terms}})."
    }

    val suggested = if (title == "Loan Agreement") {
        fileName.replaceFirst(rei("""\.[a-z0-9]+$"""), "").replace(Regex("[_-]+"), " ").trim().take(60).ifEmpty { "Imported terms" }
    } else title
    return TermsDraft(title = title, suggestedName = suggested, body = body, notes = notes)
}
