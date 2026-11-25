package com.lendflow.app.feature.borrowers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.app.Route
import com.lendflow.app.app.Router
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Row as JRow
import com.lendflow.app.data.has
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.EMPLOYMENT_TYPES
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PH_ID_TYPES
import com.lendflow.app.util.PH_REGIONS
import com.lendflow.app.util.RELATIONSHIPS
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.trimNumber
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

// Port of src/features/borrowers/BorrowerFormPage.tsx

private val PH_MOBILE = Regex("^\\+639\\d{9}$")
private const val MOBILE_MSG = "Enter a valid PH mobile number (09XX XXX XXXX)"
private val EMAIL = Regex("^(?!\\.)(?!.*\\.\\.)([A-Za-z0-9_'+\\-.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$")

private fun isPhMobile(v: String) = PH_MOBILE.matches(normalizePhMobile(v.trim()))

private fun age(dob: String): Int? {
    val d = try { LocalDate.parse(dob.take(10)) } catch (_: Exception) { return null }
    val now = LocalDate.now(ZoneOffset.UTC)
    var a = now.year - d.year
    val m = now.monthValue - d.monthValue
    if (m < 0 || (m == 0 && now.dayOfMonth < d.dayOfMonth)) a--
    return a
}

private val FIELDS = listOf(
    "first_name", "middle_name", "last_name", "suffix", "date_of_birth", "gender", "civil_status", "mobile_number", "email",
    "address_line", "barangay", "city_municipality", "province", "region", "zip_code", "occupation", "employer_name",
    "employment_type", "monthly_income", "emergency_contact_name", "emergency_contact_relationship", "emergency_contact_number",
    "internal_notes", "risk_rating", "assessment_notes", "id_type", "id_number", "id_expiry",
    "guarantor_name", "guarantor_relationship", "guarantor_mobile", "guarantor_address", "guarantor_occupation",
)

private fun blankValues(): Map<String, String> = FIELDS.associateWith { if (it == "risk_rating") "unrated" else "" }

private fun maxLen(n: Int) = "String must contain at most $n character(s)"

/** The zod schema of the web form. */
private fun validate(v: Map<String, String>): Map<String, String> {
    val e = linkedMapOf<String, String>()
    fun t(k: String) = (v[k] ?: "").trim()
    if (t("first_name").isEmpty()) e["first_name"] = "First name is required" else if (t("first_name").length > 80) e["first_name"] = maxLen(80)
    if (t("middle_name").length > 80) e["middle_name"] = maxLen(80)
    if (t("last_name").isEmpty()) e["last_name"] = "Last name is required" else if (t("last_name").length > 80) e["last_name"] = maxLen(80)
    if (t("suffix").length > 10) e["suffix"] = maxLen(10)
    val dob = v["date_of_birth"].orEmpty()
    if (dob.isNotEmpty()) {
        val a = age(dob)
        if (a == null || a < 18 || a > 100) e["date_of_birth"] = "Borrower must be between 18 and 100 years old"
    }
    if (t("mobile_number").isEmpty()) e["mobile_number"] = "Mobile number is required"
    else if (!isPhMobile(t("mobile_number"))) e["mobile_number"] = MOBILE_MSG
    if (t("email").isNotEmpty() && !EMAIL.matches(t("email"))) e["email"] = "Enter a valid email"
    if (t("zip_code").isNotEmpty() && !Regex("^\\d{4}$").matches(t("zip_code"))) e["zip_code"] = "ZIP codes have 4 digits"
    val inc = v["monthly_income"].orEmpty()
    if (inc.isNotEmpty()) {
        val n = inc.replace(",", "").trim().toDoubleOrNull()
        if (n == null || n < 0) e["monthly_income"] = "Enter a valid amount"
    }
    if (t("emergency_contact_number").isNotEmpty() && !isPhMobile(t("emergency_contact_number"))) e["emergency_contact_number"] = MOBILE_MSG
    if (t("guarantor_mobile").isNotEmpty() && !isPhMobile(t("guarantor_mobile"))) e["guarantor_mobile"] = MOBILE_MSG
    // object-level refinements run only when every field is valid (zod)
    if (e.isEmpty()) {
        if (v["id_type"].orEmpty().isNotEmpty() && t("id_number").isEmpty()) e["id_number"] = "Enter the ID number"
        if (t("id_number").isNotEmpty() && v["id_type"].orEmpty().isEmpty()) e["id_type"] = "Select the ID type"
    }
    return e
}

private fun n(v: String?): String? = v?.trim()?.ifEmpty { null }

private class Existing(val b: JRow, val identity: JRow?, val guarantor: JRow?)

private data class DupKey(val fn: String, val ln: String, val dob: String, val mobile: String, val idType: String, val idNumber: String)

/** Go back to [path] when it is the previous screen, else open it (Link to={path} on the web). */
internal fun Router.backTo(path: String) {
    val prev = stack.getOrNull(stack.size - 2)
    if (prev != null && prev.substringBefore('?') == path.substringBefore('?')) back() else go(path)
}

private val GENDERS = listOf(Opt("", "Prefer not to say"), Opt("male", "Male"), Opt("female", "Female"), Opt("other", "Other"))
private val CIVIL = listOf(Opt("", "—"), Opt("single", "Single"), Opt("married", "Married"), Opt("widowed", "Widowed"), Opt("separated", "Separated"), Opt("annulled", "Annulled"))
private val RISKS = listOf(Opt("unrated", "Unrated"), Opt("low", "Low risk"), Opt("medium", "Medium risk"), Opt("high", "High risk"))
private fun withBlank(blank: String, items: List<String>) = listOf(Opt("", blank)) + items.map { Opt(it, it) }

/** id = null: register a new borrower */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BorrowerFormScreen(id: String?) {
    val isEdit = id != null
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val canSensitive = auth.can("borrowers.view_sensitive")
    val canSupervise = auth.can("borrowers.view_all")
    val safeReturn = remember {
        val ret = Route(router.current).param("return")
        if (ret != null && ret.startsWith("/") && !ret.startsWith("//")) ret else null
    }

    val existing = rememberLoad(id, live = false, enabled = isEdit) {
        if (id == null) null
        else Existing(
            BorrowersService.get(id),
            if (canSensitive) BorrowersService.identity(id) else null,
            BorrowersService.guarantors(id).firstOrNull(),
        )
    }

    val values = remember { mutableStateMapOf<String, String>().apply { putAll(blankValues()) } }
    var submitted by remember { mutableStateOf(false) }
    val errors: Map<String, String> = if (submitted) validate(values) else emptyMap()
    fun v(k: String) = values[k] ?: ""
    fun set(k: String, x: String) { values[k] = x }

    LaunchedEffect(existing.data) {
        val d = existing.data ?: return@LaunchedEffect
        val b = d.b
        val g = d.guarantor
        val i = d.identity
        values.putAll(blankValues())
        values.putAll(mapOf(
            "first_name" to b.str("first_name"), "middle_name" to b.str("middle_name"), "last_name" to b.str("last_name"), "suffix" to b.str("suffix"),
            "date_of_birth" to b.str("date_of_birth"), "gender" to b.str("gender"), "civil_status" to b.str("civil_status"),
            "mobile_number" to phMobile(b.strOrNull("mobile_number")).let { if (it == "—") "" else it },
            "email" to b.str("email"), "address_line" to b.str("address_line"), "barangay" to b.str("barangay"),
            "city_municipality" to b.str("city_municipality"), "province" to b.str("province"), "region" to b.str("region"),
            "zip_code" to b.str("zip_code"), "occupation" to b.str("occupation"), "employer_name" to b.str("employer_name"),
            "employment_type" to b.str("employment_type"),
            "monthly_income" to (b.numOrNull("monthly_income")?.let { trimNumber(it, 2).replace(",", "") } ?: ""),
            "emergency_contact_name" to b.str("emergency_contact_name"), "emergency_contact_relationship" to b.str("emergency_contact_relationship"),
            "emergency_contact_number" to (b.strOrNull("emergency_contact_number")?.ifBlank { null }?.let { phMobile(it) } ?: ""),
            "internal_notes" to b.str("internal_notes"), "risk_rating" to (b.strOrNull("risk_rating") ?: "unrated"),
            "assessment_notes" to b.str("assessment_notes"),
            "id_type" to i.str("id_type"), "id_number" to i.str("id_number"), "id_expiry" to i.str("id_expiry"),
            "guarantor_name" to g.str("full_name"), "guarantor_relationship" to g.str("relationship"),
            "guarantor_mobile" to (g.strOrNull("mobile_number")?.ifBlank { null }?.let { phMobile(it) } ?: ""),
            "guarantor_address" to g.str("address"), "guarantor_occupation" to g.str("occupation"),
        ))
    }

    var photo by remember { mutableStateOf<PickedFile?>(null) }
    var idFront by remember { mutableStateOf<PickedFile?>(null) }
    var idBack by remember { mutableStateOf<PickedFile?>(null) }
    val photoPath = existing.data?.b.strOrNull("profile_photo_path")?.ifBlank { null }
    val idFrontPath = existing.data?.identity.strOrNull("id_front_path")?.ifBlank { null }
    val idBackPath = existing.data?.identity.strOrNull("id_back_path")?.ifBlank { null }

    // ---- duplicate detection (debounced) ----
    val dupKey = rememberDebounced(DupKey(v("first_name"), v("last_name"), v("date_of_birth"), v("mobile_number"), v("id_type"), v("id_number")), 500)
    val dupEnabled = (dupKey.fn.trim().length >= 2 && dupKey.ln.trim().length >= 2) || isPhMobile(dupKey.mobile)
    val dups = rememberLoad(dupKey, id, live = false, enabled = dupEnabled && auth.can("borrowers.create")) {
        try {
            BorrowersService.checkDuplicates(
                firstName = dupKey.fn, lastName = dupKey.ln, dateOfBirth = dupKey.dob.ifBlank { null },
                mobile = normalizePhMobile(dupKey.mobile), idType = dupKey.idType, idNumber = dupKey.idNumber, exclude = id,
            )
        } catch (_: Exception) { emptyList() }
    }
    val matches = if (dupEnabled && auth.can("borrowers.create")) dups.data ?: emptyList() else emptyList()
    val hardDuplicate = matches.any { it.str("match_reason") == "Same ID document" || it.str("match_reason") == "Same name and date of birth" }
    var ackDuplicate by remember { mutableStateOf(false) }
    LaunchedEffect(matches.size) { ackDuplicate = false }

    val scope = rememberCoroutineScope()
    val dupRequester = remember { BringIntoViewRequester() }
    val requesters = remember { mutableMapOf<String, BringIntoViewRequester>() }
    fun req(k: String) = requesters.getOrPut(k) { BringIntoViewRequester() }
    val runner = rememberRunner()

    fun submit() {
        submitted = true
        val errs = validate(values)
        if (errs.isNotEmpty()) {
            val first = FIELDS.firstOrNull { it in errs }
            if (first != null) scope.launch { try { req(first).bringIntoView() } catch (_: Exception) {} }
            return
        }
        if (matches.isNotEmpty() && !hardDuplicate && !ackDuplicate) {
            Toasts.error("Possible duplicate found. Review the matches and confirm this is a different person.")
            scope.launch { try { dupRequester.bringIntoView() } catch (_: Exception) {} }
            return
        }
        if (hardDuplicate) {
            Toasts.error("A borrower with the same ID or the same name and birth date already exists.")
            scope.launch { try { dupRequester.bringIntoView() } catch (_: Exception) {} }
            return
        }
        val input = linkedMapOf<String, Any?>(
            "first_name" to v("first_name").trim(), "middle_name" to n(v("middle_name")), "last_name" to v("last_name").trim(), "suffix" to n(v("suffix")),
            "date_of_birth" to n(v("date_of_birth")), "gender" to n(v("gender")), "civil_status" to n(v("civil_status")),
            "mobile_number" to normalizePhMobile(v("mobile_number").trim()), "email" to n(v("email")),
            "address_line" to n(v("address_line")), "barangay" to n(v("barangay")), "city_municipality" to n(v("city_municipality")), "province" to n(v("province")),
            "region" to n(v("region")), "zip_code" to n(v("zip_code")), "occupation" to n(v("occupation")), "employer_name" to n(v("employer_name")),
            "employment_type" to n(v("employment_type")),
            "monthly_income" to v("monthly_income").ifEmpty { null }?.replace(",", "")?.trim()?.toDoubleOrNull(),
            "emergency_contact_name" to n(v("emergency_contact_name")), "emergency_contact_relationship" to n(v("emergency_contact_relationship")),
            "emergency_contact_number" to v("emergency_contact_number").trim().ifEmpty { null }?.let { normalizePhMobile(it) },
            "internal_notes" to n(v("internal_notes")),
        )
        if (canSupervise) {
            input["risk_rating"] = v("risk_rating")
            input["assessment_notes"] = n(v("assessment_notes"))
        }
        val identity: Map<String, Any?>? = if (canSensitive && v("id_type").isNotEmpty() && v("id_number").trim().isNotEmpty())
            mapOf("id_type" to v("id_type"), "id_number" to v("id_number").trim(), "id_expiry" to n(v("id_expiry"))) else null
        val guarantor: Map<String, Any?>? = if (v("guarantor_name").trim().isNotEmpty()) mapOf(
            "full_name" to v("guarantor_name").trim(), "relationship" to n(v("guarantor_relationship")),
            "mobile_number" to v("guarantor_mobile").trim().ifEmpty { null }?.let { normalizePhMobile(it) },
            "address" to n(v("guarantor_address")), "occupation" to n(v("guarantor_occupation")),
        ) else null
        val ex = existing.data

        runner.run {
            var borrowerId = id
            if (isEdit && id != null) {
                BorrowersService.update(id, input)
                if (identity != null) BorrowersService.saveIdentity(id, identity)
                if (guarantor != null) BorrowersService.saveGuarantor(id, guarantor + ("id" to ex?.guarantor.strOrNull("id")))
            } else {
                borrowerId = BorrowersService.create(input, identity, guarantor).str("id")
            }

            // uploads need the borrower id in the storage path
            val orgId = auth.organizationId ?: auth.organization.strOrNull("id")
            var uploadFailed = false
            if (orgId != null && !borrowerId.isNullOrBlank()) {
                try {
                    photo?.let { f ->
                        val path = BorrowersService.uploadBorrowerFile(orgId, borrowerId, "profile", f)
                        BorrowersService.update(borrowerId, mapOf("profile_photo_path" to path))
                    }
                    val hasIdentity = identity != null || ex?.identity != null
                    if (canSensitive && (idFront != null || idBack != null) && !hasIdentity) {
                        Toasts.error("ID images were not saved — enter the ID type and number first.")
                    } else if (canSensitive && (idFront != null || idBack != null)) {
                        val patch = mutableMapOf<String, Any?>()
                        idFront?.let { patch["id_front_path"] = BorrowersService.uploadBorrowerFile(orgId, borrowerId, "identity", it) }
                        idBack?.let { patch["id_back_path"] = BorrowersService.uploadBorrowerFile(orgId, borrowerId, "identity", it) }
                        BorrowersService.saveIdentity(borrowerId, patch)
                    }
                } catch (e: Exception) {
                    uploadFailed = true
                    Toasts.error("Borrower saved, but a file upload failed: ${e.readable()}")
                }
            }

            if (!uploadFailed) Toasts.success(if (isEdit) "Borrower updated" else "Borrower registered")
            if (!isEdit && safeReturn != null) {
                router.replace("$safeReturn${if (safeReturn.contains('?')) "&" else "?"}borrower=$borrowerId")
            } else if (isEdit) {
                router.backTo("/borrowers/$borrowerId")
            } else {
                router.replace("/borrowers/$borrowerId")
            }
        }
    }

    if (isEdit && existing.data == null) {
        if (existing.error != null) ErrorState(existing.error, existing.reload) else PageLoader()
        return
    }

    val backTo = if (isEdit) "/borrowers/$id" else safeReturn ?: "/borrowers"
    val wc = widthClass()
    val wide = wc == WidthClass.Expanded
    // web: sm = 2 columns, lg = 3–4 columns
    fun cols(sm: Int, lg: Int) = when (wc) { WidthClass.Compact -> 1; WidthClass.Medium -> sm; WidthClass.Expanded -> lg }

    // ---------- field helpers ----------
    @Composable
    fun Text_(k: String, label: String, required: Boolean = false, hint: String? = null, keyboard: KeyboardType = KeyboardType.Text,
              placeholder: String? = null, maxChars: Int? = null, lines: Int = 1) {
        Field(label, Modifier.bringIntoViewRequester(req(k)), required = required, error = errors[k], hint = hint) {
            TextInput(v(k), { set(k, if (maxChars != null) it.take(maxChars) else it) }, placeholder = placeholder, keyboardType = keyboard,
                isError = errors[k] != null, singleLine = lines == 1, minLines = lines)
        }
    }

    @Composable
    fun Select_(k: String, label: String, options: List<Opt>, hint: String? = null) {
        Field(label, Modifier.bringIntoViewRequester(req(k)), error = errors[k], hint = hint) {
            SelectField(v(k), options, { set(k, it) }, placeholder = options.firstOrNull()?.label ?: "Select")
        }
    }

    @Composable
    fun Date_(k: String, label: String, hint: String? = null) {
        Field(label, Modifier.bringIntoViewRequester(req(k)), error = errors[k], hint = hint) {
            DateField(v(k), { set(k, it) }, clearable = true)
        }
    }

    Column {
        PageHeader(
            if (isEdit) "Edit ${existing.data?.b.strOrNull("full_name") ?: "borrower"}" else "Register borrower",
            description = if (isEdit) existing.data?.b.strOrNull("borrower_code") else "A borrower ID is generated automatically when you save.",
            onBack = { router.backTo(backTo) },
        )

        if (matches.isNotEmpty()) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp).bringIntoViewRequester(dupRequester).clip(shape)
                    .background(if (hardDuplicate) LF.Red50 else LF.Amber50)
                    .border(1.dp, if (hardDuplicate) LF.Red200 else LF.Amber200, shape).padding(16.dp),
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp).padding(top = 2.dp), tint = if (hardDuplicate) LF.Red600 else LF.Amber600)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hardDuplicate) "This borrower is already registered" else "Possible duplicate borrower",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (hardDuplicate) LF.Red800 else LF.Amber800,
                    )
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        matches.forEach { m ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                LinkText(m.str("full_name"), { router.go("/borrowers/${m.str("borrower_id")}") }, color = LF.Blue700)
                                Text("${m.str("borrower_code")} · ${phMobile(m.strOrNull("mobile_number"))}", fontSize = 14.sp, color = LF.MutedForeground,
                                    modifier = Modifier.align(Alignment.CenterVertically))
                                Text(m.str("match_reason"), fontSize = 12.sp, color = LF.Foreground,
                                    modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    if (!hardDuplicate) {
                        Spacer(Modifier.size(8.dp))
                        CheckboxRow(ackDuplicate, { ackDuplicate = it }, "I checked the matches — this is a different person.")
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // ---------------- Personal information ----------------
            Panel(title = "Personal information") {
                FormGrid(cols(2, 4), listOf(
                    GridCell { Text_("first_name", "First name", required = true) },
                    GridCell { Text_("middle_name", "Middle name") },
                    GridCell { Text_("last_name", "Last name", required = true) },
                    GridCell { Text_("suffix", "Suffix", hint = "Jr., Sr., III") },
                    GridCell { Date_("date_of_birth", "Date of birth") },
                    GridCell { Select_("gender", "Gender", GENDERS) },
                    GridCell { Select_("civil_status", "Civil status", CIVIL) },
                    GridCell { Text_("mobile_number", "Mobile number", required = true, keyboard = KeyboardType.Phone, placeholder = "0917 123 4567") },
                    GridCell(2) { Text_("email", "Email", keyboard = KeyboardType.Email) },
                    GridCell(2) {
                        Field("Profile photo", hint = "Take a clear photo of the borrower's face") {
                            FileDrop(
                                onFile = { photo = it }, accept = arrayOf("image/*"),
                                label = photo?.name ?: if (photoPath != null) "Replace photo" else "Take or upload a photo",
                                hint = "JPG or PNG, up to 10 MB", picked = photo, existingPath = photoPath,
                            )
                        }
                    },
                ))
            }

            val address: @Composable (Modifier) -> Unit = { m ->
                Panel(m, title = "Residential address") {
                    FormGrid(if (wc == WidthClass.Compact) 1 else 2, listOf(
                        GridCell(2) { Text_("address_line", "House no. / street / subdivision") },
                        GridCell { Text_("barangay", "Barangay") },
                        GridCell { Text_("city_municipality", "City / municipality") },
                        GridCell { Text_("province", "Province") },
                        GridCell { Text_("zip_code", "ZIP code", keyboard = KeyboardType.Number, maxChars = 4) },
                        GridCell(2) { Select_("region", "Region", withBlank("Select region", PH_REGIONS)) },
                    ))
                }
            }
            val employment: @Composable (Modifier) -> Unit = { m ->
                Panel(m, title = "Employment & income") {
                    FormGrid(if (wc == WidthClass.Compact) 1 else 2, listOf(
                        GridCell { Text_("occupation", "Occupation") },
                        GridCell { Select_("employment_type", "Employment type", withBlank("Select", EMPLOYMENT_TYPES)) },
                        GridCell(2) { Text_("employer_name", "Employer / business name") },
                        GridCell {
                            Field("Monthly income (₱)", Modifier.bringIntoViewRequester(req("monthly_income")), error = errors["monthly_income"], hint = "Optional") {
                                MoneyInput(v("monthly_income"), { set("monthly_income", it) }, placeholder = "25,000", isError = errors["monthly_income"] != null)
                            }
                        },
                    ))
                }
            }
            PanelPair(wide, address, employment)

            // ---------------- Identification ----------------
            Panel(title = "Identification", description = "Government-issued ID used to verify identity") {
                if (canSensitive) {
                    FormGrid(cols(2, 3), listOf(
                        GridCell { Select_("id_type", "ID type", withBlank("Select ID type", PH_ID_TYPES)) },
                        GridCell { Text_("id_number", "ID number") },
                        GridCell { Date_("id_expiry", "Expiry date", hint = "If applicable") },
                        GridCell {
                            Field("Front of ID") {
                                FileDrop(
                                    onFile = { idFront = it },
                                    label = idFront?.name ?: if (idFrontPath != null) "Replace front image" else "Capture front of ID",
                                    hint = "Ensure all text is readable", picked = idFront, existingPath = idFrontPath,
                                )
                            }
                        },
                        GridCell {
                            Field("Back of ID") {
                                FileDrop(
                                    onFile = { idBack = it },
                                    label = idBack?.name ?: if (idBackPath != null) "Replace back image" else "Capture back of ID",
                                    hint = "Optional for single-sided IDs", picked = idBack, existingPath = idBackPath,
                                )
                            }
                        },
                        GridCell {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp)) {
                                Icon(Icons.Default.Lock, null, Modifier.size(14.dp).padding(top = 2.dp), tint = LF.MutedForeground)
                                Spacer(Modifier.width(8.dp))
                                Text("ID images are stored privately and visible only to staff with permission to view sensitive data.",
                                    fontSize = 12.sp, color = LF.MutedForeground)
                            }
                        },
                    ))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                        Spacer(Modifier.width(8.dp))
                        Text("Your role cannot view or edit identity documents.", fontSize = 14.sp, color = LF.MutedForeground)
                    }
                }
            }

            val emergency: @Composable (Modifier) -> Unit = { m ->
                Panel(m, title = "Emergency contact") {
                    FormGrid(if (wc == WidthClass.Compact) 1 else 2, listOf(
                        GridCell(2) { Text_("emergency_contact_name", "Full name") },
                        GridCell { Select_("emergency_contact_relationship", "Relationship", withBlank("Select", RELATIONSHIPS)) },
                        GridCell { Text_("emergency_contact_number", "Mobile number", keyboard = KeyboardType.Phone, placeholder = "09XX XXX XXXX") },
                    ))
                }
            }
            val guarantorPanel: @Composable (Modifier) -> Unit = { m ->
                Panel(m, title = "Guarantor / co-maker", description = "Optional — more can be added from the borrower profile") {
                    FormGrid(if (wc == WidthClass.Compact) 1 else 2, listOf(
                        GridCell(2) { Text_("guarantor_name", "Full name") },
                        GridCell { Select_("guarantor_relationship", "Relationship", withBlank("Select", RELATIONSHIPS)) },
                        GridCell { Text_("guarantor_mobile", "Mobile number", keyboard = KeyboardType.Phone) },
                        GridCell { Text_("guarantor_occupation", "Occupation") },
                        GridCell { Text_("guarantor_address", "Address") },
                    ))
                }
            }
            PanelPair(wide, emergency, guarantorPanel)

            // ---------------- Internal notes & assessment ----------------
            Panel(title = "Internal notes & assessment") {
                val c = if (wide) 3 else 1
                FormGrid(c, buildList {
                    add(GridCell(if (canSupervise) 1 else 3) { Text_("internal_notes", "Internal notes", hint = "Visible to staff only", lines = 4) })
                    if (canSupervise) {
                        add(GridCell { Select_("risk_rating", "Risk rating", RISKS) })
                        add(GridCell { Text_("assessment_notes", "Assessment notes", hint = "Credit investigation, character references, capacity to pay", lines = 4) })
                    }
                })
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            HorizontalDivider(color = LF.Border)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn("Cancel", { router.backTo(backTo) }, variant = BtnVariant.Outline, enabled = !runner.busy)
                Btn(
                    if (isEdit) "Save changes" else if (safeReturn != null) "Register & continue" else "Register borrower",
                    { submit() }, Modifier.widthIn(min = 144.dp), icon = Icons.Default.Save, loading = runner.busy, enabled = !hardDuplicate,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// layout helpers (CSS grid with column spans)
// ---------------------------------------------------------------------------
internal class GridCell(val span: Int = 1, val content: @Composable () -> Unit)

/** Packs cells into rows of [columns] (a span wider than the grid is clamped), like `grid sm:grid-cols-N`. */
@Composable
internal fun FormGrid(columns: Int, cells: List<GridCell>, gap: androidx.compose.ui.unit.Dp = 16.dp) {
    val rows = mutableListOf<MutableList<GridCell>>()
    var used = columns
    cells.forEach { c ->
        val s = c.span.coerceIn(1, columns)
        if (used + s > columns) { rows += mutableListOf<GridCell>(); used = 0 }
        rows.last() += c
        used += s
    }
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                var total = 0
                row.forEach { c ->
                    val s = c.span.coerceIn(1, columns)
                    total += s
                    Box(Modifier.weight(s.toFloat())) { c.content() }
                }
                if (total < columns) Spacer(Modifier.weight((columns - total).toFloat()))
            }
        }
    }
}

/** Two panels side by side on large screens (lg:grid-cols-2), stacked otherwise. */
@Composable
internal fun PanelPair(wide: Boolean, a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            a(Modifier.weight(1f))
            b(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            a(Modifier)
            b(Modifier)
        }
    }
}

@Suppress("unused") private fun keepHas(r: JRow) = r.has("id")
