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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import com.paperfeed.PaperFeedApp
import com.paperfeed.net.Text as TextUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onArticle: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onSupport: () -> Unit,
    onSearch: () -> Unit,
) {
    val app = PaperFeedApp.instance
    var sourceFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    val articles by remember(sourceFilter) { repo.endlessFeed(sourceFilter) }.collectAsState(initial = null)
    val sources by repo.sources().collectAsState(initial = emptyList())
    val refreshing by app.refreshing.collectAsState()
    val loadingOlder by repo.loadingOlder.collectAsState()
    var reachedEnd by remember(sourceFilter) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var sizeSheet by remember { mutableStateOf(false) }

    fun loadOlder() {
        if (loadingOlder || reachedEnd) return
        app.appScope.launch {
            val added = repo.loadOlder(sourceFilter)
            if (added < 0) reachedEnd = true
        }
    }

    LaunchedEffect(Unit) {
        app.messages.collect { snackbar.showSnackbar(it) }
    }
    // First launch: the database is empty, so fetch right away.
    LaunchedEffect(articles) {
        if (articles?.isEmpty() == true && sourceFilter == null && !refreshing && !app.didInitialRefresh) {
            app.didInitialRefresh = true
            app.refreshNow()
        }
    }
    // Endless scrolling: near the bottom, fetch the next older page from the journals.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, articles?.size) {
        if (nearEnd && !articles.isNullOrEmpty() && !refreshing) loadOlder()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Material You search bar, as in Google's own apps: tap it to search papers by meaning.
            Surface(
                onClick = onSearch,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.Outlined.Menu, "Menu") }
                    Text(
                        "Search papers",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = { sizeSheet = true }) { Icon(Icons.Outlined.TextFields, "Text size and layout") }
                    IconButton(onClick = onSupport, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.Outlined.Paid, "Support PaperFeed") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val enabled = sources.filter { it.enabled }
            if (enabled.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = sourceFilter == null, onClick = { sourceFilter = null }, label = { Text("All") }, shape = CircleShape)
                    enabled.forEach { s ->
                        FilterChip(
                            selected = sourceFilter == s.id,
                            onClick = { sourceFilter = if (sourceFilter == s.id) null else s.id },
                            label = { Text(s.name) },
                            shape = CircleShape,
                        )
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { reachedEnd = false; app.refreshNow() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val list = articles
                when {
                    list == null -> Box(Modifier.fillMaxSize())
                    list.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                if (refreshing) "Fetching the latest articles…" else "No articles yet. Pull down to refresh.",
                                Modifier.fillParentMaxSize(),
                            )
                        }
                    }
                    else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.key }) { a ->
                            ArticleRow(a, onClick = { onArticle(a.key) }, onAuthorClick = onAuthor, modifier = Modifier.animateItem())
                            FeedDivider()
                        }
                        item(key = "footer") {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                when {
                                    loadingOlder -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(
                                            "  Loading articles before ${list.last().dateText()}…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    reachedEnd -> Text(
                                        "No older articles available",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> TextButton(onClick = { loadOlder() }) { Text("Load older articles") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sizeSheet) TextSizeSheet(forFeed = true, onDismiss = { sizeSheet = false })
}
