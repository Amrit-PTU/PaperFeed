package com.paperfeed.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paperfeed.PaperFeedApp
import com.paperfeed.data.Repository
import com.paperfeed.net.Fetchers.SearchField
import com.paperfeed.search.SmartSearch.Hit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Mode(val label: String, val icon: ImageVector) {
    MEANING("Meaning", Icons.Outlined.AutoAwesome),
    WORDS("Exact words", Icons.Outlined.TextFields),
    AUTHOR("Author", Icons.Outlined.Person),
}

/**
 * Search. "Meaning" (default) embeds the keywords with the on-device bge-small model and finds
 * papers about that topic: papers already in the app plus the journal archives (Crossref) and
 * arXiv, all ranked by closeness in meaning. "Exact words" and "Author" are plain server searches.
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onArticle: (String) -> Unit, onAuthor: (String) -> Unit) {
    val app = PaperFeedApp.instance
    val smart = app.smart
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(Mode.MEANING) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var cursor by remember { mutableStateOf(Repository.SearchCursor()) }
    var queryVec by remember { mutableStateOf<FloatArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pending by smart.pending.collectAsState(initial = 0)
    val indexing by smart.indexing.collectAsState()
    val listState = rememberLazyListState()
    val finished = cursor.crossrefDone && cursor.arxivDone

    LaunchedEffect(Unit) { app.appScope.launch { runCatching { smart.index() } } }

    fun ordered(list: List<Hit>) = if (newest) list.sortedByDescending { it.article.published } else list

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
                queryVec = null
                listState.requestScrollToItem(0)
            }
            try {
                if (mode == Mode.MEANING) {
                    val qv = queryVec ?: run {
                        status = "Understanding your search…"
                        smart.queryVector(q).also { queryVec = it }
                    }
                    if (reset) {
                        results = ordered(smart.searchLibrary(q, qv))
                    }
                    status = "Searching journals and arXiv…"
                    val (items, next) = repo.search(q, SearchField.ANY, false, start)
                    cursor = next
                    val seen = results.map { it.article.key }.toSet()
                    status = "Ranking ${items.size} papers by meaning…"
                    val online = smart.rankOnline(q, qv, items.filter { it.key !in seen })
                    // The first page is merged with the library hits; later pages go below.
                    results = if (reset) ordered((results + online).sortedByDescending { it.score })
                    else results + ordered(online)
                } else {
                    val field = if (mode == Mode.AUTHOR) SearchField.AUTHOR else SearchField.ANY
                    status = null
                    val (items, next) = repo.search(q, field, newest, start)
                    cursor = next
                    results = (results + items.map { Hit(it, 0f, false) }).distinctBy { it.article.key }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Search failed"
            } finally {
                loading = false
                status = null
            }
        }
    }

    LaunchedEffect(Unit) { if (query.isBlank()) focus.requestFocus() }
    LaunchedEffect(mode, newest) { if (searchedFor != null) loadMore(reset = true) }

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
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text(
                                    when (mode) {
                                        Mode.AUTHOR -> "Author name, e.g. L. M. Robledo"
                                        Mode.MEANING -> "Topic or keywords"
                                        Mode.WORDS -> "Words in the title or abstract"
                                    },
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); loadMore(reset = true) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                        )
                        if (query.isNotEmpty()) IconButton(onClick = { query = ""; focus.requestFocus() }, modifier = Modifier.padding(end = 4.dp)) {
                            Icon(Icons.Outlined.Close, "Clear")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m, onClick = { mode = m }, label = { Text(m.label) },
                            leadingIcon = { Icon(m.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                        )
                    }
                    VerticalDivider(Modifier.height(24.dp))
                    FilterChip(selected = !newest, onClick = { newest = false }, label = { Text("Best match") })
                    FilterChip(selected = newest, onClick = { newest = true }, label = { Text("Newest") })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading && results.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val note = status ?: when {
                mode != Mode.MEANING -> "Searches every year of the journals in your sources, plus arXiv."
                pending > 0 && indexing -> "Finds papers on your topic, even without the exact words. Indexing $pending papers on this phone…"
                else -> "Finds papers on your topic, even without the exact words. Runs on this phone."
            }
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            when {
                searchedFor == null -> EmptyState("Type a topic, keywords or an author and press search.")
                results.isEmpty() && loading -> Box(Modifier.fillMaxSize())
                results.isEmpty() && error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Search failed: $error", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { loadMore(reset = true) }) { Text("Retry") }
                }
                results.isEmpty() -> EmptyState("No papers found for “$searchedFor”.")
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.article.key }) { h ->
                        ArticleRow(
                            h.article,
                            onClick = { scope.launch { repo.ensureStored(h.article); onArticle(h.article.key) } },
                            onAuthorClick = onAuthor,
                            modifier = Modifier.animateItem(),
                        )
                        FeedDivider()
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    status?.let {
                                        Text("  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
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
