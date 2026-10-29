package com.example.speedcam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val Navy = Color(0xFF0B1F3A)
val NavyLight = Color(0xFF12304F)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F4C81), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6F7), onPrimaryContainer = Color(0xFF0B2A49),
    secondary = Color(0xFF00897B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFEA), onSecondaryContainer = Color(0xFF00423B),
    tertiary = Color(0xFFF59E0B), onTertiary = Color(0xFF3A2600),
    background = Color(0xFFF2F5F9), onBackground = Color(0xFF111A22),
    surface = Color.White, onSurface = Color(0xFF111A22),
    surfaceVariant = Color(0xFFE6ECF3), onSurfaceVariant = Color(0xFF475766),
    outline = Color(0xFFB8C4D0), outlineVariant = Color(0xFFDCE3EA),
    error = Color(0xFFD32F2F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF062546),
    primaryContainer = Color(0xFF16406B), onPrimaryContainer = Color(0xFFD6E6F7),
    secondary = Color(0xFF4DD0C4), onSecondary = Color(0xFF00352F),
    secondaryContainer = Color(0xFF0B4A43), onSecondaryContainer = Color(0xFFCDEFEA),
    tertiary = Color(0xFFFFCA28), onTertiary = Color(0xFF3A2600),
    background = Color(0xFF0D141C), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF16202B), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF212D3A), onSurfaceVariant = Color(0xFF9FB1C2),
    outline = Color(0xFF4A5B6C), outlineVariant = Color(0xFF2A3947),
    error = Color(0xFFFF6B6B)
)

@Composable
fun SpeedCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable
fun AppHeader(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(NavyLight)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.75f))
        }
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF9FB3C8), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
