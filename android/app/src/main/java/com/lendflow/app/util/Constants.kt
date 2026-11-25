package com.lendflow.app.util

// Port of src/lib/constants.ts

data class Opt(val value: String, val label: String)

enum class Tone { slate, amber, blue, violet, teal, green, red, navy }

data class StatusInfo(val label: String, val tone: Tone)

val PH_ID_TYPES = listOf(
    "PhilSys National ID (PhilID / ePhilID)", "Philippine Passport", "Driver's License (LTO)", "UMID", "SSS ID", "GSIS eCard",
    "PRC ID", "Postal ID", "Voter's ID / Certification", "PhilHealth ID", "TIN ID", "Senior Citizen ID", "PWD ID",
    "OFW ID (iDOLE)", "Barangay ID / Clearance", "Company ID", "Other government-issued ID",
)

val PH_REGIONS = listOf(
    "NCR — National Capital Region", "CAR — Cordillera Administrative Region", "Region I — Ilocos Region", "Region II — Cagayan Valley",
    "Region III — Central Luzon", "Region IV-A — CALABARZON", "MIMAROPA Region", "Region V — Bicol Region", "Region VI — Western Visayas",
    "NIR — Negros Island Region", "Region VII — Central Visayas", "Region VIII — Eastern Visayas", "Region IX — Zamboanga Peninsula",
    "Region X — Northern Mindanao", "Region XI — Davao Region", "Region XII — SOCCSKSARGEN", "Region XIII — Caraga",
    "BARMM — Bangsamoro Autonomous Region",
)

val EMPLOYMENT_TYPES = listOf("Employed (private)", "Employed (government)", "Self-employed / Business owner", "OFW", "Freelancer", "Pensioner", "Unemployed", "Other")

val RELATIONSHIPS = listOf("Spouse", "Parent", "Child", "Sibling", "Relative", "Friend", "Co-worker", "Employer", "Other")

val DOCUMENT_TYPES = listOf(
    Opt("proof_of_income", "Proof of income (payslip, ITR, COE)"),
    Opt("proof_of_billing", "Proof of billing / address"),
    Opt("bank_statement", "Bank statement"),
    Opt("business_permit", "Business permit / DTI"),
    Opt("collateral", "Collateral document"),
    Opt("cedula", "Community tax certificate (cedula)"),
    Opt("atm_card", "ATM card"),
    Opt("government_id", "Government ID"),
    Opt("other", "Other"),
)

val PAYMENT_METHODS = listOf(
    Opt("cash", "Cash"),
    Opt("bank_transfer", "Bank transfer"),
    Opt("e_wallet", "E-wallet (GCash, Maya…)"),
    Opt("check", "Check"),
    Opt("other", "Other"),
)

/** Payment methods that only the system posts (shown in history, never offered in forms) */
val SYSTEM_PAYMENT_METHODS = mapOf("renewal" to "Renewal offset")

fun paymentMethodLabel(v: String?): String =
    PAYMENT_METHODS.firstOrNull { it.value == v }?.label ?: SYSTEM_PAYMENT_METHODS[v] ?: titleCase(v)

val LOAN_STATUS = mapOf(
    "draft" to StatusInfo("Draft", Tone.slate),
    "pending_review" to StatusInfo("Pending review", Tone.amber),
    "approved" to StatusInfo("Approved", Tone.blue),
    "awaiting_borrower_signature" to StatusInfo("Awaiting borrower signature", Tone.violet),
    "awaiting_lender_signature" to StatusInfo("Awaiting lender signature", Tone.violet),
    "fully_signed" to StatusInfo("Fully signed", Tone.teal),
    "ready_for_release" to StatusInfo("Ready for release", Tone.teal),
    "active" to StatusInfo("Active", Tone.green),
    "rejected" to StatusInfo("Rejected", Tone.red),
    "cancelled" to StatusInfo("Cancelled", Tone.slate),
    "completed" to StatusInfo("Completed", Tone.navy),
    "defaulted" to StatusInfo("Defaulted", Tone.red),
)

val INSTALLMENT_STATUS = mapOf(
    "upcoming" to StatusInfo("Upcoming", Tone.slate),
    "pending" to StatusInfo("Upcoming", Tone.slate),
    "due_today" to StatusInfo("Due today", Tone.amber),
    "partially_paid" to StatusInfo("Partially paid", Tone.blue),
    "paid" to StatusInfo("Paid", Tone.green),
    "overdue" to StatusInfo("Overdue", Tone.red),
    "rescheduled" to StatusInfo("Rescheduled", Tone.violet),
    "waived" to StatusInfo("Waived", Tone.teal),
)

val AGREEMENT_STATUS = mapOf(
    "generating" to StatusInfo("Generating", Tone.slate),
    "failed" to StatusInfo("Failed", Tone.red),
    "pending_signatures" to StatusInfo("Awaiting signatures", Tone.amber),
    "partially_signed" to StatusInfo("Partially signed", Tone.violet),
    "fully_signed" to StatusInfo("Fully signed", Tone.green),
    "superseded" to StatusInfo("Superseded", Tone.slate),
    "voided" to StatusInfo("Voided", Tone.red),
)

val ROLE_LABEL = mapOf("owner" to "Owner", "admin" to "Administrator", "loan_officer" to "Loan officer", "collector" to "Collector")
val ROLES = listOf("owner", "admin", "loan_officer", "collector")

data class Permission(val key: String, val label: String, val group: String)

val PERMISSIONS = listOf(
    Permission("dashboard.view", "View dashboard", "General"),
    Permission("reports.view", "View financial reports", "General"),
    Permission("borrowers.view", "View assigned borrowers", "Borrowers"),
    Permission("borrowers.view_all", "View all borrowers", "Borrowers"),
    Permission("borrowers.create", "Register borrowers", "Borrowers"),
    Permission("borrowers.edit", "Edit borrowers", "Borrowers"),
    Permission("borrowers.archive", "Archive / blacklist borrowers", "Borrowers"),
    Permission("borrowers.view_sensitive", "View IDs and sensitive data", "Borrowers"),
    Permission("loans.view", "View assigned loans", "Loans"),
    Permission("loans.view_all", "View all loans", "Loans"),
    Permission("loans.create", "Create loan applications", "Loans"),
    Permission("loans.approve", "Approve / reject loans", "Loans"),
    Permission("loans.release", "Release funds (activate)", "Loans"),
    Permission("loans.cancel", "Cancel loans", "Loans"),
    Permission("loans.manage", "Reschedule, default, close loans", "Loans"),
    Permission("agreements.view", "View agreements", "Agreements"),
    Permission("agreements.generate", "Generate agreements & signing links", "Agreements"),
    Permission("agreements.sign_lender", "Sign as lender", "Agreements"),
    Permission("payments.view", "View payments", "Payments"),
    Permission("payments.record", "Record payments", "Payments"),
    Permission("payments.reverse", "Reverse payments", "Payments"),
    Permission("payments.adjust", "Waivers & adjustments", "Payments"),
    Permission("collections.assign", "Assign collectors", "Payments"),
    Permission("products.manage", "Manage loan products", "Administration"),
    Permission("templates.manage", "Manage agreement templates", "Administration"),
    Permission("users.manage", "Manage users & permissions", "Administration"),
    Permission("settings.manage", "Manage system settings", "Administration"),
    Permission("audit.view", "View audit logs", "Administration"),
)

val PENALTY_TYPES = listOf(
    Opt("percent_monthly", "% per month of unpaid amount (accrues daily)"),
    Opt("percent_daily", "% per day of unpaid amount"),
    Opt("percent_once", "One-time % of unpaid installment"),
    Opt("fixed", "One-time fixed fee per late installment"),
)

data class FrequencyOpt(val value: String, val label: String, val unit: String)

val FREQUENCIES = listOf(
    FrequencyOpt("monthly", "Monthly", "months"),
    FrequencyOpt("semimonthly", "Semi-monthly (twice a month)", "semi-monthly payments"),
    FrequencyOpt("weekly", "Weekly", "weeks"),
    FrequencyOpt("daily", "Daily", "days"),
)

val RATE_PERIODS = listOf(
    Opt("monthly", "per month"),
    Opt("yearly", "per year"),
    Opt("weekly", "per week"),
    Opt("daily", "per day"),
)
