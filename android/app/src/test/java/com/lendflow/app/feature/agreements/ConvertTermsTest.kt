package com.lendflow.app.feature.agreements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of src/features/agreements/converter/convertTerms.test.ts */
class ConvertTermsTest {
    private val known = PLACEHOLDERS.map { it.key }.toSet()
    private fun placeholders(body: String) = Regex("\\{\\{([a-z0-9_]+)\\}\\}").findAll(body).map { it.groupValues[1] }.toList()
    private fun contains(body: String, s: String) = assertTrue("expected body to contain: $s\n---\n$body", body.contains(s))

    private val blankForm = """GOLDEN PESO LENDING CORPORATION
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
"""

    private val filled = """PROMISSORY NOTE

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
"""

    @Test
    fun convertsABlankLoanAgreementFormIntoATemplate() {
        val d = convertTermsText(blankForm, "golden-peso-loan.docx")
        val b = d.body
        assertEquals("Loan Agreement", d.title)
        contains(b, "**KNOW ALL MEN BY THESE PRESENTS:**")
        assertFalse(Regex("(?m)^- and").containsMatchIn(b))
        // letterhead and title are removed from the body; signature area replaced
        assertFalse(b.contains("GOLDEN PESO LENDING CORPORATION\n123"))
        assertFalse(Regex("(?m)^Tel\\.").containsMatchIn(b))
        assertFalse(b.contains("Signed in the presence of"))
        assertTrue(b.trim().endsWith("{{signature_block}}"))
        contains(b, "IN WITNESS WHEREOF")
        // parties and blanks
        contains(b, "{{lender_name}}, a corporation")
        contains(b, "with principal office at {{lender_address}}")
        contains(b, "{{borrower_name}}, of legal age, Filipino, residing at {{borrower_address}}")
        contains(b, "the sum of {{loan_amount_words}}")
        contains(b, "the sum of {{loan_amount_words}} ({{loan_amount}}), Philippine currency")
        contains(b, "at the rate of {{interest_rate}} computed")
        contains(b, "within {{loan_duration}} in {{installment_count}} equal semi-monthly installments of {{installment_amount}} each")
        contains(b, "The co-maker, {{co_maker_name}}, residing at {{co_maker_address}}")
        contains(b, "made and entered into on {{agreement_date}} at Quezon City")
        // the penalty clause is kept as written
        contains(b, "penalty of 5% per month")
        // headings and added sections
        assertTrue(Regex("(?m)^## 1\\. Loan Amount$").containsMatchIn(b))
        contains(b, "## Disclosure Statement\n\n{{disclosure_table}}")
        contains(b, "## Repayment Schedule\n\n{{repayment_schedule}}")
        for (k in placeholders(b)) assertTrue("unknown placeholder $k", k in known)
        assertTrue(d.notes.joinToString(" ").contains("Filled in automatically"))
    }

    @Test
    fun replacesValuesInAFilledInSampleAndTurnsItsScheduleIntoTheScheduleTable() {
        val d = convertTermsText(filled, "note.pdf")
        val b = d.body
        assertEquals("Promissory Note", d.title)
        contains(b, "I, {{borrower_name}}, of legal age, residing at {{borrower_address}}")
        contains(b, "promise to pay {{lender_name}} the amount")
        contains(b, "Loan Amount: {{loan_amount}}")
        contains(b, "Interest Rate: {{interest_rate}}")
        contains(b, "Term: {{loan_duration}}")
        contains(b, "Monthly Amortization: {{installment_amount}}")
        contains(b, "Release Date: {{release_date}}")
        contains(b, "the amount of {{loan_amount}} ({{loan_amount_words}})")
        contains(b, "penalty of 2% per month")
        contains(b, "## Schedule of Payments\n\n{{repayment_schedule}}")
        assertFalse(b.contains("4,916.67  20,833.33"))
        assertFalse(b.contains("Signature over printed name"))
        for (k in placeholders(b)) assertTrue("unknown placeholder $k", k in known)
    }
}
