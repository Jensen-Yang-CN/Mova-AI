package com.mova.sceneai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 允许任何组件直接取语义色，而不必层层传参。 */
val LocalMovaSemanticColors = staticCompositionLocalOf { LightSemanticColors }

private val LightScheme = lightColorScheme(
    primary = MovaPrimaryLight,
    onPrimary = MovaOnPrimaryLight,
    primaryContainer = MovaPrimaryContainerLight,
    onPrimaryContainer = MovaOnPrimaryContainerLight,
    secondary = MovaSecondaryLight,
    onSecondary = MovaOnSecondaryLight,
    secondaryContainer = MovaSecondaryContainerLight,
    onSecondaryContainer = MovaOnSecondaryContainerLight,
    tertiary = MovaTertiaryLight,
    tertiaryContainer = MovaTertiaryContainerLight,
    background = MovaSurfaceLight,
    onBackground = MovaOnSurfaceLight,
    surface = MovaSurfaceLight,
    onSurface = MovaOnSurfaceLight,
    surfaceVariant = MovaSurfaceVariantLight,
    onSurfaceVariant = MovaOnSurfaceVariantLight,
    surfaceContainer = MovaSurfaceContainerLight,
    surfaceContainerHigh = MovaSurfaceContainerHighLight,
    outline = MovaOutlineLight,
    error = MovaErrorLight,
    onError = MovaOnErrorLight,
    errorContainer = MovaErrorContainerLight,
)

private val DarkScheme = darkColorScheme(
    primary = MovaPrimaryDark,
    onPrimary = MovaOnPrimaryDark,
    primaryContainer = MovaPrimaryContainerDark,
    onPrimaryContainer = MovaOnPrimaryContainerDark,
    secondary = MovaSecondaryDark,
    onSecondary = MovaOnSecondaryDark,
    secondaryContainer = MovaSecondaryContainerDark,
    onSecondaryContainer = MovaOnSecondaryContainerDark,
    tertiary = MovaTertiaryDark,
    tertiaryContainer = MovaTertiaryContainerDark,
    background = MovaSurfaceDark,
    onBackground = MovaOnSurfaceDark,
    surface = MovaSurfaceDark,
    onSurface = MovaOnSurfaceDark,
    surfaceVariant = MovaSurfaceVariantDark,
    onSurfaceVariant = MovaOnSurfaceVariantDark,
    surfaceContainer = MovaSurfaceContainerDark,
    surfaceContainerHigh = MovaSurfaceContainerHighDark,
    outline = MovaOutlineDark,
    error = MovaErrorDark,
    onError = MovaOnErrorDark,
    errorContainer = MovaErrorContainerDark,
)

@Composable
fun MovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            // 透明系统栏 + 由本主题决定图标明暗，避免深色模式下状态栏图标看不清
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalMovaSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MovaTypography,
            content = content,
        )
    }
}
