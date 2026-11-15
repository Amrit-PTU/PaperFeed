package com.paperfeed.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paperfeed.data.Article
import com.paperfeed.data.Repository
import com.paperfeed.net.Fetchers.SearchField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Searches the complete archive of every journal in the user's sources (via Crossref, all years)
 * plus arXiv, by title/keywords or by author. Results load page by page while scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onArticle: (String) -> Unit, onAuthor: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf(SearchField.ANY) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Article>>(emptyList()) }
    var cursor by remember { mutableStateOf(Repository.SearchCursor()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val sources by repo.sources().collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    val finished = cursor.crossrefDone && cursor.arxivDone

    fun loadMore(reset: Boolean) {
        val q = query.trim()
        if (q.length < 2) return
        if (!reset && (loading || finished)) return
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            val start = if (reset) Repository.SearchCursor() else cursor
            if (reset) {
                results = emptyList()
                searchedFor = q
                // Non-suspending: the list may not be on screen yet (scrollToItem would wait for it forever).
                listState.requestScrollToItem(0)
            }
            try {
                val (items, next) = repo.search(q, field, newest, start)
                results = (results + items).distinctBy { it.key }
                cursor = next
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (query.isBlank()) focus.requestFocus() }
    LaunchedEffect(field, newest) { if (searchedFor != null) loadMore(reset = true) }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, results.size) {
        if (nearEnd && results.isNotEmpty() && error == null) loadMore(reset = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (field == SearchField.AUTHOR) "Author name, e.g. L. M. Robledo" else "Title, topic, keywords…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); loadMore(reset = true) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; focus.requestFocus() }) { Icon(Icons.Outlined.Close, "Clear") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = field == SearchField.ANY, onClick = { field = SearchField.ANY }, label = { Text("Title & keywords") })
                FilterChip(selected = field == SearchField.AUTHOR, onClick = { field = SearchField.AUTHOR }, label = { Text("Author") })
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = !newest, onClick = { newest = false }, label = { Text("Best match") })
                FilterChip(selected = newest, onClick = { newest = true }, label = { Text("Newest first") })
            }
            val enabled = sources.filter { it.enabled }
            Text(
                "Searches every year of the ${enabled.count { it.type.name != "ARXIV" }} journals in your sources" +
                    (if (enabled.any { it.type.name == "ARXIV" }) " and arXiv" else "") + ".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                searchedFor == null -> EmptyState("Type a title, topic or author and press search.")
                results.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results.isEmpty() && error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Search failed: $error", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { loadMore(reset = true) }) { Text("Retry") }
                }
                results.isEmpty() -> EmptyState("No articles found for “$searchedFor”.")
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.key }) { a ->
                        ArticleRow(
                            a,
                            onClick = { scope.launch { repo.ensureStored(a); onArticle(a.key) } },
                            onAuthorClick = onAuthor,
                        )
                        FeedDivider()
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                error != null -> TextButton(onClick = { loadMore(reset = false) }) { Text("Couldn't load more · Retry") }
                                finished -> Text("End of results", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> TextButton(onClick = { loadMore(reset = false) }) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }
}
