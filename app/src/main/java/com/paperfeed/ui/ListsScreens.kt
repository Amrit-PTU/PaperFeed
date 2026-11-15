package com.paperfeed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperfeed.data.ArticleList
import kotlinx.coroutines.launch

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Save", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(onOpenDrawer: () -> Unit, onList: (Long) -> Unit, onNotes: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lists by repo.listsWithCounts().collectAsState(initial = emptyList())
    val notesCount by repo.notesCount().collectAsState(initial = 0)
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ArticleList?>(null) }
    var deleting by remember { mutableStateOf<ArticleList?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Menu") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "New list") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "notes") {
                ListItem(
                    modifier = Modifier.clickable(onClick = onNotes),
                    leadingContent = { Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text("Notes", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("$notesCount note${if (notesCount == 1) "" else "s"} · with the paper each came from") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            items(lists, key = { it.list.id }) { (list, count) ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { onList(list.id) },
                    leadingContent = {
                        Icon(
                            if (isReadLater(list)) Icons.Outlined.Bookmark else Icons.AutoMirrored.Outlined.List,
                            null, tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text(list.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            "$count article${if (count == 1) "" else "s"}" +
                                if (list.notificationAction) " · quick action in notifications" else "",
                        )
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "List options") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (!isReadLater(list)) {
                                    DropdownMenuItem(
                                        text = { Text("Show as notification button") },
                                        leadingIcon = { Icon(Icons.Outlined.NotificationsActive, null) },
                                        trailingIcon = { RadioButton(selected = list.notificationAction, onClick = null) },
                                        onClick = { menu = false; scope.launch { repo.setNotificationList(list.id) } },
                                    )
                                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = { menu = false; renaming = list })
                                }
                                DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                                    onClick = { menu = false; scope.launch { repo.moveList(list, up = true) } })
                                DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                                    onClick = { menu = false; scope.launch { repo.moveList(list, up = false) } })
                                if (!isReadLater(list)) {
                                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = { menu = false; deleting = list })
                                }
                            }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
    if (creating) NameDialog("New list", confirm = "Create", onDismiss = { creating = false }) {
        scope.launch { repo.createList(it) }; creating = false
    }
    renaming?.let { l ->
        NameDialog("Rename list", initial = l.name, onDismiss = { renaming = null }) {
            scope.launch { repo.renameList(l, it) }; renaming = null
        }
    }
    deleting?.let { l ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${l.name}”?") },
            text = { Text("The articles stay in your feed; only the list is removed.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteList(l.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(listId: Long, onBack: () -> Unit, onArticle: (String) -> Unit, onAuthor: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var byRating by rememberSaveable { mutableStateOf(false) }
    val list by repo.list(listId).collectAsState(initial = null)
    val articles by remember(listId, byRating) { repo.listArticles(listId, byRating) }.collectAsState(initial = null)
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(list?.name ?: "", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Newest first") },
                                trailingIcon = { RadioButton(selected = !byRating, onClick = null) },
                                onClick = { byRating = false; sortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Rating (★★★ first)") },
                                leadingIcon = { Icon(Icons.Outlined.Star, null, tint = StarColor) },
                                trailingIcon = { RadioButton(selected = byRating, onClick = null) },
                                onClick = { byRating = true; sortMenu = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val items = articles
        when {
            items == null -> Box(Modifier.padding(padding))
            items.isEmpty() -> EmptyState("This list is empty.\nSave articles from the article page or straight from a notification.", Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "${items.size} article${if (items.size == 1) "" else "s"} · " + if (byRating) "by rating" else "newest first",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(items, key = { it.key }) { a ->
                    ArticleRow(
                        a,
                        onClick = { onArticle(a.key) },
                        onAuthorClick = onAuthor,
                        modifier = Modifier.animateItem(),
                        extraMenu = { dismiss ->
                            DropdownMenuItem(
                                text = { Text("Remove from this list") },
                                leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
                                onClick = { dismiss(); scope.launch { repo.removeFromList(listId, a.key) } },
                            )
                        },
                    )
                    FeedDivider()
                }
            }
        }
    }
}
