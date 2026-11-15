package com.paperfeed.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperfeed.R
import com.paperfeed.data.Article
import com.paperfeed.data.ArticleList
import com.paperfeed.data.NoteWithArticle
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    key: String,
    onBack: () -> Unit,
    onAuthor: (String) -> Unit,
    onNote: (noteId: Long) -> Unit,
    onPublisherAbstract: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val article by repo.article(key).collectAsState(initial = null)
    val inLists by repo.listIdsFor(key).collectAsState(initial = emptyList())
    val notes by repo.notesFor(key).collectAsState(initial = emptyList())
    var loadingAbstract by remember { mutableStateOf(false) }
    var lookupFailed by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var sizeSheet by remember { mutableStateOf(false) }
    var zoteroMissing by remember { mutableStateOf(false) }
    val readerScale = LocalSettings.current.readerScale

    LaunchedEffect(key) {
        repo.markRead(key)
        loadingAbstract = true
        repo.ensureAbstract(key)
        loadingAbstract = false
    }

    /** Tries every API; if none has it, offers the publisher page. */
    // "Copy in browser, come back" flow: remember what was on the clipboard before leaving, and
    // when the user returns with new text, offer it as the abstract.
    var awaitingCopy by remember { mutableStateOf(false) }
    var clipBefore by remember { mutableStateOf<String?>(null) }
    var copiedCandidate by remember { mutableStateOf<String?>(null) }

    fun openForCopy(a: Article) {
        val link = a.link ?: return
        clipBefore = clipboardText(context)
        awaitingCopy = true
        Toast.makeText(context, "Long-press to select the abstract, copy it, then come back", Toast.LENGTH_LONG).show()
        openUrl(context, link)
    }

    LifecycleResumeEffect(awaitingCopy) {
        val job = scope.launch {
            if (awaitingCopy) {
                delay(400) // clipboard is readable only once the window has focus again
                val clip = clipboardText(context)
                if (clip != null && clip != clipBefore && clip.length > 80) copiedCandidate = clip
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    /** Tries every database; if none has it, opens the publisher page in the browser. */
    fun fetchAbstract(a: Article) {
        scope.launch {
            loadingAbstract = true
            lookupFailed = false
            val found = repo.enrichAbstracts(listOf(a), 40_000).isNotEmpty()
            loadingAbstract = false
            if (!found) {
                lookupFailed = true
                openForCopy(a)
            }
        }
    }

    val a = article
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (a != null) {
                        IconButton(onClick = { sizeSheet = true }) { Icon(Icons.Outlined.TextFields, "Text size") }
                        a.link?.let { link ->
                            IconButton(onClick = { openUrl(context, link) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open article") }
                        }
                        Box {
                            IconButton(onClick = { shareMenu = true }) { Icon(Icons.Outlined.Share, "Share") }
                            DropdownMenu(shareMenu, onDismissRequest = { shareMenu = false }) {
                                DropdownMenuItem(text = { Text("Share link") }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                    onClick = { shareMenu = false; shareLink(context, a) })
                                DropdownMenuItem(text = { Text("Share citation") }, leadingIcon = { Icon(Icons.Outlined.FormatQuote, null) },
                                    onClick = { shareMenu = false; shareArticle(context, a) })
                                DropdownMenuItem(text = { Text("Share citation + abstract") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                    onClick = { shareMenu = false; shareWithAbstract(context, a) })
                                if (a.doi != null) {
                                    DropdownMenuItem(text = { Text("Share BibTeX") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                        onClick = {
                                            shareMenu = false
                                            scope.launch {
                                                val bib = repo.bibtex(a)
                                                if (bib != null) shareText(context, a.title, bib, "Share BibTeX")
                                                else Toast.makeText(context, "Couldn't get BibTeX from doi.org", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                }
                                DropdownMenuItem(
                                    text = { Text("Save to Zotero") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_zotero), null) },
                                    onClick = { shareMenu = false; if (!Zotero.send(context, a)) zoteroMissing = true },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Fetch abstract again") }, leadingIcon = { Icon(Icons.Outlined.TravelExplore, null) },
                                    onClick = { menu = false; fetchAbstract(a) })
                                DropdownMenuItem(text = { Text("Copy abstract from publisher page") }, leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                    onClick = { menu = false; openForCopy(a) })
                                DropdownMenuItem(text = { Text("Try in-app extraction") }, leadingIcon = { Icon(Icons.Outlined.TravelExplore, null) },
                                    onClick = { menu = false; onPublisherAbstract() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Copy citation") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; copyText(context, "Citation", a.citation()) })
                                DropdownMenuItem(text = { Text("Copy title") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; copyText(context, "Title", a.title) })
                                a.abstract?.let { abs ->
                                    DropdownMenuItem(text = { Text("Copy abstract") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                        onClick = { menu = false; copyText(context, "Abstract", abs) })
                                }
                                a.link?.let { link ->
                                    DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                        onClick = { menu = false; copyText(context, "Link", link) })
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (a != null) {
                BottomAppBar {
                    StarRating(a.rating, size = 24.dp, onChange = { r -> scope.launch { repo.setRating(key, r) } })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onNote(0) }) {
                        BadgedBox(badge = { if (notes.isNotEmpty()) Badge { Text("${notes.size}") } }) {
                            Icon(Icons.Outlined.EditNote, "Write a note")
                        }
                    }
                    IconButton(onClick = { if (!Zotero.send(context, a)) zoteroMissing = true }) {
                        Icon(painterResource(R.drawable.ic_zotero), "Save to Zotero")
                    }
                    val inReadLater = ArticleList.READ_LATER_ID in inLists
                    IconButton(onClick = { scope.launch { repo.toggleInList(ArticleList.READ_LATER_ID, key) } }) {
                        Icon(
                            if (inReadLater) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (inReadLater) "Remove from Read later" else "Read later",
                            tint = if (inReadLater) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val others = inLists.count { it != ArticleList.READ_LATER_ID }
                    IconButton(onClick = { sheet = true }) {
                        BadgedBox(badge = { if (others > 0) Badge { Text("$others") } }) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, "Save to lists")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (a == null) {
            EmptyState("Article not found", Modifier.padding(padding))
            return@Scaffold
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        ScaledText(readerScale) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                SelectionContainer {
                    Column {
                        Text(a.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        // Every author opens their own chronological list.
                        val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.onSurface))
                        Text(
                            buildAnnotatedString {
                                a.authorList.forEachIndexed { i, name ->
                                    if (i > 0) append(", ")
                                    withLink(LinkAnnotation.Clickable("a$i", linkStyle) { onAuthor(name) }) { append(name) }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        val details = buildList {
                            add(a.journal)
                            val vol = listOfNotNull(a.volume?.let { "Vol. $it" }, a.issue?.let { "No. $it" }, a.pages).joinToString(", ")
                            if (vol.isNotBlank()) add(vol)
                            add(a.dateText())
                        }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(details.first()) }
                                details.drop(1).forEach { append("  ·  "); append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        val doi = a.doi
                        val link = a.link
                        if (link != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildAnnotatedString {
                                    append(if (doi != null) "DOI: " else "Link: ")
                                    withLink(
                                        LinkAnnotation.Url(
                                            link,
                                            TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)),
                                        )
                                    ) { append(doi ?: link) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Text("Abstract", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        a.abstract?.let { Text(it, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp) }
                    }
                }
                val abs = a.abstract
                if (abs == null || abs.endsWith("…")) {
                    Spacer(Modifier.height(8.dp))
                    if (loadingAbstract) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Looking for the abstract…", style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    } else {
                        Text(
                            when {
                                abs != null -> "This abstract is shortened by the publisher's feed."
                                lookupFailed -> "Not in the open databases yet. Copy it from the publisher page; the app picks it up when you come back."
                                else -> "The publisher doesn't include the abstract in its feed."
                            } + if (a.doi?.startsWith("10.1016/") == true && LocalSettings.current.elsevierApiKey.isBlank())
                                "\nTip: add a free Elsevier API key in Settings → Network to fetch these automatically." else "",
                            style = MaterialTheme.typography.bodyMedium, color = muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { fetchAbstract(a) }) {
                                Icon(Icons.Outlined.TravelExplore, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fetch abstract")
                            }
                            if (a.link != null) {
                                OutlinedButton(onClick = { openForCopy(a) }) { Text("Copy from publisher page") }
                            }
                            TextButton(onClick = {
                                val clip = clipboardText(context)
                                if (clip != null && clip.length > 40) scope.launch { repo.saveAbstract(key, clip) }
                                else Toast.makeText(context, "Copy the abstract (e.g. in your browser) first", Toast.LENGTH_SHORT).show()
                            }) { Text("Paste abstract") }
                        }
                    }
                }

                // --- Notes on this paper ---
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Notes" + if (notes.isNotEmpty()) " (${notes.size})" else "",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onNote(0) }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add note")
                    }
                }
                notes.forEach { n ->
                    NoteCard(NoteWithArticle(n, a), onClick = { onNote(n.id) }, onPaper = null, showSource = false)
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (sheet && a != null) SaveToListsSheet(a, onDismiss = { sheet = false })
    if (sizeSheet) TextSizeSheet(forFeed = false, onDismiss = { sizeSheet = false })
    copiedCandidate?.let { clip ->
        AlertDialog(
            onDismissRequest = { copiedCandidate = null },
            title = { Text("Use the copied text as the abstract?") },
            text = { Text(clip.take(600) + if (clip.length > 600) "…" else "", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    copiedCandidate = null
                    awaitingCopy = false
                    scope.launch { repo.saveAbstract(key, clip) }
                }) { Text("Use it") }
            },
            dismissButton = { TextButton(onClick = { copiedCandidate = null }) { Text("Not now") } },
        )
    }
    if (zoteroMissing && a != null) {
        AlertDialog(
            onDismissRequest = { zoteroMissing = false },
            title = { Text("Zotero isn't installed") },
            text = { Text("Install the Zotero app to save papers to your library with one tap, or share the link to another app.") },
            confirmButton = { TextButton(onClick = { zoteroMissing = false; Zotero.openStorePage(context) }) { Text("Get Zotero") } },
            dismissButton = { TextButton(onClick = { zoteroMissing = false; shareLink(context, a) }) { Text("Share link instead") } },
        )
    }
}
