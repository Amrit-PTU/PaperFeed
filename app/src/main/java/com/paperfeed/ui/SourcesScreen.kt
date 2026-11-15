package com.paperfeed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperfeed.PaperFeedApp
import com.paperfeed.data.Source
import com.paperfeed.data.SourceType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onOpenDrawer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources by repo.sources().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Source?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Source?>(null) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Menu") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Refresh all now") }, onClick = { menu = false; PaperFeedApp.instance.refreshNow() })
                            DropdownMenuItem(text = { Text("Restore default journals") }, onClick = { menu = false; scope.launch { repo.restoreDefaultSources() } })
                        }
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add source") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(sources, key = { it.id }) { s ->
                ListItem(
                    modifier = Modifier.clickable { editing = s },
                    headlineContent = { Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text("${s.type.label} · ${s.query}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            val status = when {
                                s.lastError != null -> "⚠ ${s.lastError}"
                                s.lastFetched > 0 -> "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.lastFetched))
                                else -> "Not fetched yet"
                            }
                            Text(
                                status, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                color = if (s.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { repo.updateSource(s.copy(notify = !s.notify)) } }, enabled = s.enabled) {
                                Icon(
                                    if (s.notify) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                                    if (s.notify) "Notifications on" else "Notifications off",
                                    tint = if (s.notify && s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = s.enabled, onCheckedChange = { scope.launch { repo.updateSource(s.copy(enabled = it)) } })
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }

    if (adding) SourceDialog(null, onDismiss = { adding = false }, onSave = { s ->
        adding = false
        scope.launch {
            val id = repo.addSource(s)
            PaperFeedApp.instance.refreshNow(onlySource = id)
        }
    })
    editing?.let { e ->
        SourceDialog(e, onDismiss = { editing = null }, onDelete = { editing = null; deleting = e }, onSave = { s ->
            editing = null
            scope.launch {
                repo.updateSource(s)
                if (s.query != e.query || s.type != e.type) PaperFeedApp.instance.refreshNow(onlySource = s.id)
            }
        })
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove “${s.name}”?") },
            text = { Text("Its articles disappear from the feed. Articles you saved to lists or rated are kept.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteSource(s.id) }; deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private val examples = mapOf(
    SourceType.CROSSREF to "Any journal registered with Crossref, by ISSN (print or electronic). Find it on the journal's homepage.",
    SourceType.RSS to "Paste the RSS or Atom link from the journal's website (e.g. APS, IOP, Wiley, Springer, Nature, T&F).",
    SourceType.ARXIV to "arXiv API query: cat:nucl-th, cat:hep-ph AND ti:neutrino, au:Witten, or plain keywords.",
    SourceType.OPENALEX to "Keyword search across all journals via OpenAlex, newest first. Reliable alternative to Scholar.",
    SourceType.SCHOLAR to "Keywords or a full scholar.google.com URL. Google often blocks automated requests with a captcha, so this may fail at times.",
)

@Composable
fun SourceDialog(initial: Source?, onDismiss: () -> Unit, onSave: (Source) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: SourceType.CROSSREF) }
    var query by remember { mutableStateOf(initial?.query ?: "") }
    var notify by remember { mutableStateOf(initial?.notify ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add source" else "Edit source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.labelLarge)
                SourceType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { type = t },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    query, { query = it }, label = { Text(type.hint) },
                    singleLine = type != SourceType.ARXIV, modifier = Modifier.fillMaxWidth(),
                )
                Text(examples[type].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify about new articles", Modifier.weight(1f))
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && query.isNotBlank(), onClick = {
                val base = initial ?: Source(name = "", type = type, query = "")
                onSave(base.copy(name = name.trim(), type = type, query = query.trim(), notify = notify,
                    lastFetched = if (initial != null && (initial.type != type || initial.query != query.trim())) 0 else base.lastFetched))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
