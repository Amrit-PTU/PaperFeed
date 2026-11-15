package com.paperfeed.work

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.paperfeed.PaperFeedApp
import com.paperfeed.ui.PaperFeedTheme
import com.paperfeed.ui.StarRating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Dialog opened from the "Lists…" notification button: tick any lists (or create one)
 * and set a star rating without opening the full app.
 */
class ListPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(Notifier.EXTRA_ARTICLE_KEY) ?: return finish()
        val app = application as PaperFeedApp
        val repo = app.repo
        setContent {
            val settings by repo.settings.flow.collectAsState(initial = null)
            val s = settings ?: return@setContent
            PaperFeedTheme(s.themeId, s.customTheme, s.textScale) {
                val lists by repo.lists().collectAsState(initial = emptyList())
                val article by repo.article(key).collectAsState(initial = null)
                val checked = remember { mutableStateListOf<Long>() }
                var initial by remember { mutableStateOf<Set<Long>?>(null) }
                var rating by remember { mutableStateOf<Int?>(null) }
                var newName by remember { mutableStateOf("") }
                LaunchedEffect(key) {
                    val ids = repo.listIdsFor(key).first()
                    checked.addAll(ids); initial = ids.toSet()
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = {
                        Text(article?.title ?: "Save to lists", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    },
                    text = {
                        Column {
                            StarRating(rating ?: article?.rating ?: 0, onChange = { rating = it })
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(lists, key = { it.id }) { l ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { if (l.id in checked) checked.remove(l.id) else checked.add(l.id) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = l.id in checked, onCheckedChange = { on -> if (on) checked.add(l.id) else checked.remove(l.id) })
                                        Text(l.name)
                                    }
                                }
                            }
                            OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("New list (optional)") })
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            lifecycleScope.launch {
                                val toAdd = checked.toMutableList()
                                if (newName.isNotBlank()) toAdd += repo.createList(newName)
                                val before = initial.orEmpty()
                                (before - toAdd.toSet()).forEach { repo.removeFromList(it, key) }
                                rating?.let { repo.setRating(key, it) }
                                saveAndRefreshNotification(applicationContext, key, toAdd.filter { it !in before })
                                finish()
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}
