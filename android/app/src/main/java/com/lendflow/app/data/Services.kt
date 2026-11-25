package com.lendflow.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.lendflow.app.util.addDays
import java.security.SecureRandom

// Native ports of the web app's service layer (src/services/*.ts). Same tables, views, RPCs,
// Edge Functions and storage buckets — business rules stay in the database for both clients.

/** A file chosen on the device (photo, ID, proof of payment, template document). */
class PickedFile(val name: String, val mime: String?, val bytes: ByteArray) {
    val size: Int get() = bytes.size
}

private fun searchParts(s: String, vararg cols: String, mobile: Boolean = false): String {
    val parts = cols.map { "$it.ilike.%$s%" }.toMutableList()
    if (mobile) {
        val digits = s.filter { it.isDigit() }
        if (digits.length >= 4) parts += "mobile_number.ilike.%${digits.removePrefix("0")}%"
    }
    return parts.joinToString(",")
}

// ---------------------------------------------------------------------------
// auth, organization & settings
// ---------------------------------------------------------------------------
object AuthService {
    suspend fun signIn(email: String, password: String) = Supa.signIn(email, password)
    suspend fun resetPassword(email: String) = Supa.resetPasswordForEmail(email)
    suspend fun updatePassword(password: String) = Supa.updateUser(password = password, data = mapOf("must_change_password" to false))
    suspend fun signOut() = Supa.signOut()
}

object OrgService {
    suspend fun myAccess(): Row = Supa.rpcObj("my_access")
    suspend fun get(): Row? = Supa.from("organizations").select("*").maybeSingle()
    suspend fun update(id: String, patch: Map<String, Any?>): Row = Supa.from("organizations").eq("id", id).updateSingle(patch)
    suspend fun uploadLogo(orgId: String, file: PickedFile): String {
        val path = "$orgId/${safeFileName(file.name)}"
        Supa.upload("branding", path, file.bytes, file.mime, upsert = true)
        update(orgId, mapOf("logo_path" to path))
        return path
    }
    suspend fun logoUrl(path: String) = Supa.signedUrl("branding", path, 3600)
    suspend fun getSettings(): Row = Supa.rpcObj("get_settings")
    suspend fun updateSettings(section: String, values: Map<String, Any?>): Row =
        Supa.rpcObj("update_settings", mapOf("p_section" to section, "p_values" to values))
    suspend fun profile(): Row? {
        val uid = Supa.session.value?.userId ?: return null
        return Supa.from("user_profiles").select("*").eq("id", uid).maybeSingle()
    }
    suspend fun updateMyProfile(fullName: String, phone: String?) =
        Supa.rpc("update_my_profile", mapOf("p_full_name" to fullName, "p_phone" to phone))
    /** Owner only: loads realistic sample borrowers, loans and payments. */
    suspend fun seedDemoData(): Row = Supa.rpcObj("seed_demo_data")
}

object UsersService {
    /** user_profiles joined with their role (StaffMember on the web). */
    suspend fun list(): List<Row> {
        val profiles = Supa.from("user_profiles").select("*").order("full_name").get()
        val roles = Supa.from("user_roles").select("user_id, role").get()
        val map = roles.associate { it.str("user_id") to it.strOrNull("role") }
        return profiles.filter { it.has("organization_id") }.map { it.with("role", map[it.str("id")]) }
    }
    suspend fun create(email: String, password: String, fullName: String, role: String, phone: String?) =
        Supa.invokeObj("admin-users", mapOf("action" to "create", "email" to email, "password" to password, "full_name" to fullName, "role" to role, "phone" to phone))
    suspend fun resetPassword(userId: String, password: String) =
        Supa.invoke("admin-users", mapOf("action" to "reset_password", "user_id" to userId, "password" to password))
    suspend fun setRole(userId: String, role: String) = Supa.rpc("set_user_role", mapOf("p_user_id" to userId, "p_role" to role))
    suspend fun setActive(userId: String, active: Boolean) = Supa.rpc("set_user_active", mapOf("p_user_id" to userId, "p_active" to active))
    suspend fun rolePermissions(): Map<String, List<String>> {
        val rows = Supa.from("role_permissions").select("role, permission").get()
        val out = linkedMapOf<String, MutableList<String>>("owner" to mutableListOf(), "admin" to mutableListOf(), "loan_officer" to mutableListOf(), "collector" to mutableListOf())
        rows.forEach { out.getOrPut(it.str("role")) { mutableListOf() }.add(it.str("permission")) }
        return out
    }
    suspend fun setRolePermissions(role: String, permissions: List<String>) =
        Supa.rpc("set_role_permissions", mapOf("p_role" to role, "p_permissions" to permissions))
    suspend fun resetRolePermissions(role: String) = Supa.rpc("reset_role_permissions", mapOf("p_role" to role))
}

object NotificationsService {
    suspend fun list(): List<Row> = Supa.from("notifications").select("*").order("created_at", false).limit(30).get()
    suspend fun markRead(ids: List<String>? = null) = Supa.rpc("mark_notifications_read", mapOf("p_ids" to ids))
}

object AuditService {
    suspend fun list(table: String? = null, action: String? = null, search: String? = null, from: String? = null, to: String? = null, page: Int = 0, pageSize: Int = 50): Supa.Page {
        val q = Supa.from("audit_logs").select("*").order("created_at", false)
        if (!table.isNullOrBlank()) q.eq("table_name", table)
        if (!action.isNullOrBlank()) q.eq("action", action)
        if (!from.isNullOrBlank()) q.gte("created_at", "${from}T00:00:00+08:00")
        if (!to.isNullOrBlank()) q.lte("created_at", "${to}T23:59:59+08:00")
        if (!search.isNullOrBlank()) q.or("actor_name.ilike.%$search%,record_id.ilike.%$search%")
        return q.page(page, pageSize).getPage()
    }
}

// ---------------------------------------------------------------------------
// borrowers
// ---------------------------------------------------------------------------
object BorrowersService {
    suspend fun list(search: String? = null, status: String? = null, risk: String? = null, page: Int = 0, pageSize: Int = 25): Supa.Page {
        val q = Supa.from("borrowers").select("*, loans(id, status, total_outstanding)").order("created_at", false)
        if (!status.isNullOrBlank() && status != "all") q.eq("status", status)
        else if (status.isNullOrBlank()) q.neq("status", "archived")
        if (!risk.isNullOrBlank() && risk != "all") q.eq("risk_rating", risk)
        if (!search.isNullOrBlank()) {
            val s = cleanSearch(search)
            q.or(searchParts(s, "full_name", "borrower_code", "email", mobile = true))
        }
        return q.page(page, pageSize).getPage()
    }

    suspend fun search(term: String, limit: Int = 8): List<Row> {
        val s = cleanSearch(term)
        val q = Supa.from("borrowers").select("*").eq("status", "active").order("full_name").limit(limit)
        if (s.isNotBlank()) q.or(searchParts(s, "full_name", "borrower_code", mobile = true))
        return q.get()
    }

    suspend fun get(id: String): Row = Supa.from("borrowers").select("*").eq("id", id).single()

    /** null when the user may not see sensitive data */
    suspend fun identity(id: String): Row? = try {
        Supa.from("borrower_identities").select("*").eq("borrower_id", id).maybeSingle()
    } catch (_: Exception) { null }

    suspend fun create(input: Map<String, Any?>, identity: Map<String, Any?>? = null, guarantor: Map<String, Any?>? = null): Row {
        val borrower = Supa.from("borrowers").insertSingle(input)
        if (!(identity?.get("id_type") as? String).isNullOrBlank() && !(identity?.get("id_number") as? String).isNullOrBlank()) {
            Supa.from("borrower_identities").insertSingle(identity!! + ("borrower_id" to borrower.str("id")))
        }
        if (!(guarantor?.get("full_name") as? String).isNullOrBlank()) {
            Supa.from("guarantors").insertSingle(guarantor!! + ("borrower_id" to borrower.str("id")))
        }
        return borrower
    }

    suspend fun update(id: String, input: Map<String, Any?>): Row = Supa.from("borrowers").eq("id", id).updateSingle(input)

    suspend fun saveIdentity(borrowerId: String, identity: Map<String, Any?>): Row {
        val existing = Supa.from("borrower_identities").select("borrower_id").eq("borrower_id", borrowerId).maybeSingle()
        return if (existing != null) Supa.from("borrower_identities").eq("borrower_id", borrowerId).updateSingle(identity)
        else Supa.from("borrower_identities").insertSingle(identity + ("borrower_id" to borrowerId))
    }

    suspend fun setStatus(id: String, status: String) = update(id, mapOf("status" to status))

    suspend fun checkDuplicates(firstName: String, lastName: String, dateOfBirth: String?, mobile: String?, idType: String?, idNumber: String?, exclude: String?): List<Row> =
        Supa.rpcList("check_borrower_duplicates", mapOf(
            "p_first_name" to firstName, "p_last_name" to lastName, "p_date_of_birth" to dateOfBirth?.ifBlank { null },
            "p_mobile" to (mobile ?: ""), "p_id_type" to idType?.ifBlank { null }, "p_id_number" to idNumber?.ifBlank { null }, "p_exclude" to exclude,
        ))

    /** category: profile | identity | documents */
    suspend fun uploadBorrowerFile(orgId: String, borrowerId: String, category: String, file: PickedFile): String {
        val path = "$orgId/$borrowerId/$category/${safeFileName(file.name)}"
        Supa.upload("borrower-files", path, file.bytes, file.mime)
        return path
    }
    suspend fun fileUrl(path: String, seconds: Int = 600) = Supa.signedUrl("borrower-files", path, seconds)

    suspend fun documents(borrowerId: String): List<Row> =
        Supa.from("borrower_documents").select("*").eq("borrower_id", borrowerId).order("created_at", false).get()

    /** Sensitive documents (e.g. ATM cards) go to the 'identity' folder, readable only with borrowers.view_sensitive. */
    suspend fun addDocument(orgId: String, borrowerId: String, file: PickedFile, documentType: String, title: String?, category: String = "documents"): Row {
        val path = uploadBorrowerFile(orgId, borrowerId, category, file)
        return Supa.from("borrower_documents").insertSingle(mapOf(
            "borrower_id" to borrowerId, "document_type" to documentType, "title" to (title?.ifBlank { null } ?: file.name), "file_path" to path,
            "file_name" to file.name, "mime_type" to file.mime, "file_size" to file.size,
        ))
    }
    suspend fun deleteDocument(doc: Row) {
        Supa.from("borrower_documents").eq("id", doc.str("id")).delete()
        Supa.removeFiles("borrower-files", listOf(doc.str("file_path")))
    }

    suspend fun guarantors(borrowerId: String): List<Row> = Supa.from("guarantors").select("*").eq("borrower_id", borrowerId).order("created_at").get()
    suspend fun saveGuarantor(borrowerId: String, g: Map<String, Any?>): Row {
        val id = g["id"] as? String
        return if (!id.isNullOrBlank()) Supa.from("guarantors").eq("id", id).updateSingle(g - "id")
        else Supa.from("guarantors").insertSingle((g - "id") + ("borrower_id" to borrowerId))
    }
    suspend fun deleteGuarantor(id: String) = Supa.from("guarantors").eq("id", id).delete()

    suspend fun loans(borrowerId: String): List<Row> = Supa.from("v_loans").select("*").eq("borrower_id", borrowerId).order("created_at", false).get()
    suspend fun agreements(borrowerId: String): List<Row> = Supa.from("loan_agreements")
        .select("id, loan_id, agreement_number, version, kind, status, generated_at, fully_signed_at, unsigned_pdf_path, current_pdf_path, signed_pdf_path, signed_pdf_hash, loans!inner(loan_number, borrower_id)")
        .eq("loans.borrower_id", borrowerId).order("created_at", false).get()
    suspend fun notes(borrowerId: String): List<Row> = Supa.from("loan_notes").select("*, author:user_profiles(full_name)").eq("borrower_id", borrowerId)
        .order("created_at", false).limit(200).get()
    suspend fun payments(borrowerId: String): List<Row> = Supa.from("loan_payments").select("*, loans(loan_number)").eq("borrower_id", borrowerId)
        .order("payment_date", false).order("created_at", false).get()
}

// ---------------------------------------------------------------------------
// loans & products
// ---------------------------------------------------------------------------
val LOAN_PIPELINE = listOf("draft", "pending_review", "approved", "awaiting_borrower_signature", "awaiting_lender_signature", "fully_signed", "ready_for_release")

object LoansService {
    suspend fun preview(p: Map<String, Any?>): Row = Supa.rpcObj("preview_loan", mapOf("p" to p))
    suspend fun create(p: Map<String, Any?>): Row = Supa.rpcObj("create_loan", mapOf("p" to p))
    suspend fun update(id: String, p: Map<String, Any?>): Row = Supa.rpcObj("update_loan", mapOf("p_loan_id" to id, "p" to p))
    suspend fun submit(id: String) = Supa.rpc("submit_loan", mapOf("p_loan_id" to id))
    suspend fun approve(id: String, notes: String?): Row = Supa.rpcObj("approve_loan", mapOf("p_loan_id" to id, "p_notes" to notes?.ifBlank { null }))
    suspend fun reject(id: String, reason: String) = Supa.rpc("reject_loan", mapOf("p_loan_id" to id, "p_reason" to reason))
    suspend fun cancel(id: String, reason: String) = Supa.rpc("cancel_loan", mapOf("p_loan_id" to id, "p_reason" to reason))
    suspend fun release(id: String, releaseDate: String, method: String, reference: String?): Row =
        Supa.rpcObj("release_loan", mapOf("p_loan_id" to id, "p" to mapOf("release_date" to releaseDate, "method" to method, "reference" to reference)))
    suspend fun markDefault(id: String, reason: String) = Supa.rpc("mark_loan_default", mapOf("p_loan_id" to id, "p_reason" to reason))
    suspend fun reinstate(id: String, reason: String) = Supa.rpc("reinstate_loan", mapOf("p_loan_id" to id, "p_reason" to reason))
    suspend fun close(id: String) = Supa.rpc("close_loan", mapOf("p_loan_id" to id))
    suspend fun assignStaff(id: String, officer: String?, collector: String?) =
        Supa.rpc("assign_loan_staff", mapOf("p_loan_id" to id, "p_loan_officer" to officer, "p_collector" to collector))
    suspend fun settlementQuote(id: String, asOf: String? = null): Row =
        Supa.rpcObj("get_settlement_quote", mapOf("p_loan_id" to id, "p_as_of" to asOf?.ifBlank { null }))
    suspend fun reschedule(id: String, p: Map<String, Any?>): Row = Supa.rpcObj("reschedule_loan", mapOf("p_loan_id" to id, "p" to p))
    suspend fun adjust(loanId: String, installmentId: String, type: String, amount: Double, reason: String) =
        Supa.rpc("apply_loan_adjustment", mapOf("p" to mapOf("loan_id" to loanId, "installment_id" to installmentId, "type" to type, "amount" to amount, "reason" to reason)))

    /** order: null (newest), "overdue", "next_due" */
    suspend fun list(statuses: List<String>? = null, search: String? = null, collectorId: String? = null, overdueOnly: Boolean = false, page: Int = 0, pageSize: Int = 25, order: String? = null): Supa.Page {
        val q = Supa.from("v_loans").select("*")
        if (!statuses.isNullOrEmpty()) q.inList("status", statuses)
        if (!collectorId.isNullOrBlank()) q.eq("collector_id", collectorId)
        if (overdueOnly) q.gt("overdue_count", 0)
        if (!search.isNullOrBlank()) {
            val s = cleanSearch(search)
            q.or("loan_number.ilike.%$s%,borrower_name.ilike.%$s%,borrower_code.ilike.%$s%")
        }
        when (order) {
            "overdue" -> q.order("days_overdue", false)
            "next_due" -> q.order("next_due_date", true, nullsFirst = false)
            else -> q.order("created_at", false)
        }
        return q.page(page, pageSize).getPage()
    }

    suspend fun get(id: String): Row = Supa.from("v_loans").select("*").eq("id", id).single()
    suspend fun raw(id: String): Row = Supa.from("loans").select("*").eq("id", id).single()
    suspend fun installments(loanId: String): List<Row> = Supa.from("v_installments").select("*").eq("loan_id", loanId).order("installment_no").get()
    suspend fun payments(loanId: String): List<Row> = Supa.from("loan_payments").select("*").eq("loan_id", loanId)
        .order("payment_date", false).order("created_at", false).get()
    suspend fun ledger(loanId: String): List<Row> = Supa.from("financial_ledger").select("*").eq("loan_id", loanId).order("entry_no").get()
    suspend fun notes(loanId: String): List<Row> = Supa.from("loan_notes").select("*, author:user_profiles(full_name)").eq("loan_id", loanId)
        .order("created_at", false).get()
    suspend fun addNote(loanId: String?, borrowerId: String?, body: String, noteType: String, promiseDate: String? = null, promiseAmount: Double? = null): Row =
        Supa.from("loan_notes").insertSingle(mapOf(
            "loan_id" to loanId, "borrower_id" to borrowerId, "body" to body, "note_type" to noteType,
            "promise_date" to promiseDate, "promise_amount" to promiseAmount, "created_by" to Supa.session.value?.userId,
        ))
    suspend fun snapshots(loanId: String): List<Row> = Supa.from("loan_terms_snapshots").select("id, version, kind, terms_hash, reason, created_at, summary")
        .eq("loan_id", loanId).order("version", false).get()
    suspend fun snapshotSchedule(snapshotId: String): Row = Supa.from("loan_terms_snapshots").select("schedule, summary").eq("id", snapshotId).single()
    suspend fun auditHistory(loanId: String): List<Row> = Supa.from("audit_logs").select("*").eq("table_name", "loans").eq("record_id", loanId)
        .order("created_at", false).limit(100).get()
    suspend fun application(loanId: String): Row? = Supa.from("loan_applications").select("*").eq("loan_id", loanId).maybeSingle()
}

object ProductsService {
    suspend fun list(activeOnly: Boolean = false): List<Row> {
        val q = Supa.from("loan_products").select("*").order("name")
        if (activeOnly) q.eq("is_active", true)
        return q.get()
    }
    suspend fun save(p: Map<String, Any?>): Row {
        val id = p["id"] as? String
        val rest = p - "id"
        return if (!id.isNullOrBlank()) Supa.from("loan_products").eq("id", id).updateSingle(rest) else Supa.from("loan_products").insertSingle(rest)
    }
    suspend fun setActive(id: String, active: Boolean) = save(mapOf("id" to id, "is_active" to active))
}

// ---------------------------------------------------------------------------
// payments, collections & reports
// ---------------------------------------------------------------------------
object PaymentsService {
    /** p: loan_id, amount, payment_date, method, method_detail?, reference_no?, proof_path?, notes?, payment_type?,
     *  installment_id?, received_by?, atm_withdrawal?, idempotency_key */
    suspend fun record(p: Map<String, Any?>): Row = Supa.rpcObj("record_payment", mapOf("p" to p))
    suspend fun reverse(id: String, reason: String): Row = Supa.rpcObj("reverse_payment", mapOf("p_payment_id" to id, "p_reason" to reason))
    suspend fun uploadProof(orgId: String, loanId: String, file: PickedFile): String {
        val path = "$orgId/$loanId/${safeFileName(file.name)}"
        Supa.upload("payment-proofs", path, file.bytes, file.mime)
        return path
    }
    suspend fun proofUrl(path: String) = Supa.signedUrl("payment-proofs", path, 600)
    /** { url, receipt_number } */
    suspend fun receiptPdf(paymentId: String, download: Boolean = false): Row =
        Supa.invokeObj("receipt-pdf", mapOf("payment_id" to paymentId, "download" to download))
    suspend fun receipt(paymentId: String): Row? = Supa.from("payment_receipts").select("*").eq("payment_id", paymentId).maybeSingle()

    suspend fun list(from: String? = null, to: String? = null, method: String? = null, status: String? = null, search: String? = null, collectorId: String? = null, page: Int = 0, pageSize: Int = 25): Supa.Page {
        val q = Supa.from("loan_payments")
            .select("*, loans(loan_number), borrowers(full_name, borrower_code), payment_receipts(receipt_number, is_void), receiver:user_profiles!loan_payments_received_by_fkey(full_name)")
            .order("payment_date", false).order("created_at", false)
        if (!from.isNullOrBlank()) q.gte("payment_date", from)
        if (!to.isNullOrBlank()) q.lte("payment_date", to)
        if (!method.isNullOrBlank() && method != "all") q.eq("method", method)
        if (!status.isNullOrBlank() && status != "all") q.eq("status", status)
        if (!collectorId.isNullOrBlank()) q.eq("received_by", collectorId)
        if (!search.isNullOrBlank()) {
            val s = cleanSearch(search)
            q.or("payment_number.ilike.%$s%,reference_no.ilike.%$s%")
        }
        return q.page(page, pageSize).getPage()
    }

    suspend fun get(id: String): Row = Supa.from("loan_payments")
        .select("*, loans(loan_number, status), borrowers(full_name, borrower_code, mobile_number), payment_receipts(*), payment_allocations(*, loan_installments(installment_no, due_date))")
        .eq("id", id).single()

    suspend fun collectionsSummary(): Row = Supa.rpcObj("collections_summary")

    /** view: due_today | upcoming | overdue | partially_paid | paid */
    suspend fun installments(view: String, today: String, days: Int = 14, search: String? = null, collectorId: String? = null, from: String? = null, to: String? = null, page: Int = 0, pageSize: Int = 50): Supa.Page {
        val q = Supa.from("v_installments").select("*").inList("loan_status", listOf("active", "defaulted", "completed"))
        when (view) {
            "due_today" -> q.eq("due_date", today).inList("status", listOf("pending", "partially_paid")).order("borrower_name")
            "upcoming" -> q.gt("due_date", today).lte("due_date", addDays(today, days)).inList("status", listOf("pending", "partially_paid"))
                .order("due_date").order("borrower_name")
            "overdue" -> q.lt("due_date", today).inList("status", listOf("pending", "partially_paid")).order("due_date")
            "partially_paid" -> q.eq("status", "partially_paid").order("due_date")
            "paid" -> {
                q.eq("status", "paid").order("paid_at", false)
                if (!from.isNullOrBlank()) q.gte("paid_at", from)
                if (!to.isNullOrBlank()) q.lte("paid_at", to)
            }
        }
        if (!collectorId.isNullOrBlank()) q.eq("collector_id", collectorId)
        if (!search.isNullOrBlank()) {
            val s = cleanSearch(search)
            q.or("loan_number.ilike.%$s%,borrower_name.ilike.%$s%,borrower_code.ilike.%$s%")
        }
        return q.page(page, pageSize).getPage()
    }

    suspend fun openInstallments(loanId: String): List<Row> = Supa.from("v_installments").select("*").eq("loan_id", loanId)
        .inList("status", listOf("pending", "partially_paid")).order("installment_no").get()

    suspend fun projectedPenalty(installmentId: String, asOf: String): Double =
        Supa.rpc("get_projected_penalty", mapOf("p_installment" to installmentId, "p_as_of" to asOf)).toNum()

    /** Loans with overdue installments; collectorId "none" = unassigned. */
    suspend fun overdueLoans(search: String? = null, collectorId: String? = null, minDays: Int? = null, maxDays: Int? = null, page: Int = 0, pageSize: Int = 25): Supa.Page {
        val q = Supa.from("v_loans").select("*").inList("status", listOf("active", "defaulted")).gt("overdue_count", 0)
            .order("days_overdue", false).order("overdue_amount", false)
        if (minDays != null) q.gte("days_overdue", minDays)
        if (maxDays != null) q.lte("days_overdue", maxDays)
        if (collectorId == "none") q.isNull("collector_id") else if (!collectorId.isNullOrBlank()) q.eq("collector_id", collectorId)
        if (!search.isNullOrBlank()) {
            val s = cleanSearch(search)
            q.or("loan_number.ilike.%$s%,borrower_name.ilike.%$s%,borrower_code.ilike.%$s%")
        }
        return q.page(page, pageSize).getPage()
    }
}

object ReportsService {
    suspend fun dashboard(from: String? = null, to: String? = null): Row = Supa.rpcObj("dashboard_summary", mapOf("p_from" to from?.ifBlank { null }, "p_to" to to?.ifBlank { null }))
    /** { report, from, to, generated_at, columns[{key,label,type,total}], rows[], totals{} } */
    suspend fun get(report: String, from: String, to: String, filters: Map<String, Any?> = emptyMap()): Row =
        Supa.rpcObj("get_report", mapOf("p_report" to report, "p_from" to from, "p_to" to to, "p_filters" to filters))
}

// ---------------------------------------------------------------------------
// simplified lending & payment records
// ---------------------------------------------------------------------------
object SimplifiedService {
    suspend fun borrowerStatus(borrowerId: String): Row = Supa.rpcObj("simplified_borrower_status", mapOf("p_borrower_id" to borrowerId))
    /** p: transaction_type, borrower_id, principal, term_months?, term_count?, repayment_frequency?, pay_days?, first_due_date?, interest_rate?, purpose? */
    suspend fun preview(p: Map<String, Any?>): Row = Supa.rpcObj("preview_simplified_loan", mapOf("p" to p))
    suspend fun create(p: Map<String, Any?>): Row = Supa.rpcObj("create_simplified_loan", mapOf("p" to p))
    suspend fun renewals(loanId: String): List<Row> = Supa.from("loan_renewals")
        .select("*, old_loan:loans!loan_renewals_old_loan_id_fkey(loan_number, status), new_loan:loans!loan_renewals_new_loan_id_fkey(loan_number, status)")
        .or("new_loan_id.eq.$loanId,old_loan_id.eq.$loanId").order("created_at").get()
}

val RECORD_LOAN_STATUSES = listOf("active", "defaulted", "completed")

object RecordsService {
    suspend fun searchBorrowers(term: String, limit: Int = 10): List<Row> {
        val s = cleanSearch(term)
        val q = Supa.from("borrowers").select("*").order("full_name").limit(limit)
        if (s.isNotBlank()) q.or(searchParts(s, "full_name", "borrower_code", mobile = true))
        return q.get()
    }
    suspend fun borrower(id: String): Row = Supa.from("borrowers").select("*").eq("id", id).single()
    suspend fun loans(borrowerId: String): List<Row> = Supa.from("v_loans").select("*").eq("borrower_id", borrowerId)
        .inList("status", RECORD_LOAN_STATUSES).order("created_at", false).get()
    suspend fun installments(loanId: String): List<Row> = Supa.from("v_installments").select("*").eq("loan_id", loanId).order("installment_no").get()
    suspend fun allocations(loanId: String): List<Row> = Supa.from("payment_allocations")
        .select("installment_id, payment_id, total_amount, interest_rebate, is_reversal, loan_payments(payment_number, payment_date, status)")
        .eq("loan_id", loanId).order("created_at").get()
    suspend fun payments(loanId: String): List<Row> = Supa.from("loan_payments")
        .select("*, payment_receipts(receipt_number, is_void), payment_allocations(is_reversal, loan_installments(installment_no))")
        .eq("loan_id", loanId).order("payment_date", false).order("created_at", false).get()
}

// ---------------------------------------------------------------------------
// agreements, templates & signing
// ---------------------------------------------------------------------------
object TemplatesService {
    /** templates with their current version embedded as `current` */
    suspend fun list(): List<Row> = Supa.from("loan_agreement_templates")
        .select("*, current:loan_agreement_template_versions!loan_agreement_templates_current_version_fk(*)")
        .order("is_default", false).order("name").get()
    /** { template, versions } */
    suspend fun get(id: String): Row {
        val t = Supa.from("loan_agreement_templates").select("*").eq("id", id).single()
        val versions = Supa.from("loan_agreement_template_versions").select("*").eq("template_id", id).order("version_no", false).get()
        return jsonOf("template" to t, "versions" to versions)
    }
    /** p: id?, name, description?, title, body, settings?, change_note?, is_default?, is_active? */
    suspend fun save(p: Map<String, Any?>): Row = Supa.rpcObj("save_agreement_template", mapOf("p" to p))
    suspend fun duplicate(id: String, name: String? = null): Row = Supa.rpcObj("duplicate_agreement_template", mapOf("p_template_id" to id, "p_name" to name))
    suspend fun setDefault(id: String) = Supa.rpc("set_default_agreement_template", mapOf("p_template_id" to id))
    suspend fun setActive(id: String, active: Boolean) {
        Supa.from("loan_agreement_templates").eq("id", id).update(mapOf("is_active" to active), returning = false)
    }
}

object AgreementsService {
    suspend fun list(status: String? = null, search: String? = null, page: Int = 0, pageSize: Int = 25): Supa.Page {
        val q = Supa.from("loan_agreements").select("*, loans!inner(loan_number, borrower_id, status, borrowers(full_name))").order("created_at", false)
        if (!status.isNullOrBlank() && status != "all") q.eq("status", status)
        if (!search.isNullOrBlank()) q.or("agreement_number.ilike.%${cleanSearch(search)}%")
        return q.page(page, pageSize).getPage()
    }
    suspend fun forLoan(loanId: String): List<Row> = Supa.from("loan_agreements").select("*").eq("loan_id", loanId).order("version", false).get()
    suspend fun get(id: String): Row = Supa.from("loan_agreements").select("*").eq("id", id).single()
    suspend fun sessions(agreementId: String): List<Row> = Supa.from("agreement_signing_sessions").select("*").eq("agreement_id", agreementId).order("created_at", false).get()
    suspend fun signatures(agreementId: String): List<Row> = Supa.from("agreement_signatures").select("*").eq("agreement_id", agreementId).order("signed_at").get()
    suspend fun events(agreementId: String): List<Row> = Supa.from("agreement_audit_events").select("*").eq("agreement_id", agreementId).order("created_at").get()
    /** { id, agreement_number, status, missing_placeholders[] } */
    suspend fun generate(loanId: String): Row = Supa.invokeObj("agreement-generate", mapOf("loan_id" to loanId))
    suspend fun void(id: String, reason: String) = Supa.rpc("agreement_void", mapOf("p_agreement_id" to id, "p_reason" to reason))
    /** { session_id, token, expires_at, verification_method, signer_name } */
    suspend fun createLink(agreementId: String, role: String, signerName: String? = null, contact: String? = null, expiryHours: Int? = null): Row =
        Supa.rpcObj("signing_session_create", mapOf("p_agreement_id" to agreementId, "p_signer_role" to role, "p_signer_name" to signerName, "p_contact" to contact, "p_expiry_hours" to expiryHours))
    suspend fun revokeLink(sessionId: String) = Supa.rpc("signing_session_revoke", mapOf("p_session_id" to sessionId))
    /** Staff signatures, or the borrower signing in person on this device (inPerson = true). signature = PNG data URL */
    suspend fun signInApp(agreementId: String, role: String, signature: String, signerName: String? = null, inPerson: Boolean = false): Row =
        Supa.invokeObj("agreement-sign", mapOf("action" to "sign", "consent" to true, "agreement_id" to agreementId, "role" to role, "signature" to signature, "signer_name" to signerName, "in_person" to inPerson))
    suspend fun rebuild(agreementId: String) = Supa.invoke("agreement-sign", mapOf("action" to "rebuild", "agreement_id" to agreementId))
    suspend fun pdfUrl(path: String, download: String? = null) = Supa.signedUrl("agreements", path, 900, download)
    suspend fun signatureUrl(path: String) = Supa.signedUrl("agreements", path, 600)
    /** The borrower opens this on their own phone (the web signing page). */
    fun signingLink(token: String) = "${Supa.webUrl}/sign/$token"
}

/** Public signing page (no login): the anon key is used as the bearer when signed out. */
object PublicSigning {
    suspend fun load(token: String): Row = Supa.invokeObj("public-signing", mapOf("action" to "load", "token" to token))
    suspend fun verify(token: String, answer: String): Row = Supa.invokeObj("public-signing", mapOf("action" to "verify", "token" to token, "answer" to answer))
    suspend fun document(token: String, nonce: String): Row = Supa.invokeObj("public-signing", mapOf("action" to "document", "token" to token, "nonce" to nonce))
    suspend fun sign(token: String, nonce: String, signature: String, typedName: String): Row =
        Supa.invokeObj("public-signing", mapOf("action" to "sign", "token" to token, "nonce" to nonce, "signature" to signature, "consent" to true, "typed_name" to typedName))
    suspend fun download(token: String, nonce: String): Row = Supa.invokeObj("public-signing", mapOf("action" to "download", "token" to token, "nonce" to nonce))
}

// ---------------------------------------------------------------------------
// platform (super admin) console
// ---------------------------------------------------------------------------
object PlatformService {
    suspend fun overview(): Row = Supa.rpcObj("platform_overview")
    suspend fun listOrganizations(): List<Row> = Supa.rpcList("platform_list_organizations")
    suspend fun getOrganization(id: String): Row = Supa.rpcObj("platform_get_organization", mapOf("p_org" to id))
    suspend fun updateOrganization(id: String, patch: Map<String, String?>): Row = Supa.rpcObj("platform_update_organization", mapOf("p_org" to id, "p" to patch))
    suspend fun setStatus(id: String, status: String, reason: String?): Row =
        Supa.rpcObj("platform_set_organization_status", mapOf("p_org" to id, "p_status" to status, "p_reason" to reason))
    suspend fun setLendingMode(id: String, mode: String): Row = Supa.rpcObj("platform_set_lending_mode", mapOf("p_org" to id, "p_mode" to mode))
    suspend fun listTemplates(orgId: String): List<Row> = Supa.rpcList("platform_list_templates", mapOf("p_org" to orgId))
    /** { template, versions } */
    suspend fun getTemplate(orgId: String, id: String): Row = Supa.rpcObj("platform_get_template", mapOf("p_org" to orgId, "p_template_id" to id))
    suspend fun saveTemplate(orgId: String, p: Map<String, Any?>): Row = Supa.rpcObj("platform_save_template", mapOf("p_org" to orgId, "p" to p))
    suspend fun setDefaultTemplate(orgId: String, id: String) = Supa.rpc("platform_set_default_template", mapOf("p_org" to orgId, "p_template_id" to id))
    suspend fun setTemplateActive(orgId: String, id: String, active: Boolean) =
        Supa.rpc("platform_set_template_active", mapOf("p_org" to orgId, "p_template_id" to id, "p_active" to active))
    /** organization: name, legal_name?, registration_no?, tin?, address?, city?, province?, contact_phone?, contact_email?, lending_mode?
     *  owner: full_name, email, phone?, password */
    suspend fun createAccount(organization: Map<String, Any?>, owner: Map<String, Any?>): Row =
        Supa.invokeObj("platform-admin", mapOf("action" to "create_account", "organization" to organization, "owner" to owner))
    suspend fun addUser(organizationId: String, fullName: String, email: String, phone: String?, role: String, password: String): Row =
        Supa.invokeObj("platform-admin", mapOf("action" to "add_user", "organization_id" to organizationId, "full_name" to fullName, "email" to email, "phone" to phone, "role" to role, "password" to password))
    suspend fun resetPassword(userId: String, password: String): Row =
        Supa.invokeObj("platform-admin", mapOf("action" to "reset_password", "user_id" to userId, "password" to password))
}

/** Strong random temporary password (no look-alike characters). */
fun generatePassword(length: Int = 14): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%"
    val rnd = SecureRandom()
    return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

@Suppress("unused") private fun keep(e: JsonElement, o: JsonObject) = e to o
