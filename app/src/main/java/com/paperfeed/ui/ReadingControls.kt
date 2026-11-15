package com.paperfeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperfeed.data.AppSettings
import com.paperfeed.data.FeedStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIN_SCALE = 0.8f
private const val MAX_SCALE = 1.8f

/**
 * Quick "Aa" panel. [forFeed] = true adjusts the article lists (size, layout, abstract preview);
 * false adjusts the article reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSizeSheet(forFeed: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = LocalSettings.current
    var scale by remember { mutableFloatStateOf(if (forFeed) s.feedScale else s.readerScale) }

    fun save(v: Float) {
        scale = ((v * 20).roundToInt() / 20f).coerceIn(MIN_SCALE, MAX_SCALE)
        scope.launch {
            repo.settings.update { if (forFeed) it.copy(feedScale = scale) else it.copy(readerScale = scale) }
        }
    }

    fun update(t: (AppSettings) -> AppSettings) = scope.launch { repo.settings.update(t) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(if (forFeed) "Article list" else "Reading", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { save(scale - 0.1f) }) { Icon(Icons.Outlined.TextDecrease, "Smaller text") }
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { save(scale) },
                    valueRange = MIN_SCALE..MAX_SCALE,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { save(scale + 0.1f) }) { Icon(Icons.Outlined.TextIncrease, "Larger text") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(1f) }) { Text("Reset") }
            }
            ScaledText(scale) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("Shell-model study of neutron-rich N=126 isotones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!forFeed) {
                        Text(
                            "Shell model calculations for ²⁰⁶Hg–¹⁹⁷Lu were performed with two parametrizations…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            if (forFeed) {
                Spacer(Modifier.height(12.dp))
                Text("Layout", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedStyle.entries.forEach { st ->
                        FilterChip(selected = s.feedStyle == st, onClick = { update { it.copy(feedStyle = st) } }, label = { Text(st.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Abstract preview", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 2 to "2 lines", 4 to "4 lines").forEach { (n, label) ->
                        FilterChip(selected = s.previewLines == n, onClick = { update { it.copy(previewLines = n) } }, label = { Text(label) })
                    }
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
