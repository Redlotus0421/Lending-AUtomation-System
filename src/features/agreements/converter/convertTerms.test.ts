import { describe, expect, it } from 'vitest'
import { PLACEHOLDERS } from '../../../../supabase/functions/_shared/template'
import { convertTermsText } from './convertTerms'

const known = new Set(PLACEHOLDERS.map((p) => p.key))
const placeholders = (body: string) => [...body.matchAll(/\{\{([a-z0-9_]+)\}\}/g)].map((m) => m[1])

const BLANK_FORM = `GOLDEN PESO LENDING CORPORATION
123 Rizal Avenue, Quezon City
Tel. 8123-4567

LOAN AGREEMENT

KNOW ALL MEN BY THESE PRESENTS:

This Loan Agreement is made and entered into this ______ day of ____________, 20____ at Quezon City, by and between:

GOLDEN PESO LENDING CORPORATION, a corporation duly organized under Philippine laws, with principal office at 123 Rizal Avenue, Quezon City, hereinafter referred to as the "LENDER";

- and -

______________________, of legal age, Filipino, residing at ______________________________, hereinafter referred to as the "BORROWER".

1. LOAN AMOUNT
The LENDER agrees to lend the BORROWER the sum of ______________________ PESOS (PHP ____________), Philippine currency.

2. INTEREST
The loan shall earn interest at the rate of ______% per month computed on the principal.

3. TERM AND PAYMENT
The BORROWER shall pay the loan within ______ months in ______ equal semi-monthly installments of PHP __________ each, starting on the first payment date.

4. PENALTY
Any installment not paid on its due date shall be charged a penalty of 5% per month until fully paid.

5. CO-MAKER
The co-maker, ______________________, residing at ______________________, binds himself jointly and severally with the BORROWER.

IN WITNESS WHEREOF, the parties have signed this agreement on the date and place first written above.

______________________          ______________________
BORROWER                        LENDER

______________________
CO-MAKER

Signed in the presence of:
______________________          ______________________
`

const FILLED = `PROMISSORY NOTE

For value received, I, MARIA SANTOS, of legal age, residing at 45 Mabini Street, Marikina City, promise to pay ABC Lending Inc. the amount of PHP 25,000.00 (TWENTY FIVE THOUSAND PESOS) on or before the due dates below.

Loan Amount: PHP 25,000.00    Interest Rate: 3% per month
Term: 6 months    Monthly Amortization: PHP 4,916.67
Release Date: November 20, 2026

The loan shall bear interest at 3% per month. A late payment penalty of 2% per month applies to unpaid installments.

SCHEDULE OF PAYMENTS
1  Dec 20, 2026  4,916.67  20,833.33
2  Jan 20, 2027  4,916.67  16,666.66

Signature over printed name
MARIA SANTOS
`

describe('built-in terms converter', () => {
  it('converts a blank loan agreement form into a template', () => {
    const d = convertTermsText(BLANK_FORM, 'golden-peso-loan.docx')
    expect(d.title).toBe('Loan Agreement')
    expect(d.body).toContain('**KNOW ALL MEN BY THESE PRESENTS:**')
    expect(d.body).not.toMatch(/^- and/m)
    // letterhead and title are removed from the body; signature area replaced
    expect(d.body).not.toContain('GOLDEN PESO LENDING CORPORATION\n123')
    expect(d.body).not.toMatch(/^Tel\./m)
    expect(d.body).not.toMatch(/Signed in the presence of/)
    expect(d.body.trim().endsWith('{{signature_block}}')).toBe(true)
    expect(d.body).toContain('IN WITNESS WHEREOF')
    // parties and blanks
    expect(d.body).toContain('{{lender_name}}, a corporation')
    expect(d.body).toContain('with principal office at {{lender_address}}')
    expect(d.body).toContain('{{borrower_name}}, of legal age, Filipino, residing at {{borrower_address}}')
    expect(d.body).toContain('the sum of {{loan_amount_words}}')
    expect(d.body).toContain('the sum of {{loan_amount_words}} ({{loan_amount}}), Philippine currency')
    expect(d.body).toContain('at the rate of {{interest_rate}} computed')
    expect(d.body).toContain('within {{loan_duration}} in {{installment_count}} equal semi-monthly installments of {{installment_amount}} each')
    expect(d.body).toContain('The co-maker, {{co_maker_name}}, residing at {{co_maker_address}}')
    expect(d.body).toContain('made and entered into on {{agreement_date}} at Quezon City')
    // the penalty clause is kept as written
    expect(d.body).toContain('penalty of 5% per month')
    // headings and added sections
    expect(d.body).toMatch(/^## 1\. Loan Amount$/m)
    expect(d.body).toContain('## Disclosure Statement\n\n{{disclosure_table}}')
    expect(d.body).toContain('## Repayment Schedule\n\n{{repayment_schedule}}')
    for (const k of placeholders(d.body)) expect(known.has(k)).toBe(true)
    expect(d.notes.join(' ')).toMatch(/Filled in automatically/)
  })

  it('replaces values in a filled-in sample and turns its schedule into the schedule table', () => {
    const d = convertTermsText(FILLED, 'note.pdf')
    expect(d.title).toBe('Promissory Note')
    expect(d.body).toContain('I, {{borrower_name}}, of legal age, residing at {{borrower_address}}') // "I, NAME" with an address
    expect(d.body).toContain('promise to pay {{lender_name}} the amount')
    expect(d.body).toContain('Loan Amount: {{loan_amount}}')
    expect(d.body).toContain('Interest Rate: {{interest_rate}}')
    expect(d.body).toContain('Term: {{loan_duration}}')
    expect(d.body).toContain('Monthly Amortization: {{installment_amount}}')
    expect(d.body).toContain('Release Date: {{release_date}}')
    expect(d.body).toContain('the amount of {{loan_amount}} ({{loan_amount_words}})')
    expect(d.body).toContain('penalty of 2% per month')
    expect(d.body).toContain('## Schedule of Payments\n\n{{repayment_schedule}}')
    expect(d.body).not.toContain('4,916.67  20,833.33')
    expect(d.body).not.toContain('Signature over printed name')
    for (const k of placeholders(d.body)) expect(known.has(k)).toBe(true)
  })
})
