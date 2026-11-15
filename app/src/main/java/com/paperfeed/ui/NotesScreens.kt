package com.paperfeed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperfeed.data.NoteWithArticle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Split screen: the paper (title, details, abstract) on top, the note editor below, so you can
 * read and write side by side. The divider can be dragged. Notes save automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(articleKey: String, noteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val article by repo.article(articleKey).collectAsState(initial = null)
    var id by rememberSaveable { mutableLongStateOf(noteId) }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var savedText by remember { mutableStateOf("") }
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sizeSheet by remember { mutableStateOf(false) }
    val readerScale = LocalSettings.current.readerScale

    LaunchedEffect(noteId) {
        if (text == null) {
            val existing = if (noteId > 0) repo.note(noteId)?.text.orEmpty() else ""
            text = existing
            savedText = existing
        }
    }

    suspend fun save() {
        val t = text ?: return
        if (t == savedText) return
        if (id == 0L) {
            if (t.isBlank()) return
            id = repo.addNote(articleKey, t)
        } else {
            repo.updateNote(id, t)
        }
        savedText = t
    }

    // Debounced autosave while typing.
    LaunchedEffect(text) {
        delay(700)
        save()
    }

    fun leave() {
        scope.launch {
            withContext(NonCancellable) {
                save()
                // An emptied note is removed rather than kept blank.
                if (id != 0L && text.isNullOrBlank()) repo.deleteNote(id)
            }
            onBack()
        }
    }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == 0L) "New note" else "Note") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Save and go back") } },
                actions = {
                    IconButton(onClick = { sizeSheet = true }) { Icon(Icons.Outlined.TextFields, "Text size") }
                    IconButton(onClick = {
                        val t = text.orEmpty()
                        if (t.isNotBlank()) shareText(context, "Note", t + "\n\n— " + noteSource(article), "Share note")
                    }) { Icon(Icons.Outlined.Share, "Share note") }
                    if (id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete note") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).imePadding().fillMaxSize()) {
            val total = maxHeight
            val density = LocalDensity.current
            Column(Modifier.fillMaxSize()) {
                // --- Paper pane ---
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(split)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ScaledText(readerScale) {
                        SelectionContainer {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                val a = article
                                if (a == null) {
                                    Text("Paper not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        listOfNotNull(
                                            a.authorList.take(3).joinToString(", ") + if (a.authorList.size > 3) " et al." else "",
                                            a.year?.toString(), a.journal,
                                        ).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        a.abstract ?: "No abstract available. Use “Fetch abstract” on the article page.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = 24.sp,
                                        fontStyle = if (a.abstract == null) FontStyle.Italic else FontStyle.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
                // --- Drag handle ---
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(total) {
                            val totalPx = with(density) { total.toPx() }
                            detectVerticalDragGestures { change, dy ->
                                change.consume()
                                split = (split + dy / totalPx).coerceIn(0.2f, 0.8f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                }
                // --- Editor pane ---
                Box(Modifier.fillMaxWidth().weight(1f - split)) {
                    ScaledText(readerScale) {
                        TextField(
                            value = text.orEmpty(),
                            onValueChange = { text = it },
                            placeholder = { Text("Jot down an idea, a question, a result to check…") },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (sizeSheet) TextSizeSheet(forFeed = false, onDismiss = { sizeSheet = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteNote(id)
                        id = 0; text = ""; savedText = ""
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Every note, newest first, each labelled with the paper it came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onOpenDrawer: () -> Unit, onEdit: (articleKey: String, noteId: Long) -> Unit, onArticle: (String) -> Unit) {
    val context = LocalContext.current
    val notes by repo.notes().collectAsState(initial = null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Menu") } },
                actions = {
                    val all = notes.orEmpty()
                    if (all.isNotEmpty()) {
                        IconButton(onClick = {
                            val text = all.joinToString("\n\n────────\n\n") { n -> n.note.text + "\n\n— " + noteSource(n.article) }
                            shareText(context, "PaperFeed notes", text, "Share all notes")
                        }) { Icon(Icons.Outlined.Share, "Share all notes") }
                    }
                },
            )
        },
    ) { padding ->
        val list = notes
        when {
            list == null -> Box(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(
                "No notes yet.\nOpen an article and tap the note button to write while you read.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.note.id }) { n ->
                    NoteCard(n, onClick = { onEdit(n.note.articleKey, n.note.id) }, onPaper = { onArticle(n.note.articleKey) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(n: NoteWithArticle, onClick: () -> Unit, onPaper: (() -> Unit)?, showSource: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val a = n.article
    Box {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(n.note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showSource) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.clickable(enabled = onPaper != null && a != null) { onPaper?.invoke() }) {
                        if (a == null) {
                            Text("Paper no longer in the database", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(a.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val names = a.authorList
                            Text(
                                // Year first so it stays visible when the author list is cut off.
                                listOfNotNull(
                                    a.year?.toString(),
                                    (if (names.size > 3) names.take(3).joinToString(", ") + " et al." else names.joinToString(", ")).ifBlank { null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            a.doi?.let {
                                Text("doi:$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy note") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; copyText(context, "Note", n.note.text + "\n\n— " + noteSource(a)) })
            DropdownMenuItem(text = { Text("Share note") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menu = false; shareNote(context, n.note, a) })
            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menu = false; scope.launch { repo.deleteNote(n.note.id) } })
        }
    }
}
