package com.morris.spottrader.ui

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

val PAIRS = Quotes.fallback()

// ---------- formatting ----------
fun BigDecimal.dp(n: Int): String = setScale(n, RoundingMode.HALF_UP).toPlainString()
fun compact(v: BigDecimal): String { val d = v.toDouble(); return when { d >= 1e9 -> "%.2fB".format(d / 1e9); d >= 1e6 -> "%.2fM".format(d / 1e6); d >= 1e3 -> "%.2fK".format(d / 1e3); else -> "%.2f".format(d) } }
private val mono = FontFamily.Monospace

@Composable
fun TradeScreen(vm: AppVM) {
    LazyColumn(Modifier.fillMaxSize().background(Palette.bg)) {
        item { PairBar(vm) }
        item { StatsStrip(vm) }
        item { ChartCard(vm) }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Box(Modifier.weight(0.45f)) { BookPanel(vm) }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(0.55f)) { OrderForm(vm) } } }
        item { ChasePanel(com.morris.spottrader.chase.ChaseManager.tasks) }
        item { BottomPanel(vm) }
    }
}

// ---------- pair bar + selector ----------
@Composable
fun PairBar(vm: AppVM) {
    var open by remember { mutableStateOf(false) }
    val fav = vm.pair.toString() in vm.favorites
    Row(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (fav) "★" else "☆", color = if (fav) Palette.warn else Palette.dim, fontSize = 18.sp, modifier = Modifier.clickable { vm.toggleFav(vm.pair) }.padding(end = 8.dp))
        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.pair.base, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("/${vm.pair.quote}", color = Palette.dim, fontSize = 14.sp)
            Text("  ▾", color = Palette.dim) }
        Spacer(Modifier.width(8.dp))
        Tag("现货", Palette.accent); Spacer(Modifier.width(4.dp)); Tag(Exchanges.names[vm.exchangeId] ?: "", Palette.dim)
        Spacer(Modifier.weight(1f))
        vm.ticker?.let { val c = if (it.changePct.signum() >= 0) Palette.up else Palette.down
            Text("${if (it.changePct.signum() >= 0) "+" else ""}${it.changePct.dp(2)}%", color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)) }
    }
    if (open) PairPicker(vm) { open = false }
}

@Composable
fun Tag(t: String, c: Color) = Text(t, color = c, fontSize = 10.sp, modifier = Modifier.border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))

@Composable
fun PairPicker(vm: AppVM, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) { PairPickerContent(vm, onClose) }
}

@Composable
fun PairPickerContent(vm: AppVM, onClose: () -> Unit, initialTab: String = "FAV", initialQuery: String = "") {
    var q by remember { mutableStateOf(initialQuery) }
    var tab by remember { mutableStateOf(if (initialTab == "FAV" && vm.favorites.isEmpty()) "USDT" else initialTab) }
    var other by remember { mutableStateOf<String?>(null) }
    val rank = remember { Quotes.POPULAR.withIndex().associate { it.value to it.index } }
    val syms = vm.symbols
    val sorted = remember(syms) { syms.sortedWith(compareBy({ rank[it.base] ?: 10_000 }, { it.base }, { it.quote })) }
    val counts = remember(syms) { syms.groupingBy { it.quote }.eachCount() }
    val otherQuotes = remember(counts) { counts.filterKeys { it != "USDT" && it != "USDC" }.entries.sortedByDescending { it.value }.take(14).map { it.key } }
    val key = q.trim().uppercase()
    val qBase = key.substringBefore("/"); val qQuote = key.substringAfter("/", "")
    fun matches(p: SymPair) = key.isEmpty() || (p.base.contains(qBase) && (qQuote.isEmpty() || p.quote.startsWith(qQuote)))
    val base: List<SymPair> = when (tab) {
        "FAV" -> vm.favorites.mapNotNull { f -> f.split("/").takeIf { it.size == 2 }?.let { SymPair(it[0], it[1]) } }.filter { vm.hasSymbol(it) }.sortedBy { it.toString() }
        "USDT" -> sorted.filter { it.quote == "USDT" }
        "USDC" -> sorted.filter { it.quote == "USDC" }
        "OTHER" -> sorted.filter { it.quote != "USDT" && it.quote != "USDC" && (other == null || it.quote == other) }
        else -> sorted }
    val list = base.filter(::matches)
    // typing a pair like "ARB/USDC" or a coin that only exists under another quote: offer results from all quotes
    val widened = if (list.isEmpty() && key.isNotEmpty() && tab != "ALL") sorted.filter(::matches) else emptyList()
    Surface(color = Palette.panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) { Column(Modifier.padding(12.dp)) {
        SearchField(q) { q = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Chip("自选 ★", tab == "FAV") { tab = "FAV" }
            Chip("USDT ${counts["USDT"] ?: ""}", tab == "USDT") { tab = "USDT" }
            Chip("USDC ${counts["USDC"] ?: ""}", tab == "USDC", Palette.warn) { tab = "USDC" }
            Chip("其他", tab == "OTHER") { tab = "OTHER" }
            Chip("全部 ${syms.size}", tab == "ALL") { tab = "ALL" } }
        if (tab == "OTHER") Row(Modifier.horizontalScroll(rememberScrollState())) {
            Chip("全部", other == null) { other = null }
            otherQuotes.forEach { oq -> Chip("$oq ${counts[oq]}", other == oq) { other = oq } } }
        Row(Modifier.padding(vertical = 4.dp)) { Lbl("${Exchanges.names[vm.exchangeId]} · ${list.size} 个币对", s = 11); Spacer(Modifier.weight(1f)); Lbl("自选", s = 11) }
        if (vm.symbolsStatus.isNotEmpty()) Text(vm.symbolsStatus, color = if (vm.symbolsStatus.startsWith("币对列表加载失败")) Palette.warn else Palette.dim, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(list.take(400)) { p -> PairRow(vm, p, null) { vm.selectPair(p); onClose() } }
            if (list.size > 400) item { Lbl("仅显示前400个，请用搜索缩小范围", s = 11) }
            if (list.isEmpty() && widened.isNotEmpty()) { item { Lbl("当前分类无结果，显示其他计价币种:", s = 11) }; items(widened.take(100)) { p -> PairRow(vm, p, null) { vm.selectPair(p); onClose() } } }
            if (list.isEmpty() && widened.isEmpty()) item { Lbl(if (tab == "FAV") "暂无自选，点击 ☆ 添加" else if (vm.symbols.isEmpty()) "币对加载中…" else "无匹配币对", s = 13) }
        }
    } }
}

@Composable
fun SearchField(v: String, on: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.field).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕ ", color = Palette.dim)
        BasicTextField(v, on, singleLine = true, textStyle = TextStyle(color = Palette.text, fontSize = 15.sp), cursorBrush = SolidColor(Palette.accent), modifier = Modifier.weight(1f),
            decorationBox = { inner -> if (v.isEmpty()) Text("搜索币种，如 SOL 或 ARB/USDC", color = Palette.faint, fontSize = 15.sp); inner() })
    }
}

@Composable
fun PairRow(vm: AppVM, p: SymPair, label: String?, onClick: () -> Unit) {
    val fav = p.toString() in vm.favorites
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.base, color = Palette.text, fontWeight = FontWeight.Bold); Text("/${p.quote}", color = Palette.dim, fontSize = 12.sp)
        label?.let { Text("  $it", color = Palette.accent, fontSize = 12.sp) }
        if (p == vm.pair) Text("  当前", color = Palette.accent, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(if (fav) "★" else "☆", color = if (fav) Palette.warn else Palette.dim, fontSize = 18.sp, modifier = Modifier.clickable { vm.toggleFav(p) }.padding(horizontal = 6.dp))
    }
    HorizontalDivider(color = Palette.line, thickness = 0.5.dp)
}

// ---------- stats ----------
@Composable
fun StatsStrip(vm: AppVM) {
    val t = vm.ticker; val dp = vm.info.priceDp
    Row(Modifier.fillMaxWidth().background(Palette.panel).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.1f)) {
            val c = if ((t?.changePct?.signum() ?: 0) >= 0) Palette.up else Palette.down
            Text(t?.last?.dp(dp) ?: "--", color = c, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = mono)
            Text((if (vm.pair.quote in Quotes.STABLE) "≈ $${t?.last?.dp(dp) ?: "--"}" else vm.pair.quote) + "   最新成交价", color = Palette.dim, fontSize = 11.sp) }
        Column(Modifier.weight(1f)) {
            Stat("24h最高", t?.high?.dp(dp)); Stat("24h最低", t?.low?.dp(dp)) }
        Column(Modifier.weight(1f)) {
            Stat("24h量(${vm.pair.base})", t?.vol?.let(::compact)); Stat("24h额(${vm.pair.quote})", t?.let { compact(it.vol * it.last) }) }
    }
}
@Composable fun Stat(k: String, v: String?) = Column(Modifier.padding(vertical = 1.dp)) { Text(k, color = Palette.dim, fontSize = 10.sp); Text(v ?: "--", color = Palette.text, fontSize = 12.sp, fontFamily = mono) }

// ---------- indicators ----------
object Ind {
    fun sma(c: List<Double>, n: Int) = c.indices.map { i -> if (i < n - 1) Double.NaN else c.subList(i - n + 1, i + 1).average() }
    fun ema(c: List<Double>, n: Int): List<Double> { val k = 2.0 / (n + 1); var e = Double.NaN
        return c.mapIndexed { i, v -> e = if (i == 0) v else v * k + e * (1 - k); if (i < n - 1) Double.NaN else e } }
    fun boll(c: List<Double>, n: Int = 20, m: Double = 2.0): Triple<List<Double>, List<Double>, List<Double>> {
        val mid = sma(c, n); val sd = c.indices.map { i -> if (i < n - 1) Double.NaN else { val s = c.subList(i - n + 1, i + 1); val a = mid[i]; Math.sqrt(s.sumOf { (it - a) * (it - a) } / n) } }
        return Triple(mid.indices.map { mid[it] + m * sd[it] }, mid, mid.indices.map { mid[it] - m * sd[it] }) }
}

// ---------- chart ----------
@Composable
fun ChartCard(vm: AppVM) {
    val cfg = remember(vm.pair, vm.cfgVersion) { vm.chartCfg() }
    val ctl = remember { KChartController() }
    val open = LocalOpenFullChart.current
    Column(Modifier.fillMaxWidth().background(Palette.panel).padding(top = 2.dp)) {
        HorizontalDivider(color = Palette.line, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TF.all.forEach { iv -> TabText(iv, vm.interval == iv) { vm.interval = iv; vm.loadKlines() } }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(14.dp).background(Palette.line))
                listOf("MA", "EMA", "BOLL", "VOL").forEach { n -> TabText(n, cfg.inds.any { it.name == n && it.on }) { vm.saveChartCfg(cfg.toggle(n)) } } }
            Text("⇥", color = Palette.accent, fontSize = 16.sp, modifier = Modifier.clickable { ctl.scrollLatest() }.padding(8.dp))
            // Binance-style expand icon → full-screen chart of the same symbol
            if (open != null) Box(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp)).clickable { open() }.padding(8.dp)) { ExpandGlyph() }
        }
        KLineChartView(vm, ctl, Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 4.dp), compact = true, showBidAsk = false, showTrades = true,
            cfg = cfg.copy(inds = cfg.inds.map { if (!it.main && it.name != "VOL") it.copy(on = false) else it }))
        Text("KLineChart · 单指拖动 · 双指缩放 · 长按十字光标 · 设置与全屏图表同步", color = Palette.faint, fontSize = 9.sp, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
    }
}

/** Four-corner "expand" glyph (drawn, no external assets). */
@Composable fun ExpandGlyph(c: Color = Palette.dim) = Canvas(Modifier.size(16.dp)) { val s = size.width; val k = s * 0.36f; val sw = 2.2f
    listOf(Offset(0f, 0f) to (1 to 1), Offset(s, 0f) to (-1 to 1), Offset(0f, s) to (1 to -1), Offset(s, s) to (-1 to -1)).forEach { (p, d) ->
        drawLine(c, p, Offset(p.x + d.first * k, p.y), sw); drawLine(c, p, Offset(p.x, p.y + d.second * k), sw) } }

private val labelPaint = Paint().apply { isAntiAlias = true; textSize = 26f; typeface = android.graphics.Typeface.MONOSPACE }
fun DrawScope.drawLabel(t: String, x: Float, y: Float, c: Color) { labelPaint.color = c.toArgb(); drawContext.canvas.nativeCanvas.drawText(t, x, y, labelPaint) }

@Composable
fun TabText(t: String, sel: Boolean, onClick: () -> Unit) = Column(Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(t, color = if (sel) Palette.text else Palette.dim, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
    Box(Modifier.padding(top = 3.dp).width(14.dp).height(2.dp).background(if (sel) Palette.accent else Color.Transparent)) }

// ---------- order book ----------
fun aggregate(levels: List<Level>, g: BigDecimal, bid: Boolean): List<Level> {
    if (g.signum() <= 0) return levels
    return levels.groupBy { it.price.divide(g, 0, if (bid) RoundingMode.FLOOR else RoundingMode.CEILING).multiply(g) }
        .map { (p, l) -> Level(p, l.fold(BigDecimal.ZERO) { a, b -> a + b.qty }) }.let { if (bid) it.sortedByDescending { x -> x.price } else it.sortedBy { x -> x.price } }
}

@Composable
fun BookPanel(vm: AppVM) {
    var tab by remember { mutableIntStateOf(0) }; var mode by remember { mutableIntStateOf(0) }
    var gi by remember(vm.pair, vm.exchangeId) { mutableIntStateOf(0) }; var gOpen by remember { mutableStateOf(false) }
    val groups = remember(vm.info) { (0..3).map { vm.info.tick.multiply(BigDecimal.TEN.pow(it)).stripTrailingZeros() } }
    val g = groups[gi.coerceIn(0, 3)]; val gdp = maxOf(0, g.scale())
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.panel).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TabText("订单簿", tab == 0) { tab = 0 }; TabText("成交", tab == 1) { tab = 1 } }
        if (tab == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("⇅", "▼", "▲").forEachIndexed { i, s -> Text(s, color = if (mode == i) Palette.text else Palette.faint, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(if (mode == i) Palette.field else Color.Transparent).clickable { mode = i }.padding(horizontal = 6.dp, vertical = 2.dp)) }
                Spacer(Modifier.weight(1f))
                Box { Text("${g.toPlainString()} ▾", color = Palette.text, fontSize = 11.sp, fontFamily = mono,
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Palette.field).clickable { gOpen = true }.padding(horizontal = 6.dp, vertical = 2.dp))
                    DropdownMenu(gOpen, { gOpen = false }) { groups.forEachIndexed { i, x -> DropdownMenuItem(text = { Text(x.toPlainString(), fontFamily = mono) }, onClick = { gi = i; gOpen = false }) } } }
            }
            Row(Modifier.padding(vertical = 3.dp)) { Lbl("价格(${vm.pair.quote})", s = 10); Spacer(Modifier.weight(1f)); Lbl("数量(${vm.pair.base})", s = 10) }
            val n = when (mode) { 0 -> 8; else -> 16 }
            val asks = aggregate(vm.book.asks, g, false).take(n); val bids = aggregate(vm.book.bids, g, true).take(n)
            fun cum(l: List<Level>) = l.runningFold(BigDecimal.ZERO) { a, b -> a + b.qty }.drop(1)
            val ca = cum(asks); val cb = cum(bids); val maxC = maxOf(ca.lastOrNull() ?: BigDecimal.ONE, cb.lastOrNull() ?: BigDecimal.ONE).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
            if (mode != 1) asks.indices.reversed().forEach { i -> BookRow(asks[i], ca[i], maxC, Palette.down, gdp, vm.info.qtyDp) { vm.bookClick = asks[i].price } }
            val t = vm.ticker
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t?.last?.dp(vm.info.priceDp) ?: "--", color = if ((t?.changePct?.signum() ?: 0) >= 0) Palette.up else Palette.down, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = mono)
                Spacer(Modifier.width(4.dp)); Text(if ((t?.changePct?.signum() ?: 0) >= 0) "↑" else "↓", color = if ((t?.changePct?.signum() ?: 0) >= 0) Palette.up else Palette.down) }
            if (mode != 2) bids.indices.forEach { i -> BookRow(bids[i], cb[i], maxC, Palette.up, gdp, vm.info.qtyDp) { vm.bookClick = bids[i].price } }
            // ratio
            val bq = vm.book.bids.fold(BigDecimal.ZERO) { a, b -> a + b.qty }; val aq = vm.book.asks.fold(BigDecimal.ZERO) { a, b -> a + b.qty }
            val tot = (bq + aq).takeIf { it.signum() > 0 }
            val bp = tot?.let { bq.multiply(BigDecimal(100)).divide(it, 0, RoundingMode.HALF_UP).toInt() } ?: 50
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(18.dp).clip(RoundedCornerShape(3.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(maxOf(bp, 1).toFloat()).fillMaxHeight().background(Palette.up.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterStart) { Text(" B $bp%", color = Palette.up, fontSize = 10.sp) }
                Box(Modifier.weight(maxOf(100 - bp, 1).toFloat()).fillMaxHeight().background(Palette.down.copy(alpha = 0.25f)), contentAlignment = Alignment.CenterEnd) { Text("${100 - bp}% S ", color = Palette.down, fontSize = 10.sp) } }
        } else {
            Row(Modifier.padding(vertical = 3.dp)) { Lbl("价格", s = 10); Spacer(Modifier.weight(1f)); Lbl("数量", s = 10); Spacer(Modifier.weight(1f)); Lbl("时间", s = 10) }
            val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            vm.trades.take(24).forEach { t -> Row(Modifier.fillMaxWidth().height(19.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.price.dp(vm.info.priceDp), color = if (t.side == Side.BUY) Palette.up else Palette.down, fontSize = 11.sp, fontFamily = mono, modifier = Modifier.weight(1.2f))
                Text(t.qty.dp(minOf(vm.info.qtyDp, 4)), color = Palette.text, fontSize = 11.sp, fontFamily = mono, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(tf.format(Date(t.time)), color = Palette.dim, fontSize = 10.sp, fontFamily = mono, modifier = Modifier.weight(1f), textAlign = TextAlign.End) } }
            if (vm.trades.isEmpty()) Lbl("暂无成交")
        }
    }
}

@Composable
fun BookRow(l: Level, cum: BigDecimal, maxC: BigDecimal, c: Color, pdp: Int, qdp: Int, onClick: () -> Unit) {
    val frac = cum.divide(maxC, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(19.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).align(Alignment.CenterEnd).background(c.copy(alpha = 0.13f)))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(l.price.dp(pdp), color = c, fontSize = 11.sp, fontFamily = mono); Spacer(Modifier.weight(1f))
            Text(l.qty.dp(minOf(qdp, 4)), color = Palette.text, fontSize = 11.sp, fontFamily = mono) } }
}

// ---------- order form ----------
@Composable
fun NumField(v: String, on: (String) -> Unit, label: String, unit: String, step: BigDecimal?, onUnit: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Palette.field), verticalAlignment = Alignment.CenterVertically) {
        if (step != null) Text("−", color = Palette.dim, fontSize = 18.sp, modifier = Modifier.clickable { val x = v.bd() - step; if (x.signum() > 0) on(x.plain()) }.padding(horizontal = 8.dp))
        else Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.dim, fontSize = 9.sp)
            BasicTextField(v, { s -> on(s.filter { it.isDigit() || it == '.' }) }, singleLine = true, textStyle = TextStyle(color = Palette.text, fontSize = 14.sp, fontFamily = mono),
                cursorBrush = SolidColor(Palette.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner -> if (v.isEmpty()) Text("0", color = Palette.faint, fontSize = 14.sp, fontFamily = mono); inner() }) }
        trailing?.invoke()
        Text(unit + if (onUnit != null) " ⇄" else "", color = if (onUnit != null) Palette.accent else Palette.dim, fontSize = 11.sp, modifier = Modifier.then(if (onUnit != null) Modifier.clickable { onUnit() } else Modifier).padding(horizontal = 4.dp))
        if (step != null) Text("+", color = Palette.dim, fontSize = 18.sp, modifier = Modifier.clickable { on((v.bd() + step).plain()) }.padding(horizontal = 8.dp))
    }
}

@Composable
fun PctSlider(pct: Float, col: Color, on: (Float) -> Unit) {
    Column {
        Slider(pct, on, valueRange = 0f..1f, modifier = Modifier.height(26.dp), colors = SliderDefaults.colors(thumbColor = col, activeTrackColor = col, inactiveTrackColor = Palette.line))
        Row(Modifier.fillMaxWidth()) { listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            Text("${(p * 100).toInt()}%", color = if (pct >= p - 0.001f && p > 0) col else Palette.dim, fontSize = 10.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(3.dp)).border(0.5.dp, if (kotlin.math.abs(pct - p) < 0.01f) col else Palette.line, RoundedCornerShape(3.dp)).clickable { on(p) }.padding(vertical = 2.dp)) ; if (p < 1f) Spacer(Modifier.width(3.dp)) } }
    }
}

@Composable
fun OrderForm(vm: AppVM, initialChase: Boolean = false, initialType: OType = OType.LIMIT) {
    var side by remember { mutableStateOf(Side.BUY) }; var type by remember { mutableStateOf(initialType) }
    var chase by remember { mutableStateOf(initialChase) }
    var cOffset by remember { mutableStateOf("0") }; var cLimit by remember(vm.pair) { mutableStateOf("") }
    var cDist by remember { mutableStateOf("") }; var cMinutes by remember { mutableStateOf("10") }
    var price by remember(vm.pair, vm.exchangeId) { mutableStateOf("") }; var trigger by remember(vm.pair) { mutableStateOf("") }
    var amount by remember(vm.pair) { mutableStateOf("") }; var quoteUnit by remember { mutableStateOf(false) }
    var tif by remember { mutableStateOf(TIF.GTC) }; var pct by remember { mutableFloatStateOf(0f) }; var confirm by remember { mutableStateOf(false) }
    val f = vm.features; val info = vm.info; val p = vm.pair
    val maker = vm.makerOnly
    val etif = if (maker) TIF.POST_ONLY else tif            // global maker-only forces post-only regardless of the per-order selector
    if (maker && type == OType.MARKET) type = OType.LIMIT   // market orders are not allowed while 只做Maker is on
    LaunchedEffect(vm.bookClick) { vm.bookClick?.let { price = it.plain() } }
    LaunchedEffect(vm.ticker == null, p) { if (price.isBlank()) vm.ticker?.let { price = info.roundPrice(it.last).plain() } }
    if (type == OType.STOP_LIMIT && Feature.STOP_LIMIT !in f && !maker) type = OType.LIMIT
    if (tif != TIF.GTC && ((tif == TIF.POST_ONLY && Feature.POST_ONLY !in f) || (tif == TIF.IOC && Feature.IOC !in f) || (tif == TIF.FOK && Feature.FOK !in f))) tif = TIF.GTC
    val quoteFree = vm.balances.find { it.asset == p.quote }?.free ?: BigDecimal.ZERO
    val baseFree = vm.balances.find { it.asset == p.base }?.free ?: BigDecimal.ZERO
    val last = vm.ticker?.last ?: BigDecimal.ZERO
    val px = if (type == OType.MARKET) last else price.bd()
    val amt = amount.bd()
    val baseQty = if (quoteUnit) (if (px.signum() > 0) amt.divide(px, 12, RoundingMode.DOWN) else BigDecimal.ZERO) else amt
    val notional = if (quoteUnit) amt else amt * px
    val feeRate = if ((type == OType.LIMIT || (maker && type == OType.STOP_LIMIT)) && etif == TIF.POST_ONLY) info.maker else info.taker
    val col = if (side == Side.BUY) Palette.up else Palette.down
    val unit = if (quoteUnit) p.quote else p.base

    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.panel).padding(8.dp)) {
        MakerSwitch(vm)
        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(Palette.field)) {
            listOf(Side.BUY to "买入", Side.SELL to "卖出").forEach { (s, t) -> val on = side == s; val c = if (s == Side.BUY) Palette.up else Palette.down
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if (on) c else Color.Transparent).clickable { side = s; pct = 0f }, contentAlignment = Alignment.Center) {
                    Text(t, color = if (on) Color.White else Palette.dim, fontWeight = FontWeight.Bold) } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabText("限价", type == OType.LIMIT && !chase) { type = OType.LIMIT; chase = false }; if (!maker) TabText("市价", type == OType.MARKET && !chase) { type = OType.MARKET; chase = false }
            if (Feature.STOP_LIMIT in f || maker) TabText(if (maker) "条件(Maker)" else "止盈止损", type == OType.STOP_LIMIT && !chase) { type = OType.STOP_LIMIT; chase = false }
            TabText("追逐", chase) { chase = true; type = OType.LIMIT } }
        if (chase) { ChaseForm(vm, side, quoteUnit, { quoteUnit = !quoteUnit; amount = "" }, amount, { amount = it }, cOffset, { cOffset = it }, cLimit, { cLimit = it }, cDist, { cDist = it }, cMinutes, { cMinutes = it }, quoteFree, baseFree); return@Column }
        if (maker && type == OType.STOP_LIMIT) Text("Maker条件单：App本地监控最新价，触发后以只做Maker追逐挂单（价格不超过委托价）。交易所原生条件单无法保证只做Maker；需保持App运行（前台服务）。", color = Palette.dim, fontSize = 10.sp, modifier = Modifier.padding(vertical = 2.dp))
        if (type == OType.STOP_LIMIT) NumField(trigger, { trigger = it }, "触发价", p.quote, info.tick) { Text("最新", color = Palette.accent, fontSize = 10.sp, modifier = Modifier.clickable { trigger = info.roundPrice(last).plain() }.padding(4.dp)) }
        if (type != OType.MARKET) NumField(price, { price = it }, if (type == OType.STOP_LIMIT) "委托价" else "价格", p.quote, info.tick) { Text("最新", color = Palette.accent, fontSize = 10.sp, modifier = Modifier.clickable { price = info.roundPrice(last).plain() }.padding(4.dp)) }
        else Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Palette.field.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Text("以市场最优价格成交", color = Palette.dim, fontSize = 12.sp) }
        NumField(amount, { amount = it; pct = 0f }, if (quoteUnit) "成交额" else "数量", unit, if (quoteUnit) BigDecimal.ONE else info.step, onUnit = { quoteUnit = !quoteUnit; amount = ""; pct = 0f })
        PctSlider(pct, col) { v -> pct = v
            val fv = BigDecimal(v.toDouble()).setScale(4, RoundingMode.HALF_UP)
            amount = if (side == Side.BUY) { val q = quoteFree * fv * (BigDecimal.ONE - feeRate)
                if (quoteUnit) q.setScale(2, RoundingMode.DOWN).plain() else if (px.signum() > 0) info.roundQty(q.divide(px, 12, RoundingMode.DOWN)).plain() else ""
            } else { val b = info.roundQty(baseFree * fv); if (quoteUnit) (b * px).setScale(2, RoundingMode.DOWN).plain() else b.plain() } }
        if (maker && type == OType.LIMIT) Text("🔒 只做Maker (Post-Only)  ·  IOC / FOK 已禁用", color = Palette.accent, fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(3.dp)).border(0.5.dp, Palette.accent, RoundedCornerShape(3.dp)).padding(vertical = 4.dp))
        else if (type == OType.LIMIT) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf(TIF.GTC to "GTC", TIF.POST_ONLY to "只做Maker", TIF.IOC to "IOC", TIF.FOK to "FOK").forEach { (t, n) ->
                val ok = t == TIF.GTC || (t == TIF.POST_ONLY && Feature.POST_ONLY in f) || (t == TIF.IOC && Feature.IOC in f) || (t == TIF.FOK && Feature.FOK in f)
                Text(n, fontSize = 10.sp, color = when { !ok -> Palette.faint; tif == t -> Palette.accent; else -> Palette.dim }, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(if (t == TIF.POST_ONLY) 1.5f else 1f).padding(horizontal = 1.dp).clip(RoundedCornerShape(3.dp)).border(0.5.dp, if (tif == t) Palette.accent else Palette.line, RoundedCornerShape(3.dp))
                        .clickable(enabled = ok) { tif = t }.padding(vertical = 3.dp)) } }
        Spacer(Modifier.height(4.dp))
        KV("可用", if (side == Side.BUY) "${quoteFree.dp(2)} ${p.quote}" else "${baseFree.plain()} ${p.base}")
        KV(if (side == Side.BUY) "可买" else "可卖", if (side == Side.BUY) (if (px.signum() > 0) "${info.roundQty(quoteFree.divide(px, 12, RoundingMode.DOWN)).plain()} ${p.base}" else "--") else "${(baseFree * px).dp(2)} ${p.quote}")
        KV("预估成交额", "${notional.dp(2)} ${p.quote}")
        KV("预估手续费", "${(notional * feeRate).dp(4)} ${p.quote} (${(feeRate * BigDecimal(100)).stripTrailingZeros().toPlainString()}%)")
        if (info.minNotional.signum() > 0) KV("最小下单额", "${info.minNotional.plain()} ${p.quote}")
        Button({ if (!vm.hasKeys()) vm.message = "请先在『API密钥』页配置 ${Exchanges.names[vm.exchangeId]} 密钥" else confirm = true }, Modifier.fillMaxWidth().padding(top = 6.dp).height(42.dp), enabled = !vm.busy,
            shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = col)) {
            Text((if (maker && type == OType.STOP_LIMIT) "启动Maker条件单 " else if (maker) "Maker " else "") + "${if (side == Side.BUY) "买入" else "卖出"} ${p.base}", color = Color.White, fontWeight = FontWeight.Bold) }
    }
    vm.postOnlyReject?.let { r -> PostOnlyRejectDialog(vm, r) }
    if (confirm) {
        val req = OrderReq(p, side, type,
            qty = if (type == OType.MARKET && side == Side.BUY && quoteUnit) null else info.roundQty(baseQty),
            quoteQty = if (type == OType.MARKET && side == Side.BUY) (if (quoteUnit) amt else (amt * last)) else null,
            price = if (type == OType.MARKET) null else info.roundPrice(price.bd()), trigger = if (type == OType.STOP_LIMIT) info.roundPrice(trigger.bd()) else null, tif = etif)
        AlertDialog({ confirm = false }, containerColor = Palette.panel2, title = { Text("确认${if (side == Side.BUY) "买入" else "卖出"} ${p}", color = col) },
            text = { Column {
                KV("交易所", Exchanges.names[vm.exchangeId] ?: ""); KV("类型", when (type) { OType.LIMIT -> "限价 ${if (etif == TIF.POST_ONLY) "只做Maker" else etif.name}"; OType.MARKET -> "市价"; OType.STOP_LIMIT -> if (maker) "Maker条件单(本地触发后只做Maker)" else "止盈止损(条件限价)" })
                req.trigger?.let { KV("触发价", it.plain()) }; req.price?.let { KV("委托价", it.plain()) }
                req.qty?.let { KV("数量", "${it.plain()} ${p.base}") }; req.quoteQty?.let { KV("金额", "${it.dp(2)} ${p.quote}") }
                KV("预估手续费", "${(notional * feeRate).dp(4)} ${p.quote}")
                if (maker) Lbl("只做Maker：若委托会立即成交将被拒绝，可一键改价到买一/卖一。", Palette.accent, 10)
                Lbl("价格/数量已按交易所精度 (tick ${info.tick.plain()}, step ${info.step.plain()}) 取整", s = 10) } },
            confirmButton = { TextButton({ confirm = false
                when {
                    type != OType.MARKET && (req.price?.signum() ?: 0) <= 0 -> vm.message = "请输入有效价格"
                    type == OType.STOP_LIMIT && (req.trigger?.signum() ?: 0) <= 0 -> vm.message = "请输入触发价"
                    (req.qty?.signum() ?: 0) <= 0 && (req.quoteQty?.signum() ?: 0) <= 0 -> vm.message = "请输入数量"
                    maker && type == OType.STOP_LIMIT -> {
                        val q = req.qty ?: BigDecimal.ZERO; val pr = req.price ?: BigDecimal.ZERO
                        if (info.minNotional.signum() > 0 && q * pr < info.minNotional) vm.message = "低于最小下单额 ${info.minNotional.plain()}"
                        else vm.startChase(com.morris.spottrader.chase.ChaseParams(p, side, q, 0, limitPrice = pr, maxDurationSec = 600, triggerPrice = req.trigger)) }
                    else -> vm.place(req) } }) { Text("确认", color = col) } },
            dismissButton = { TextButton({ confirm = false }) { Text("取消", color = Palette.dim) } })
    }
}

@Composable
fun ChaseForm(vm: AppVM, side: Side, quoteUnit: Boolean, toggleUnit: () -> Unit, amount: String, setAmount: (String) -> Unit,
              off: String, setOff: (String) -> Unit, lim: String, setLim: (String) -> Unit, dist: String, setDist: (String) -> Unit, mins: String, setMins: (String) -> Unit,
              quoteFree: BigDecimal, baseFree: BigDecimal) {
    val p = vm.pair; val info = vm.info; val buy = side == Side.BUY; val col = if (buy) Palette.up else Palette.down
    val best = (if (buy) vm.book.bids.maxByOrNull { it.price } else vm.book.asks.minByOrNull { it.price })?.price ?: vm.ticker?.last ?: BigDecimal.ZERO
    val offN = off.toIntOrNull() ?: 0
    val startPx = info.roundPrice(if (buy) best - info.tick * BigDecimal(offN) else best + info.tick * BigDecimal(offN))
    var pct by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accent.copy(alpha = 0.10f)).padding(8.dp)) {
        Column { Text("只做Maker · 追逐${if (buy) "买一" else "卖一"}", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("当前${if (buy) "买一" else "卖一"} ${best.dp(info.priceDp)} → 挂单 ${startPx.dp(info.priceDp)}", color = Palette.text, fontSize = 11.sp, fontFamily = mono) } }
    NumField(off, { setOff(it.filter(Char::isDigit)) }, "偏移(档, 远离盘口)", "tick", null)
    NumField(amount, { setAmount(it); pct = 0f }, if (quoteUnit) "成交额" else "数量", if (quoteUnit) p.quote else p.base, if (quoteUnit) BigDecimal.ONE else info.step, onUnit = toggleUnit)
    PctSlider(pct, col) { v -> pct = v; val fv = BigDecimal(v.toDouble()).setScale(4, RoundingMode.HALF_UP)
        setAmount(if (buy) { val q = quoteFree * fv * (BigDecimal.ONE - info.maker); if (quoteUnit) q.setScale(2, RoundingMode.DOWN).plain() else if (startPx.signum() > 0) info.roundQty(q.divide(startPx, 12, RoundingMode.DOWN)).plain() else "" }
            else { val b = info.roundQty(baseFree * fv); if (quoteUnit) (b * startPx).setScale(2, RoundingMode.DOWN).plain() else b.plain() }) }
    NumField(lim, setLim, if (buy) "最高追价 (可选)" else "最低追价 (可选)", p.quote, info.tick)
    Row { Box(Modifier.weight(1f)) { NumField(dist, setDist, "最大偏离%", "%", null) }; Spacer(Modifier.width(4.dp)); Box(Modifier.weight(1f)) { NumField(mins, { setMins(it.filter(Char::isDigit)) }, "最长时长", "分钟", null) } }
    val qtyBase = if (quoteUnit) (if (startPx.signum() > 0) amount.bd().divide(startPx, 12, RoundingMode.DOWN) else BigDecimal.ZERO) else amount.bd()
    val q = info.roundQty(qtyBase)
    KV("数量(取整)", "${q.plain()} ${p.base}"); KV("预估成交额", "${(q * startPx).dp(2)} ${p.quote}")
    KV("预估手续费(Maker)", "${(q * startPx * info.maker).dp(4)} ${p.quote}"); KV("运行中", "${com.morris.spottrader.chase.ChaseManager.activeCount()}/${com.morris.spottrader.chase.ChaseManager.MAX_ACTIVE}")
    Button({
        when { !vm.hasKeys() -> vm.message = "请先配置API密钥"
            q.signum() <= 0 -> vm.message = "请输入数量"
            info.minNotional.signum() > 0 && q * startPx < info.minNotional -> vm.message = "低于最小下单额 ${info.minNotional.plain()}"
            else -> vm.startChase(com.morris.spottrader.chase.ChaseParams(p, side, q, offN, lim.bd().takeIf { it.signum() > 0 }, dist.bd().takeIf { it.signum() > 0 }, ((mins.toLongOrNull() ?: 10).coerceIn(1, 360)) * 60)) } },
        Modifier.fillMaxWidth().padding(top = 6.dp).height(42.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = col)) {
        Text("开始追逐${if (buy) "买入" else "卖出"}", color = Color.White, fontWeight = FontWeight.Bold) }
}

@Composable
fun ChasePanel(tasks: List<com.morris.spottrader.chase.ChaseTask>) {
    if (tasks.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("追逐限价单", color = Palette.text, fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Tag("Maker", Palette.accent)
            Spacer(Modifier.weight(1f)); if (tasks.any { !it.active }) Text("清除已结束", color = Palette.dim, fontSize = 11.sp, modifier = Modifier.clickable { com.morris.spottrader.chase.ChaseManager.clearFinished() }.padding(4.dp)) }
        tasks.forEach { t -> val c = if (t.p.side == Side.BUY) Palette.up else Palette.down
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t.p.side == Side.BUY) "追买" else "追卖", color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 4.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp)); Text("${t.p.pair}", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp); Spacer(Modifier.width(6.dp)); Lbl(t.exchangeName, s = 10)
                    t.p.triggerPrice?.let { Spacer(Modifier.width(6.dp)); Tag("触发 ${it.plain()}", Palette.warn) }
                    Spacer(Modifier.weight(1f))
                    if (t.active) OutlinedButton({ com.morris.spottrader.chase.ChaseManager.stop(t) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 10.dp), border = BorderStroke(0.5.dp, Palette.down)) { Text("停止", color = Palette.down, fontSize = 11.sp) }
                    else Lbl("已结束", s = 11) }
                val prog = if (t.p.qty.signum() > 0) t.filled.divide(t.p.qty, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp), color = c, trackColor = Palette.line)
                Row { Cell("已成交/总量", "${t.filled.stripTrailingZeros().toPlainString()}/${t.p.qty.stripTrailingZeros().toPlainString()}", Modifier.weight(1.3f))
                    Cell("成交均价", t.avgPrice?.round(java.math.MathContext(8))?.stripTrailingZeros()?.toPlainString() ?: "--", Modifier.weight(1f))
                    Cell("当前挂价", t.currentPrice?.stripTrailingZeros()?.toPlainString() ?: "--", Modifier.weight(1f)) }
                Row(Modifier.padding(top = 2.dp)) { Cell("重挂次数", "${t.replaces}", Modifier.weight(1.3f)); Cell("Maker被拒", "${t.rejects}", Modifier.weight(1f))
                    Cell("用时", "${(System.currentTimeMillis() - t.startedAt) / 1000}s / ${t.p.maxDurationSec / 60}m", Modifier.weight(1f)) }
                Text(t.status, color = if (t.active) Palette.accent else Palette.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            HorizontalDivider(color = Palette.line, thickness = 0.5.dp) }
    }
}

@Composable fun KV(k: String, v: String) = Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) { Text(k, color = Palette.dim, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(v, color = Palette.text, fontSize = 11.sp, fontFamily = mono) }

// ---------- bottom panel ----------
private val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@Composable
fun OrderRow(o: Order, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val c = if (o.side == Side.BUY) Palette.up else Palette.down
            Text(if (o.side == Side.BUY) "买入" else "卖出", color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 4.dp, vertical = 1.dp))
            Spacer(Modifier.width(6.dp)); Text(o.symbol, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp); Spacer(Modifier.width(6.dp)); Lbl(o.type.lowercase(), s = 11)
            Spacer(Modifier.weight(1f)); Lbl(if (o.time > 0) df.format(Date(o.time)) else "", s = 10) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell("价格", o.price.plain(), Modifier.weight(1f)); Cell("数量", o.qty.plain(), Modifier.weight(1f)); Cell("已成交", o.filled.plain(), Modifier.weight(1f))
            if (onCancel != null) OutlinedButton(onCancel, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(28.dp), border = BorderStroke(0.5.dp, Palette.line)) { Text("撤单", color = Palette.text, fontSize = 11.sp) }
            else Lbl(o.status, s = 10) }
    }
    HorizontalDivider(color = Palette.line, thickness = 0.5.dp)
}
@Composable fun Cell(k: String, v: String, m: Modifier) = Column(m) { Text(k, color = Palette.dim, fontSize = 10.sp); Text(v, color = Palette.text, fontSize = 12.sp, fontFamily = mono) }

@Composable
fun PositionCard(p: com.morris.spottrader.Position, onTpSl: (() -> Unit)? = null, rule: com.morris.spottrader.pos.TpSlRule? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.asset, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.width(6.dp)); Tag("现货", Palette.accent)
            Spacer(Modifier.weight(1f))
            p.pnl?.let { val c = if (it.signum() >= 0) Palette.up else Palette.down
                Column(horizontalAlignment = Alignment.End) { Text("未实现盈亏(${p.quote})", color = Palette.dim, fontSize = 10.sp)
                    Text("${if (it.signum() >= 0) "+" else ""}${it.dp(2)} (${if (it.signum() >= 0) "+" else ""}${p.pnlPct?.toPlainString()}%)", color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = mono) } } }
        Row(Modifier.padding(top = 6.dp)) { Cell("持仓数量", p.qty.stripTrailingZeros().toPlainString(), Modifier.weight(1f)); Cell("市值(${p.quote})", p.value?.dp(2) ?: "--", Modifier.weight(1f)); Cell("可用", p.free.stripTrailingZeros().toPlainString(), Modifier.weight(1f)) }
        Row(Modifier.padding(top = 4.dp)) { Cell("持仓均价(${p.quote})", p.avgCost?.let { it.round(java.math.MathContext(8)).stripTrailingZeros().toPlainString() } ?: "--", Modifier.weight(1f)); Cell("最新价", p.price?.stripTrailingZeros()?.toPlainString() ?: "--", Modifier.weight(1f)); Cell("成本覆盖数量", p.costQty?.min(p.qty)?.stripTrailingZeros()?.toPlainString() ?: "--", Modifier.weight(1f)) }
        if (p.asset !in Quotes.STABLE) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell("已实现盈亏(${p.quote})", p.realized?.let { (if (it.signum() >= 0) "+" else "") + it.dp(2) } ?: "--", Modifier.weight(1f))
            Column(Modifier.weight(2f)) { if (rule != null) com.morris.spottrader.ui.TpSlStatus(rule) else Text("未设置 TP/SL", color = Palette.faint, fontSize = 10.sp) }
            if (onTpSl != null) Text("TP/SL", color = Palette.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.warn.copy(alpha = 0.18f)).clickable { onTpSl() }.padding(horizontal = 10.dp, vertical = 5.dp)) }
    }
    HorizontalDivider(color = Palette.line, thickness = 0.5.dp)
}

@Composable
fun BottomPanel(vm: AppVM) {
    var t by remember { mutableIntStateOf(1) }; var onlyPair by remember { mutableStateOf(false) }
    val sym = listOf(vm.pair.base + vm.pair.quote, "${vm.pair.base}-${vm.pair.quote}", "${vm.pair.base}_${vm.pair.quote}")
    val oo = if (onlyPair) vm.openOrders.filter { it.symbol.uppercase() in sym } else vm.openOrders
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(Palette.panel)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TabText("持仓(${vm.positions.count { it.asset != vm.pair.quote }})", t == 0) { t = 0; if (vm.hasKeys()) vm.loadPositions() }
            TabText("当前委托(${vm.openOrders.size})", t == 1) { t = 1 }; TabText("历史委托", t == 2) { t = 2 }
            TabText("成交历史", t == 3) { t = 3 }; TabText("资产", t == 4) { t = 4 }
            Text("⟳", color = Palette.dim, fontSize = 16.sp, modifier = Modifier.clickable { vm.loadAccount(); if (t == 0) vm.loadPositions() }.padding(10.dp)) }
        HorizontalDivider(color = Palette.line, thickness = 0.5.dp)
        if (!vm.hasKeys()) { Text("配置 API 密钥后可查看持仓与委托", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(20.dp)); }
        when (t) {
            0 -> { PosQuoteChips(vm); if (vm.positions.isEmpty() && vm.hasKeys()) Empty("点击『持仓』加载"); vm.positions.forEach { PositionCard(it) } }
            1 -> { Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onlyPair, { onlyPair = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.accent)); Text("只看当前币对", color = Palette.dim, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (oo.isNotEmpty()) { var ask by remember { mutableStateOf(false) }
                        OutlinedButton({ ask = true }, Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp), border = BorderStroke(0.5.dp, Palette.down)) { Text("全部撤单", color = Palette.down, fontSize = 12.sp) }
                        if (ask) AlertDialog({ ask = false }, containerColor = Palette.panel2, title = { Text("全部撤单") }, text = { Text("将撤销 ${oo.size} 个委托（含条件单），确认？") },
                            confirmButton = { TextButton({ ask = false; vm.cancelAll() }) { Text("确认", color = Palette.down) } }, dismissButton = { TextButton({ ask = false }) { Text("取消") } }) } }
                if (oo.isEmpty()) Empty("暂无委托"); oo.forEach { o -> OrderRow(o) { vm.cancel(o) } } }
            2 -> { if (vm.history.isEmpty()) Empty("暂无记录（${vm.pair}）"); vm.history.forEach { OrderRow(it, null) } }
            3 -> { if (vm.myTrades.isEmpty()) Empty("暂无成交（${vm.pair}）"); vm.myTrades.take(100).forEach { tr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tr.side == Side.BUY) "买" else "卖", color = if (tr.side == Side.BUY) Palette.up else Palette.down, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Cell("价格", tr.price.plain(), Modifier.weight(1f)); Cell("数量", tr.qty.plain(), Modifier.weight(1f)); Cell("手续费", "${tr.fee.plain()} ${tr.feeAsset}", Modifier.weight(1f))
                    Lbl(df.format(Date(tr.time)), s = 10) }; HorizontalDivider(color = Palette.line, thickness = 0.5.dp) } }
            else -> { if (vm.balances.isEmpty()) Empty("暂无资产"); vm.balances.forEach { b -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(b.asset, color = Palette.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f)); Cell("总额", b.total.plain(), Modifier.weight(1f)); Cell("可用", b.free.plain(), Modifier.weight(1f)); Cell("冻结", b.locked.plain(), Modifier.weight(1f)) }
                HorizontalDivider(color = Palette.line, thickness = 0.5.dp) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable fun Empty(t: String) = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(t, color = Palette.faint, fontSize = 13.sp) }

/** Valuation / cost-basis currency for holdings: USDT, USDC, or both merged (valued in USDT). */
@Composable
fun PosQuoteChips(vm: AppVM) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Lbl("计价", s = 11); Spacer(Modifier.width(4.dp))
        listOf("USDT" to "USDT", "USDC" to "USDC", "ALL" to "USDT+USDC 折合USDT").forEach { (k, n) -> Chip(n, vm.posQuote == k) { vm.choosePosQuote(k); if (vm.hasKeys()) vm.loadPositions() } } }
}

/** Prominent global 只做Maker switch (persisted). */
@Composable
fun MakerSwitch(vm: AppVM) {
    val on = vm.makerOnly
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Palette.accent.copy(alpha = 0.16f) else Palette.field)
        .border(1.dp, if (on) Palette.accent else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("只做Maker", color = if (on) Palette.accent else Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp)); Text(if (on) "已开启" else "已关闭", color = if (on) Palette.accent else Palette.dim, fontSize = 10.sp,
                        modifier = Modifier.border(0.5.dp, if (on) Palette.accent else Palette.faint, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) } }
            Switch(on, { vm.toggleMaker(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Color.White)) }
        if (on) Text("限价单以 Post-Only 提交；会立即成交(吃单)的委托将被拒绝。市价/IOC/FOK 已禁用，条件单改为本地Maker触发。", color = Palette.text, fontSize = 10.sp, lineHeight = 13.sp)
        else Text("开启后所有限价单只挂单不吃单，赚取Maker费率。", color = Palette.dim, fontSize = 10.sp)
    }
}

@Composable
fun PostOnlyRejectDialog(vm: AppVM, r: OrderReq) {
    val buy = r.side == Side.BUY; val dp = vm.info.priceDp
    val best = (if (buy) vm.book.bids.maxByOrNull { it.price } else vm.book.asks.minByOrNull { it.price })?.price
    AlertDialog({ vm.dismissPostOnlyReject() }, containerColor = Palette.panel2, title = { Text("只做Maker：委托被拒绝", color = Palette.warn) },
        text = { Column {
            Text("你的${if (buy) "买入" else "卖出"}价 ${r.price?.plain()} 会立即与对手盘成交（吃单），而「只做Maker」只允许挂单，交易所已拒绝/撤销该委托，未产生成交。", color = Palette.text, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (buy) "改价方案：以买一价挂单" else "改价方案：以卖一价挂单", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            KV(if (buy) "买一" else "卖一", best?.dp(dp) ?: "获取中…"); KV("数量", "${r.qty?.plain()} ${r.pair.base}")
            Lbl("盘口变化很快，若改价后仍被拒，会再次提示。", s = 10) } },
        confirmButton = { TextButton({ vm.repriceAndPlace() }) { Text(if (buy) "改价到买一并重新下单" else "改价到卖一并重新下单", color = Palette.accent) } },
        dismissButton = { TextButton({ vm.dismissPostOnlyReject() }) { Text("取消", color = Palette.dim) } })
}
