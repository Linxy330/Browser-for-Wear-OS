package com.kenny.browserforwearos.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

@Composable
fun BrowserForWearOSTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Color(0xFFB8EDE2), onPrimary = Color(0xFF00382F),
            primaryContainer = Color(0xFF164E44), onPrimaryContainer = Color(0xFFD4FFF2),
        ),
        content = content
    )
}
