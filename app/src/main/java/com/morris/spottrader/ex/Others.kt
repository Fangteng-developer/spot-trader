package com.morris.spottrader.ex

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
private fun lvls(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONArray(it); Level(x.getString(0).bd(), x.getString(1).bd()) }
private fun cr(c: Creds?) = c ?: throw ApiException("未配置API密钥")
private fun sd(s: String) = if (s.equals("buy", true)) Side.BUY else Side.SELL
private fun karr(a: JSONArray, t: Int, o: Int, h: Int, l: Int, c: Int, v: Int, tMul: Long = 1) = (0 until a.length()).map { val x = a.getJSONArray(it)
    Kline(x.getString(t).toLong() * tMul, x.getString(o).toDouble(), x.getString(h).toDouble(), x.getString(l).toDouble(), x.getString(c).toDouble(), x.optString(v, "0").toDoubleOrNull() ?: 0.0) }

/** OKX API v5 (spot, tdMode=cash). Sign: base64(HMAC-SHA256(ts+METHOD+path+body)). Conditional = order-algo ordType=trigger. */
class Okx(private val c: Creds?) : SpotExchange {
    override val id = "okx"; override val display = "OKX"; override val needsPassphrase = true
    override val features = setOf(Feature.STOP_LIMIT, Feature.POST_ONLY, Feature.IOC, Feature.FOK)
    private val base = "https://www.okx.com"
    private fun inst(p: SymPair) = "${p.base}-${p.quote}"
    private fun iso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    private suspend fun req(method: String, path: String, body: String? = null, auth: Boolean = true): JSONArray {
        val h = HashMap<String, String>()
        if (auth) { val k = cr(c); val ts = iso()
            h["OK-ACCESS-KEY"] = k.apiKey; h["OK-ACCESS-TIMESTAMP"] = ts; h["OK-ACCESS-PASSPHRASE"] = k.passphrase
            h["OK-ACCESS-SIGN"] = Sign.b64(Sign.hmac("HmacSHA256", k.secret, ts + method + path + (body ?: ""))) }
        val o = JSONObject(Http.call(method, base + path, h, body))
        if (o.optString("code") != "0") throw ApiException("OKX ${o.optString("code")}: ${o.optString("msg")} ${o.optJSONArray("data")?.optJSONObject(0)?.optString("sMsg") ?: ""}")
        return o.getJSONArray("data")
    }
    override suspend fun ticker(p: SymPair): Ticker { val x = req("GET", "/api/v5/market/ticker?instId=${inst(p)}", auth = false).getJSONObject(0)
        val last = x.getString("last").bd(); val open = x.getString("open24h").bd()
        return Ticker(last, if (open.signum() > 0) (last - open) * BigDecimal(100) / open else BigDecimal.ZERO, x.getString("high24h").bd(), x.getString("low24h").bd(), x.getString("vol24h").bd()) }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook { val x = req("GET", "/api/v5/market/books?instId=${inst(p)}&sz=$depth", auth = false).getJSONObject(0)
        return OrderBook(lvls(x.getJSONArray("bids")), lvls(x.getJSONArray("asks"))) }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> {
        val bar = mapOf("1m" to "1m", "5m" to "5m", "15m" to "15m", "30m" to "30m", "1h" to "1H", "4h" to "4H", "1d" to "1Dutc", "1w" to "1Wutc", "1M" to "1Mutc")[interval] ?: "15m"
        return karr(req("GET", if (endTime == null) "/api/v5/market/candles?instId=${inst(p)}&bar=$bar&limit=200" else "/api/v5/market/history-candles?instId=${inst(p)}&bar=$bar&limit=100&after=$endTime", auth = false), 0, 1, 2, 3, 4, 5).reversed() }
    /** OKX: /public (tickers, books5, trades) + /business (candles); text "ping" every 20s (server drops idle >30s). */
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent> {
        val i = inst(p)
        val pub = jsonObj("op" to "subscribe", "args" to org.json.JSONArray(listOf("tickers", "books5", "trades").map { org.json.JSONObject().put("channel", it).put("instId", i) }))
        val biz = jsonObj("op" to "subscribe", "args" to org.json.JSONArray().put(org.json.JSONObject().put("channel", WsParse.okxBar(interval)).put("instId", i)))
        return Ws.mergeAll(Ws.connect("wss://ws.okx.com:8443/ws/v5/public", listOf(pub), "ping", parse = WsParse::okx),
            Ws.connect("wss://ws.okx.com:8443/ws/v5/business", listOf(biz), "ping", parse = WsParse::okx))
    }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo { val x = req("GET", "/api/v5/public/instruments?instType=SPOT&instId=${inst(p)}", auth = false).getJSONObject(0)
        return SymbolInfo(x.getString("tickSz").bd(), x.getString("lotSz").bd(), x.optString("minSz").bd(), BigDecimal.ZERO, BigDecimal("0.0008"), BigDecimal("0.001")) }
    override suspend fun symbols() = req("GET", "/api/v5/public/instruments?instType=SPOT", auth = false).objs().filter { it.optString("state") == "live" }.map { SymPair(it.getString("baseCcy"), it.getString("quoteCcy")) }
    override suspend fun lastPrices() = req("GET", "/api/v5/market/tickers?instType=SPOT", auth = false).objs().associate { Quotes.key(it.getString("instId")) to it.optString("last").bd() }
    override suspend fun recentTrades(p: SymPair) = req("GET", "/api/v5/market/trades?instId=${inst(p)}&limit=50", auth = false).objs().map {
        PublicTrade(it.getString("px").bd(), it.getString("sz").bd(), sd(it.getString("side")), it.getString("ts").toLong()) }
    override suspend fun balances(): List<Balance> = req("GET", "/api/v5/account/balance").getJSONObject(0).getJSONArray("details").objs()
        .map { Balance(it.getString("ccy"), it.optString("availBal").bd(), it.optString("frozenBal").bd()) }.filter { it.total.signum() > 0 }
    override suspend fun place(r: OrderReq): String {
        val o = JSONObject().put("instId", inst(r.pair)).put("tdMode", "cash").put("side", r.side.name.lowercase())
        when (r.type) {
            OType.LIMIT -> o.put("ordType", when (r.tif) { TIF.GTC -> "limit"; TIF.POST_ONLY -> "post_only"; TIF.IOC -> "ioc"; TIF.FOK -> "fok" }).put("px", r.price!!.plain()).put("sz", r.qty!!.plain())
            OType.MARKET -> { o.put("ordType", "market"); if (r.side == Side.BUY && r.quoteQty != null) o.put("sz", r.quoteQty.plain()).put("tgtCcy", "quote_ccy") else o.put("sz", r.qty!!.plain()).put("tgtCcy", "base_ccy") }
            OType.STOP_LIMIT -> { o.put("ordType", "trigger").put("sz", r.qty!!.plain()).put("triggerPx", r.trigger!!.plain()).put("orderPx", r.price!!.plain())
                return "algo:" + req("POST", "/api/v5/trade/order-algo", o.toString()).getJSONObject(0).getString("algoId") }
        }
        return req("POST", "/api/v5/trade/order", o.toString()).getJSONObject(0).getString("ordId") }
    override suspend fun cancelOrder(p: SymPair, orderId: String) {
        if (orderId.startsWith("algo:")) req("POST", "/api/v5/trade/cancel-algos", JSONArray().put(JSONObject().put("instId", inst(p)).put("algoId", orderId.removePrefix("algo:"))).toString())
        else req("POST", "/api/v5/trade/cancel-order", JSONObject().put("instId", inst(p)).put("ordId", orderId).toString()) }
    private fun ord(x: JSONObject) = Order(x.getString("ordId"), x.getString("instId"), sd(x.getString("side")), x.getString("ordType"),
        x.optString("px").bd(), x.optString("sz").bd(), x.optString("accFillSz").bd(), x.optString("state"), x.optString("cTime").toLongOrNull() ?: 0)
    override suspend fun openOrders(p: SymPair?): List<Order> {
        val q = p?.let { "&instId=${inst(it)}" } ?: ""
        val normal = req("GET", "/api/v5/trade/orders-pending?instType=SPOT$q").objs().map(::ord)
        val algo = runCatching { req("GET", "/api/v5/trade/orders-algo-pending?ordType=trigger&instType=SPOT$q").objs().map { x ->
            Order("algo:" + x.getString("algoId"), x.getString("instId"), sd(x.getString("side")), "trigger @${x.optString("triggerPx")}", x.optString("ordPx").bd(), x.optString("sz").bd(), BigDecimal.ZERO, x.optString("state"), x.optString("cTime").toLongOrNull() ?: 0) } }.getOrDefault(emptyList())
        return normal + algo }
    override suspend fun getOrder(p: SymPair, orderId: String): Order { val x = req("GET", "/api/v5/trade/order?instId=${inst(p)}&ordId=$orderId").getJSONObject(0)
        return ord(x).copy(avg = x.optString("avgPx").bd()) }
    override suspend fun orderHistory(p: SymPair) = req("GET", "/api/v5/trade/orders-history-archive?instType=SPOT&instId=${inst(p)}").objs().map(::ord)
    override suspend fun myTrades(p: SymPair) = req("GET", "/api/v5/trade/fills-history?instType=SPOT&instId=${inst(p)}&limit=100").objs().map {
        Trade(sd(it.getString("side")), it.getString("fillPx").bd(), it.getString("fillSz").bd(), it.optString("fee").bd().abs(), it.optString("feeCcy"), it.optString("ts").toLongOrNull() ?: 0) }
}

/** Gate.io API v4 spot. Sign: hex(HMAC-SHA512(METHOD\npath\nquery\nsha512(body)\nts)). Conditional = /spot/price_orders. */
class Gate(private val c: Creds?) : SpotExchange {
    override val id = "gate"; override val display = "Gate.io"
    override val features = setOf(Feature.STOP_LIMIT, Feature.POST_ONLY, Feature.IOC, Feature.FOK)
    private val host = "https://api.gateio.ws"; private val prefix = "/api/v4"
    private fun cp(p: SymPair) = "${p.base}_${p.quote}"
    private suspend fun req(method: String, path: String, query: String = "", body: String? = null, auth: Boolean = true): String {
        val h = HashMap<String, String>(); h["Accept"] = "application/json"
        if (auth) { val k = cr(c); val ts = (System.currentTimeMillis() / 1000).toString()
            val pre = "$method\n$prefix$path\n$query\n${Sign.sha512Hex(body ?: "")}\n$ts"
            h["KEY"] = k.apiKey; h["Timestamp"] = ts; h["SIGN"] = Sign.hex(Sign.hmac("HmacSHA512", k.secret, pre)) }
        return Http.call(method, host + prefix + path + (if (query.isNotEmpty()) "?$query" else ""), h, body)
    }
    override suspend fun ticker(p: SymPair): Ticker { val x = JSONArray(req("GET", "/spot/tickers", "currency_pair=${cp(p)}", auth = false)).getJSONObject(0)
        return Ticker(x.getString("last").bd(), x.optString("change_percentage").bd(), x.optString("high_24h").bd(), x.optString("low_24h").bd(), x.optString("base_volume").bd()) }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook { val x = JSONObject(req("GET", "/spot/order_book", "currency_pair=${cp(p)}&limit=$depth", auth = false))
        return OrderBook(lvls(x.getJSONArray("bids")), lvls(x.getJSONArray("asks"))) }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> { val iv = when (interval) { "1w" -> "7d"; "1M" -> "30d"; else -> interval }
        // [t, quoteVol, close, high, low, open, baseVol]
        return karr(JSONArray(req("GET", "/spot/candlesticks", if (endTime == null) "currency_pair=${cp(p)}&interval=$iv&limit=200" else "currency_pair=${cp(p)}&interval=$iv&from=${(endTime - TF.ms(interval) * 200) / 1000}&to=${(endTime - 1) / 1000}", auth = false)), 0, 5, 3, 4, 2, 6, 1000) }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo { val x = JSONObject(req("GET", "/spot/currency_pairs/${cp(p)}", auth = false))
        return SymbolInfo(SymbolInfo.dp(x.optInt("precision", 2)), SymbolInfo.dp(x.optInt("amount_precision", 6)), x.optString("min_base_amount").bd(), x.optString("min_quote_amount").bd(), x.optString("fee", "0.2").bd().movePointLeft(2), x.optString("fee", "0.2").bd().movePointLeft(2)) }
    override suspend fun symbols() = JSONArray(req("GET", "/spot/currency_pairs", auth = false)).objs().filter { it.optString("trade_status") == "tradable" }.map { SymPair(it.getString("base"), it.getString("quote")) }
    override suspend fun lastPrices() = JSONArray(req("GET", "/spot/tickers", auth = false)).objs().associate { Quotes.key(it.getString("currency_pair")) to it.optString("last").bd() }
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent> = Ws.connect(WsMore.GATE_URL, WsMore.gateSubs(cp(p), interval), WsMore.gatePing(), parse = WsMore::gate)
    override suspend fun recentTrades(p: SymPair) = JSONArray(req("GET", "/spot/trades", "currency_pair=${cp(p)}&limit=50", auth = false)).objs().map {
        PublicTrade(it.getString("price").bd(), it.getString("amount").bd(), sd(it.getString("side")), it.optString("create_time_ms").bd().toLong()) }
    override suspend fun balances() = JSONArray(req("GET", "/spot/accounts")).objs().map { Balance(it.getString("currency"), it.getString("available").bd(), it.getString("locked").bd()) }.filter { it.total.signum() > 0 }
    override suspend fun place(r: OrderReq): String {
        if (r.type == OType.STOP_LIMIT) {
            val rule = if (r.trigger!! >= (r.lastPrice ?: r.trigger)) ">=" else "<="
            val o = JSONObject().put("market", cp(r.pair)).put("trigger", JSONObject().put("price", r.trigger.plain()).put("rule", rule).put("expiration", 86400 * 30))
                .put("put", JSONObject().put("type", "limit").put("side", r.side.name.lowercase()).put("price", r.price!!.plain()).put("amount", r.qty!!.plain()).put("account", "normal").put("time_in_force", "gtc"))
            return "stop:" + JSONObject(req("POST", "/spot/price_orders", body = o.toString())).get("id").toString() }
        val o = JSONObject().put("currency_pair", cp(r.pair)).put("side", r.side.name.lowercase()).put("type", r.type.name.lowercase())
        if (r.type == OType.LIMIT) o.put("amount", r.qty!!.plain()).put("price", r.price!!.plain()).put("time_in_force", when (r.tif) { TIF.GTC -> "gtc"; TIF.POST_ONLY -> "poc"; TIF.IOC -> "ioc"; TIF.FOK -> "fok" })
        else o.put("time_in_force", "ioc").put("amount", if (r.side == Side.BUY) (r.quoteQty ?: throw ApiException("市价买入需按${r.pair.quote}金额下单")).plain() else r.qty!!.plain())
        return JSONObject(req("POST", "/spot/orders", body = o.toString())).getString("id") }
    override suspend fun cancelOrder(p: SymPair, orderId: String) {
        if (orderId.startsWith("stop:")) req("DELETE", "/spot/price_orders/${orderId.removePrefix("stop:")}") else req("DELETE", "/spot/orders/$orderId", "currency_pair=${cp(p)}") }
    override suspend fun cancelAll(p: SymPair) { req("DELETE", "/spot/orders", "currency_pair=${cp(p)}"); runCatching { req("DELETE", "/spot/price_orders", "market=${cp(p)}&account=normal") } }
    private fun ord(x: JSONObject) = Order(x.getString("id"), x.optString("currency_pair"), sd(x.getString("side")), x.optString("type") + (x.optString("time_in_force").takeIf { it != "gtc" && it.isNotEmpty() }?.let { "/$it" } ?: ""),
        x.optString("price").bd(), x.optString("amount").bd(), x.optString("amount").bd() - x.optString("left").bd(), x.optString("status"), x.optString("create_time_ms").bd().toLong())
    override suspend fun openOrders(p: SymPair?): List<Order> {
        val normal = if (p != null) JSONArray(req("GET", "/spot/orders", "currency_pair=${cp(p)}&status=open")).objs().map(::ord)
            else JSONArray(req("GET", "/spot/open_orders")).objs().flatMap { it.getJSONArray("orders").objs().map(::ord) }
        val stops = runCatching { JSONArray(req("GET", "/spot/price_orders", "status=open" + (p?.let { "&market=${cp(it)}" } ?: ""))).objs().map { x -> val put = x.getJSONObject("put")
            Order("stop:" + x.get("id"), x.optString("market"), sd(put.getString("side")), "trigger ${x.getJSONObject("trigger").optString("rule")}${x.getJSONObject("trigger").optString("price")}",
                put.optString("price").bd(), put.optString("amount").bd(), BigDecimal.ZERO, x.optString("status"), x.optLong("ctime") * 1000) } }.getOrDefault(emptyList())
        return normal + stops }
    override suspend fun getOrder(p: SymPair, orderId: String): Order { val x = JSONObject(req("GET", "/spot/orders/$orderId", "currency_pair=${cp(p)}"))
        return ord(x).copy(avg = x.optString("avg_deal_price").bd().takeIf { it.signum() > 0 } ?: x.optString("fill_price").bd()) }
    override suspend fun orderHistory(p: SymPair) = JSONArray(req("GET", "/spot/orders", "currency_pair=${cp(p)}&status=finished&limit=100")).objs().map(::ord)
    override suspend fun myTrades(p: SymPair) = JSONArray(req("GET", "/spot/my_trades", "currency_pair=${cp(p)}&limit=1000")).objs().map {
        Trade(sd(it.getString("side")), it.getString("price").bd(), it.getString("amount").bd(), it.optString("fee").bd(), it.optString("fee_currency"), it.optString("create_time_ms").bd().toLong()) }
}

/** Bitget API v2 spot. Sign: base64(HMAC-SHA256(ts+METHOD+path[?query]+body)). Conditional = plan orders. */
class Bitget(private val c: Creds?) : SpotExchange {
    override val id = "bitget"; override val display = "Bitget"; override val needsPassphrase = true
    override val features = setOf(Feature.STOP_LIMIT, Feature.POST_ONLY, Feature.IOC, Feature.FOK)
    private val base = "https://api.bitget.com"
    private fun sym(p: SymPair) = p.base + p.quote
    private suspend fun req(method: String, path: String, query: String = "", body: String? = null, auth: Boolean = true): Any {
        val full = path + (if (query.isNotEmpty()) "?$query" else "")
        val h = HashMap<String, String>(); h["locale"] = "zh-CN"
        if (auth) { val k = cr(c); val ts = System.currentTimeMillis().toString()
            h["ACCESS-KEY"] = k.apiKey; h["ACCESS-TIMESTAMP"] = ts; h["ACCESS-PASSPHRASE"] = k.passphrase
            h["ACCESS-SIGN"] = Sign.b64(Sign.hmac("HmacSHA256", k.secret, ts + method + full + (body ?: ""))) }
        val o = JSONObject(Http.call(method, base + full, h, body))
        if (o.optString("code") != "00000") throw ApiException("Bitget ${o.optString("code")}: ${o.optString("msg")}")
        return o.get("data")
    }
    private fun arr(a: Any) = a as JSONArray
    override suspend fun ticker(p: SymPair): Ticker { val x = arr(req("GET", "/api/v2/spot/market/tickers", "symbol=${sym(p)}", auth = false)).getJSONObject(0)
        return Ticker(x.getString("lastPr").bd(), x.optString("change24h").bd() * BigDecimal(100), x.optString("high24h").bd(), x.optString("low24h").bd(), x.optString("baseVolume").bd()) }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook { val x = req("GET", "/api/v2/spot/market/orderbook", "symbol=${sym(p)}&type=step0&limit=$depth", auth = false) as JSONObject
        return OrderBook(lvls(x.getJSONArray("bids")), lvls(x.getJSONArray("asks"))) }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> {
        val g = mapOf("1m" to "1min", "5m" to "5min", "15m" to "15min", "30m" to "30min", "1h" to "1h", "4h" to "4h", "1d" to "1day", "1w" to "1week", "1M" to "1M")[interval] ?: "15min"
        return karr(arr(if (endTime == null) req("GET", "/api/v2/spot/market/candles", "symbol=${sym(p)}&granularity=$g&limit=200", auth = false) else req("GET", "/api/v2/spot/market/history-candles", "symbol=${sym(p)}&granularity=$g&limit=200&endTime=${endTime - 1}", auth = false)), 0, 1, 2, 3, 4, 5).sortedBy { it.t } }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo { val x = arr(req("GET", "/api/v2/spot/public/symbols", "symbol=${sym(p)}", auth = false)).getJSONObject(0)
        return SymbolInfo(SymbolInfo.dp(x.optString("pricePrecision", "2").toInt()), SymbolInfo.dp(x.optString("quantityPrecision", "6").toInt()), x.optString("minTradeAmount").bd(),
            if (p.quote in Quotes.STABLE) x.optString("minTradeUSDT").bd() else BigDecimal.ZERO, x.optString("makerFeeRate", "0.001").bd(), x.optString("takerFeeRate", "0.001").bd()) }
    override suspend fun symbols() = arr(req("GET", "/api/v2/spot/public/symbols", auth = false)).objs().filter { it.optString("status") == "online" }.map { SymPair(it.getString("baseCoin"), it.getString("quoteCoin")) }
    override suspend fun lastPrices() = arr(req("GET", "/api/v2/spot/market/tickers", auth = false)).objs().associate { Quotes.key(it.getString("symbol")) to it.optString("lastPr").bd() }
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent> = Ws.connect(WsMore.BITGET_URL, WsMore.bitgetSubs(sym(p), interval), "ping", 25_000, parse = WsMore::bitget)
    override suspend fun recentTrades(p: SymPair) = arr(req("GET", "/api/v2/spot/market/fills", "symbol=${sym(p)}&limit=50", auth = false)).objs().map {
        PublicTrade(it.getString("price").bd(), it.getString("size").bd(), sd(it.getString("side")), it.getString("ts").toLong()) }
    override suspend fun balances() = arr(req("GET", "/api/v2/spot/account/assets")).objs().map { Balance(it.getString("coin"), it.optString("available").bd(), it.optString("frozen").bd() + it.optString("locked").bd()) }.filter { it.total.signum() > 0 }
    override suspend fun place(r: OrderReq): String {
        val force = when (r.tif) { TIF.GTC -> "gtc"; TIF.POST_ONLY -> "post_only"; TIF.IOC -> "ioc"; TIF.FOK -> "fok" }
        if (r.type == OType.STOP_LIMIT) {
            val o = JSONObject().put("symbol", sym(r.pair)).put("side", r.side.name.lowercase()).put("triggerPrice", r.trigger!!.plain()).put("orderType", "limit")
                .put("executePrice", r.price!!.plain()).put("planType", "amount").put("size", r.qty!!.plain()).put("triggerType", "fill_price").put("force", "gtc")
            return "plan:" + (req("POST", "/api/v2/spot/trade/place-plan-order", body = o.toString()) as JSONObject).getString("orderId") }
        val o = JSONObject().put("symbol", sym(r.pair)).put("side", r.side.name.lowercase()).put("orderType", r.type.name.lowercase()).put("force", if (r.type == OType.LIMIT) force else "gtc")
        if (r.type == OType.LIMIT) o.put("price", r.price!!.plain()).put("size", r.qty!!.plain())
        else o.put("size", if (r.side == Side.BUY) (r.quoteQty ?: throw ApiException("市价买入需按${r.pair.quote}金额下单")).plain() else r.qty!!.plain())
        return (req("POST", "/api/v2/spot/trade/place-order", body = o.toString()) as JSONObject).getString("orderId") }
    override suspend fun cancelOrder(p: SymPair, orderId: String) {
        if (orderId.startsWith("plan:")) req("POST", "/api/v2/spot/trade/cancel-plan-order", body = JSONObject().put("orderId", orderId.removePrefix("plan:")).toString())
        else req("POST", "/api/v2/spot/trade/cancel-order", body = JSONObject().put("symbol", sym(p)).put("orderId", orderId).toString()) }
    private fun ord(x: JSONObject) = Order(x.getString("orderId"), x.optString("symbol"), sd(x.getString("side")), x.optString("orderType") + (x.optString("force").takeIf { it != "gtc" && it.isNotEmpty() }?.let { "/$it" } ?: ""),
        x.optString("price").bd().takeIf { it.signum() > 0 } ?: x.optString("priceAvg").bd(), x.optString("size").bd(), x.optString("baseVolume").bd(), x.optString("status"), x.optString("cTime").toLongOrNull() ?: 0)
    override suspend fun openOrders(p: SymPair?): List<Order> {
        val normal = arr(req("GET", "/api/v2/spot/trade/unfilled-orders", p?.let { "symbol=${sym(it)}" } ?: "")).objs().map(::ord)
        val plans = if (p == null) emptyList() else runCatching { ((req("GET", "/api/v2/spot/trade/current-plan-order", "symbol=${sym(p)}") as JSONObject).optJSONArray("orderList") ?: JSONArray()).objs().map { x ->
            Order("plan:" + x.getString("orderId"), x.optString("symbol"), sd(x.getString("side")), "plan @${x.optString("triggerPrice")}", x.optString("executePrice").bd(), x.optString("size").bd(), BigDecimal.ZERO, x.optString("status"), x.optString("cTime").toLongOrNull() ?: 0) } }.getOrDefault(emptyList())
        return normal + plans }
    override suspend fun getOrder(p: SymPair, orderId: String): Order { val x = arr(req("GET", "/api/v2/spot/trade/orderInfo", "orderId=$orderId")).getJSONObject(0)
        return ord(x).copy(price = x.optString("price").bd(), avg = x.optString("priceAvg").bd()) }
    override suspend fun orderHistory(p: SymPair) = arr(req("GET", "/api/v2/spot/trade/history-orders", "symbol=${sym(p)}&limit=100")).objs().map(::ord)
    override suspend fun myTrades(p: SymPair) = arr(req("GET", "/api/v2/spot/trade/fills", "symbol=${sym(p)}&limit=100")).objs().map {
        val fd = it.optJSONObject("feeDetail")
        Trade(sd(it.getString("side")), it.getString("priceAvg").bd(), it.getString("size").bd(), fd?.optString("totalFee")?.bd()?.abs() ?: BigDecimal.ZERO, fd?.optString("feeCoin") ?: "", it.optString("cTime").toLongOrNull() ?: 0) }
}

/** Bybit API v5 (category=spot, UNIFIED). Sign: hex(HMAC-SHA256(ts+key+recvWindow+(query|body))). Conditional = orderFilter=StopOrder. */
class Bybit(private val c: Creds?) : SpotExchange {
    override val id = "bybit"; override val display = "Bybit"
    override val features = setOf(Feature.STOP_LIMIT, Feature.POST_ONLY, Feature.IOC, Feature.FOK)
    private val base = "https://api.bybit.com"
    private fun sym(p: SymPair) = p.base + p.quote
    private suspend fun req(method: String, path: String, query: String = "", body: String? = null, auth: Boolean = true): JSONObject {
        val h = HashMap<String, String>()
        if (auth) { val k = cr(c); val ts = System.currentTimeMillis().toString(); val rw = "5000"
            h["X-BAPI-API-KEY"] = k.apiKey; h["X-BAPI-TIMESTAMP"] = ts; h["X-BAPI-RECV-WINDOW"] = rw
            h["X-BAPI-SIGN"] = Sign.hex(Sign.hmac("HmacSHA256", k.secret, ts + k.apiKey + rw + (body ?: query))) }
        val o = JSONObject(Http.call(method, base + path + (if (query.isNotEmpty()) "?$query" else ""), h, body))
        if (o.optInt("retCode") != 0) throw ApiException("Bybit ${o.optInt("retCode")}: ${o.optString("retMsg")}")
        return o.getJSONObject("result")
    }
    override suspend fun ticker(p: SymPair): Ticker { val x = req("GET", "/v5/market/tickers", "category=spot&symbol=${sym(p)}", auth = false).getJSONArray("list").getJSONObject(0)
        return Ticker(x.getString("lastPrice").bd(), x.optString("price24hPcnt").bd() * BigDecimal(100), x.optString("highPrice24h").bd(), x.optString("lowPrice24h").bd(), x.optString("volume24h").bd()) }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook { val x = req("GET", "/v5/market/orderbook", "category=spot&symbol=${sym(p)}&limit=$depth", auth = false)
        return OrderBook(lvls(x.getJSONArray("b")), lvls(x.getJSONArray("a"))) }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> {
        val iv = mapOf("1m" to "1", "5m" to "5", "15m" to "15", "30m" to "30", "1h" to "60", "4h" to "240", "1d" to "D", "1w" to "W", "1M" to "M")[interval] ?: "15"
        return karr(req("GET", "/v5/market/kline", "category=spot&symbol=${sym(p)}&interval=$iv&limit=200${if (endTime != null) "&end=${endTime - 1}" else ""}", auth = false).getJSONArray("list"), 0, 1, 2, 3, 4, 5).reversed() }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo { val x = req("GET", "/v5/market/instruments-info", "category=spot&symbol=${sym(p)}", auth = false).getJSONArray("list").getJSONObject(0)
        val lot = x.getJSONObject("lotSizeFilter")
        return SymbolInfo(x.getJSONObject("priceFilter").getString("tickSize").bd(), lot.optString("basePrecision").bd(), lot.optString("minOrderQty").bd(), lot.optString("minOrderAmt").bd()) }
    override suspend fun symbols() = req("GET", "/v5/market/instruments-info", "category=spot", auth = false).getJSONArray("list").objs()
        .filter { it.optString("status").let { st -> st == "1" || st.equals("Trading", true) } }.map { SymPair(it.getString("baseCoin"), it.getString("quoteCoin")) }
    override suspend fun lastPrices() = req("GET", "/v5/market/tickers", "category=spot", auth = false).getJSONArray("list").objs().associate { Quotes.key(it.getString("symbol")) to it.optString("lastPrice").bd() }
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent> = WsMore.Bybit().let { b -> Ws.connect(WsMore.BYBIT_URL, WsMore.bybitSubs(sym(p), interval), WsMore.BYBIT_PING, parse = b::parse) }
    override suspend fun recentTrades(p: SymPair) = req("GET", "/v5/market/recent-trade", "category=spot&symbol=${sym(p)}&limit=50", auth = false).getJSONArray("list").objs().map {
        PublicTrade(it.getString("price").bd(), it.getString("size").bd(), sd(it.getString("side")), it.getString("time").toLong()) }
    override suspend fun balances(): List<Balance> = req("GET", "/v5/account/wallet-balance", "accountType=UNIFIED").getJSONArray("list").objs().flatMap { it.getJSONArray("coin").objs() }.map {
        val total = it.optString("walletBalance").bd(); val locked = it.optString("locked").bd(); Balance(it.getString("coin"), total - locked, locked) }.filter { it.total.signum() > 0 }
    override suspend fun place(r: OrderReq): String {
        val o = JSONObject().put("category", "spot").put("symbol", sym(r.pair)).put("side", if (r.side == Side.BUY) "Buy" else "Sell")
        when (r.type) {
            OType.LIMIT -> o.put("orderType", "Limit").put("qty", r.qty!!.plain()).put("price", r.price!!.plain()).put("timeInForce", when (r.tif) { TIF.GTC -> "GTC"; TIF.POST_ONLY -> "PostOnly"; TIF.IOC -> "IOC"; TIF.FOK -> "FOK" })
            OType.MARKET -> { o.put("orderType", "Market"); if (r.side == Side.BUY && r.quoteQty != null) o.put("qty", r.quoteQty.plain()).put("marketUnit", "quoteCoin") else o.put("qty", r.qty!!.plain()).put("marketUnit", "baseCoin") }
            OType.STOP_LIMIT -> { o.put("orderType", "Limit").put("qty", r.qty!!.plain()).put("price", r.price!!.plain()).put("triggerPrice", r.trigger!!.plain()).put("orderFilter", "StopOrder")
                return "stop:" + req("POST", "/v5/order/create", body = o.toString()).getString("orderId") }
        }
        return req("POST", "/v5/order/create", body = o.toString()).getString("orderId") }
    override suspend fun cancelOrder(p: SymPair, orderId: String) { val stop = orderId.startsWith("stop:")
        req("POST", "/v5/order/cancel", body = JSONObject().put("category", "spot").put("symbol", sym(p)).put("orderId", orderId.removePrefix("stop:")).put("orderFilter", if (stop) "StopOrder" else "Order").toString()) }
    override suspend fun cancelAll(p: SymPair) { req("POST", "/v5/order/cancel-all", body = JSONObject().put("category", "spot").put("symbol", sym(p)).toString())
        runCatching { req("POST", "/v5/order/cancel-all", body = JSONObject().put("category", "spot").put("symbol", sym(p)).put("orderFilter", "StopOrder").toString()) } }
    private fun ord(x: JSONObject, prefix: String = "") = Order(prefix + x.getString("orderId"), x.optString("symbol"), sd(x.getString("side")),
        x.optString("orderType") + (x.optString("triggerPrice").bd().takeIf { it.signum() > 0 }?.let { " @${it.plain()}" } ?: ""),
        x.optString("price").bd(), x.optString("qty").bd(), x.optString("cumExecQty").bd(), x.optString("orderStatus"), x.optString("createdTime").toLongOrNull() ?: 0)
    override suspend fun openOrders(p: SymPair?): List<Order> { val q = "category=spot" + (p?.let { "&symbol=${sym(it)}" } ?: "")
        val normal = req("GET", "/v5/order/realtime", "$q&orderFilter=Order").getJSONArray("list").objs().map { ord(it) }
        val stops = runCatching { req("GET", "/v5/order/realtime", "$q&orderFilter=StopOrder").getJSONArray("list").objs().map { ord(it, "stop:") } }.getOrDefault(emptyList())
        return normal + stops }
    override suspend fun getOrder(p: SymPair, orderId: String): Order {
        val l = req("GET", "/v5/order/realtime", "category=spot&symbol=${sym(p)}&orderId=$orderId").getJSONArray("list")
        val x = if (l.length() > 0) l.getJSONObject(0) else req("GET", "/v5/order/history", "category=spot&symbol=${sym(p)}&orderId=$orderId").getJSONArray("list").getJSONObject(0)
        return ord(x).copy(avg = x.optString("avgPrice").bd()) }
    override suspend fun orderHistory(p: SymPair) = req("GET", "/v5/order/history", "category=spot&symbol=${sym(p)}&limit=50").getJSONArray("list").objs().map { ord(it) }
    override suspend fun myTrades(p: SymPair) = req("GET", "/v5/execution/list", "category=spot&symbol=${sym(p)}&limit=100").getJSONArray("list").objs().map {
        Trade(sd(it.getString("side")), it.getString("execPrice").bd(), it.getString("execQty").bd(), it.optString("execFee").bd().abs(), it.optString("feeCurrency"), it.optString("execTime").toLongOrNull() ?: 0) }
}
