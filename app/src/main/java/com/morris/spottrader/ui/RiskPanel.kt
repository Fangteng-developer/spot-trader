package com.morris.spottrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.*
import com.morris.spottrader.pos.RiskCalc
import com.morris.spottrader.pos.TpSlManager
import com.morris.spottrader.pos.TpSlRule
import java.math.BigDecimal
import java.math.RoundingMode

private val mf = FontFamily.Monospace

@Composable
private fun MiniField(label: String, v: String, on: (String) -> Unit, m: Modifier, color: Color = Palette.text) = Column(m.padding(horizontal = 2.dp)) {
    Text(label, color = Palette.dim, fontSize = 9.sp, maxLines = 1)
    BasicTextField(v, { on(it.filter { c -> c.isDigit() || c == '.' }) }, singleLine = true, cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = TextStyle(color = color, fontSize = 12.sp, fontFamily = mf),
        modifier = Modifier.fillMaxWidth().background(Palette.field, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 5.dp)) }

@Composable
private fun SmallBtn(t: String, c: Color, enabled: Boolean = true, m: Modifier = Modifier, onClick: () -> Unit) =
    Box(m.padding(2.dp).clip(RoundedCornerShape(4.dp)).background(if (enabled) c.copy(alpha = 0.22f) else Palette.field).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) { Text(t, color = if (enabled) c else Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }

/**
 * Risk-based position size calculator (Jesse-style risk_to_qty) + TP/SL for the current spot position.
 * Used inside the one-click panel (compact) and on the 持仓 screen.
 */
@Composable
fun RiskTpSlSection(vm: AppVM, compact: Boolean = false, pair: SymPair = vm.pair, onUseQty: ((BigDecimal) -> Unit)? = null,
                    initStop: String = "", initTp: String = "") {
    val i = vm.info; val last = vm.ticker?.last
    val bal = vm.balances.firstOrNull { it.asset == pair.quote }?.free ?: BigDecimal.ZERO
    var riskTxt by remember { mutableStateOf("1") }
    var entryTxt by remember(pair) { mutableStateOf((vm.bestAsk ?: last)?.let { i.roundPrice(it).plain() } ?: "") }
    var stopTxt by remember(pair) { mutableStateOf(initStop) }; var tpTxt by remember(pair) { mutableStateOf(initTp) }
    val res = remember(riskTxt, entryTxt, stopTxt, tpTxt, bal, i) { val st = stopTxt.toBigDecimalOrNull(); val en = entryTxt.toBigDecimalOrNull()
        if (st == null || en == null) null else RiskCalc.size(bal, riskTxt.toBigDecimalOrNull() ?: BigDecimal.ZERO, en, st, i, tpTxt.toBigDecimalOrNull()) }
    val rule = TpSlManager.forPair(vm.exchangeId, pair)
    val held = vm.pairCost?.qty?.takeIf { pair == vm.pair } ?: vm.balances.firstOrNull { it.asset == pair.base }?.total ?: BigDecimal.ZERO
    val fs = if (compact) 10 else 12
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Row { MiniField("风险%", riskTxt, { riskTxt = it }, Modifier.weight(0.7f)); MiniField("入场价", entryTxt, { entryTxt = it }, Modifier.weight(1.15f))
            MiniField("止损 SL", stopTxt, { stopTxt = it }, Modifier.weight(1.15f), Palette.down); MiniField("止盈 TP", tpTxt, { tpTxt = it }, Modifier.weight(1.15f), Palette.up) }
        Text("可用 ${bal.setScale(2, RoundingMode.DOWN).plain()} ${pair.quote}", color = Palette.dim, fontSize = (fs - 1).sp, fontFamily = mf, modifier = Modifier.padding(top = 2.dp, start = 2.dp))
        if (res != null) {
            if (res.error != null) Text(res.error, color = Palette.down, fontSize = fs.sp, modifier = Modifier.padding(start = 2.dp))
            Text("数量 ${res.qty.plain()} ${pair.base} · 金额 ${res.notional.setScale(2, RoundingMode.HALF_UP).plain()}", color = Palette.text, fontSize = fs.sp, fontFamily = mf, modifier = Modifier.padding(start = 2.dp))
            Text("风险 ${res.actualRisk.setScale(2, RoundingMode.HALF_UP).plain()}/${res.riskAmount.setScale(2, RoundingMode.HALF_UP).plain()} · 止损距 ${res.stopDistPct.setScale(2, RoundingMode.HALF_UP).plain()}%${res.rr?.let { " · R:R 1:${it.plain()}" } ?: ""}${if (res.cappedByBalance) " · 受余额限制" else ""}",
                color = Palette.dim, fontSize = (fs - 1).sp, fontFamily = mf, modifier = Modifier.padding(start = 2.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onUseQty != null) SmallBtn("用此数量", Palette.accent, res != null && res.error == null) { res?.let { onUseQty(it.qty) } }
            SmallBtn(if (rule == null) "设置TP/SL(持仓 ${held.plain()})" else "更新TP/SL", Palette.warn, held.signum() > 0 && (tpTxt.isNotBlank() || stopTxt.isNotBlank()), Modifier.weight(1f)) {
                vm.setTpSl(held, tpTxt.toBigDecimalOrNull(), stopTxt.toBigDecimalOrNull(), pair) }
            if (rule != null) SmallBtn("取消", Palette.down) { vm.cancelTpSl(rule) } }
        rule?.let { TpSlStatus(it, fs) }
        if (!compact) Lbl("仓位 = 余额×风险% ÷ (入场−止损 + 双边手续费)，按步长向下取整并校验最小下单量/金额；现货不加杠杆，数量不超过余额。TP/SL：Binance 同时设 TP+SL 时使用交易所 OCO，其他交易所由 App 前台服务监控价格触发后下单${if (vm.makerOnly) "（只做Maker：挂卖一 post-only，可能不成交）" else "（市价卖出）"}。", s = 10)
    }
}

@Composable
fun TpSlStatus(r: TpSlRule, fs: Int = 11) = Text("${if (r.native) "交易所OCO" else "App监控"} · ${r.qty.plain()} ${r.base}${r.tp?.let { " · TP ${it.plain()}" } ?: ""}${r.sl?.let { " · SL ${it.plain()}" } ?: ""} · ${r.status}",
    color = if (r.active) Palette.warn else Palette.dim, fontSize = (fs - 1).sp, fontFamily = mf, modifier = Modifier.padding(start = 2.dp, top = 1.dp))

/** 持仓 screen per-position TP/SL dialog */
@Composable
fun TpSlDialog(vm: AppVM, p: com.morris.spottrader.Position, onClose: () -> Unit) {
    val pair = SymPair(p.asset, p.quote); val r = TpSlManager.forPair(vm.exchangeId, pair)
    var tp by remember { mutableStateOf(r?.tp?.plain() ?: "") }; var sl by remember { mutableStateOf(r?.sl?.plain() ?: "") }; var q by remember { mutableStateOf((r?.qty ?: p.free).plain()) }
    AlertDialog(onClose, containerColor = Palette.panel2, title = { Text("${p.asset}/${p.quote} 止盈止损", color = Palette.text) },
        text = { Column {
            KV("持仓均价", p.avgCost?.round(java.math.MathContext(8))?.plain() ?: "--"); KV("最新价", p.price?.plain() ?: "--")
            Row { MiniField("数量", q, { q = it }, Modifier.weight(1f)); MiniField("止盈 TP", tp, { tp = it }, Modifier.weight(1f), Palette.up); MiniField("止损 SL", sl, { sl = it }, Modifier.weight(1f), Palette.down) }
            val qty = q.toBigDecimalOrNull(); val avg = p.avgCost
            if (qty != null && avg != null) { tp.toBigDecimalOrNull()?.let { KV("止盈预计盈亏", ((it - avg) * qty).setScale(2, RoundingMode.HALF_UP).plain() + " " + p.quote) }
                sl.toBigDecimalOrNull()?.let { KV("止损预计盈亏", ((it - avg) * qty).setScale(2, RoundingMode.HALF_UP).plain() + " " + p.quote) } }
            r?.let { TpSlStatus(it) }
            Lbl(if (vm.exchangeId == "binance") "Binance：同时填写 TP 与 SL 时提交交易所原生 OCO（App 关闭后仍有效）。" else "${Exchanges.names[vm.exchangeId]}：由 App 前台服务监控价格，触发后下单（需保持 App 运行）。", s = 10) } },
        confirmButton = { TextButton({ q.toBigDecimalOrNull()?.let { vm.setTpSl(it, tp.toBigDecimalOrNull(), sl.toBigDecimalOrNull(), pair) }; onClose() }) { Text("保存", color = Palette.accent) } },
        dismissButton = { Row { if (r != null) TextButton({ vm.cancelTpSl(r); onClose() }) { Text("取消TP/SL", color = Palette.down) }; TextButton(onClose) { Text("关闭", color = Palette.dim) } } })
}
