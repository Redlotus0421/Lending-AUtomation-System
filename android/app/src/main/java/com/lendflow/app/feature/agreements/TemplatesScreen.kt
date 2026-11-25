package com.lendflow.app.feature.agreements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.Row
import com.lendflow.app.data.TemplatesService
import com.lendflow.app.data.bool
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDateTime

@Composable
fun TemplatesScreen() {
    val router = LocalRouter.current
    val q = rememberLoad("templates") { TemplatesService.list() }
    val runner = rememberRunner()

    if (q.initialLoading) { PageLoader(); return }
    if (q.error != null && q.data == null) { ErrorState(q.error, onRetry = q.reload); return }
    val list = q.data ?: emptyList()

    Column {
        PageHeader(
            "Agreement templates",
            description = "Configurable, versioned agreement templates with dynamic placeholders. Each generated agreement freezes the template version it used.",
            actions = { Btn("New template", { router.go("/agreements/templates/new") }, icon = Icons.Default.Add) },
        )
        if (list.isEmpty()) {
            EmptyState("No templates yet", icon = Icons.Default.Dashboard, action = {
                Btn("Create template", { router.go("/agreements/templates/new") }, icon = Icons.Default.Add)
            })
            return@Column
        }
        val cols = when (widthClass()) { WidthClass.Compact -> 1; WidthClass.Medium -> 2; WidthClass.Expanded -> 3 }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            list.chunked(cols).forEach { rowItems ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { t ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            TemplateCard(t,
                                onOpen = { router.go("/agreements/templates/${t.str("id")}") },
                                onDuplicate = {
                                    runner.run(success = "Template duplicated") {
                                        val r = TemplatesService.duplicate(t.str("id"))
                                        router.go("/agreements/templates/${r.str("id")}")
                                    }
                                },
                                onDefault = { runner.run(success = "Default template updated") { TemplatesService.setDefault(t.str("id")) } },
                                onActive = { runner.run(success = "Template updated") { TemplatesService.setActive(t.str("id"), !t.bool("is_active")) } },
                            )
                        }
                    }
                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCard(t: Row, onOpen: () -> Unit, onDuplicate: () -> Unit, onDefault: () -> Unit, onActive: () -> Unit) {
    val current = t.obj("current")
    val isDefault = t.bool("is_default")
    val active = t.bool("is_active")
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().fillMaxHeight().card().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t.str("name"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.strOrNull("description") ?: current?.strOrNull("title") ?: "", Modifier.padding(top = 2.dp), fontSize = 12.sp,
                    color = LF.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreHoriz, "Template actions") }
                DropdownMenu(menu, { menu = false }, containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    if (!isDefault && active) DropdownMenuItem(text = { Text("Set as default") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; onDefault() })
                    if (!isDefault) DropdownMenuItem(text = { Text(if (active) "Deactivate" else "Activate") }, leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) }, onClick = { menu = false; onActive() })
                }
            }
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isDefault) Pill("Default", Tone.blue)
            Pill(if (active) "Active" else "Inactive", if (active) Tone.green else Tone.slate)
            Pill("v${current?.strOrNull("version_no") ?: "—"}", Tone.slate, dot = false)
        }
        Spacer(Modifier.weight(1f).height(16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Updated ${fmtDateTime(current?.strOrNull("created_at") ?: t.strOrNull("updated_at"))}", Modifier.weight(1f), fontSize = 12.sp, color = LF.MutedForeground)
            Btn("Open", onOpen, variant = BtnVariant.Outline, small = true)
        }
    }
}
