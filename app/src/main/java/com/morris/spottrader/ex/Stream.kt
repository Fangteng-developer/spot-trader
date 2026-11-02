package com.morris.spottrader.ex

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Real-time market data pushed by an exchange WebSocket. */
sealed class MarketEvent {
    object Connected : MarketEvent()
    data class Bar(val k: Kline) : MarketEvent()
    data class Book(val b: OrderBook) : MarketEvent()
    data class Bbo(val bid: Level, val ask: Level) : MarketEvent()
    data class Trades(val t: List<PublicTrade>) : MarketEvent()
    data class Tick(val t: Ticker) : MarketEvent()
}

object Ws {
    /** readTimeout 0 for long-lived sockets; protocol ping every 20s detects dead links (Binance/Aster answer pong frames). */
    val client = Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val textPingClient = Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /**
     * Opens [url]; sends [subscribe] messages on open; [textPing] (e.g. OKX "ping") every [pingMs].
     * The flow completes when the server closes (e.g. Binance 24h cut) and throws on failure -> caller reconnects.
     */
    fun connect(url: String, subscribe: List<String> = emptyList(), textPing: String? = null, pingMs: Long = 20_000, parse: (String) -> List<MarketEvent>, binary: ((ByteArray) -> List<MarketEvent>)? = null): Flow<MarketEvent> = callbackFlow {
        val ws = (if (textPing != null) textPingClient else client).newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { subscribe.forEach { webSocket.send(it) }; trySend(MarketEvent.Connected) }
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { parse(text) }.getOrNull()?.forEach { trySend(it) } }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { val b = binary ?: return; runCatching { b(bytes.toByteArray()) }.getOrNull()?.forEach { trySend(it) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        val pinger = textPing?.let { m -> launch { while (true) { delay(pingMs); if (!ws.send(m)) break } } }
        awaitClose { pinger?.cancel(); ws.cancel() }
    }.buffer(512, BufferOverflow.DROP_OLDEST)

    /** merges sockets; if any one closes/fails the whole stream fails so the caller reconnects all of them */
    fun mergeAll(vararg f: Flow<MarketEvent>): Flow<MarketEvent> = kotlinx.coroutines.flow.channelFlow {
        f.forEach { fl -> launch { fl.collect { send(it) }; throw ApiException("WebSocket closed") } } }
}

/** Pure message parsers (unit-tested). Unknown / control messages -> empty list. */
object WsParse {
    private fun lv(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONArray(it); Level(x.getString(0).bd(), x.getString(1).bd()) }

    /** Binance / Aster combined-stream envelope {"stream":..,"data":{..}} (raw single stream also accepted). */
    fun binance(text: String): List<MarketEvent> {
        val root = JSONObject(text); val d = root.optJSONObject("data") ?: root
        val stream = root.optString("stream")
        val e = d.optString("e")
        return when {
            e == "kline" -> { val k = d.getJSONObject("k")
                listOf(MarketEvent.Bar(Kline(k.getLong("t"), k.getString("o").toDouble(), k.getString("h").toDouble(), k.getString("l").toDouble(), k.getString("c").toDouble(), k.optString("v", "0").toDoubleOrNull() ?: 0.0))) }
            e == "aggTrade" || e == "trade" -> listOf(MarketEvent.Trades(listOf(PublicTrade(d.getString("p").bd(), d.getString("q").bd(), if (d.optBoolean("m")) Side.SELL else Side.BUY, d.optLong("T")))))
            e == "24hrTicker" -> listOf(MarketEvent.Tick(Ticker(d.getString("c").bd(), d.optString("P").bd(), d.optString("h").bd(), d.optString("l").bd(), d.optString("v").bd())))
            e == "bookTicker" || (stream.endsWith("@bookTicker") && d.has("b")) -> listOf(MarketEvent.Bbo(Level(d.getString("b").bd(), d.getString("B").bd()), Level(d.getString("a").bd(), d.getString("A").bd())))
            d.has("bids") && d.has("asks") -> listOf(MarketEvent.Book(OrderBook(lv(d.getJSONArray("bids")), lv(d.getJSONArray("asks")))))
            e == "depthUpdate" && stream.contains("@depth") && d.has("b") -> listOf(MarketEvent.Book(OrderBook(lv(d.getJSONArray("b")), lv(d.getJSONArray("a")))))  // Aster partial depth
            else -> emptyList()
        }
    }

    fun okxBar(iv: String) = "candle" + (mapOf("1h" to "1H", "4h" to "4H", "1d" to "1Dutc", "1w" to "1Wutc", "1M" to "1Mutc")[iv] ?: iv)

    /** OKX v5 public + business channels. */
    fun okx(text: String): List<MarketEvent> {
        if (text == "pong") return emptyList()
        val root = JSONObject(text); val ch = root.optJSONObject("arg")?.optString("channel") ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        return when {
            ch == "tickers" -> (0 until data.length()).map { val x = data.getJSONObject(it)
                val last = x.getString("last").bd(); val open = x.optString("open24h").bd()
                MarketEvent.Tick(Ticker(last, if (open.signum() > 0) (last - open) * BigDecimal(100) / open else BigDecimal.ZERO, x.optString("high24h").bd(), x.optString("low24h").bd(), x.optString("vol24h").bd())) }
            ch == "books5" || ch == "books" -> (0 until data.length()).map { val x = data.getJSONObject(it); MarketEvent.Book(OrderBook(lv(x.getJSONArray("bids")), lv(x.getJSONArray("asks")))) }
            ch == "trades" -> listOf(MarketEvent.Trades((0 until data.length()).map { val x = data.getJSONObject(it)
                PublicTrade(x.getString("px").bd(), x.getString("sz").bd(), if (x.optString("side") == "buy") Side.BUY else Side.SELL, x.optString("ts").toLongOrNull() ?: 0L) }.sortedByDescending { it.time }))
            ch.startsWith("candle") -> (0 until data.length()).map { val x = data.getJSONArray(it)
                MarketEvent.Bar(Kline(x.getString(0).toLong(), x.getString(1).toDouble(), x.getString(2).toDouble(), x.getString(3).toDouble(), x.getString(4).toDouble(), x.optString(5, "0").toDoubleOrNull() ?: 0.0)) }
            else -> emptyList()
        }
    }

    /** Hyperliquid /ws channels: l2Book, trades, candle (pong / subscriptionResponse ignored). */
    fun hyperliquid(text: String): List<MarketEvent> {
        val root = JSONObject(text)
        return when (root.optString("channel")) {
            "l2Book" -> { val lv = root.getJSONObject("data").getJSONArray("levels")
                fun side(a: JSONArray) = (0 until a.length()).map { val x = a.getJSONObject(it); Level(x.getString("px").bd(), x.getString("sz").bd()) }
                listOf(MarketEvent.Book(OrderBook(side(lv.getJSONArray(0)), side(lv.getJSONArray(1))))) }
            "trades" -> { val a = root.getJSONArray("data")
                listOf(MarketEvent.Trades((0 until a.length()).map { val x = a.getJSONObject(it)
                    PublicTrade(x.getString("px").bd(), x.getString("sz").bd(), if (x.optString("side") == "B") Side.BUY else Side.SELL, x.optLong("time")) }.sortedByDescending { it.time })) }
            "candle" -> { val x = root.getJSONObject("data")
                listOf(MarketEvent.Bar(Kline(x.getLong("t"), x.getString("o").toDouble(), x.getString("h").toDouble(), x.getString("l").toDouble(), x.getString("c").toDouble(), x.optString("v", "0").toDoubleOrNull() ?: 0.0))) }
            else -> emptyList()
        }
    }
}
