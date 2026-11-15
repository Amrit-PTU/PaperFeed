package com.paperfeed.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperfeed.data.AppSettings
import kotlinx.coroutines.launch

private val swatches = listOf(
    0xFFFFFFFF, 0xFFF5F5F5, 0xFFFDF6E3, 0xFFF4ECD8, 0xFFE8F0FE, 0xFF000000, 0xFF121212, 0xFF1E1F22,
    0xFF282A36, 0xFF2E3440, 0xFF002B36, 0xFF0B1E2D, 0xFF1565C0, 0xFF3F51B5, 0xFF6750A4, 0xFF00897B,
    0xFF2E7D32, 0xFFF9A825, 0xFFEF6C00, 0xFFC62828, 0xFFC2185B, 0xFF8AB4F8, 0xFFBD93F9, 0xFF88C0D0,
    0xFFFABD2F, 0xFF4FC3F7, 0xFFE6E6E6, 0xFF1B1C1F, 0xFF4B3A2A, 0xFF586E75,
).map { Color(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s by repo.settings.flow.collectAsState(initial = AppSettings())
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    fun select(id: String) = scope.launch { repo.settings.update { it.copy(themeId = id) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                val p = Themes.presets[if (systemDark) 1 else 0]
                ThemeCard(p.copy(name = "Follow system"), s.themeId == Themes.SYSTEM) { select(Themes.SYSTEM) }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                item {
                    val dyn = if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    ThemeCard(
                        ThemePreset(Themes.DYNAMIC, "Material You", systemDark, dyn.primary, dyn.background, dyn.surfaceContainer, dyn.onBackground),
                        s.themeId == Themes.DYNAMIC,
                    ) { select(Themes.DYNAMIC) }
                }
            }
            items(Themes.presets, key = { it.id }) { p ->
                ThemeCard(p, s.themeId == p.id) { select(p.id) }
            }
            item {
                ThemeCard(Themes.decodeCustom(s.customTheme), s.themeId == Themes.CUSTOM) { select(Themes.CUSTOM) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CustomThemeEditor(s.customTheme) { encoded ->
                    scope.launch { repo.settings.update { it.copy(customTheme = encoded, themeId = Themes.CUSTOM) } }
                }
            }
        }
    }
}

/** Miniature of a feed row rendered in the theme's own colors. */
@Composable
private fun ThemeCard(t: ThemePreset, selected: Boolean, onClick: () -> Unit) {
    val muted = lerp(t.text, t.background, 0.4f)
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .background(t.background)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)).background(t.surface))
            Spacer(Modifier.height(10.dp))
            Text("Nuclear pairing in N=Z nuclei", color = t.text, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A. Bohr · 2026", color = muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("Phys. Rev. C", color = t.primary, fontSize = 9.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = t.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CustomThemeEditor(encoded: String, onChange: (String) -> Unit) {
    val t = Themes.decodeCustom(encoded)
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Custom theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dark base (status bar, dialogs)", Modifier.weight(1f))
            Switch(checked = t.dark, onCheckedChange = { onChange(Themes.encodeCustom(t.copy(dark = it))) })
        }
        ColorRow("Accent", t.primary) { onChange(Themes.encodeCustom(t.copy(primary = it))) }
        ColorRow("Background", t.background) { onChange(Themes.encodeCustom(t.copy(background = it))) }
        ColorRow("Cards & bars", t.surface) { onChange(Themes.encodeCustom(t.copy(surface = it))) }
        ColorRow("Text", t.text) { onChange(Themes.encodeCustom(t.copy(text = it))) }
    }
}

@Composable
private fun ColorRow(label: String, color: Color, onPick: (Color) -> Unit) {
    var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = hex,
                onValueChange = { v ->
                    hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    if (hex.length == 6) onPick(Color(0xFF000000 or hex.toLong(16)))
                },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            swatches.forEach { c ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { onPick(c) }
                )
            }
        }
    }
}
