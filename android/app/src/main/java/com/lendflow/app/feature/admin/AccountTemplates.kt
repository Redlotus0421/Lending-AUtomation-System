package com.lendflow.app.feature.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate

// Platform admin: agreement / terms templates of one lender account (AccountTemplates.tsx). The list
// sits on the account page; editing opens the same template editor lenders use, saving into that account.

/** Terms templates panel on the account detail page (AccountTemplates.tsx). org = platform_get_organization result */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountTemplatesPanel(org: Row) {
    val router = LocalRouter.current
    val orgId = org.str("id")
    val q = rememberLoad(orgId, "platform-templates") { PlatformService.listTemplates(orgId) }
    val runner = rememberRunner()

    Panel(
        title = "Agreement / terms templates",
        description = "The terms printed on ${org.str("name")}'s loan agreements. You and the lender can both create and edit them; the default is used for new agreements.",
        actions = { Btn("New template", { router.go("/admin/accounts/$orgId/templates/new") }, small = true, icon = Icons.Default.NoteAdd) },
    ) {
        val list = q.data
        when {
            q.initialLoading -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spinner() }
            q.error != null && list == null -> ErrorState(q.error, onRetry = q.reload)
            list.isNullOrEmpty() -> EmptyState("No templates yet", description = "Create the first terms template for this lender.")
            else -> Column(Modifier.fillMaxWidth().border(1.dp, LF.Border, RoundedCornerShape(8.dp))) {
                list.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = LF.Border)
                    val id = t.str("id")
                    val isDefault = t.bool("is_default")
                    val active = t.bool("is_active")
                    val cur = t.obj("current")
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t.str("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (isDefault) Pill("Default", Tone.blue)
                            if (!active) Pill("Inactive", Tone.slate)
                        }
                        Text(
                            if (cur != null) "${cur.str("title")} · version ${cur.str("version_no")} · updated ${fmtDate(cur.strOrNull("created_at"))}" else "No saved version",
                            fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Edit", { router.go("/admin/accounts/$orgId/templates/$id") }, small = true, variant = BtnVariant.Outline, icon = Icons.Default.Edit)
                            if (!isDefault && active) {
                                Btn("Make default", {
                                    runner.run(success = "Default template changed") { PlatformService.setDefaultTemplate(orgId, id); q.reload() }
                                }, small = true, variant = BtnVariant.Ghost, icon = Icons.Default.Star, enabled = !runner.busy)
                            }
                            if (!isDefault) {
                                Btn(if (active) "Deactivate" else "Activate", {
                                    runner.run(success = if (active) "Template deactivated" else "Template activated") {
                                        PlatformService.setTemplateActive(orgId, id, !active); q.reload()
                                    }
                                }, small = true, variant = BtnVariant.Ghost, icon = Icons.Default.PowerSettingsNew, enabled = !runner.busy)
                            }
                        }
                    }
                }
            }
        }
    }
}
