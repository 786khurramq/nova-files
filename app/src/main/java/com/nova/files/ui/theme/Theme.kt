package com.nova.files.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nova.files.core.DarkMode
import com.nova.files.core.FileCategory
import com.nova.files.core.ThemeChoice

@Immutable
data class NovaPalette(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val outline: Color,
    val text: Color, val muted: Color, val accent: Color, val accent2: Color, val onAccent: Color,
    val dark: Boolean, val glow: Float,
) {
    val accentBrush get() = Brush.linearGradient(listOf(accent, accent2))
}

object Spectrum {
    val Documents = Color(0xFF4F8BFF)
    val Apps = Color(0xFFA15CFF)
    val Downloads = Color(0xFFFF9640)
    val Photos = Color(0xFFFF5CAA)
    val Audio = Color(0xFF3CD678)
    val Video = Color(0xFFFF6E60)
    val Transfer = Color(0xFF19D3C5)
    val Cloud = Color(0xFF8A6CFF)
    val Cleaner = Color(0xFF5EF2B8)
    val Archive = Color(0xFFFFC94D)
    val Folder = Color(0xFF6FA8FF)
    val Danger = Color(0xFFFF5470)
    val Success = Color(0xFF3CD678)
    val bar = Brush.horizontalGradient(listOf(Documents, Apps, Photos, Downloads))

    fun forCategory(c: FileCategory): Color = when (c) {
        FileCategory.FOLDER -> Folder; FileCategory.DOCUMENT -> Documents; FileCategory.APK -> Apps
        FileCategory.ARCHIVE -> Archive; FileCategory.IMAGE -> Photos; FileCategory.AUDIO -> Audio
        FileCategory.VIDEO -> Video; FileCategory.OTHER -> Color(0xFF8E95AB)
    }
}

private fun darkPalette(t: ThemeChoice) = when (t) {
    ThemeChoice.SPECTRUM -> NovaPalette(Color(0xFF000000), Color(0xFF0E0F14), Color(0xFF181A23), Color(0xFF2A2D3C), Color(0xFFF2F4FA), Color(0xFF9BA1B5), Color(0xFF7C6CFF), Color(0xFF19D3C5), Color.White, true, 0.35f)
    ThemeChoice.MIDNIGHT -> NovaPalette(Color(0xFF04060D), Color(0xFF0B1020), Color(0xFF131B34), Color(0xFF233056), Color(0xFFEAF0FF), Color(0xFF8E9BC0), Color(0xFF4F8BFF), Color(0xFF2EE6C9), Color.White, true, 0.3f)
    ThemeChoice.MINIMAL -> NovaPalette(Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFF1E1E1E), Color(0xFF2E2E2E), Color(0xFFF0F0F0), Color(0xFF9A9A9A), Color(0xFFEDEDED), Color(0xFFBDBDBD), Color(0xFF0A0A0A), true, 0f)
    ThemeChoice.CYBER -> NovaPalette(Color(0xFF020207), Color(0xFF0A0A17), Color(0xFF13132A), Color(0xFF26264A), Color(0xFFEFFBFF), Color(0xFF8C93B8), Color(0xFF00E5FF), Color(0xFFFF2BD6), Color(0xFF02020A), true, 0.55f)
    ThemeChoice.CLAY -> NovaPalette(Color(0xFF16110E), Color(0xFF211A16), Color(0xFF2C231E), Color(0xFF3F332C), Color(0xFFF6EEE7), Color(0xFFB4A396), Color(0xFFE07A5F), Color(0xFFF2CC8F), Color(0xFF1A110C), true, 0.15f)
    ThemeChoice.GLASS -> NovaPalette(Color(0xFF0A0E13), Color(0xCC141B24), Color(0xD91C2632), Color(0x4D9DB4D1), Color(0xFFEFF5FF), Color(0xFF9AA9BD), Color(0xFF8FB8FF), Color(0xFFB8F2E6), Color(0xFF0A0E13), true, 0.25f)
}

private fun lightPalette(t: ThemeChoice) = when (t) {
    ThemeChoice.SPECTRUM -> NovaPalette(Color(0xFFF6F7FB), Color.White, Color(0xFFEEF0F6), Color(0xFFDADDE8), Color(0xFF11131B), Color(0xFF5D6378), Color(0xFF5B4BEA), Color(0xFF0FA89C), Color.White, false, 0.12f)
    ThemeChoice.MIDNIGHT -> NovaPalette(Color(0xFFF3F6FD), Color.White, Color(0xFFE8EEFB), Color(0xFFD3DCF0), Color(0xFF0D1428), Color(0xFF56628A), Color(0xFF2F6CE5), Color(0xFF12A891), Color.White, false, 0.1f)
    ThemeChoice.MINIMAL -> NovaPalette(Color(0xFFFAFAFA), Color.White, Color(0xFFF0F0F0), Color(0xFFDDDDDD), Color(0xFF111111), Color(0xFF666666), Color(0xFF111111), Color(0xFF555555), Color.White, false, 0f)
    ThemeChoice.CYBER -> NovaPalette(Color(0xFFF4F7FF), Color.White, Color(0xFFEAF0FF), Color(0xFFD4DBF2), Color(0xFF0A0C1C), Color(0xFF5A6288), Color(0xFF0091A8), Color(0xFFC0129E), Color.White, false, 0.15f)
    ThemeChoice.CLAY -> NovaPalette(Color(0xFFF5EFE8), Color(0xFFFFFAF4), Color(0xFFEFE6DC), Color(0xFFDDCFC1), Color(0xFF2A1F19), Color(0xFF7C6A5D), Color(0xFFC75C41), Color(0xFFB68A3E), Color.White, false, 0.08f)
    ThemeChoice.GLASS -> NovaPalette(Color(0xFFEFF3F8), Color(0xE6FFFFFF), Color(0xD9F1F5FA), Color(0x66A9B8CC), Color(0xFF0F1722), Color(0xFF5B6B80), Color(0xFF3B74D6), Color(0xFF2E9C8A), Color.White, false, 0.1f)
}

data class NovaEnv(val palette: NovaPalette, val isTv: Boolean, val hasTouch: Boolean)

val LocalNova = staticCompositionLocalOf { NovaEnv(darkPalette(ThemeChoice.SPECTRUM), false, true) }

object Nova {
    val colors: NovaPalette @Composable get() = LocalNova.current.palette
    val isTv: Boolean @Composable get() = LocalNova.current.isTv
}

private fun typography(scale: Float, c: NovaPalette): Typography {
    fun s(size: Int, w: FontWeight, ls: Double = 0.0) = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = (size * scale).sp, fontWeight = w, letterSpacing = ls.sp, color = c.text)
    return Typography(
        displaySmall = s(34, FontWeight.Bold, -0.5),
        headlineMedium = s(26, FontWeight.Bold, -0.3),
        headlineSmall = s(22, FontWeight.SemiBold, -0.2),
        titleLarge = s(19, FontWeight.SemiBold),
        titleMedium = s(16, FontWeight.SemiBold),
        titleSmall = s(14, FontWeight.Medium),
        bodyLarge = s(16, FontWeight.Normal),
        bodyMedium = s(14, FontWeight.Normal),
        bodySmall = s(12, FontWeight.Normal),
        labelLarge = s(14, FontWeight.SemiBold, 0.1),
        labelMedium = s(12, FontWeight.Medium, 0.2),
        labelSmall = s(11, FontWeight.Medium, 0.3),
    )
}

@Composable
fun NovaTheme(theme: ThemeChoice, mode: DarkMode, isTv: Boolean, hasTouch: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { DarkMode.DARK -> true; DarkMode.LIGHT -> false; DarkMode.SYSTEM -> isSystemInDarkTheme() }
    val p = if (dark) darkPalette(theme) else lightPalette(theme)
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh, error = Spectrum.Danger,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh, error = Spectrum.Danger,
    )
    CompositionLocalProvider(LocalNova provides NovaEnv(p, isTv, hasTouch)) {
        MaterialTheme(colorScheme = scheme, typography = typography(if (isTv) 1.12f else 1f, p), content = content)
    }
}
