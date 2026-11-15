package com.paperfeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.paperfeed.data.AppSettings

/** Settings that many leaf composables need; provided once at the root. */
val LocalSettings = staticCompositionLocalOf { AppSettings() }

/** Multiplies the text size of [content] (on top of the global size) without touching layout sizes. */
@Composable
fun ScaledText(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * scale), content = content)
}
