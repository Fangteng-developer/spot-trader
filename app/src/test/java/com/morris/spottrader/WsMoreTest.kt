package com.morris.spottrader

import com.morris.spottrader.ex.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class WsMoreTest {
    @Test fun gate() {
        val k = WsMore.gate("""{"time":1,"channel":"spot.candlesticks","event":"update","result":{"t":"1700000000","v":"1","c":"101","h":"102","l":"99","o":"100","n":"15m_BTC_USDT","a":"5"}}""").single() as MarketEvent.Bar
        assertEquals(1700000000000L, k.k.t); assertEquals(101.0, k.k.c, 0.0)
        val t = WsMore.gate("""{"channel":"spot.trades","event":"update","result":{"id":1,"create_time_ms":"1700000000123.4","side":"sell","currency_pair":"BTC_USDT","amount":"0.5","price":"100"}}""").single() as MarketEvent.Trades
        assertEquals(Side.SELL, t.t[0].side); assertEquals(1700000000123L, t.t[0].time)
        val b = WsMore.gate("""{"channel":"spot.order_book","event":"update","result":{"t":1,"bids":[["99","1"]],"asks":[["101","2"]]}}""").single() as MarketEvent.Book
        assertEquals(BigDecimal("101"), b.b.asks[0].price)
        val tk = WsMore.gate("""{"channel":"spot.tickers","event":"update","result":{"currency_pair":"BTC_USDT","last":"100","change_percentage":"1.5","high_24h":"110","low_24h":"90","base_volume":"7"}}""").single() as MarketEvent.Tick
        assertEquals(BigDecimal("1.5"), tk.t.changePct)
        assertTrue(WsMore.gate("""{"channel":"spot.pong","event":"","result":null}""").isEmpty())
    }
    @Test fun bitget() {
        val k = WsMore.bitget("""{"action":"update","arg":{"instType":"SPOT","channel":"candle15m","instId":"BTCUSDT"},"data":[["1700000000000","100","102","99","101","3","300","300"]]}""").single() as MarketEvent.Bar
        assertEquals(102.0, k.k.h, 0.0)
        val t = WsMore.bitget("""{"arg":{"channel":"trade"},"data":[{"ts":"2","price":"1","size":"2","side":"buy","tradeId":"a"},{"ts":"5","price":"1","size":"2","side":"sell","tradeId":"b"}]}""").single() as MarketEvent.Trades
        assertEquals(5L, t.t[0].time)
        val tk = WsMore.bitget("""{"arg":{"channel":"ticker"},"data":[{"lastPr":"100","change24h":"0.012","high24h":"1","low24h":"1","baseVolume":"9"}]}""").single() as MarketEvent.Tick
        assertEquals(0, BigDecimal("1.2").compareTo(tk.t.changePct))
        assertTrue(WsMore.bitget("pong").isEmpty()); assertTrue(WsMore.bitget("""{"event":"subscribe","arg":{"channel":"trade"}}""").isEmpty())
        assertEquals("candle1H", WsMore.bitgetCandle("1h"))
    }
    @Test fun bybitBookDelta() {
        val b = WsMore.Bybit()
        b.parse("""{"topic":"orderbook.50.BTCUSDT","type":"snapshot","data":{"s":"BTCUSDT","b":[["99","1"],["98","1"]],"a":[["101","1"],["102","1"]]}}""")
        val e = b.parse("""{"topic":"orderbook.50.BTCUSDT","type":"delta","data":{"b":[["99","0"],["98.5","3"]],"a":[["100.5","2"]]}}""").single() as MarketEvent.Book
        assertEquals(BigDecimal("98.5"), e.b.bids[0].price); assertEquals(BigDecimal("100.5"), e.b.asks[0].price); assertEquals(3, e.b.asks.size)
        val k = b.parse("""{"topic":"kline.15.BTCUSDT","data":[{"start":1700000000000,"open":"1","close":"2","high":"3","low":"0.5","volume":"4","confirm":false}]}""").single() as MarketEvent.Bar
        assertEquals(2.0, k.k.c, 0.0)
        val t = b.parse("""{"topic":"publicTrade.BTCUSDT","data":[{"T":7,"s":"BTCUSDT","S":"Buy","v":"0.1","p":"100"}]}""").single() as MarketEvent.Trades
        assertEquals(Side.BUY, t.t[0].side)
        assertTrue(b.parse("""{"success":true,"ret_msg":"pong","op":"ping"}""").isEmpty())
    }
    // --- protobuf encoder for test vectors (mirrors mexcdevelop/websocket-proto field numbers) ---
    private fun vi(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    private fun msg(vararg f: Pair<Int, Any>): ByteArray { val o = ByteArrayOutputStream()
        for ((n, v) in f) when (v) { is Long -> { vi(o, (n shl 3).toLong()); vi(o, v) }; is String -> { val b = v.toByteArray(); vi(o, ((n shl 3) or 2).toLong()); vi(o, b.size.toLong()); o.write(b) }
            is ByteArray -> { vi(o, ((n shl 3) or 2).toLong()); vi(o, v.size.toLong()); o.write(v) } }
        return o.toByteArray() }
    @Test fun mexcProto() {
        val kl = msg(1 to "spot@public.kline.v3.api.pb@BTCUSDT@Min15", 308 to msg(1 to "Min15", 2 to 1700000000L, 3 to "100", 4 to "101", 5 to "102", 6 to "99", 7 to "5"), 3 to "BTCUSDT", 6 to 1700000000123L)
        val k = WsMore.mexc(kl).single() as MarketEvent.Bar
        assertEquals(1700000000000L, k.k.t); assertEquals(101.0, k.k.c, 0.0); assertEquals(102.0, k.k.h, 0.0)
        val d = WsMore.mexc(msg(1 to "c", 314 to msg(1 to msg(1 to "100", 2 to "0.1", 3 to 2L, 4 to 9L), 1 to msg(1 to "101", 2 to "0.2", 3 to 1L, 4 to 10L), 2 to "spot@public.aggre.deals.v3.api.pb"))).single() as MarketEvent.Trades
        assertEquals(2, d.t.size); assertEquals(Side.BUY, d.t[0].side); assertEquals(Side.SELL, d.t[1].side)
        val b = WsMore.mexc(msg(303 to msg(1 to msg(1 to "101", 2 to "1"), 2 to msg(1 to "99", 2 to "2")))).single() as MarketEvent.Book
        assertEquals(BigDecimal("99"), b.b.bids[0].price); assertEquals(BigDecimal("101"), b.b.asks[0].price)
        val bb = WsMore.mexc(msg(315 to msg(1 to "99", 2 to "1", 3 to "101", 4 to "2"))).single() as MarketEvent.Bbo
        assertEquals(BigDecimal("101"), bb.ask.price)
        val t = WsMore.mexc(msg(309 to msg(1 to "BTCUSDT", 2 to "100", 3 to "0.0123", 5 to "110", 6 to "90", 8 to "7"))).single() as MarketEvent.Tick
        assertEquals(0, BigDecimal("1.23").compareTo(t.t.changePct))
        assertEquals("Min60", WsMore.mexcIv("1h"))
    }

    // ---- live (opt-in: LIVE_WS=1) ----
    private fun live(name: String, f: Flow<MarketEvent>) = runBlocking {
        val evs = java.util.Collections.synchronizedList(mutableListOf<MarketEvent>())
        withTimeoutOrNull(15_000) { f.collect { evs.add(it) } }
        val kinds = evs.map { it::class.simpleName }.toSet(); java.io.File("/tmp/live_ws.txt").appendText("LIVE $name -> ${evs.size} events kinds=$kinds\n"); kinds }
    private fun on() = Assume.assumeTrue(System.getenv("LIVE_WS") == "1")
    @Test fun liveGate() { on(); live("gate", Exchanges.create("gate", null).stream(SymPair("BTC", "USDT"), "1m")!!) }
    @Test fun liveBitget() { on(); live("bitget", Exchanges.create("bitget", null).stream(SymPair("BTC", "USDT"), "1m")!!) }
    @Test fun liveBybit() { on(); live("bybit", Exchanges.create("bybit", null).stream(SymPair("BTC", "USDT"), "1m")!!) }
    @Test fun liveMexc() { on(); live("mexc", Exchanges.create("mexc", null).stream(SymPair("BTC", "USDT"), "1m")!!) }
}
