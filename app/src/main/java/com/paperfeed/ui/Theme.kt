package com.paperfeed.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

data class ThemePreset(
    val id: String,
    val name: String,
    val dark: Boolean,
    val primary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
)

object Themes {
    const val SYSTEM = "system"
    const val DYNAMIC = "dynamic"
    const val CUSTOM = "custom"

    val presets = listOf(
        ThemePreset("light", "Paper", false, Color(0xFF1565C0), Color(0xFFFFFFFF), Color(0xFFF4F6FA), Color(0xFF1B1C1F)),
        ThemePreset("dark", "Night", true, Color(0xFF8AB4F8), Color(0xFF121212), Color(0xFF1E1F22), Color(0xFFE6E6E6)),
        ThemePreset("amoled", "AMOLED Black", true, Color(0xFF82B1FF), Color(0xFF000000), Color(0xFF0D0D0D), Color(0xFFEDEDED)),
        ThemePreset("sepia", "Sepia", false, Color(0xFFA0522D), Color(0xFFF4ECD8), Color(0xFFEADFC8), Color(0xFF4B3A2A)),
        ThemePreset("solarized_light", "Solarized Light", false, Color(0xFF268BD2), Color(0xFFFDF6E3), Color(0xFFEEE8D5), Color(0xFF4F5F66)),
        ThemePreset("solarized_dark", "Solarized Dark", true, Color(0xFF2AA198), Color(0xFF002B36), Color(0xFF073642), Color(0xFFA7B5B5)),
        ThemePreset("nord", "Nord", true, Color(0xFF88C0D0), Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFFECEFF4)),
        ThemePreset("dracula", "Dracula", true, Color(0xFFBD93F9), Color(0xFF282A36), Color(0xFF343746), Color(0xFFF8F8F2)),
        ThemePreset("gruvbox", "Gruvbox", true, Color(0xFFFABD2F), Color(0xFF282828), Color(0xFF3C3836), Color(0xFFEBDBB2)),
        ThemePreset("ocean", "Deep Ocean", true, Color(0xFF4FC3F7), Color(0xFF0B1E2D), Color(0xFF12293B), Color(0xFFD6E9F8)),
        ThemePreset("forest", "Forest", false, Color(0xFF2E7D32), Color(0xFFF3F8F2), Color(0xFFE3EEE1), Color(0xFF1B2E1B)),
        ThemePreset("rose", "Rosé", false, Color(0xFFC2185B), Color(0xFFFFF7F9), Color(0xFFF8E6EC), Color(0xFF3E1F2A)),
    )

    fun decodeCustom(encoded: String): ThemePreset {
        val p = encoded.split(';').mapNotNull { it.toIntOrNull() }
        val d = presets[0]
        return ThemePreset(
            CUSTOM, "Custom", (p.getOrNull(0) ?: 0) == 1,
            p.getOrNull(1)?.let { Color(it) } ?: d.primary,
            p.getOrNull(2)?.let { Color(it) } ?: d.background,
            p.getOrNull(3)?.let { Color(it) } ?: d.surface,
            p.getOrNull(4)?.let { Color(it) } ?: d.text,
        )
    }

    fun encodeCustom(t: ThemePreset): String =
        listOf(if (t.dark) 1 else 0, t.primary.toArgb(), t.background.toArgb(), t.surface.toArgb(), t.text.toArgb())
            .joinToString(";")

    private fun on(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

    fun scheme(t: ThemePreset): ColorScheme {
        val muted = lerp(t.text, t.background, 0.38f)
        val variant = lerp(t.surface, t.text, 0.07f)
        val primaryContainer = lerp(t.primary, t.background, if (t.dark) 0.6f else 0.78f)
        val base = if (t.dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = t.primary,
            onPrimary = on(t.primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = on(primaryContainer),
            secondary = lerp(t.primary, t.text, 0.35f),
            onSecondary = on(lerp(t.primary, t.text, 0.35f)),
            secondaryContainer = lerp(t.primary, t.surface, 0.82f),
            onSecondaryContainer = t.text,
            tertiary = t.primary,
            background = t.background,
            onBackground = t.text,
            surface = t.background,
            onSurface = t.text,
            surfaceVariant = variant,
            onSurfaceVariant = muted,
            surfaceTint = t.primary,
            surfaceContainerLowest = t.background,
            surfaceContainerLow = lerp(t.background, t.surface, 0.5f),
            surfaceContainer = t.surface,
            surfaceContainerHigh = lerp(t.surface, t.text, 0.04f),
            surfaceContainerHighest = lerp(t.surface, t.text, 0.08f),
            surfaceBright = lerp(t.surface, Color.White, 0.05f),
            surfaceDim = lerp(t.surface, Color.Black, 0.05f),
            outline = lerp(t.text, t.background, 0.6f),
            outlineVariant = lerp(t.text, t.background, 0.82f),
            inverseSurface = t.text,
            inverseOnSurface = t.background,
            inversePrimary = lerp(t.primary, if (t.dark) Color.Black else Color.White, 0.4f),
        )
    }
}

/** Star color used for ratings; readable on every theme. */
val StarColor = Color(0xFFFFB300)

@Composable
fun PaperFeedTheme(themeId: String, customTheme: String, textScale: Float, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when (themeId) {
        Themes.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else Themes.scheme(Themes.presets[if (systemDark) 1 else 0])
        Themes.SYSTEM -> Themes.scheme(Themes.presets[if (systemDark) 1 else 0])
        Themes.CUSTOM -> Themes.scheme(Themes.decodeCustom(customTheme))
        else -> Themes.scheme(Themes.presets.firstOrNull { it.id == themeId } ?: Themes.presets[0])
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

fun isDarkScheme(scheme: ColorScheme) = scheme.background.luminance() < 0.4f
