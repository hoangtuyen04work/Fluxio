package com.example.fluxio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = FluxioBlueDark,
    onPrimary = FluxioOnBlueDark,
    primaryContainer = FluxioBlueContainerDark,
    onPrimaryContainer = FluxioOnBlueContainerDark,
    secondary = FluxioTealDark,
    onSecondary = FluxioOnTealDark,
    secondaryContainer = FluxioTealContainerDark,
    onSecondaryContainer = FluxioOnTealContainerDark,
    background = FluxioBackgroundDark,
    surface = FluxioSurfaceDark,
    surfaceVariant = FluxioSurfaceVariantDark,
    outline = FluxioOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = FluxioBlueLight,
    onPrimary = FluxioOnBlueLight,
    primaryContainer = FluxioBlueContainerLight,
    onPrimaryContainer = FluxioOnBlueContainerLight,
    secondary = FluxioTealLight,
    onSecondary = FluxioOnTealLight,
    secondaryContainer = FluxioTealContainerLight,
    onSecondaryContainer = FluxioOnTealContainerLight,
    background = FluxioBackgroundLight,
    surface = FluxioSurfaceLight,
    surfaceVariant = FluxioSurfaceVariantLight,
    outline = FluxioOutlineLight
)

@Composable
fun FluxioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
