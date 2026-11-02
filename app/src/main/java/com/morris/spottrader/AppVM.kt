package com.morris.spottrader

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morris.spottrader.ex.*
import com.morris.spottrader.sec.KeyStoreRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** quote = currency in which price/value/avg/PnL are expressed. costQty = quantity covered by the trade history used for avgCost. */
data class Position(val asset: String, val qty: BigDecimal, val free: BigDecimal, val price: BigDecimal?, val avgCost: BigDecimal?, val value: BigDecimal?, val pnl: BigDecimal?, val pnlPct: BigDecimal?,
                    val quote: String = "USDT", val costQty: BigDecimal? = null, val realized: BigDecimal? = null)

class AppVM(val repo: KeyStoreRepo, private val uiPrefs: SharedPreferences?) : ViewModel() {
    var exchangeId by mutableStateOf(uiPrefs?.getString("ex", "binance") ?: "binance")
    var pair by mutableStateOf(uiPrefs?.getString("pair", null)?.let { Quotes.guess(it) } ?: SymPair("BTC", "USDT"))
    var symbols by mutableStateOf<List<SymPair>>(emptyList())
    var symbolsStatus by mutableStateOf("")
    /** USDT | USDC | ALL (ALL = USDT+USDC trades merged, valued in USDT) */
    var posQuote by mutableStateOf(uiPrefs?.getString("posQuote", null) ?: "USDT")
    /** Global "只做Maker" switch (persisted): limit orders always post-only, market/IOC/FOK disabled, stop-limit becomes a local maker trigger. */
    var makerOnly by mutableStateOf(uiPrefs?.getBoolean("makerOnly", false) ?: false); private set
    fun toggleMaker(on: Boolean) { makerOnly = on; uiPrefs?.edit()?.putBoolean("makerOnly", on)?.apply() }
    /** set when an order was rejected because it would have taken liquidity; UI offers one-tap re-price */
    var postOnlyReject by mutableStateOf<OrderReq?>(null)
    private val symCache = HashMap<String, List<SymPair>>()
    private var symIndex: Map<String, SymPair> = emptyMap()
    var interval by mutableStateOf("15m")
    var ticker by mutableStateOf<Ticker?>(null)
    var book by mutableStateOf(OrderBook(emptyList(), emptyList()))
    var klines by mutableStateOf<List<Kline>>(emptyList())
    var info by mutableStateOf(SymbolInfo.DEFAULT)
    var trades by mutableStateOf<List<PublicTrade>>(emptyList())
    var balances by mutableStateOf<List<Balance>>(emptyList())
    var openOrders by mutableStateOf<List<Order>>(emptyList())
    var history by mutableStateOf<List<Order>>(emptyList())
    var myTrades by mutableStateOf<List<Trade>>(emptyList())
    var positions by mutableStateOf<List<Position>>(emptyList())
    var favorites by mutableStateOf(uiPrefs?.getStringSet("fav", null)?.toSet() ?: setOf("BTC/USDT", "ETH/USDT"))
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var keysVersion by mutableStateOf(0)
    var bookClick by mutableStateOf<BigDecimal?>(null)
    private var poll: Job? = null

    fun ex(): SpotExchange = Exchanges.create(exchangeId, repo.creds(exchangeId))
    var demo = false // screenshot tests only
    fun hasKeys() = demo || repo.creds(exchangeId) != null
    val features: Set<Feature> get() = Exchanges.create(exchangeId, null).features

    private fun err(e: Throwable) { message = "错误: ${e.message?.take(240)}" }

    fun toggleFav(p: SymPair) { val k = p.toString(); favorites = if (k in favorites) favorites - k else favorites + k; uiPrefs?.edit()?.putStringSet("fav", favorites)?.apply() }

    /** true while the exchange WebSocket is connected and pushing data (REST polling then only refreshes 24h stats slowly) */
    var wsLive by mutableStateOf(false)
    private var stream: Job? = null
    private var inBackground = false

    /** REST polling: full-rate when no WebSocket (fallback), slow 24h-ticker refresh while WS is live. */
    fun startPolling() { poll?.cancel(); if (inBackground) return; startStream(); poll = viewModelScope.launch {
        var n = 0
        while (isActive) { val e = ex(); val p = pair
            if (wsLive) { if (n % 6 == 0) runCatching { val t = e.ticker(p); if (p == pair) ticker = t.copy(last = ticker?.last ?: t.last) } }
            else {
                try { ticker = e.ticker(p) } catch (t: Throwable) { if (n == 0) err(t) }
                runCatching { book = e.orderBook(p, 50); bookTs = System.currentTimeMillis() }
                if (!fastPoll || n % 3 == 0) runCatching { trades = e.recentTrades(p) }
                if (n % 12 == 11) runCatching { val iv = interval; val k = e.klines(p, iv); if (p == pair && iv == interval) klines = mergeKlines(klines, k) } }
            n++; delay(if (fastPoll && !wsLive) 1000 else 2500) } } }

    /** WebSocket market stream with auto-reconnect (exponential backoff 1s..30s); restarted on pair / interval / exchange change. */
    private var streamKey = ""
    fun startStream() { val exId = exchangeId; val p = pair; val iv = interval; val key = "$exId|$p|$iv"
        if (stream?.isActive == true && streamKey == key) return
        stream?.cancel(); wsLive = false; if (demo || inBackground) return; streamKey = key
        stream = viewModelScope.launch {
            var backoff = 1000L
            while (isActive) {
                val f = runCatching { Exchanges.create(exId, null).stream(p, iv) }.getOrNull()
                if (f == null) { if (exId != "hyperliquid") return@launch; delay(3000); continue }  // HL needs symbol meta (REST) first
                try { f.collect { ev -> if (p != pair || iv != interval || exId != exchangeId) return@collect
                    if (ev is MarketEvent.Connected) { backoff = 1000L } else { wsLive = true; onEvent(ev) } } }
                catch (t: kotlinx.coroutines.CancellationException) { throw t } catch (_: Throwable) { }
                wsLive = false   // polling loop resumes full-rate REST fallback
                delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000L)
            } } }
    private fun onEvent(ev: MarketEvent) { when (ev) {
        is MarketEvent.Bar -> { if (klines.isNotEmpty() && (klinesFor.isEmpty() || klinesFor == "$pair|$interval")) klines = applyBar(klines, ev.k) }
        is MarketEvent.Book -> { book = ev.b; bookTs = System.currentTimeMillis() }
        is MarketEvent.Bbo -> { val b = book
            book = OrderBook(listOf(ev.bid) + b.bids.filter { it.price < ev.bid.price }, listOf(ev.ask) + b.asks.filter { it.price > ev.ask.price }); bookTs = System.currentTimeMillis() }
        is MarketEvent.Trades -> { if (ev.t.isNotEmpty()) { trades = (ev.t + trades).sortedByDescending { it.time }.take(50)
            val last = ev.t.maxByOrNull { it.time }!!.price; ticker = ticker?.copy(last = last) ?: ticker } }
        is MarketEvent.Tick -> ticker = ev.t
        MarketEvent.Connected -> {}
    } }
    /** app lifecycle: stop all network market data in background, resume on foreground */
    fun onBackground() { inBackground = true; poll?.cancel(); stream?.cancel(); wsLive = false }
    fun onForeground() { if (!inBackground) return; inBackground = false; startPolling()
        val p = pair; val iv = interval; viewModelScope.launch { runCatching { val k = ex().klines(p, iv); if (p == pair && iv == interval) klines = mergeKlines(klines, k) } } }
    override fun onCleared() { poll?.cancel(); stream?.cancel(); super.onCleared() }
    fun loadKlines() = viewModelScope.launch { startStream(); try { olderMore = true; val p = pair; val iv = interval; val k = ex().klines(p, iv); if (p == pair && iv == interval) { klines = k; klinesFor = "$p|$iv" } } catch (t: Throwable) { err(t) } }
    /** history paging when the chart is scrolled to the left edge */
    var olderMore = true
    /** "pair|interval" the current [klines] belong to ("" = unknown/demo) */
    var klinesFor by mutableStateOf("")
    suspend fun loadOlder(endTime: Long): List<Kline> { val p = pair; val iv = interval
        val k = runCatching { ex().klines(p, iv, endTime) }.getOrDefault(emptyList()).filter { it.t < endTime }.sortedBy { it.t }
        if (k.isEmpty()) olderMore = false
        if (p == pair && iv == interval) klines = mergeKlines(k, klines)
        return k }

    // ---- chart settings / drawings remembered per symbol ----
    private fun ck(p: SymPair) = "${p.base}${p.quote}"
    fun chartCfg(p: SymPair = pair): com.morris.spottrader.ui.ChartCfg = com.morris.spottrader.ui.ChartCfg.parse(uiPrefs?.getString("chartCfg_${ck(p)}", null) ?: memCfg[ck(p)])
    fun saveChartCfg(c: com.morris.spottrader.ui.ChartCfg, p: SymPair = pair) { memCfg[ck(p)] = c.toJson(); uiPrefs?.edit()?.putString("chartCfg_${ck(p)}", c.toJson())?.apply(); cfgVersion++ }
    fun drawings(p: SymPair = pair): String = uiPrefs?.getString("draw_${ck(p)}", null) ?: memDraw[ck(p)] ?: "[]"
    fun saveDrawings(json: String, p: SymPair = pair) { memDraw[ck(p)] = json; uiPrefs?.edit()?.putString("draw_${ck(p)}", json)?.apply() }
    private val memCfg = HashMap<String, String>(); val memDraw = HashMap<String, String>()
    var cfgVersion by mutableStateOf(0)

    // ---- drag a pending order line: cancel + replace ----
    fun modifyOrder(o: Order, newPrice: BigDecimal) = viewModelScope.launch {
        val (req, msg) = modifyPlan(o, newPrice, info, makerOnly, bestBid, bestAsk, pair)
        if (req == null) { message = msg; return@launch }
        busy = true
        try { ex().cancelOrder(pair, o.id) } catch (t: Throwable) { err(t); busy = false; return@launch }
        busy = false; place(req) }

    // ---- TP / SL (app-managed or exchange OCO) ----
    val tpsl get() = com.morris.spottrader.pos.TpSlManager.forPair(exchangeId, pair)
    fun setTpSl(qty: BigDecimal, tp: BigDecimal?, sl: BigDecimal?, p: SymPair = pair) = viewModelScope.launch {
        if (demo) { com.morris.spottrader.pos.TpSlManager.rules.add(0, com.morris.spottrader.pos.TpSlRule("demo", exchangeId, p.base, p.quote, qty, tp, sl, native = exchangeId == "binance", nativeId = "1", status = "演示")); return@launch }
        val i = if (p == pair) info else runCatching { ex().symbolInfo(p) }.getOrDefault(SymbolInfo.DEFAULT)
        val last = if (p == pair) ticker?.last else runCatching { ex().ticker(p).last }.getOrNull()
        message = com.morris.spottrader.pos.TpSlManager.set(ex(), i, p, qty, tp, sl, last, makerOnly) }
    fun cancelTpSl(r: com.morris.spottrader.pos.TpSlRule) = viewModelScope.launch { message = com.morris.spottrader.pos.TpSlManager.cancel(r) }
    fun moveTpSl(r: com.morris.spottrader.pos.TpSlRule, which: String, price: BigDecimal) = viewModelScope.launch {
        message = com.morris.spottrader.pos.TpSlManager.move(ex(), info, r, which, price, ticker?.last) }
    fun loadInfo() = viewModelScope.launch { info = runCatching { ex().symbolInfo(pair) }.getOrElse { SymbolInfo.DEFAULT } }
    fun selectExchange(id: String) { exchangeId = id; uiPrefs?.edit()?.putString("ex", id)?.apply(); clear(); positions = emptyList(); balances = emptyList(); bootstrap() }
    fun selectPair(p: SymPair) { pair = p; uiPrefs?.edit()?.putString("pair", p.toString())?.apply()
        if (p.quote == "USDT" || p.quote == "USDC") choosePosQuote(p.quote)
        clear(); refreshAll() }
    fun choosePosQuote(q: String) { if (posQuote == q) return; posQuote = q; uiPrefs?.edit()?.putString("posQuote", q)?.apply(); positions = emptyList() }

    /** Load the exchange's real symbol list first, make sure the selected pair exists there, then start data. */
    fun bootstrap() { poll?.cancel(); viewModelScope.launch { loadSymbols(); fixPair(); refreshAll() } }
    suspend fun loadSymbols() {
        val id = exchangeId
        symCache[id]?.let { apply(it, ""); return }
        symbolsStatus = "加载币对列表…"
        val r = runCatching { Exchanges.create(id, null).symbols().distinct() }
        if (id != exchangeId) return
        r.onSuccess { if (it.isNotEmpty()) { symCache[id] = it; apply(it, ""); return } }
        apply(Quotes.fallback(), "币对列表加载失败，使用内置列表: ${r.exceptionOrNull()?.message?.take(80) ?: "空"}")
    }
    private fun apply(l: List<SymPair>, status: String) { symbols = l; symbolsStatus = status; symIndex = l.associateBy { Quotes.key(it.toString()) } }
    fun hasSymbol(p: SymPair) = symIndex.isEmpty() || symIndex.containsKey(Quotes.key(p.toString()))
    private fun fixPair() {
        if (symbolsStatus.startsWith("币对列表加载失败") || hasSymbol(pair)) return
        val alt = listOf(SymPair(pair.base, "USDT"), SymPair(pair.base, "USDC"), SymPair("BTC", pair.quote), SymPair("BTC", "USDT")).firstOrNull { hasSymbol(it) } ?: symbols.firstOrNull()
        if (alt != null) { pair = alt; message = "当前交易所无该币对，已切换到 $alt"; uiPrefs?.edit()?.putString("pair", alt.toString())?.apply() }
    }
    private fun clear() { ticker = null; book = OrderBook(emptyList(), emptyList()); klines = emptyList(); trades = emptyList(); openOrders = emptyList(); history = emptyList(); myTrades = emptyList() }

    fun refreshAll() { startPolling(); loadKlines(); loadInfo(); if (hasKeys()) loadAccount() }
    fun loadAccount() = viewModelScope.launch {
        val e = ex()
        try { balances = e.balances() } catch (t: Throwable) { err(t) }
        try { openOrders = e.openOrders(null) } catch (t: Throwable) { runCatching { openOrders = e.openOrders(pair) } }
        runCatching { history = e.orderHistory(pair) }
        runCatching { myTrades = e.myTrades(pair).sortedByDescending { it.time } }
    }
    /** Rounds to tick/lot from exchange info, validates min qty/notional, then places. */
    private fun looksLikePostOnlyReject(e: Throwable): Boolean { val m = (e.message ?: "").lowercase()
        return listOf("immediately match", "would immediately", "post only", "post-only", "postonly", "would take", "take liquidity").any { it in m } }

    fun place(r0: OrderReq) = viewModelScope.launch {
        val i = info
        var r = r0.copy(qty = r0.qty?.let(i::roundQty), price = r0.price?.let(i::roundPrice), trigger = r0.trigger?.let(i::roundPrice),
            quoteQty = r0.quoteQty?.setScale(if (r0.pair.quote in Quotes.STABLE) 2 else i.priceDp.coerceAtMost(8).coerceAtLeast(2), RoundingMode.DOWN), lastPrice = ticker?.last)
        // ---- global maker-only enforcement (defense in depth; the UI already hides these) ----
        if (makerOnly) {
            if (r.type == OType.MARKET) { message = "只做Maker已开启：不支持市价单"; return@launch }
            if (r.type == OType.STOP_LIMIT) { message = "只做Maker已开启：条件单请使用『Maker条件单』（本地监控触发）"; return@launch }
            if (Feature.POST_ONLY !in features) { message = "${Exchanges.names[exchangeId]} 不支持只做Maker"; return@launch }
            r = r.copy(tif = TIF.POST_ONLY)
        }
        val q = r.qty; val px = r.price ?: ticker?.last
        if (q != null && r.quoteQty == null) {
            if (q.signum() <= 0) { message = "数量过小（步长 ${i.step.plain()}）"; return@launch }
            if (i.minQty.signum() > 0 && q < i.minQty) { message = "低于最小下单量 ${i.minQty.plain()}"; return@launch }
            if (px != null && i.minNotional.signum() > 0 && q * px < i.minNotional) { message = "低于最小下单金额 ${i.minNotional.plain()} ${pair.quote}"; return@launch } }
        val qq = r.quoteQty
        if (qq != null && i.minNotional.signum() > 0 && qq < i.minNotional) { message = "低于最小下单金额 ${i.minNotional.plain()}"; return@launch }
        busy = true
        try {
            val e = ex(); val id = e.place(r)
            val postOnly = r.type == OType.LIMIT && r.tif == TIF.POST_ONLY
            if (postOnly) {
                // Binance/MEXC reject synchronously; OKX/Gate/Bitget/Bybit accept then auto-cancel a would-be taker -> verify shortly after
                delay(450)
                val o = runCatching { e.getOrder(r.pair, id) }.getOrNull()
                if (o != null && !o.isOpen && o.filled.signum() == 0) { postOnlyReject = r; message = "只做Maker：委托会立即成交，已被交易所拒绝"; busy = false; loadAccount(); return@launch }
            }
            message = "下单成功 #$id"; loadAccount()
        } catch (t: Throwable) {
            if (r.tif == TIF.POST_ONLY && r.type == OType.LIMIT && looksLikePostOnlyReject(t)) { postOnlyReject = r; message = "只做Maker：委托会立即成交，已被交易所拒绝" } else err(t) }
        busy = false }

    /** One-tap fix for a post-only rejection: buy at best bid / sell at best ask, same quantity, still post-only. */
    fun repriceAndPlace() { val r = postOnlyReject ?: return; postOnlyReject = null
        viewModelScope.launch {
            val b = runCatching { ex().orderBook(r.pair, 5) }.getOrNull()
            val best = (if (r.side == Side.BUY) b?.bids?.maxByOrNull { it.price } else b?.asks?.minByOrNull { it.price })?.price
            if (best == null) { message = "无法获取盘口，请稍后重试"; return@launch }
            place(r.copy(price = info.roundPrice(best), tif = TIF.POST_ONLY)) } }
    fun dismissPostOnlyReject() { postOnlyReject = null }

    // ================= MT5-style one-click trading (full-screen chart) =================
    /** Full-screen chart open -> poll book every 1s for fresher one-click prices. */
    var fastPoll by mutableStateOf(false)
    var bookTs by mutableStateOf(0L)
    var oneClick by mutableStateOf(uiPrefs?.getBoolean("oneClick", false) ?: false); private set
    val oneClickAcked get() = uiPrefs?.getBoolean("oneClickAck", false) ?: false
    fun enableOneClick(on: Boolean) { oneClick = on; uiPrefs?.edit()?.putBoolean("oneClick", on)?.apply { if (on) putBoolean("oneClickAck", true) }?.apply() }
    fun savedVolume(p: SymPair): BigDecimal? = uiPrefs?.getString("ocVol_$p", null)?.toBigDecimalOrNull()
    fun saveVolume(p: SymPair, v: BigDecimal) { uiPrefs?.edit()?.putString("ocVol_$p", v.toPlainString())?.apply() }
    val bestBid get() = book.bids.maxByOrNull { it.price }?.price
    val bestAsk get() = book.asks.minByOrNull { it.price }?.price
    /** Average cost of the current base asset from this pair's own trade history (null if unknown). */
    val pairCost: CostCalc.Res? get() = myTrades.takeIf { it.isNotEmpty() }?.let { CostCalc.compute(it, pair.base) }?.takeIf { it.avg.signum() > 0 && it.qty.signum() > 0 }

    data class Quick(val req: OrderReq, val price: BigDecimal, val notional: BigDecimal, val desc: String)
    /** Builds (and validates) the one-click order. Returns null + sets [message] when invalid. */
    fun buildQuick(side: Side, vol0: BigDecimal, now: Long = System.currentTimeMillis()): Quick? {
        val i = info; val bid = bestBid; val ask = bestAsk
        if (bid == null || ask == null) { message = "暂无盘口报价"; return null }
        if (bookTs > 0 && now - bookTs > 6000) { message = "报价已过期（>6秒未更新），请稍后重试"; return null }
        val vol = i.roundQty(vol0)
        if (vol.signum() <= 0) { message = "数量过小（步长 ${i.step.plain()}）"; return null }
        if (i.minQty.signum() > 0 && vol < i.minQty) { message = "低于最小下单量 ${i.minQty.plain()} ${pair.base}"; return null }
        // maker: buy rests at best bid, sell rests at best ask. taker(market): buy pays ask, sell hits bid.
        val px = if (makerOnly) (if (side == Side.BUY) bid else ask) else (if (side == Side.BUY) ask else bid)
        val notional = vol * px
        if (i.minNotional.signum() > 0 && notional < i.minNotional) { message = "低于最小下单金额 ${i.minNotional.plain()} ${pair.quote}"; return null }
        if (balances.isNotEmpty()) {
            if (side == Side.BUY) { val free = balances.firstOrNull { it.asset == pair.quote }?.free ?: BigDecimal.ZERO
                val need = notional * (BigDecimal.ONE + (if (makerOnly) i.maker else i.taker))
                if (free < need) { message = "${pair.quote} 可用不足：需 ≈${need.setScale(4, RoundingMode.UP).plain()}，可用 ${free.plain()}"; return null } }
            else { val free = balances.firstOrNull { it.asset == pair.base }?.free ?: BigDecimal.ZERO
                if (free < vol) { message = "${pair.base} 可用不足：需 ${vol.plain()}，可用 ${free.plain()}"; return null } } }
        val req = if (makerOnly) {
            if (Feature.POST_ONLY !in features) { message = "${Exchanges.names[exchangeId]} 不支持只做Maker"; return null }
            OrderReq(pair, side, OType.LIMIT, vol, null, i.roundPrice(px), tif = TIF.POST_ONLY)
        } else {
            // Gate/Bitget market BUY only accept a quote amount -> convert at best ask
            val quoteOnly = side == Side.BUY && exchangeId in setOf("gate", "bitget")
            OrderReq(pair, side, OType.MARKET, if (quoteOnly) null else vol, if (quoteOnly) notional else null, null)
        }
        val sideTxt = if (side == Side.BUY) "买入" else "卖出"
        val desc = if (makerOnly) "只做Maker限价 $sideTxt ${vol.plain()} ${pair.base} @ ${i.roundPrice(px).plain()}（≈${notional.setScale(2, RoundingMode.HALF_UP).plain()} ${pair.quote}）"
                   else "市价 $sideTxt ${vol.plain()} ${pair.base}（参考价 ${px.plain()}，≈${notional.setScale(2, RoundingMode.HALF_UP).plain()} ${pair.quote}）"
        return Quick(req, px, notional, desc)
    }
    fun quickOrder(q: Quick) { place(q.req) }

    fun cancel(o: Order) = viewModelScope.launch {
        val p = symbolToPair(o.symbol) ?: pair
        try { ex().cancelOrder(p, o.id); message = "已撤单"; loadAccount() } catch (t: Throwable) { err(t) } }
    fun startChase(params: com.morris.spottrader.chase.ChaseParams) {
        try { com.morris.spottrader.chase.ChaseManager.start(ex(), info, params); message = "追逐限价单已启动" } catch (t: Throwable) { err(t) } }
    fun cancelAll() = viewModelScope.launch {
        busy = true
        val pairs = openOrders.mapNotNull { symbolToPair(it.symbol) }.toSet().ifEmpty { setOf(pair) }
        try { val e = ex(); pairs.forEach { e.cancelAll(it) }; message = "已全部撤单（${pairs.joinToString()}）"; loadAccount() } catch (t: Throwable) { err(t) }
        busy = false }

    /** order symbol string (BTCUSDC / BTC-USDC / BTC_USDC) -> pair, via the loaded symbol list, else by separator / known-quote suffix */
    private fun symbolToPair(s: String): SymPair? = symIndex[Quotes.key(s)] ?: Quotes.guess(s)

    /**
     * Holdings valued in the selected quote (USDT / USDC), or ALL = USDT+USDC trades merged and valued in USDT.
     * Cross-quote conversions use the exchange's own USDC/USDT-style price when listed, else stablecoins are assumed 1:1.
     * Avg cost = moving average over that coin's trades in the selected quote pair(s); PnL is computed on the quantity those trades cover.
     */
    fun loadPositions() = viewModelScope.launch {
        busy = true
        try { val e = ex(); val bals = e.balances(); balances = bals
            val prices = runCatching { e.lastPrices() }.getOrDefault(emptyMap())
            val target = if (posQuote == "ALL") "USDT" else posQuote
            val tradeQuotes = if (posQuote == "ALL") listOf("USDT", "USDC") else listOf(target)
            fun conv(from: String, to: String): BigDecimal? {
                if (from == to) return BigDecimal.ONE
                prices[from + to]?.takeIf { it.signum() > 0 }?.let { return it }
                prices[to + from]?.takeIf { it.signum() > 0 }?.let { return BigDecimal.ONE.divide(it, 12, RoundingMode.HALF_UP) }
                return if (from in Quotes.STABLE && to in Quotes.STABLE) BigDecimal.ONE else null }
            val out = ArrayList<Position>(); var fetched = 0
            for (b in bals) {
                if (b.asset in Quotes.STABLE) { val r = conv(b.asset, target); out.add(Position(b.asset, b.total, b.free, r, null, r?.multiply(b.total), null, null, target)); continue }
                var px: BigDecimal? = prices[b.asset + target]?.takeIf { it.signum() > 0 }
                if (px == null) for (q2 in listOf("USDT", "USDC")) { if (px != null) break
                    val p2 = prices[b.asset + q2]?.takeIf { it.signum() > 0 } ?: continue; val c = conv(q2, target) ?: continue; px = p2 * c }
                if (px == null && prices.isEmpty()) px = runCatching { e.ticker(SymPair(b.asset, target)).last }.getOrNull()
                val value = px?.multiply(b.total)
                var avg: BigDecimal? = null; var costQty: BigDecimal? = null; var realized: BigDecimal? = null
                if (value != null && value >= BigDecimal.ONE && fetched < 40) {   // skip dust, cap requests (rate limits)
                    val all = ArrayList<Trade>()
                    for (q in tradeQuotes) { val sp = SymPair(b.asset, q); if (!hasSymbol(sp)) continue
                        val r = conv(q, target) ?: BigDecimal.ONE; fetched++
                        runCatching { e.myTrades(sp) }.getOrNull()?.forEach { all.add(if (q == target) it else it.copy(price = it.price * r)) } }
                    if (all.isNotEmpty()) { val r = CostCalc.compute(all, b.asset); realized = r.realized; if (r.avg.signum() > 0) { avg = r.avg; costQty = r.qty } }
                }
                out.add(position(b, px, avg, target, costQty).copy(realized = realized))
            }
            positions = out.sortedByDescending { it.value ?: BigDecimal.ZERO }
            runCatching { openOrders = e.openOrders(null) }
        } catch (t: Throwable) { err(t) }
        busy = false }

    companion object {
        /** merges two kline lists by open time (later list wins for duplicates) */
        /** live bar: replace last / append newer in O(1); anything older falls back to [mergeKlines] */
        fun applyBar(k: List<Kline>, b: Kline): List<Kline> { if (k.isEmpty()) return listOf(b); val last = k.last()
            return when { b.t == last.t -> k.subList(0, k.size - 1) + b; b.t > last.t -> k + b; else -> mergeKlines(k, listOf(b)) } }
        fun mergeKlines(a: List<Kline>, b: List<Kline>): List<Kline> { val m = java.util.TreeMap<Long, Kline>(); a.forEach { m[it.t] = it }; b.forEach { m[it.t] = it }; return m.values.toList() }
        /** validation for drag-to-modify (tick rounding, remaining qty, post-only side check) */
        fun modifyPlan(o: Order, newPrice: BigDecimal, i: SymbolInfo, makerOnly: Boolean, bid: BigDecimal?, ask: BigDecimal?, p: SymPair): Pair<OrderReq?, String> {
            val px = i.roundPrice(newPrice); val rem = i.roundQty(o.qty - o.filled)
            if (px.signum() <= 0) return null to "价格无效"
            if (rem.signum() <= 0) return null to "剩余数量为 0"
            if (px.compareTo(o.price) == 0) return null to "价格未变化"
            if (i.minNotional.signum() > 0 && rem * px < i.minNotional) return null to "低于最小下单金额 ${i.minNotional.plain()}"
            if (makerOnly) { if (o.side == Side.BUY && ask != null && px >= ask) return null to "只做Maker：买单价须低于卖一 ${ask.plain()}"
                if (o.side == Side.SELL && bid != null && px <= bid) return null to "只做Maker：卖单价须高于买一 ${bid.plain()}" }
            return OrderReq(p, o.side, OType.LIMIT, rem, null, px, tif = if (makerOnly) TIF.POST_ONLY else TIF.GTC) to "改价 ${o.price.plain()} → ${px.plain()}" }
        fun position(b: Balance, px: BigDecimal?, avg: BigDecimal?, quote: String = "USDT", costQty: BigDecimal? = null): Position {
            val value = px?.multiply(b.total)
            val pnlQty = costQty?.takeIf { it.signum() > 0 }?.min(b.total) ?: b.total
            val pnl = if (px != null && avg != null) (px - avg) * pnlQty else null
            val pct = if (px != null && avg != null) (px - avg).multiply(BigDecimal(100)).divide(avg, 2, RoundingMode.HALF_UP) else null
            return Position(b.asset, b.total, b.free, px, avg, value, pnl, pct, quote, costQty)
        }
    }
}
