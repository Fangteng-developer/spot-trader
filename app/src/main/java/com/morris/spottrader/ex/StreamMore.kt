package com.morris.spottrader.ex

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

private val H = BigDecimal(100)
private fun lvA(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONArray(it); Level(x.getString(0).bd(), x.getString(1).bd()) }
private fun kl(t: Long, o: String, h: String, l: String, c: String, v: String) = Kline(t, o.toDouble(), h.toDouble(), l.toDouble(), c.toDouble(), v.toDoubleOrNull() ?: 0.0)

/** WebSocket parsers + subscription builders for Gate v4, Bitget v2, Bybit v5, MEXC v3 (protobuf). */
object WsMore {
    // ---------- Gate.io spot v4 ----------
    const val GATE_URL = "wss://api.gateio.ws/ws/v4/"
    fun gateSubs(cp: String, interval: String, now: Long = System.currentTimeMillis() / 1000): List<String> {
        val iv = when (interval) { "1w" -> "7d"; "1M" -> "30d"; else -> interval }
        fun sub(ch: String, vararg p: String) = JSONObject().put("time", now).put("channel", ch).put("event", "subscribe").put("payload", JSONArray(p.toList())).toString()
        return listOf(sub("spot.candlesticks", iv, cp), sub("spot.trades", cp), sub("spot.order_book", cp, "20", "100ms"), sub("spot.tickers", cp))
    }
    fun gatePing() = JSONObject().put("time", System.currentTimeMillis() / 1000).put("channel", "spot.ping").toString()
    fun gate(text: String): List<MarketEvent> {
        val r = JSONObject(text); if (r.optString("event") != "update" && r.optString("event") != "all") return emptyList()
        val d = r.optJSONObject("result") ?: return emptyList()
        return when (r.optString("channel")) {
            "spot.candlesticks" -> listOf(MarketEvent.Bar(kl(d.getString("t").toLong() * 1000, d.getString("o"), d.getString("h"), d.getString("l"), d.getString("c"), d.optString("a", "0"))))
            "spot.trades" -> listOf(MarketEvent.Trades(listOf(PublicTrade(d.getString("price").bd(), d.getString("amount").bd(), if (d.optString("side") == "buy") Side.BUY else Side.SELL,
                d.optString("create_time_ms").substringBefore('.').toLongOrNull() ?: (d.optLong("create_time") * 1000)))))
            "spot.order_book" -> listOf(MarketEvent.Book(OrderBook(lvA(d.getJSONArray("bids")), lvA(d.getJSONArray("asks")))))
            "spot.tickers" -> listOf(MarketEvent.Tick(Ticker(d.getString("last").bd(), d.optString("change_percentage").bd(), d.optString("high_24h").bd(), d.optString("low_24h").bd(), d.optString("base_volume").bd())))
            else -> emptyList()
        }
    }

    // ---------- Bitget v2 public ----------
    const val BITGET_URL = "wss://ws.bitget.com/v2/ws/public"
    fun bitgetCandle(iv: String) = "candle" + (mapOf("1h" to "1H", "4h" to "4H", "1d" to "1D", "1w" to "1W", "1M" to "1M")[iv] ?: iv)
    fun bitgetSubs(sym: String, interval: String): List<String> {
        val args = JSONArray(); listOf(bitgetCandle(interval), "trade", "books15", "ticker").forEach { args.put(JSONObject().put("instType", "SPOT").put("channel", it).put("instId", sym)) }
        return listOf(JSONObject().put("op", "subscribe").put("args", args).toString())
    }
    fun bitget(text: String): List<MarketEvent> {
        if (text == "pong") return emptyList()
        val r = JSONObject(text); val ch = r.optJSONObject("arg")?.optString("channel") ?: return emptyList(); val data = r.optJSONArray("data") ?: return emptyList()
        return when {
            ch.startsWith("candle") -> (0 until data.length()).map { val x = data.getJSONArray(it); MarketEvent.Bar(kl(x.getString(0).toLong(), x.getString(1), x.getString(2), x.getString(3), x.getString(4), x.optString(5, "0"))) }
            ch == "trade" -> listOf(MarketEvent.Trades((0 until data.length()).map { val x = data.getJSONObject(it)
                PublicTrade(x.getString("price").bd(), x.getString("size").bd(), if (x.optString("side") == "buy") Side.BUY else Side.SELL, x.optString("ts").toLongOrNull() ?: 0L) }.sortedByDescending { it.time }))
            ch.startsWith("books") -> (0 until data.length()).map { val x = data.getJSONObject(it); MarketEvent.Book(OrderBook(lvA(x.getJSONArray("bids")), lvA(x.getJSONArray("asks")))) }
            ch == "ticker" -> (0 until data.length()).map { val x = data.getJSONObject(it)
                MarketEvent.Tick(Ticker(x.getString("lastPr").bd(), x.optString("change24h").bd() * H, x.optString("high24h").bd(), x.optString("low24h").bd(), x.optString("baseVolume").bd())) }
            else -> emptyList()
        }
    }

    // ---------- Bybit v5 spot ----------
    const val BYBIT_URL = "wss://stream.bybit.com/v5/public/spot"
    const val BYBIT_PING = """{"op":"ping"}"""
    fun bybitIv(iv: String) = mapOf("1m" to "1", "5m" to "5", "15m" to "15", "30m" to "30", "1h" to "60", "4h" to "240", "1d" to "D", "1w" to "W", "1M" to "M")[iv] ?: "15"
    fun bybitSubs(sym: String, interval: String) = listOf(JSONObject().put("op", "subscribe").put("args", JSONArray(listOf("kline.${bybitIv(interval)}.$sym", "publicTrade.$sym", "orderbook.50.$sym", "tickers.$sym"))).toString())

    /** Stateful (one per socket): orderbook.50 is snapshot + deltas (size 0 = delete). */
    class Bybit {
        private val bids = java.util.TreeMap<BigDecimal, BigDecimal>(compareByDescending { it }); private val asks = java.util.TreeMap<BigDecimal, BigDecimal>()
        private fun apply(m: MutableMap<BigDecimal, BigDecimal>, a: JSONArray) { for (i in 0 until a.length()) { val x = a.getJSONArray(i); val p = x.getString(0).bd(); val q = x.getString(1).bd(); if (q.signum() == 0) m.remove(p) else m[p] = q } }
        fun parse(text: String): List<MarketEvent> {
            val r = JSONObject(text); val topic = r.optString("topic"); if (topic.isEmpty()) return emptyList()
            return when {
                topic.startsWith("kline.") -> { val a = r.getJSONArray("data"); (0 until a.length()).map { val x = a.getJSONObject(it)
                    MarketEvent.Bar(kl(x.getLong("start"), x.getString("open"), x.getString("high"), x.getString("low"), x.getString("close"), x.optString("volume", "0"))) } }
                topic.startsWith("publicTrade.") -> { val a = r.getJSONArray("data")
                    listOf(MarketEvent.Trades((0 until a.length()).map { val x = a.getJSONObject(it)
                        PublicTrade(x.getString("p").bd(), x.getString("v").bd(), if (x.optString("S") == "Buy") Side.BUY else Side.SELL, x.optLong("T")) }.sortedByDescending { it.time })) }
                topic.startsWith("orderbook.") -> { val d = r.getJSONObject("data")
                    if (r.optString("type") == "snapshot") { bids.clear(); asks.clear() }
                    apply(bids, d.getJSONArray("b")); apply(asks, d.getJSONArray("a"))
                    listOf(MarketEvent.Book(OrderBook(bids.entries.take(20).map { Level(it.key, it.value) }, asks.entries.take(20).map { Level(it.key, it.value) }))) }
                topic.startsWith("tickers.") -> { val x = r.getJSONObject("data")
                    listOf(MarketEvent.Tick(Ticker(x.getString("lastPrice").bd(), x.optString("price24hPcnt").bd() * H, x.optString("highPrice24h").bd(), x.optString("lowPrice24h").bd(), x.optString("volume24h").bd()))) }
                else -> emptyList()
            }
        }
    }

    // ---------- MEXC spot v3 (protobuf, mexcdevelop/websocket-proto) ----------
    const val MEXC_URL = "wss://wbs-api.mexc.com/ws"
    const val MEXC_PING = """{"method":"PING"}"""
    fun mexcIv(iv: String) = mapOf("1m" to "Min1", "5m" to "Min5", "15m" to "Min15", "30m" to "Min30", "1h" to "Min60", "4h" to "Hour4", "1d" to "Day1", "1w" to "Week1", "1M" to "Month1")[iv] ?: "Min15"
    fun mexcSubs(sym: String, interval: String) = listOf(JSONObject().put("method", "SUBSCRIPTION").put("params", JSONArray(listOf(
        "spot@public.kline.v3.api.pb@$sym@${mexcIv(interval)}", "spot@public.aggre.deals.v3.api.pb@100ms@$sym", "spot@public.limit.depth.v3.api.pb@$sym@20",
        "spot@public.aggre.bookTicker.v3.api.pb@100ms@$sym", "spot@public.miniTicker.v3.api.pb@$sym@UTC+8"))).toString())

    /** PushDataV3ApiWrapper: 301 deals, 303 limitDepths, 308 kline, 309 miniTicker, 313 aggreDepths, 314 aggreDeals, 315 aggreBookTicker. */
    fun mexc(b: ByteArray): List<MarketEvent> {
        val w = Pb.fields(b)
        fun str(m: Map<Int, List<Any>>, f: Int) = (m[f]?.firstOrNull() as? ByteArray)?.let { String(it) } ?: ""
        fun num(m: Map<Int, List<Any>>, f: Int) = (m[f]?.firstOrNull() as? Long) ?: 0L
        fun levels(m: Map<Int, List<Any>>, f: Int) = (m[f] ?: emptyList()).map { val x = Pb.fields(it as ByteArray); Level(str(x, 1).bd(), str(x, 2).bd()) }
        fun body(f: Int) = (w[f]?.firstOrNull() as? ByteArray)?.let { Pb.fields(it) }
        body(308)?.let { k -> return listOf(MarketEvent.Bar(kl(num(k, 2) * 1000, str(k, 3), str(k, 5), str(k, 6), str(k, 4), str(k, 7)))) }
        (body(314) ?: body(301))?.let { d -> return listOf(MarketEvent.Trades((d[1] ?: emptyList()).map { val x = Pb.fields(it as ByteArray)
            PublicTrade(str(x, 1).bd(), str(x, 2).bd(), if (num(x, 3) == 1L) Side.BUY else Side.SELL, num(x, 4)) }.sortedByDescending { it.time })) }
        (body(303) ?: body(313))?.let { d -> return listOf(MarketEvent.Book(OrderBook(levels(d, 2), levels(d, 1)))) }
        body(315)?.let { t -> return listOf(MarketEvent.Bbo(Level(str(t, 1).bd(), str(t, 2).bd()), Level(str(t, 3).bd(), str(t, 4).bd()))) }
        body(309)?.let { t -> return listOf(MarketEvent.Tick(Ticker(str(t, 2).bd(), str(t, 3).bd() * H, str(t, 5).bd(), str(t, 6).bd(), str(t, 8).bd()))) }
        return emptyList()
    }
}

/** Minimal protobuf wire decoder: field -> values (Long for varint/fixed, ByteArray for length-delimited). */
object Pb {
    fun fields(b: ByteArray): Map<Int, List<Any>> {
        val out = HashMap<Int, MutableList<Any>>(); var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
        while (i < b.size) {
            val tag = varint(); val f = (tag ushr 3).toInt()
            val v: Any = when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> { var r = 0L; for (k in 0 until 8) r = r or ((b[i + k].toLong() and 0xff) shl (8 * k)); i += 8; r }
                2 -> { val n = varint().toInt(); val a = b.copyOfRange(i, i + n); i += n; a }
                5 -> { var r = 0L; for (k in 0 until 4) r = r or ((b[i + k].toLong() and 0xff) shl (8 * k)); i += 4; r }
                else -> throw IllegalArgumentException("wire type")
            }
            out.getOrPut(f) { mutableListOf() }.add(v)
        }
        return out
    }
}
