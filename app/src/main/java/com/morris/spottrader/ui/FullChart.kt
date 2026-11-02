package com.morris.spottrader.ui

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** Activity hooks for the full-screen chart (null in previews/tests). */
interface ScreenControl { fun immersive(on: Boolean); fun toggleLandscape(): Boolean }
val LocalOpenFullChart = staticCompositionLocalOf<(() -> Unit)?> { null }

private val fcPaint = Paint().apply { isAntiAlias = true; textSize = 24f; typeface = android.graphics.Typeface.MONOSPACE }
private fun DrawScope.txt(t: String, x: Float, y: Float, c: Color, size: Float = 24f) { fcPaint.color = c.toArgb(); fcPaint.textSize = size; drawContext.canvas.nativeCanvas.drawText(t, x, y, fcPaint) }


@Composable
fun FullChartScreen(vm: AppVM, onClose: () -> Unit, control: ScreenControl? = null) = Mt5ChartScreen(vm, onClose, control, immersive = true)

/** 图表 tab: MT5-mobile-style chart. Rendering = KLineChart (Apache-2.0) in a WebView; toolbar, panels and dialogs are Compose. */
val DRAW_TOOLS = listOf("segment" to "趋势线", "horizontalStraightLine" to "水平线", "verticalStraightLine" to "垂直线", "rayLine" to "射线",
    "parallelStraightLine" to "平行通道", "fibonacciLine" to "斐波那契", "rectangle" to "矩形", "textLabel" to "文字")

@Composable
fun Mt5ChartScreen(vm: AppVM, onClose: (() -> Unit)?, control: ScreenControl? = null, immersive: Boolean = false, initialDraw: Boolean = false) {
    DisposableEffect(Unit) { vm.fastPoll = true; if (immersive) control?.immersive(true); onDispose { vm.fastPoll = false; if (immersive) control?.immersive(false) } }
    LaunchedEffect(vm.pair, vm.exchangeId) { if (vm.hasKeys()) vm.loadAccount() }
    val ctl = remember { KChartController() }
    val cfg = remember(vm.pair, vm.cfgVersion) { vm.chartCfg() }
    var menu by remember { mutableStateOf<String?>(null) } // "tf" | "ind" | "type"
    var drawBar by remember { mutableStateOf(initialDraw) }; var textPrompt by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }; var landscape by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<Order?>(null) }
    var moveTarget by remember { mutableStateOf<Pair<Order, BigDecimal>?>(null) }
    var tpslMove by remember { mutableStateOf<Pair<String, BigDecimal>?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) { val narrow = maxWidth < 600.dp; val panelW = minOf(310.dp, maxWidth - 8.dp)
        Column(Modifier.fillMaxSize()) {
            // ---- MT5-like top toolbar ----
            Row(Modifier.fillMaxWidth().background(Palette.panel).height(42.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onClose != null) TbIcon("✕", false) { onClose() }
                TbIcon("✎", drawBar) { drawBar = !drawBar }
                TbIcon("ƒ", menu == "ind") { menu = "ind" }
                Box { TbIcon(TF.label(vm.interval), menu == "tf", bold = true) { menu = "tf" }
                    DropdownMenu(menu == "tf", { menu = null }, Modifier.background(Palette.panel2)) {
                        TF.mt5.chunked(3).forEach { row -> Row { row.forEach { (lab, iv) ->
                            Text(lab, color = if (vm.interval == iv) Palette.accent else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                modifier = Modifier.width(64.dp).clickable { vm.interval = iv; vm.loadKlines(); menu = null }.padding(vertical = 10.dp)) } } } } }
                Box { TbIcon(when (cfg.type) { "ohlc" -> "╫"; "area" -> "〰"; "candle_stroke" -> "▯"; else -> "▮" }, menu == "type") { menu = "type" }
                    DropdownMenu(menu == "type", { menu = null }, Modifier.background(Palette.panel2)) { ChartCfg.TYPES.forEach { (k, lab) ->
                        DropdownMenuItem({ Text(lab, color = if (k == cfg.type) Palette.accent else Palette.text) }, { vm.saveChartCfg(cfg.copy(type = k)); menu = null }) } } }
                Row(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { picker = true }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column { Row(verticalAlignment = Alignment.CenterVertically) { Text(vm.pair.base + vm.pair.quote, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(" ▾", color = Palette.dim, fontSize = 10.sp) }
                        val t = vm.ticker
                        if (t != null) Text("${TF.label(vm.interval)} · ${t.last.dp(vm.info.priceDp)} ${if (t.changePct.signum() >= 0) "+" else ""}${t.changePct.setScale(2, RoundingMode.HALF_UP)}%",
                            color = if (t.changePct.signum() >= 0) Palette.up else Palette.down, fontSize = 10.sp, fontFamily = mono, maxLines = 1) } }
                TbIcon("⇥", false) { ctl.scrollLatest() }
                TbIcon("⚡", panelOpen) { panelOpen = !panelOpen }
                if (control != null) TbIcon("⟳", landscape) { landscape = control.toggleLandscape() }
            }
            if (drawBar) Row(Modifier.fillMaxWidth().background(Palette.panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                DRAW_TOOLS.forEach { (k, lab) -> Chip(lab, false) { if (k == "textLabel") textPrompt = true else ctl.startDraw(k) } }
                Chip("删除选中", ctl.selected.isNotEmpty(), Palette.down) { ctl.deleteSelected() }
                Chip("清除全部", false, Palette.down) { ctl.clearDrawings() }
            }
            KLineChartView(vm, ctl, Modifier.weight(1f).fillMaxWidth(), cfg = cfg, showBidAsk = cfg.bidAsk,
                onOrderMoved = { o, px -> moveTarget = o to px }, onOrderTap = { cancelTarget = it }, onTpSlMoved = { w, px -> tpslMove = w to px })
        }
        if (panelOpen) OneClickPanel(vm, panelW, Modifier.align(Alignment.TopEnd).padding(top = if (drawBar) 96.dp else 60.dp, end = if (narrow) 4.dp else 120.dp))
    }
    if (menu == "ind") IndicatorDialog(vm, cfg, { menu = null }) { licenses = true }
    if (licenses) LicensesDialog { licenses = false }
    if (picker) PairPicker(vm) { picker = false }
    if (textPrompt) { var t by remember { mutableStateOf("") }
        AlertDialog({ textPrompt = false }, containerColor = Palette.panel2, title = { Text("文字标注", color = Palette.text) },
            text = { Column { Lbl("输入文字后，在图上点击放置位置"); OutlinedTextField(t, { t = it.take(40) }, singleLine = true) } },
            confirmButton = { TextButton({ if (t.isNotBlank()) ctl.startDraw("textLabel", t); textPrompt = false }) { Text("放置", color = Palette.accent) } },
            dismissButton = { TextButton({ textPrompt = false }) { Text("取消", color = Palette.dim) } }) }
    cancelTarget?.let { o -> AlertDialog({ cancelTarget = null }, containerColor = Palette.panel2,
        title = { Text("撤销委托？", color = Palette.text) },
        text = { Column { KV("方向", if (o.side == Side.BUY) "买入" else "卖出"); KV("价格", o.price.plain()); KV("数量", o.qty.plain()); KV("已成交", o.filled.plain()); KV("订单号", o.id.takeLast(12))
            if (o.isPlainLimit()) Lbl("提示：按住委托线上下拖动可改价", s = 10) } },
        confirmButton = { TextButton({ vm.cancel(o); cancelTarget = null }) { Text("撤单", color = Palette.down) } },
        dismissButton = { TextButton({ cancelTarget = null }) { Text("取消", color = Palette.dim) } }) }
    moveTarget?.let { (o, px) -> val (req, msg) = AppVM.modifyPlan(o, px, vm.info, vm.makerOnly, vm.bestBid, vm.bestAsk, vm.pair)
        fun close() { moveTarget = null; ctl.linesNonce++ }
        AlertDialog({ close() }, containerColor = Palette.panel2, title = { Text("修改挂单价格", color = Palette.text) },
            text = { Column { KV("方向", if (o.side == Side.BUY) "买入" else "卖出"); KV("原价格", o.price.plain()); KV("新价格", req?.price?.plain() ?: vm.info.roundPrice(px).plain())
                KV("剩余数量", (o.qty - o.filled).plain()); if (vm.makerOnly) KV("模式", "只做Maker (post-only)")
                Spacer(Modifier.height(6.dp)); Lbl(if (req == null) msg else "将先撤销原委托，再以新价格重新下单（撤单+重下，非原子操作；撤单后若重下失败需手动补单）。", if (req == null) Palette.down else Palette.dim, 11) } },
            confirmButton = { if (req != null) TextButton({ vm.modifyOrder(o, px); moveTarget = null }) { Text("确认改价", color = Palette.accent) } },
            dismissButton = { TextButton({ close() }) { Text("取消", color = Palette.dim) } }) }
    tpslMove?.let { (w, px) -> val r = vm.tpsl; fun close() { tpslMove = null; ctl.linesNonce++ }
        if (r == null) { close(); return@let }
        val npx = vm.info.roundPrice(px)
        AlertDialog({ close() }, containerColor = Palette.panel2, title = { Text("修改${if (w == "tp") "止盈 TP" else "止损 SL"}", color = if (w == "tp") Palette.up else Palette.down) },
            text = { Column { KV("原价格", ((if (w == "tp") r.tp else r.sl) ?: BigDecimal.ZERO).plain()); KV("新价格", npx.plain()); KV("数量", r.qty.plain())
                Lbl(if (r.native) "交易所OCO：将撤销原OCO并以新价格重新提交。" else "App 本地监控：立即生效。", s = 11) } },
            confirmButton = { TextButton({ vm.moveTpSl(r, w, npx); tpslMove = null }) { Text("确认", color = Palette.accent) } },
            dismissButton = { TextButton({ close() }) { Text("取消", color = Palette.dim) } }) }
    vm.postOnlyReject?.let { PostOnlyRejectDialog(vm, it) }
}

/** MT5 "Indicators" list with editable parameters (persisted per symbol). */
@Composable
fun IndicatorDialog(vm: AppVM, cfg: ChartCfg, onClose: () -> Unit, onLicenses: () -> Unit) {
    AlertDialog(onClose, containerColor = Palette.panel2, title = { Text("指标与参数", color = Palette.text) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf(true to "主图", false to "副图").forEach { (main, title) ->
                Text(title, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                cfg.inds.filter { it.main == main }.forEach { ind -> var t by remember(vm.pair, ind.name) { mutableStateOf(ind.paramText) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ind.on, { vm.saveChartCfg(cfg.toggle(ind.name)) }, colors = CheckboxDefaults.colors(checkedColor = Palette.accent, uncheckedColor = Palette.dim))
                        Text(ind.name, color = Palette.text, fontSize = 13.sp, modifier = Modifier.width(52.dp))
                        BasicTextField(t, { v -> t = v.filter { it.isDigit() || it == '.' || it == ',' }; ChartCfg.parseParams(t)?.let { vm.saveChartCfg(vm.chartCfg().withParams(ind.name, it)) } },
                            singleLine = true, cursorBrush = SolidColor(Palette.accent), textStyle = TextStyle(color = if (ChartCfg.parseParams(t) == null) Palette.down else Palette.text, fontSize = 13.sp, fontFamily = mono),
                            modifier = Modifier.weight(1f).background(Palette.field, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 6.dp)) } } }
            Text("其他", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            CheckRow("显示 Bid/Ask 价格线", cfg.bidAsk) { vm.saveChartCfg(cfg.copy(bidAsk = !cfg.bidAsk)) }
            Lbl("参数用逗号分隔，例如 MA: 7,25,99；BOLL: 20,2。设置按币对保存。", s = 10)
            TextButton(onLicenses) { Text("开源许可 (KLineChart · Jesse)", color = Palette.accent, fontSize = 12.sp) } } },
        confirmButton = { TextButton(onClose) { Text("完成", color = Palette.accent) } })
}

@Composable private fun TbIcon(s: String, sel: Boolean, bold: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.padding(horizontal = 1.dp).clip(RoundedCornerShape(4.dp)).background(if (sel) Palette.accent.copy(alpha = 0.22f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp)) {
        Text(s, color = if (sel) Palette.accent else Palette.text, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal) }

@Composable private fun CheckRow(t: String, on: Boolean, click: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Checkbox(on, { click() }, colors = CheckboxDefaults.colors(checkedColor = Palette.accent, uncheckedColor = Palette.dim)); Text(t, color = Palette.text, fontSize = 13.sp) }

fun ordersFor(vm: AppVM): List<Order> = vm.openOrders.filter { it.price.signum() > 0 && it.isOpen &&
    Quotes.key(it.symbol) == Quotes.key(vm.pair.base + vm.pair.quote) }

/** MT5-style price: small leading digits, big last two digits. */
@Composable
private fun BigPrice(p: BigDecimal?, dp: Int, c: Color) {
    val s = p?.dp(dp) ?: "--"; val cut = (s.length - 2).coerceAtLeast(0)
    Text(buildAnnotatedString { withStyle(SpanStyle(fontSize = 12.sp)) { append(s.substring(0, cut)) }
        withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)) { append(s.substring(cut)) } }, color = c, fontFamily = mono, maxLines = 1)
}

@Composable
fun OneClickPanel(vm: AppVM, width: androidx.compose.ui.unit.Dp = 310.dp, modifier: Modifier = Modifier, initialRiskOpen: Boolean = false) {
    val i = vm.info; val dp = i.priceDp; val maker = vm.makerOnly
    val defVol = remember(vm.pair, i) { vm.savedVolume(vm.pair) ?: maxOf(i.minQty, i.step).let { m ->
        // default ≈ min notional (or 10 quote) worth, rounded up to a lot step
        val px = vm.ticker?.last; val target = maxOf(i.minNotional, BigDecimal(10))
        if (px == null || px.signum() == 0 || i.step.signum() == 0) m else maxOf(m, target.divide(px, 12, RoundingMode.UP).divide(i.step, 0, RoundingMode.UP).multiply(i.step).setScale(i.qtyDp, RoundingMode.UP)) } }
    var volTxt by remember(vm.pair, defVol) { mutableStateOf(defVol.plain()) }
    val vol = volTxt.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun setVol(v: BigDecimal) { val r = i.roundQty(v.max(BigDecimal.ZERO)); volTxt = r.plain(); vm.saveVolume(vm.pair, r) }
    val bid = vm.bestBid; val ask = vm.bestAsk
    val sellPx = if (maker) ask else bid; val buyPx = if (maker) bid else ask
    var confirm by remember { mutableStateOf<AppVM.Quick?>(null) }; var disclaimer by remember { mutableStateOf(false) }
    var riskOpen by remember { mutableStateOf(initialRiskOpen) }
    fun fire(side: Side) { val q = vm.buildQuick(side, vol) ?: return; if (vm.oneClick) vm.quickOrder(q) else confirm = q }
    Column(modifier.width(width).clip(RoundedCornerShape(6.dp)).background(Palette.panel.copy(alpha = 0.94f)).border(0.5.dp, Palette.line, RoundedCornerShape(6.dp)).padding(5.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            QuickBtn(if (maker) "SELL · 挂卖一" else "SELL · 市价", sellPx, dp, Palette.down, !vm.busy, Modifier.weight(1f)) { fire(Side.SELL) }
            Column(Modifier.width(if (width < 305.dp) 96.dp else 112.dp).padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepBtn("−") { setVol(vol - i.step) }
                    BasicTextField(volTxt, { volTxt = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, cursorBrush = SolidColor(Palette.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(color = Palette.text, fontSize = 12.sp, fontFamily = mono, textAlign = TextAlign.Center),
                        modifier = Modifier.weight(1f).background(Palette.field, RoundedCornerShape(3.dp)).padding(vertical = 5.dp))
                    StepBtn("+") { setVol(vol + i.step) } }
                Text(vm.pair.base, color = Palette.dim, fontSize = 9.sp)
                val spread = if (bid != null && ask != null) ask - bid else null
                Text("点差 ${spread?.let { s -> if (i.tick.signum() > 0) s.divide(i.tick, 0, RoundingMode.HALF_UP).toPlainString() else s.plain() } ?: "--"}",
                    color = Palette.dim, fontSize = 9.sp, fontFamily = mono, maxLines = 1) }
            QuickBtn(if (maker) "BUY · 挂买一" else "BUY · 市价", buyPx, dp, Palette.up, !vm.busy, Modifier.weight(1f)) { fire(Side.BUY) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val px = buyPx ?: vm.ticker?.last
            Text("≈${px?.let { (vol * it).setScale(2, RoundingMode.HALF_UP).plain() } ?: "--"} ${vm.pair.quote}", color = Palette.dim, fontSize = 9.sp, fontFamily = mono)
            Spacer(Modifier.weight(1f))
            if (maker) Text("只做Maker ", color = Palette.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("一键交易", color = if (vm.oneClick) Palette.warn else Palette.dim, fontSize = 10.sp)
            Switch(vm.oneClick, { on -> if (on && !vm.oneClickAcked) disclaimer = true else vm.enableOneClick(on) },
                modifier = Modifier.scale(0.6f).height(22.dp), colors = SwitchDefaults.colors(checkedTrackColor = Palette.warn, checkedThumbColor = Color.White))
        }
        Row(Modifier.fillMaxWidth().clickable { riskOpen = !riskOpen }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (riskOpen) "▾ TP/SL · 风险计算" else "▸ TP/SL · 风险计算", color = Palette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f)); vm.tpsl?.let { r -> Text("${r.tp?.let { "TP ${it.plain()} " } ?: ""}${r.sl?.let { "SL ${it.plain()}" } ?: ""}", color = Palette.warn, fontSize = 9.sp, fontFamily = mono, maxLines = 1) } }
        if (riskOpen) RiskTpSlSection(vm, compact = true, onUseQty = { setVol(it) })
    }
    confirm?.let { q -> AlertDialog({ confirm = null }, containerColor = Palette.panel2,
        title = { Text(if (q.req.side == Side.BUY) "确认买入" else "确认卖出", color = if (q.req.side == Side.BUY) Palette.up else Palette.down) },
        text = { Column { Text(q.desc, color = Palette.text, fontSize = 13.sp); Spacer(Modifier.height(6.dp))
            Lbl(if (maker) "只做Maker：若价格变动导致会立即成交，交易所会拒绝，可一键改价。" else "市价单按盘口即时成交，实际成交价可能偏离参考价（滑点）。", s = 10) } },
        confirmButton = { TextButton({ vm.quickOrder(q); confirm = null }) { Text("确认下单", color = Palette.accent) } },
        dismissButton = { TextButton({ confirm = null }) { Text("取消", color = Palette.dim) } }) }
    if (disclaimer) AlertDialog({ disclaimer = false }, containerColor = Palette.panel2, title = { Text("开启一键交易 · 风险提示", color = Palette.warn) },
        text = { Text("开启后，点击 BUY / SELL 将不再弹出确认，立即以当前数量向交易所发送真实委托。\n\n• 误触、行情快速变化、网络延迟都可能导致非预期成交与损失；\n• 关闭「只做Maker」时为市价单，可能有滑点；\n• 你可随时在此关闭一键交易。\n\n我已了解并自行承担风险。", color = Palette.text, fontSize = 13.sp) },
        confirmButton = { TextButton({ vm.enableOneClick(true); disclaimer = false }) { Text("我已了解，开启", color = Palette.warn) } },
        dismissButton = { TextButton({ disclaimer = false }) { Text("取消", color = Palette.dim) } })
}

@Composable
private fun QuickBtn(label: String, px: BigDecimal?, dp: Int, c: Color, enabled: Boolean, m: Modifier, onClick: () -> Unit) =
    Column(m.fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (enabled && px != null) c else c.copy(alpha = 0.35f))
        .clickable(enabled = enabled && px != null) { onClick() }.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1); BigPrice(px, dp, Color.White) }

/** − / + with press-and-hold auto-repeat. */
@Composable
private fun StepBtn(t: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)).background(Palette.field).pointerInput(Unit) {
        detectTapGestures(onPress = { coroutineScope { step(); val j = launch { delay(400); var d = 120L; while (true) { step(); delay(d); d = maxOf(30L, d - 10) } }
            tryAwaitRelease(); j.cancel() } }) }, contentAlignment = Alignment.Center) { Text(t, color = Palette.text, fontSize = 14.sp) }
}

private val mono = androidx.compose.ui.text.font.FontFamily.Monospace
