package com.paperfeed.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paperfeed.PaperFeedApp
import com.paperfeed.data.Article
import com.paperfeed.data.ArticleList
import com.paperfeed.data.FeedStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val repo get() = PaperFeedApp.instance.repo

fun Article.shortAuthors(): List<String> = authorList.take(2)

fun Article.dateText(): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(published).atZone(ZoneId.systemDefault()))

fun Article.citation(): String = buildString {
    val names = authorList
    append(
        when {
            names.isEmpty() -> ""
            names.size > 3 -> "${names[0]} et al."
            else -> names.joinToString(", ")
        }
    )
    if (isNotEmpty()) append(", ")
    append("\"").append(title).append("\", ").append(journal)
    volume?.let { append(" ").append(it) }
    pages?.let { append(", ").append(it) }
    year?.let { append(" (").append(it).append(")") }
    doi?.let { append(". https://doi.org/").append(it) }
}

fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

@Composable
fun StarRating(
    rating: Int,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onChange: ((Int) -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..3) {
            val filled = i <= rating
            val icon = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder
            val tint = if (filled) StarColor else MaterialTheme.colorScheme.onSurfaceVariant
            if (onChange != null) {
                IconButton(onClick = { onChange(if (rating == i) 0 else i) }, modifier = Modifier.size(size + 12.dp)) {
                    Icon(icon, contentDescription = "$i star", tint = tint, modifier = Modifier.size(size))
                }
            } else if (filled) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
            }
        }
    }
}

/**
 * Feed row: bold title, then small "first two authors · year" on the left and the
 * journal on the right. Each author name opens that author's papers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(
    article: Article,
    onClick: () -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showRating: Boolean = true,
    extraMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }
    val prefs = LocalSettings.current
    val compact = prefs.feedStyle == FeedStyle.COMPACT
    val cards = prefs.feedStyle == FeedStyle.CARDS
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = if (cards) 14.dp else 16.dp, vertical = if (compact) 7.dp else 12.dp)
        ) {
            Text(
                article.title,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (article.read) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.onSurface,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            val abstract = article.abstract
            if (prefs.previewLines > 0 && !abstract.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    abstract.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = prefs.previewLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorsLine(
                    authors = article.shortAuthors(),
                    more = article.authorList.size > 2,
                    year = article.year,
                    onAuthorClick = onAuthorClick,
                    modifier = Modifier.weight(1f),
                )
                if (showRating && article.rating > 0) {
                    StarRating(article.rating, size = 13.dp, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Text(
                    article.journal,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                )
            }
        }
    }
    Box(modifier) {
        ScaledText(prefs.feedScale) {
            if (cards) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) { content() }
            } else content()
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Save to lists…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                onClick = { menu = false; saveSheet = true },
            )
            DropdownMenuItem(
                text = { Text("Copy title") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; copyText(context, "Title", article.title) },
            )
            DropdownMenuItem(
                text = { Text("Copy citation") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; copyText(context, "Citation", article.citation()) },
            )
            article.link?.let { link ->
                DropdownMenuItem(
                    text = { Text(if (article.doi != null) "Copy DOI link" else "Copy link") },
                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                    onClick = { menu = false; copyText(context, "Link", link) },
                )
            }
            DropdownMenuItem(
                text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menu = false; shareArticle(context, article) },
            )
            extraMenu?.invoke { menu = false }
        }
    }
    if (saveSheet) SaveToListsSheet(article, onDismiss = { saveSheet = false })
}

/** Row separator for the list layouts; cards are already separated. */
@Composable
fun FeedDivider() {
    if (LocalSettings.current.feedStyle != FeedStyle.CARDS) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
fun AuthorsLine(
    authors: List<String>,
    more: Boolean,
    year: Int?,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val linkStyle = TextLinkStyles(style = SpanStyle(color = muted), pressedStyle = SpanStyle(color = muted, background = muted.copy(alpha = 0.15f)))
    val text = buildAnnotatedString {
        authors.forEachIndexed { i, name ->
            if (i > 0) append(", ")
            withLink(LinkAnnotation.Clickable("author:$name", linkStyle) { onAuthorClick(name) }) { append(name) }
        }
        if (more) append(" et al.")
    }
    // The year sits in its own Text so long author names never push it out of view.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (year != null) {
            Text(
                (if (authors.isNotEmpty()) " · " else "") + year,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Bottom sheet with every list as a checkbox, a rating row and inline list creation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToListsSheet(article: Article, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lists by repo.lists().collectAsState(initial = emptyList())
    val inLists by repo.listIdsFor(article.key).collectAsState(initial = emptyList())
    val live by repo.article(article.key).collectAsState(initial = article)
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding().padding(bottom = 16.dp)) {
            Text(
                article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Importance", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
                StarRating(live?.rating ?: 0, onChange = { r -> scope.launch { repo.ensureStored(article); repo.setRating(article.key, r) } })
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().height((lists.size.coerceIn(1, 7) * 52).dp)) {
                items(lists, key = { it.id }) { list ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { scope.launch { repo.ensureStored(article); repo.toggleInList(list.id, article.key) } }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = list.id in inLists, onCheckedChange = {
                            scope.launch { repo.ensureStored(article); repo.toggleInList(list.id, article.key) }
                        })
                        Text(list.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New list") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) scope.launch {
                            val id = repo.createList(newName); repo.ensureStored(article); repo.addToList(id, article.key); newName = ""
                        }
                    }),
                )
                IconButton(enabled = newName.isNotBlank(), onClick = {
                    scope.launch {
                        val id = repo.createList(newName); repo.ensureStored(article); repo.addToList(id, article.key); newName = ""
                    }
                }) { Icon(Icons.Outlined.Add, "Create list") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) { Text("Done") }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

fun isReadLater(list: ArticleList) = list.id == ArticleList.READ_LATER_ID
