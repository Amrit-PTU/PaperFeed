package com.paperfeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperfeed.data.Article
import kotlinx.coroutines.launch

/** All papers by one author, newest first: local feed items plus their OpenAlex profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorScreen(name: String, onBack: () -> Unit, onArticle: (String) -> Unit, onAuthor: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var local by remember { mutableStateOf<List<Article>>(emptyList()) }
    var online by remember { mutableStateOf<List<Article>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var onlyLocal by rememberSaveable { mutableStateOf(false) }

    // Online results come in pages of 100 (newest first); `cursor` continues where we stopped.
    var cursor by remember { mutableStateOf<String?>(null) }
    var onlineDone by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadPage() {
        if (loading && online.isNotEmpty()) return
        scope.launch {
            loading = true
            error = null
            runCatching { repo.authorPage(name, cursor) }
                .onSuccess { page ->
                    online = online + page.items
                    cursor = page.next
                    onlineDone = page.next == null || page.items.isEmpty()
                }
                .onFailure { error = it.message ?: "Online search failed" }
            loading = false
        }
    }

    LaunchedEffect(name) {
        local = repo.localByAuthor(name)
        loadPage()
    }

    val merged = remember(local, online, onlyLocal, onlineDone, error) {
        val localDois = local.mapNotNull { it.doi }.toSet()
        val localKeys = local.map { it.key }.toSet()
        if (onlyLocal) return@remember local.sortedByDescending { it.published }
        val extra = online.filter { it.key !in localKeys && (it.doi == null || it.doi !in localDois) }
        // Until all online pages are in, show local papers only down to the oldest loaded online one,
        // so the list stays in order while it grows.
        val floor = if (onlineDone || error != null || online.isEmpty()) Long.MIN_VALUE else online.minOf { it.published }
        // OpenAlex sometimes lists one DOI twice; duplicate keys would crash the LazyColumn.
        (local.filter { it.published >= floor } + extra).distinctBy { it.key }.sortedByDescending { it.published }
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, merged.size) {
        if (nearEnd && !onlyLocal && !onlineDone && !loading && error == null && online.isNotEmpty()) loadPage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SelectionContainer {
                        Text(
                            "${merged.size} article${if (merged.size == 1) "" else "s"} · newest first",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !onlyLocal, onClick = { onlyLocal = false }, label = { Text("All (online)") })
                        FilterChip(selected = onlyLocal, onClick = { onlyLocal = true }, label = { Text("From my feeds") })
                        if (loading) {
                            Spacer(Modifier.width(4.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Online lookup failed ($it)", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { loadPage() }) { Text("Retry") }
                        }
                    }
                }
            }
            if (!loading && merged.isEmpty()) item { EmptyState("No articles found for $name") }
            items(merged, key = { it.key }) { a ->
                ArticleRow(
                    a,
                    onClick = { scope.launch { repo.ensureStored(a); onArticle(a.key) } },
                    onAuthorClick = { if (it != name) onAuthor(it) },
                )
                FeedDivider()
            }
            if (!onlyLocal && online.isNotEmpty()) item(key = "footer") {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        onlineDone -> Text("That's every article OpenAlex has for $name", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> TextButton(onClick = { loadPage() }) { Text("Load older articles") }
                    }
                }
            }
        }
    }
}
