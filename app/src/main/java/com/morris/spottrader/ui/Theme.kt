package com.morris.spottrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.Exchanges

object Palette {
    val bg = Color(0xFF090B10); val panel = Color(0xFF10141B); val panel2 = Color(0xFF161B24); val field = Color(0xFF1B212C); val line = Color(0xFF232A36)
    val text = Color(0xFFE9EDF3); val dim = Color(0xFF7E8797); val faint = Color(0xFF4A5262)
    val up = Color(0xFF2EBD8E); val down = Color(0xFFF2566B); val accent = Color(0xFF7B8CFF); val warn = Color(0xFFE8B04B)
    val ma1 = Color(0xFFE8B04B); val ma2 = Color(0xFFB37BFF); val ma3 = Color(0xFF4FC3F7)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Palette.bg, surface = Palette.panel,
    onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.field, outline = Palette.line, secondary = Palette.accent), content = content)

@Composable
fun Chip(text: String, selected: Boolean, color: Color = Palette.accent, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) color.copy(alpha = 0.22f) else Palette.field).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, color = if (selected) color else Palette.dim, fontSize = 13.sp) }
}

@Composable
fun ExchangeBar(vm: AppVM) {
    @Suppress("UNUSED_VARIABLE") val v = vm.keysVersion
    Row(Modifier.fillMaxWidth().background(Palette.panel).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        Exchanges.all.forEach { id -> Chip(Exchanges.names[id]!! + if (vm.repo.get(id) != null) " •" else "", vm.exchangeId == id) { vm.selectExchange(id) } }
    }
}

@Composable
fun Lbl(t: String, c: Color = Palette.dim, s: Int = 12) = Text(t, color = c, fontSize = s.sp)
