package com.tiersofexistence.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TierPurple = Color(0xFF6C4AB6)
private val TierIndigo = Color(0xFF1B1035)

private val DarkColors = darkColorScheme(
    primary = TierPurple,
    background = TierIndigo,
)

private val LightColors = lightColorScheme(
    primary = TierPurple,
)

@Composable
fun TiersOfExistenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
