package com.morris.spottrader.ex

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/** Binance spot API v3 (HMAC-SHA256 or RSA) and MEXC spot API v3 (HMAC-SHA256). */
class BinanceLike(override val id: String, override val display: String, private val base: String, private val keyHeader: String, private val c: Creds?,
                  private val pv: String = "/api/v3", private val wsBase: String? = null) : SpotExchange {
    private val isMexc = id == "mexc"
    private val isAster = id == "aster"
    override val features = if (isAster) setOf(Feature.POST_ONLY, Feature.IOC) else if (isMexc) setOf(Feature.POST_ONLY, Feature.IOC, Feature.FOK) else setOf(Feature.STOP_LIMIT, Feature.POST_ONLY, Feature.IOC, Feature.FOK)
    private fun sym(p: SymPair) = p.base + p.quote

    private fun signature(q: String): String {
        val cr = c ?: throw ApiException("未配置API密钥")
        val pk = cr.rsaPrivatePkcs8
        return if (pk != null && !isMexc) {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk))
            val s = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(q.toByteArray()) }.sign()
            Sign.enc(Sign.b64(s))
        } else Sign.hex(Sign.hmac("HmacSHA256", cr.secret, q))
    }
    private suspend fun signed(method: String, path: String, params: Map<String, String>): String {
        val all = LinkedHashMap(params); all["timestamp"] = System.currentTimeMillis().toString(); all["recvWindow"] = "5000"
        val q = Sign.query(all)
        return Http.call(method, "$base$path?$q&signature=${signature(q)}", mapOf(keyHeader to c!!.apiKey))
    }
    private suspend fun pub(path: String, q: String) = Http.call("GET", "$base$path?$q")

    override suspend fun ticker(p: SymPair): Ticker {
        val o = JSONObject(pub("$pv/ticker/24hr", "symbol=${sym(p)}"))
        return Ticker(o.getString("lastPrice").bd(), o.optString("priceChangePercent").bd().let { if (isMexc) it * BigDecimal(100) else it },
            o.optString("highPrice").bd(), o.optString("lowPrice").bd(), o.optString("volume").bd())
    }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook {
        val o = JSONObject(pub("$pv/depth", "symbol=${sym(p)}&limit=$depth"))
        fun lv(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONArray(it); Level(x.getString(0).bd(), x.getString(1).bd()) }
        return OrderBook(lv(o.getJSONArray("bids")), lv(o.getJSONArray("asks")))
    }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> {
        val iv = if (isMexc) mapOf("1h" to "60m", "4h" to "4h", "1d" to "1d", "1w" to "1W", "1M" to "1M")[interval] ?: interval else interval
        val a = JSONArray(pub("$pv/klines", "symbol=${sym(p)}&interval=$iv&limit=200${if (endTime != null) "&endTime=${endTime - 1}" else ""}"))
        return (0 until a.length()).map { val x = a.getJSONArray(it); Kline(x.getLong(0), x.getString(1).toDouble(), x.getString(2).toDouble(), x.getString(3).toDouble(), x.getString(4).toDouble(), x.getString(5).toDouble()) }
    }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo {
        val s = JSONObject(pub("$pv/exchangeInfo", "symbol=${sym(p)}")).getJSONArray("symbols").getJSONObject(0)
        var tick: BigDecimal? = null; var step: BigDecimal? = null; var minQ = BigDecimal.ZERO; var minN = BigDecimal.ZERO
        s.optJSONArray("filters")?.let { f -> for (i in 0 until f.length()) { val x = f.getJSONObject(i)
            when (x.optString("filterType")) { "PRICE_FILTER" -> tick = x.optString("tickSize").bd(); "LOT_SIZE" -> { step = x.optString("stepSize").bd(); minQ = x.optString("minQty").bd() }
                "NOTIONAL", "MIN_NOTIONAL" -> minN = x.optString("minNotional").bd() } } }
        if (tick == null || tick!!.signum() == 0) tick = SymbolInfo.dp(s.optInt("quotePrecision", s.optInt("quoteAssetPrecision", 2)))
        if (step == null || step!!.signum() == 0) step = s.optString("baseSizePrecision").bd().takeIf { it.signum() > 0 } ?: SymbolInfo.dp(s.optInt("baseAssetPrecision", 6))
        if (minN.signum() == 0) minN = s.optString("quoteAmountPrecision").bd()
        // MEXC reports real per-symbol maker/taker (e.g. BTCUSDC = 0/0, BTCUSDT = 0/0.0005); Binance default tier 0.1%
        val maker = if (isMexc) s.optString("makerCommission", "0").bd() else BigDecimal("0.001")
        val taker = if (isMexc) s.optString("takerCommission", "0.0005").bd() else BigDecimal("0.001")
        return SymbolInfo(tick!!, step!!, minQ, minN, maker, taker)
    }
    override suspend fun symbols(): List<SymPair> {
        val raw = if (isMexc) pub("$pv/exchangeInfo", "") else runCatching { pub("$pv/exchangeInfo", "permissions=SPOT&symbolStatus=TRADING") }.getOrElse { pub("$pv/exchangeInfo", "") }
        val a = JSONObject(raw).getJSONArray("symbols")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { x ->
            val st = x.optString("status"); val ok = if (isMexc) (st == "1" || st == "ENABLED") && x.optBoolean("isSpotTradingAllowed", true) else st == "TRADING" && x.optBoolean("isSpotTradingAllowed", true)
            ok && x.optString("baseAsset").isNotEmpty() }.map { SymPair(it.getString("baseAsset"), it.getString("quoteAsset")) }
    }
    override suspend fun lastPrices(): Map<String, BigDecimal> { val a = JSONArray(pub("$pv/ticker/price", ""))
        return (0 until a.length()).associate { val x = a.getJSONObject(it); Quotes.key(x.getString("symbol")) to x.getString("price").bd() } }
    override suspend fun recentTrades(p: SymPair): List<PublicTrade> {
        val a = JSONArray(pub("$pv/trades", "symbol=${sym(p)}&limit=50"))
        return (0 until a.length()).map { val x = a.getJSONObject(it); PublicTrade(x.getString("price").bd(), x.getString("qty").bd(), if (x.optBoolean("isBuyerMaker")) Side.SELL else Side.BUY, x.optLong("time")) }.sortedByDescending { it.time }
    }
    override suspend fun balances(): List<Balance> {
        val a = JSONObject(signed("GET", "$pv/account", emptyMap())).getJSONArray("balances")
        return (0 until a.length()).map { val x = a.getJSONObject(it); Balance(x.getString("asset"), x.getString("free").bd(), x.getString("locked").bd()) }.filter { it.total.signum() > 0 }
    }
    override suspend fun place(r: OrderReq): String {
        val m = linkedMapOf("symbol" to sym(r.pair), "side" to r.side.name)
        when (r.type) {
            OType.LIMIT -> { m["type"] = when { isAster -> "LIMIT"; r.tif == TIF.POST_ONLY -> "LIMIT_MAKER"; isMexc && r.tif == TIF.IOC -> "IMMEDIATE_OR_CANCEL"; isMexc && r.tif == TIF.FOK -> "FILL_OR_KILL"; else -> "LIMIT" }
                if (isAster) m["timeInForce"] = if (r.tif == TIF.POST_ONLY) "GTX" else r.tif.name
                else if (!isMexc && m["type"] == "LIMIT") m["timeInForce"] = r.tif.name
                m["quantity"] = r.qty!!.plain(); m["price"] = r.price!!.plain() }
            OType.MARKET -> { m["type"] = "MARKET"; if (r.side == Side.BUY && r.quoteQty != null) m["quoteOrderQty"] = r.quoteQty.plain() else m["quantity"] = r.qty!!.plain() }
            OType.STOP_LIMIT -> { if (isMexc || isAster) throw ApiException("$display 现货API不支持条件单")
                val last = r.lastPrice ?: throw ApiException("缺少最新价"); val trig = r.trigger!!
                val stopLoss = if (r.side == Side.SELL) trig < last else trig > last
                m["type"] = if (stopLoss) "STOP_LOSS_LIMIT" else "TAKE_PROFIT_LIMIT"; m["timeInForce"] = "GTC"
                m["quantity"] = r.qty!!.plain(); m["price"] = r.price!!.plain(); m["stopPrice"] = trig.plain() }
        }
        return JSONObject(signed("POST", "$pv/order", m)).get("orderId").toString()
    }
    override suspend fun cancelOrder(p: SymPair, orderId: String) { signed("DELETE", "$pv/order", linkedMapOf("symbol" to sym(p), "orderId" to orderId)) }
    override suspend fun cancelAll(p: SymPair) { signed("DELETE", "$pv/openOrders", linkedMapOf("symbol" to sym(p))) }
    private fun parseOrders(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONObject(it)
        val sp = x.optString("stopPrice").bd()
        Order(x.get("orderId").toString(), x.getString("symbol"), Side.valueOf(x.getString("side")), x.getString("type") + if (sp.signum() > 0) " @${sp.plain()}" else "", x.optString("price").bd(),
            x.optString("origQty").bd(), x.optString("executedQty").bd(), x.optString("status"), x.optLong("time")) }
    override suspend fun getOrder(p: SymPair, orderId: String): Order { val x = JSONObject(signed("GET", "$pv/order", linkedMapOf("symbol" to sym(p), "orderId" to orderId)))
        val ex = x.optString("executedQty").bd(); val cq = x.optString("cummulativeQuoteQty").bd()
        return parseOrders(JSONArray().put(x))[0].copy(avg = if (ex.signum() > 0) cq.divide(ex, 12, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO) }
    override suspend fun openOrders(p: SymPair?): List<Order> = parseOrders(JSONArray(signed("GET", "$pv/openOrders", if (p != null) linkedMapOf("symbol" to sym(p)) else emptyMap())))
    override suspend fun orderHistory(p: SymPair) = parseOrders(JSONArray(signed("GET", "$pv/allOrders", linkedMapOf("symbol" to sym(p), "limit" to "100")))).sortedByDescending { it.time }
    override suspend fun myTrades(p: SymPair): List<Trade> {
        val a = JSONArray(signed("GET", "$pv/myTrades", linkedMapOf("symbol" to sym(p), "limit" to "1000")))
        return (0 until a.length()).map { val x = a.getJSONObject(it)
            Trade(if (x.getBoolean("isBuyer")) Side.BUY else Side.SELL, x.getString("price").bd(), x.getString("qty").bd(), x.optString("commission").bd(), x.optString("commissionAsset"), x.getLong("time")) }
    }

    // ---- Binance spot OCO (POST /api/v3/orderList/oco). MEXC has no spot OCO. ----
    override val supportsOco get() = !isMexc && !isAster
    override suspend fun placeOcoSell(p: SymPair, qty: BigDecimal, tp: BigDecimal, slTrigger: BigDecimal, slLimit: BigDecimal): String? {
        if (isMexc || isAster) return null
        val m = linkedMapOf("symbol" to sym(p), "side" to "SELL", "quantity" to qty.plain(),
            "aboveType" to "LIMIT_MAKER", "abovePrice" to tp.plain(),
            "belowType" to "STOP_LOSS_LIMIT", "belowStopPrice" to slTrigger.plain(), "belowPrice" to slLimit.plain(), "belowTimeInForce" to "GTC")
        return JSONObject(signed("POST", "$pv/orderList/oco", m)).get("orderListId").toString()
    }
    override suspend fun cancelOco(p: SymPair, listId: String) { signed("DELETE", "$pv/orderList", linkedMapOf("symbol" to sym(p), "orderListId" to listId)) }
    override suspend fun ocoDone(p: SymPair, listId: String): Boolean? = runCatching {
        JSONObject(signed("GET", "$pv/orderList", linkedMapOf("orderListId" to listId))).optString("listOrderStatus") == "ALL_DONE" }.getOrNull()

    /** Binance / Aster: combined WebSocket streams (Binance-compatible payloads). MEXC: protobuf WS (WsMore.mexc). */
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent>? {
        if (id == "mexc") return Ws.connect(WsMore.MEXC_URL, WsMore.mexcSubs(sym(p), interval), WsMore.MEXC_PING, parse = { emptyList() }, binary = WsMore::mexc)
        val w = wsBase ?: return null
        val s = sym(p).lowercase()
        val depth = if (isAster) "$s@depth20" else "$s@depth20@100ms"
        return Ws.connect("$w/stream?streams=$s@kline_$interval/$s@aggTrade/$s@bookTicker/$depth/$s@ticker", parse = WsParse::binance)
    }
}
