package com.morris.spottrader.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class ChartType(val label: String, val icon: String) { CANDLE("蜡烛图", "▮"), BAR("美国线", "╫"), LINE("折线图", "〰") }

object Ind2 {
    fun macd(c: List<Double>, f: Int = 12, s: Int = 26, sig: Int = 9): Triple<List<Double>, List<Double>, List<Double>> {
        fun e(v: List<Double>, n: Int): List<Double> { val k = 2.0 / (n + 1); var x = Double.NaN; var seen = 0
            return v.map { if (it.isNaN()) Double.NaN else { seen++; x = if (x.isNaN()) it else it * k + x * (1 - k); if (seen < n) Double.NaN else x } } }
        val fe = e(c, f); val se = e(c, s); val dif = c.indices.map { fe[it] - se[it] }; val dea = e(dif, sig)
        return Triple(dif, dea, c.indices.map { dif[it] - dea[it] }) }
    fun rsi(c: List<Double>, n: Int = 14): List<Double> { var ag = 0.0; var al = 0.0
        return c.indices.map { i -> if (i == 0) return@map Double.NaN; val d = c[i] - c[i - 1]; val g = max(d, 0.0); val l = max(-d, 0.0)
            if (i <= n) { ag += g / n; al += l / n; if (i < n) return@map Double.NaN } else { ag = (ag * (n - 1) + g) / n; al = (al * (n - 1) + l) / n }
            if (al == 0.0) 100.0 else 100 - 100 / (1 + ag / al) } }
}

/** Shared viewport state: zoom (bars visible) + pan offset (bars from latest). endOff == 0 → auto-scroll with new bars. */
class ChartViewport(initial: Float = 80f) {
    var count by mutableFloatStateOf(initial); var endOff by mutableFloatStateOf(0f)
    var cross by mutableStateOf<Offset?>(null)
    val atLatest get() = endOff < 0.5f
    fun clamp(n: Int) { count = count.coerceIn(10f, max(10f, min(400f, n.toFloat()))); endOff = endOff.coerceIn(0f, max(0f, n - count * 0.3f)) }
}

private val pcPaint = Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
private fun DrawScope.t(s: String, x: Float, y: Float, c: Color, sz: Float = 22f, right: Boolean = false) {
    pcPaint.color = c.toArgb(); pcPaint.textSize = sz; val xx = if (right) x - pcPaint.measureText(s) else x; drawContext.canvas.nativeCanvas.drawText(s, xx, y, pcPaint) }
private fun tw(s: String, sz: Float) = pcPaint.apply { textSize = sz }.measureText(s)

class ChartGeo { var left = 0f; var w = 1f; var hi = 0.0; var rng = 1.0; var mainH = 1f; var cw = 1f
    fun y(v: Double) = ((hi - v) / rng * mainH).toFloat(); fun price(y: Float) = hi - y / mainH * rng; fun idx(x: Float) = floor(left + x / w).toInt() }

/** MT5-like gesture model: 1-finger horizontal drag = pan, pinch = zoom, long-press (or crosshair mode) = crosshair, tap = onTap.
 *  Vertical single-finger drags are NOT consumed so the chart can live inside a scrolling list. */
fun Modifier.chartGestures(vp: ChartViewport, n: () -> Int, geo: ChartGeo, crossMode: () -> Boolean, onTap: (Offset) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false); val t0 = down.uptimeMillis; val slop = viewConfiguration.touchSlop
        var mode = if (crossMode()) 3 else 0 // 0 undecided, 1 pan, 2 zoom, 3 crosshair, -1 vertical (left to parent)
        if (mode == 3) vp.cross = down.position
        var last = down.position; var prevDist = 0f
        while (true) {
            val ev = if (mode == 0) { val left = viewConfiguration.longPressTimeoutMillis - (last.let { 0L }) - (android.os.SystemClock.uptimeMillis() - t0)
                withTimeoutOrNull(maxOf(1L, left)) { awaitPointerEvent(PointerEventPass.Main) } } else awaitPointerEvent(PointerEventPass.Main)
            if (ev == null) { mode = 3; vp.cross = last; continue } // long-press → crosshair
            val ps = ev.changes.filter { it.pressed }
            if (ps.isEmpty()) { if (mode == 0) onTap(down.position); break }
            if (ps.size >= 2) { val d = (ps[0].position - ps[1].position).getDistance()
                if (mode != 3) { if (prevDist > 0f && d > 0f) { vp.count = vp.count * prevDist / d; vp.clamp(n()) }; mode = 2 }
                prevDist = d; ev.changes.forEach { it.consume() }; continue }
            prevDist = 0f; val c = ps[0]; val dx = c.position.x - c.previousPosition.x; last = c.position
            if (mode == 0 && (c.position - down.position).getDistance() > slop)
                mode = if (abs(c.position.x - down.position.x) >= abs(c.position.y - down.position.y)) 1 else -1
            when (mode) {
                1 -> { vp.endOff += dx / geo.w; vp.clamp(n()); c.consume() }
                3 -> { vp.cross = Offset(c.position.x.coerceIn(0f, geo.cw), c.position.y); c.consume() }
                2 -> c.consume()
            }
        }
    }
}

data class MainInd(val name: String, val color: Color, val values: List<Double>)

/** Full-featured price chart used by both the 图表 tab and the small chart on the 交易 screen. */
@Composable
fun ProChart(vm: AppVM, vp: ChartViewport, type: ChartType, mains: Set<String>, subs: List<String>, modifier: Modifier,
             showBidAsk: Boolean = true, showTrades: Boolean = true, crossMode: Boolean = false, compact: Boolean = false,
             onOrderTap: ((Order) -> Unit)? = null, extra: List<ChartLine> = emptyList()) {
    val all = vm.klines; val closes = remember(all) { all.map { it.c } }
    val lines = remember(all, mains) { buildList {
        if ("MA" in mains) { add(MainInd("MA7", Palette.ma1, Ind.sma(closes, 7))); add(MainInd("MA25", Palette.ma2, Ind.sma(closes, 25))); add(MainInd("MA99", Palette.ma3, Ind.sma(closes, 99))) }
        if ("EMA" in mains) { add(MainInd("EMA12", Color(0xFFFF8A65), Ind.ema(closes, 12))); add(MainInd("EMA26", Color(0xFF81C784), Ind.ema(closes, 26))) }
        if ("BOLL" in mains) Ind.boll(closes).let { (u, m, l) -> add(MainInd("BB上", Color(0xFF64B5F6), u)); add(MainInd("BB中", Color(0xFFBDBDBD), m)); add(MainInd("BB下", Color(0xFF64B5F6), l)) } } }
    val macd = remember(all, "MACD" in subs) { if ("MACD" in subs) Ind2.macd(closes) else null }
    val rsi = remember(all, "RSI" in subs) { if ("RSI" in subs) Ind2.rsi(closes) else null }
    val dp = vm.info.priceDp; val geo = remember { ChartGeo() }
    val orders = if (showTrades) ordersFor(vm) else emptyList(); val cost = if (showTrades) vm.pairCost else null
    LaunchedEffect(all.size) { vp.clamp(all.size) }
    val latestCm = rememberUpdatedState(crossMode); val ordersS = rememberUpdatedState(orders); val sizeS = rememberUpdatedState(all.size)
    val tf = if (vm.interval.endsWith("d") || vm.interval.endsWith("w") || vm.interval == "1M") SimpleDateFormat(if (vm.interval == "1M") "yyyy-MM" else "yy-MM-dd", Locale.US)
             else SimpleDateFormat("MM-dd HH:mm", Locale.US)
    val sz = if (compact) 22f else 21f
    Canvas(modifier.chartGestures(vp, { sizeS.value }, geo, { latestCm.value }) { o ->
        val hit = ordersS.value.minByOrNull { abs(geo.y(it.price.toDouble()) - o.y) }
        if (onOrderTap != null && hit != null && o.x < geo.cw * 0.6f && abs(geo.y(hit.price.toDouble()) - o.y) < 28f) onOrderTap(hit)
        else if (vp.cross != null) vp.cross = null }) {
        if (all.isEmpty()) { t("加载K线…", size.width / 2 - 60f, size.height / 2, Palette.dim, 26f); return@Canvas }
        val nAll = all.size; val count = vp.count; val right = nAll - vp.endOff + 2f // 2 bars right margin (MT5 "chart shift")
        val left = right - count; val i0 = max(0, floor(left).toInt()); val i1 = min(nAll - 1, ceil(right).toInt())
        if (i1 < i0) return@Canvas
        val vis = (i0..i1)
        val axisW = tw(BigDecimal(all.maxOf { it.h } * 1.1).dp(dp), sz) + 14f; val cw = size.width - axisW
        val timeH = 26f; val subH = if (subs.isEmpty()) 0f else (size.height - timeH) * (if (subs.size == 1) 0.24f else 0.18f)
        val mainH = size.height - timeH - subH * subs.size
        val w = cw / count; fun x(i: Int) = (i - left) * w + w / 2
        var hi = vis.maxOf { all[it].h }; var lo = vis.minOf { all[it].l }
        lines.forEach { l -> vis.forEach { i -> val v = l.values[i]; if (!v.isNaN()) { hi = max(hi, v); lo = min(lo, v) } } }
        val pad = (hi - lo) * 0.07; hi += pad; lo -= pad; val rng = (hi - lo).takeIf { it > 0 } ?: 1.0
        geo.left = left; geo.w = w; geo.hi = hi; geo.rng = rng; geo.mainH = mainH; geo.cw = cw
        fun y(v: Double) = geo.y(v)
        // grid + price axis
        val gl = if (mainH > 500) 8 else 5
        for (g in 0..gl) { val gy = mainH * g / gl; drawLine(Palette.line, Offset(0f, gy), Offset(cw, gy), 0.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            t(BigDecimal(hi - rng * g / gl).dp(dp), cw + 6f, (gy + 7f).coerceIn(sz, mainH - 2f), Palette.dim, sz) }
        drawLine(Palette.line, Offset(cw, 0f), Offset(cw, size.height), 1f)
        // time axis
        val step = max(1, (170f / w).toInt()); val ty = size.height - 6f
        drawLine(Palette.line, Offset(0f, size.height - timeH), Offset(size.width, size.height - timeH), 1f)
        vis.filter { it % step == 0 }.forEach { i -> val xx = x(i); if (xx in 0f..cw - 60f) {
            drawLine(Palette.line, Offset(xx, 0f), Offset(xx, size.height - timeH), 0.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            t(tf.format(Date(all[i].t)), xx + 3f, ty, Palette.dim, 19f) } }
        // price series
        clipRectSafe(cw, mainH) {
            when (type) {
                ChartType.CANDLE -> vis.forEach { i -> val k = all[i]; val col = if (k.c >= k.o) Palette.up else Palette.down; val xx = x(i)
                    drawLine(col, Offset(xx, y(k.h)), Offset(xx, y(k.l)), 1.3f); val top = y(max(k.o, k.c)); val bot = y(min(k.o, k.c))
                    drawRect(col, Offset(xx - w * 0.36f, top), Size(max(w * 0.72f, 1f), max(bot - top, 1.3f))) }
                ChartType.BAR -> vis.forEach { i -> val k = all[i]; val col = if (k.c >= k.o) Palette.up else Palette.down; val xx = x(i); val sw = max(1.2f, w * 0.12f)
                    drawLine(col, Offset(xx, y(k.h)), Offset(xx, y(k.l)), sw); drawLine(col, Offset(xx - w * 0.38f, y(k.o)), Offset(xx, y(k.o)), sw)
                    drawLine(col, Offset(xx, y(k.c)), Offset(xx + w * 0.38f, y(k.c)), sw) }
                ChartType.LINE -> for (i in i0 + 1..i1) drawLine(Palette.accent, Offset(x(i - 1), y(all[i - 1].c)), Offset(x(i), y(all[i].c)), 2.2f)
            }
            lines.forEach { l -> for (i in i0 + 1..i1) { val a = l.values[i - 1]; val b = l.values[i]
                if (!a.isNaN() && !b.isNaN()) drawLine(l.color, Offset(x(i - 1), y(a)), Offset(x(i), y(b)), 1.5f) } }
        }
        fun tag(v: Double, c: Color, txt: String, fg: Color = Color.White) { val yy = y(v); if (yy < 0 || yy > mainH) return
            drawRect(c, Offset(cw, yy - 14f), Size(axisW, 27f)); t(txt, cw + 5f, yy + 7f, fg, sz) }
        fun hLine(v: Double, c: Color, label: String, tagTxt: String, dash: FloatArray?) { val yy = y(v)
            if (yy < 0f || yy > mainH) { t("${if (yy < 0f) "▲" else "▼"} $label", 8f, if (yy < 0f) 18f else mainH - 6f, c, 19f); return }
            drawLine(c, Offset(0f, yy), Offset(cw, yy), 1.3f, pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
            val lw = tw(label, 19f) + 12f; drawRect(c.copy(alpha = 0.2f), Offset(4f, yy - 23f), Size(lw, 21f)); t(label, 10f, yy - 7f, c, 19f); tag(v, c, tagTxt) }
        cost?.let { c -> val px = vm.ticker?.last; val pnl = px?.let { (it - c.avg).divide(c.avg, 6, RoundingMode.HALF_UP) * BigDecimal(100) }
            hLine(c.avg.toDouble(), Palette.warn, "持仓 ${c.qty.plain()} @${c.avg.dp(dp)}${pnl?.let { "  " + (if (it.signum() >= 0) "+" else "") + it.setScale(2, RoundingMode.HALF_UP) + "%" } ?: ""}", c.avg.dp(dp), floatArrayOf(4f, 4f)) }
        orders.forEach { o -> val c = if (o.side == Side.BUY) Palette.up else Palette.down
            hLine(o.price.toDouble(), c, "${if (o.side == Side.BUY) "buy limit" else "sell limit"} ${(o.qty - o.filled).plain()}${if (onOrderTap != null) "  ✕" else ""}", o.price.dp(dp), floatArrayOf(10f, 6f)) }
        extra.forEach { l -> hLine(l.price.toDouble(), l.color, l.label, l.price.dp(dp), null) }
        // bid / ask lines (MT5: bid grey, ask red)
        val bid = vm.bestBid?.toDouble() ?: vm.ticker?.last?.toDouble() ?: all.last().c; val ask = vm.bestAsk?.toDouble()
        if (showBidAsk) { val bc = Color(0xFF9AA3B2)
            if (ask != null && abs(y(ask) - y(bid)) > 1f) { drawLine(Palette.down, Offset(0f, y(ask)), Offset(cw, y(ask)), 1f); tag(ask, Palette.down, BigDecimal(ask).dp(dp)) }
            drawLine(bc, Offset(0f, y(bid)), Offset(cw, y(bid)), 1f); tag(bid, bc, BigDecimal(bid).dp(dp), Color.Black)
        } else { val lc = if (all.last().c >= all.last().o) Palette.up else Palette.down
            drawLine(lc, Offset(0f, y(bid).coerceIn(0f, mainH)), Offset(cw, y(bid).coerceIn(0f, mainH)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))); tag(bid, lc, BigDecimal(bid).dp(dp)) }
        // sub windows
        subs.forEachIndexed { si, s -> val top = mainH + subH * si; val bot = top + subH - 4f
            drawLine(Palette.line, Offset(0f, top), Offset(size.width, top), 1.2f)
            fun series(v: List<Double>, lo2: Double, hi2: Double, c: Color) { val r = (hi2 - lo2).takeIf { it > 0 } ?: 1.0
                fun yy(d: Double) = (bot - (d - lo2) / r * (bot - top - 22f)).toFloat()
                for (i in i0 + 1..i1) { val a = v[i - 1]; val b = v[i]; if (!a.isNaN() && !b.isNaN()) drawLine(c, Offset(x(i - 1), yy(a)), Offset(x(i), yy(b)), 1.4f) } }
            val li = vp.cross?.let { geo.idx(it.x).coerceIn(i0, i1) } ?: i1
            when (s) {
                "VOL" -> { val vmax = vis.maxOf { all[it].v }.takeIf { it > 0 } ?: 1.0
                    vis.forEach { i -> val k = all[i]; val h = ((k.v / vmax) * (bot - top - 22f)).toFloat()
                        drawRect((if (k.c >= k.o) Palette.up else Palette.down).copy(alpha = 0.6f), Offset(x(i) - w * 0.36f, bot - h), Size(max(w * 0.72f, 1f), h)) }
                    t("Volumes ${compact(BigDecimal(all[li].v))}", 6f, top + 19f, Palette.dim, 19f); t(compact(BigDecimal(vmax)), cw + 5f, top + 20f, Palette.dim, 19f) }
                "MACD" -> macd?.let { (dif, dea, hist) -> var a = 0.0; vis.forEach { i -> listOf(dif[i], dea[i], hist[i]).forEach { v -> if (!v.isNaN()) a = max(a, abs(v)) } }; if (a == 0.0) a = 1.0
                    val r = 2 * a
                    fun yy(d: Double) = (bot - (d + a) / r * (bot - top - 22f)).toFloat()
                    val zero = yy(0.0)
                    drawLine(Palette.faint, Offset(0f, zero), Offset(cw, zero), 0.6f)
                    vis.forEach { i -> val h = hist[i]; if (!h.isNaN()) drawRect(Color(0xFFB0BEC5).copy(alpha = 0.7f), Offset(x(i) - w * 0.3f, min(zero, yy(h))), Size(max(w * 0.6f, 1f), abs(yy(h) - zero))) }
                    series(dif, -a, a, Palette.ma1); series(dea, -a, a, Palette.down)
                    t("MACD(12,26,9) ${"%.${dp}f".format(dif[li])} ${"%.${dp}f".format(dea[li])}", 6f, top + 19f, Palette.dim, 19f); t("%.${dp}f".format(a), cw + 5f, top + 20f, Palette.dim, 19f) }
                "RSI" -> rsi?.let { r -> listOf(70.0, 30.0).forEach { lv -> val yy = (bot - lv / 100 * (bot - top - 22f)).toFloat()
                        drawLine(Palette.faint, Offset(0f, yy), Offset(cw, yy), 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))); t(lv.toInt().toString(), cw + 5f, yy + 7f, Palette.dim, 19f) }
                    series(r, 0.0, 100.0, Palette.ma3); t("RSI(14) ${"%.2f".format(r[li])}", 6f, top + 19f, Palette.dim, 19f) }
            }
        }
        // crosshair + OHLC readout
        vp.cross?.let { c -> val i = geo.idx(c.x).coerceIn(i0, i1); val k = all[i]; val xx = x(i); val ce = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
            drawLine(Palette.text.copy(alpha = 0.7f), Offset(xx, 0f), Offset(xx, size.height - timeH), 1f, pathEffect = ce)
            val cy = c.y.coerceIn(0f, size.height - timeH); drawLine(Palette.text.copy(alpha = 0.7f), Offset(0f, cy), Offset(cw, cy), 1f, pathEffect = ce)
            if (cy <= mainH) { drawRect(Palette.text, Offset(cw, cy - 14f), Size(axisW, 27f)); t(BigDecimal(geo.price(cy)).dp(dp), cw + 5f, cy + 7f, Color.Black, sz) }
            val ts = tf.format(Date(k.t)); val tww = tw(ts, 19f) + 10f; val tx = (xx - tww / 2).coerceIn(0f, cw - tww)
            drawRect(Palette.text, Offset(tx, size.height - timeH), Size(tww, timeH)); t(ts, tx + 5f, ty, Color.Black, 19f)
            val f = { d: Double -> BigDecimal(d).dp(dp) }
            val rows = listOf(ts, "O  ${f(k.o)}", "H  ${f(k.h)}", "L  ${f(k.l)}", "C  ${f(k.c)}", "Δ  ${"%+.2f".format((k.c - k.o) / k.o * 100)}%", "V  ${compact(BigDecimal(k.v))}")
            val bw = rows.maxOf { tw(it, 20f) } + 16f; val bx = if (xx > cw / 2) 8f else cw - bw - 8f; val by = if (compact) 8f else mainH * 0.32f
            drawRect(Palette.panel2.copy(alpha = 0.93f), Offset(bx, by), Size(bw, rows.size * 24f + 10f))
            rows.forEachIndexed { r, s -> t(s, bx + 8f, by + 24f + r * 24f, if (r == 0) Palette.dim else Palette.text, 20f) }
            drawCircle(Palette.text, 4f, Offset(xx, y(k.c).coerceIn(0f, mainH))) }
    }
}

private inline fun DrawScope.clipRectSafe(w: Float, h: Float, block: DrawScope.() -> Unit) { drawContext.canvas.save(); drawContext.canvas.clipRect(0f, 0f, w, h); block(); drawContext.canvas.restore() }
