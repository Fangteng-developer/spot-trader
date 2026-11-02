package com.morris.spottrader.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.*
import com.morris.spottrader.pos.TpSlManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kotlin side of the KLineChart (Apache-2.0, bundled in assets/kline, no network) WebView bridge.
 * Kotlin -> JS: window.KC.* via evaluateJavascript; JS -> Kotlin: @JavascriptInterface "Android".
 */
/** set false in JVM screenshot tests (Paparazzi/layoutlib cannot run a WebView) */
object KChartEnv { @Volatile var webViewAvailable = true }

class KChartController {
    internal var web: WebView? = null
    var ready by mutableStateOf(false); internal set
    private val queue = ArrayList<String>()
    fun js(code: String) { val w = web
        if (ready && w != null) w.post { w.evaluateJavascript(code, null) } else queue.add(code) }
    internal fun flush() { val w = web ?: return; queue.forEach { c -> w.evaluateJavascript(c, null) }; queue.clear() }
    fun startDraw(tool: String, text: String? = null) = js("KC.startDraw(${JSONObject.quote(tool)},${if (text != null) JSONObject.quote(text) else "null"})")
    fun deleteSelected() = js("KC.deleteSelected()")
    fun clearDrawings() = js("KC.clearDrawings()")
    fun scrollLatest() = js("KC.scrollLatest()")
    var selected by mutableStateOf("")
    /** bump to force the price lines to be re-sent (e.g. snap a dragged line back after "cancel") */
    var linesNonce by mutableIntStateOf(0)
}

data class ChartLine(val id: String, val price: BigDecimal, val label: String, val color: Color, val fg: Color = Color.White, val dash: Boolean = false, val drag: Boolean = false, val click: Boolean = false, val width: Float = 1f)

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
fun klinesJson(k: List<Kline>): String = StringBuilder(k.size * 60).apply { append('['); k.forEachIndexed { i, b -> if (i > 0) append(',')
    append('[').append(b.t).append(',').append(b.o).append(',').append(b.h).append(',').append(b.l).append(',').append(b.c).append(',').append(b.v).append(']') }; append(']') }.toString()

/** plain limit order (draggable / modifiable); conditional orders are shown but locked */
fun Order.isPlainLimit() = type.uppercase().let { it.contains("LIMIT") && !it.contains("STOP") && !it.contains("@") && !it.contains("TAKE") }

/** Lines drawn over the chart: bid/ask, own pending orders, average-cost position line, TP/SL. */
fun chartLines(vm: AppVM, showBidAsk: Boolean, showTrades: Boolean, compact: Boolean): List<ChartLine> = buildList {
    val dp = vm.info.priceDp
    if (showBidAsk) { vm.bestAsk?.let { add(ChartLine("ask", it, "", Palette.down)) }; vm.bestBid?.let { add(ChartLine("bid", it, "", Color(0xFF9AA3B2), Color.Black)) } }
    else vm.ticker?.last?.let { add(ChartLine("last", it, "", Palette.accent, dash = true)) }
    if (!showTrades) return@buildList
    ordersFor(vm).forEach { o -> val lim = o.isPlainLimit()
        add(ChartLine("ord:${o.id}", o.price, "${if (o.side == Side.BUY) "buy" else "sell"} ${if (lim) "limit" else o.type.lowercase().take(14)} ${(o.qty - o.filled).plain()}${if (!compact) "  ✕" else ""}",
            if (o.side == Side.BUY) Palette.up else Palette.down, dash = true, drag = lim && !compact, click = !compact)) }
    vm.pairCost?.let { c -> val px = vm.ticker?.last; val pnl = px?.let { (it - c.avg).multiply(BigDecimal(100)).divide(c.avg, 2, RoundingMode.HALF_UP) }
        add(ChartLine("pos", c.avg, "持仓 ${c.qty.plain()} @${c.avg.setScale(dp, RoundingMode.HALF_UP).plain()}${pnl?.let { "  " + (if (it.signum() >= 0) "+" else "") + it.toPlainString() + "%" } ?: ""}", Palette.warn, Color.Black, dash = true)) }
    vm.tpsl?.let { r -> val avg = vm.pairCost?.avg
        fun pl(p: BigDecimal) = avg?.let { a -> val v = (p - a) * r.qty; " → ${if (v.signum() >= 0) "+" else ""}${v.setScale(2, RoundingMode.HALF_UP).plain()} ${vm.pair.quote}" } ?: ""
        val tag = if (r.native) "OCO" else "App"
        r.tp?.let { add(ChartLine("tp", it, "TP $tag ${r.qty.plain()}${pl(it)}", Palette.up, drag = !compact, width = 1.6f)) }
        r.sl?.let { add(ChartLine("sl", it, "SL $tag ${r.qty.plain()}${pl(it)}", Palette.down, drag = !compact, width = 1.6f)) } }
}

private fun linesJson(l: List<ChartLine>) = JSONArray().apply { l.forEach { x -> put(JSONObject().put("id", x.id).put("price", x.price.toDouble()).put("label", x.label).put("color", hex(x.color))
    .put("fg", hex(x.fg)).put("dash", x.dash).put("drag", x.drag).put("click", x.click).put("width", x.width.toDouble())) } }.toString()

/**
 * KLineChart in a WebView. In Paparazzi / Android Studio preview (LocalInspectionMode) WebView cannot render,
 * so the old Compose-drawn [ProChart] is shown as a stand-in (bars, indicators, order/position/TP/SL lines; no drawings).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun KLineChartView(vm: AppVM, ctl: KChartController, modifier: Modifier, compact: Boolean = false, showBidAsk: Boolean = true, showTrades: Boolean = true,
                   cfg: ChartCfg = remember(vm.pair, vm.cfgVersion) { vm.chartCfg() },
                   onOrderMoved: (Order, BigDecimal) -> Unit = { _, _ -> }, onOrderTap: (Order) -> Unit = {},
                   onTpSlMoved: (String, BigDecimal) -> Unit = { _, _ -> }) {
    val lines = chartLines(vm, showBidAsk, showTrades, compact)
    if (LocalInspectionMode.current || !KChartEnv.webViewAvailable) {
        val type = when (cfg.type) { "ohlc" -> ChartType.BAR; "area" -> ChartType.LINE; else -> ChartType.CANDLE }
        val on = cfg.inds.filter { it.on }
        ProChart(vm, remember { ChartViewport(if (compact) 60f else 80f) }, type, on.filter { it.main && it.name in setOf("MA", "EMA", "BOLL") }.map { it.name }.toSet(),
            on.filter { !it.main && it.name in setOf("VOL", "MACD", "RSI") }.map { it.name }, modifier, showBidAsk = showBidAsk, showTrades = showTrades, compact = compact,
            extra = lines.filter { it.id == "tp" || it.id == "sl" })
        return
    }
    val scope = rememberCoroutineScope()
    val vmS = rememberUpdatedState(vm); val movedS = rememberUpdatedState(onOrderMoved); val tapS = rememberUpdatedState(onOrderTap); val tpS = rememberUpdatedState(onTpSlMoved)
    var drawPair by remember { mutableStateOf(vm.pair) }
    val bridge = remember { object {
        val h = Handler(Looper.getMainLooper())
        @JavascriptInterface fun onReady() { h.post { ctl.ready = true; ctl.flush() } }
        @JavascriptInterface fun onNeed(kind: String, ts: String, id: Int) { h.post { scope.launch { val v = vmS.value
            val end = ts.toLongOrNull() ?: 0L
            val k = if (!v.olderMore || end <= 0) emptyList() else v.loadOlder(end)
            ctl.js("KC.resolve($id,${klinesJson(k)},${k.isNotEmpty()})") } } }
        @JavascriptInterface fun onLineMoved(id: String, price: String) { h.post { val px = price.toBigDecimalOrNull() ?: return@post
            when { id.startsWith("ord:") -> ordersFor(vmS.value).firstOrNull { it.id == id.removePrefix("ord:") }?.let { movedS.value(it, px) }
                   id == "tp" || id == "sl" -> tpS.value(id, px) } } }
        @JavascriptInterface fun onLineClick(id: String) { h.post { if (id.startsWith("ord:")) ordersFor(vmS.value).firstOrNull { it.id == id.removePrefix("ord:") }?.let { tapS.value(it) } } }
        @JavascriptInterface fun onDrawings(json: String) { h.post { vmS.value.saveDrawings(json, drawPair) } }
        @JavascriptInterface fun onSelected(id: String) { h.post { ctl.selected = id } }
        @JavascriptInterface fun log(s: String) {}
    } }
    AndroidView(factory = { c -> WebView(c).apply {
        setBackgroundColor(Palette.bg.toArgb())
        settings.javaScriptEnabled = true; settings.domStorageEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.setSupportZoom(false); settings.builtInZoomControls = false
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true } // never navigate away
        addJavascriptInterface(bridge, "Android"); ctl.web = this
        loadUrl("file:///android_asset/kline/index.html") } }, modifier = modifier, onRelease = { it.removeJavascriptInterface("Android"); it.destroy(); ctl.web = null; ctl.ready = false })

    // ---- data sync ----
    val sent = remember { object { var key = ""; var last = 0L } }
    LaunchedEffect(compact) { ctl.js("KC.setCompact($compact)") }
    val key = "${vm.exchangeId}|${vm.pair}|${vm.interval}"
    LaunchedEffect(vm.klines, key, vm.klinesFor) {
        val k = vm.klines; if (k.isEmpty()) return@LaunchedEffect
        if (vm.klinesFor.isNotEmpty() && vm.klinesFor != "${vm.pair}|${vm.interval}") return@LaunchedEffect
        if (sent.key != key) { sent.key = key; sent.last = k.last().t; drawPair = vm.pair
            ctl.js("KC.load(${JSONObject.quote(vm.pair.base + vm.pair.quote)},${JSONObject.quote(vm.interval)},${vm.info.priceDp},${vm.info.qtyDp.coerceAtMost(8)},${klinesJson(k)})")
            if (!compact) ctl.js("KC.setDrawings(${vm.drawings(vm.pair)})")
        } else { k.filter { it.t >= sent.last }.forEach { b -> ctl.js("KC.bar([${b.t},${b.o},${b.h},${b.l},${b.c},${b.v}])") }; sent.last = k.last().t }
    }
    LaunchedEffect(vm.ticker) { vm.ticker?.last?.let { ctl.js("KC.tick(${it.toDouble()})") } }
    LaunchedEffect(cfg.type) { ctl.js("KC.setType(${JSONObject.quote(cfg.type)})") }
    val ind = cfg.jsIndicators(); LaunchedEffect(ind) { ctl.js("KC.setIndicators($ind)") }
    val lj = linesJson(lines); LaunchedEffect(lj, ctl.linesNonce, vm.klines.isNotEmpty()) { if (ctl.linesNonce > 0) ctl.js("KC.setLines([])"); ctl.js("KC.setLines($lj)") }
}
