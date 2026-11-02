package com.morris.spottrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

object OpenSource {
    data class Lib(val name: String, val url: String, val license: String, val notice: String, val asset: String?)
    val libs = listOf(
        Lib("KLineChart 10.1.0", "https://github.com/klinecharts/KLineChart", "Apache License 2.0",
            "KLineChart\nCopyright (c) 2019 lihu\n\nTradingView Lightweight Charts\nCopyright (c) 2019 TradingView, Inc. https://www.tradingview.com\n\n本应用将 KLineChart 的 UMD 构建（未修改）随 APK 打包于 assets/kline/，图表在本地 WebView 中渲染，不访问 CDN。",
            "kline/LICENSE-KLineChart.txt"),
        Lib("TradingView Lightweight Charts (KLineChart 中的部分代码)", "https://github.com/tradingview/lightweight-charts", "Apache License 2.0", "Copyright (c) 2019 TradingView, Inc.", "kline/LICENSE-lightweight-charts.txt"),
        Lib("Jesse (仓位/风险逻辑参考)", "https://github.com/jesse-ai/jesse", "MIT License",
            "Copyright (c) 2020 Jesse.Trade\n\n仓位计算（risk_to_qty）、持仓均价与已实现盈亏的思路参考 Jesse，并以 Kotlin 重写；未直接复制其代码。\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.", null),
        Lib("OkHttp", "https://github.com/square/okhttp", "Apache License 2.0", "Copyright 2019 Square, Inc.", null),
        Lib("AndroidX / Jetpack Compose / Kotlin Coroutines", "https://developer.android.com/jetpack", "Apache License 2.0", "Copyright The Android Open Source Project / JetBrains s.r.o.", null))
}

/** 开源许可 page (Apache-2.0 LICENSE/NOTICE attribution). */
@Composable
fun LicensesScreen(onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current; val preview = LocalInspectionMode.current
    var full by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row { Text("开源许可", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (onClose != null) TextButton(onClose) { Text("关闭") } }
        OpenSource.libs.forEach { l -> Column(Modifier.padding(vertical = 8.dp)) {
            Text(l.name, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold); Lbl(l.url, s = 11); Lbl(l.license, Palette.warn, 12)
            Text(l.notice, color = Palette.text, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            if (l.asset != null && !preview) TextButton({ full = runCatching { ctx.assets.open(l.asset).bufferedReader().readText() }.getOrNull() }) { Text("查看完整 ${l.license} 文本", fontSize = 12.sp) } }
            HorizontalDivider(color = Palette.line, thickness = 0.5.dp) }
        full?.let { Text(it, color = Palette.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
fun LicensesDialog(onClose: () -> Unit) = Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) { LicensesScreen(onClose) }
