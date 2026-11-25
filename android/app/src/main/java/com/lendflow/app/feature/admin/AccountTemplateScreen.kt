package com.lendflow.app.feature.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.str
import com.lendflow.app.feature.agreements.TemplateEditor
import com.lendflow.app.feature.agreements.TemplateSource
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.rememberLoad

/** /admin/accounts/:id/templates/:templateId — edit or create a template for that lender. templateId = "new" for a new template */
@Composable
fun AccountTemplateScreen(orgId: String, templateId: String) {
    val router = LocalRouter.current
    // not live: a background refresh would rebuild the source and reset the editor
    val org = rememberLoad(orgId, "platform-org", live = false) { PlatformService.getOrganization(orgId) }
    val data = org.data
    val source = remember(orgId, data) {
        object : TemplateSource {
            override suspend fun get(id: String): Row = PlatformService.getTemplate(orgId, id)
            override suspend fun save(p: Map<String, Any?>): Row = PlatformService.saveTemplate(orgId, p)
            override val organization: Row? get() = data
        }
    }

    if (org.initialLoading) { PageLoader(); return }
    if (data == null) { ErrorState(org.error ?: "Account not found", onRetry = org.reload); return }
    Column {
        PageHeader(
            if (templateId == "new") "New terms template" else "Edit terms template",
            description = "For ${data.str("name")}. Saved templates are available to the lender right away.",
            onBack = { if (!router.back()) router.replace("/admin/accounts/$orgId") },
            backLabel = data.str("name"),
        )
        key(templateId) {
            TemplateEditor(templateId, embedded = true, source = source,
                onSaved = { id -> router.replace("/admin/accounts/$orgId/templates/$id") })
        }
    }
}
