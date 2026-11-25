package com.lendflow.app.util

import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import com.lendflow.app.ui.components.cleanMoney
import com.lendflow.app.ui.components.groupMoney
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same expectations as the web helpers (src/lib/format.ts, _shared/format.ts, MoneyInput). */
class FormatTest {
    @Test fun peso_formats_like_the_web() {
        assertEquals("₱50,000.00", peso(50000))
        assertEquals("₱1,234.50", peso("1234.5"))
        assertEquals("-₱20.00", peso(-20))
        assertEquals("₱0.00", peso(null))
        assertEquals("₱1.2M", peso(1_234_567, compact = true))
    }

    @Test fun money_for_documents() {
        assertEquals("PHP 60,000.00", money(60000))
        assertEquals("-PHP 5.25", money(-5.25))
    }

    @Test fun dates_and_months() {
        assertEquals("Jan 5, 2026", fmtDate("2026-01-05"))
        assertEquals("January 5, 2026", fmtDate("2026-01-05", "long"))
        assertEquals("2026-02-28", addMonths("2026-01-31", 1))
        assertEquals("2024-02-29", addMonths("2024-01-31", 1))
        assertEquals("2026-03-01", addDays("2026-02-28", 1))
        assertEquals("2026-02-01", startOfMonth("2026-02-17"))
    }

    @Test fun manila_timestamps() {
        // 2026-01-05T16:30:00Z is 00:30 on Jan 6 in Manila
        assertEquals("Jan 6, 2026", fmtDate("2026-01-05T16:30:00+00:00"))
        assertEquals("Jan 6, 2026", fmtDate("2026-01-05T16:30:00.123456+00"))
    }

    @Test fun words_and_mobile_numbers() {
        assertEquals("Fifty Thousand Pesos", amountInWords(50000))
        assertEquals("One Thousand Two Hundred Pesos and 50/100", amountInWords(1200.5))
        assertEquals("One Peso", amountInWords(1))
        assertEquals("+639171234567", normalizePhMobile("0917 123 4567"))
        assertEquals("0917 123 4567", phMobile("+639171234567"))
        assertEquals("JD", initials("Juan dela Cruz Doe"))
    }

    @Test fun durations() {
        assertEquals("10 months", durationLabel(10, "monthly"))
        assertEquals("1 week", durationLabel(1, "weekly"))
        assertEquals("6 semi-monthly payments (3 months)", durationLabel(6, "semimonthly"))
    }

    @Test fun money_input_cleaning() {
        assertEquals("20000", cleanMoney("20,000"))
        assertEquals("1234.56", cleanMoney("₱1,234.567"))
        assertEquals("5", cleanMoney("005"))
        assertEquals("1,234,567.5", groupMoney("1234567.5"))
        assertEquals("12.", groupMoney("12."))
        assertEquals(1234.5, parseMoney("₱1,234.50")!!, 0.0001)
    }

    @Test fun json_helpers_accept_numeric_strings() {
        val r = jsonOf("a" to "1234.50", "b" to 7, "c" to null)
        assertEquals(1234.5, r.num("a"), 0.0)
        assertEquals(7.0, r.num("b"), 0.0)
        assertEquals(0.0, r.num("c"), 0.0)
        assertEquals("7", r.str("b"))
    }
}
