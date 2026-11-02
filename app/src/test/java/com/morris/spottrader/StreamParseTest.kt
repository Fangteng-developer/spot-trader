package com.morris.spottrader

import com.morris.spottrader.ex.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StreamParseTest {
    @Test fun binanceKline() {
        val e = WsParse.binance("""{"stream":"btcusdt@kline_1m","data":{"e":"kline","E":1,"s":"BTCUSDT","k":{"t":1700000040000,"T":1700000099999,"s":"BTCUSDT","i":"1m","o":"100.5","c":"101.0","h":"102","l":"99","v":"12.5","x":false}}}""").single() as MarketEvent.Bar
        assertEquals(Kline(1700000040000, 100.5, 102.0, 99.0, 101.0, 12.5), e.k)
    }
    @Test fun binanceAggTradeTickerBookTicker() {
        val t = (WsParse.binance("""{"stream":"btcusdt@aggTrade","data":{"e":"aggTrade","p":"80000.1","q":"0.01","T":5,"m":true}}""").single() as MarketEvent.Trades).t.single()
        assertEquals(Side.SELL, t.side); assertEquals(BigDecimal("80000.1"), t.price)
        val k = (WsParse.binance("""{"stream":"btcusdt@ticker","data":{"e":"24hrTicker","c":"80000","P":"1.5","h":"81000","l":"79000","v":"123"}}""").single() as MarketEvent.Tick).t
        assertEquals(BigDecimal("1.5"), k.changePct)
        val b = WsParse.binance("""{"stream":"btcusdt@bookTicker","data":{"u":1,"s":"BTCUSDT","b":"1.0","B":"2","a":"1.1","A":"3"}}""").single() as MarketEvent.Bbo
        assertEquals(BigDecimal("1.1"), b.ask.price)
    }
    @Test fun binanceDepthAndAsterDepth() {
        val d = (WsParse.binance("""{"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":1,"bids":[["1.0","2"]],"asks":[["1.1","3"],["1.2","4"]]}}""").single() as MarketEvent.Book).b
        assertEquals(2, d.asks.size)
        val a = (WsParse.binance("""{"stream":"btcusdt@depth20","data":{"e":"depthUpdate","E":1,"s":"BTCUSDT","b":[["80775.60","1.30930"]],"a":[["80776.41","0.1"]]}}""").single() as MarketEvent.Book).b
        assertEquals(BigDecimal("80775.60"), a.bids[0].price)
    }
    @Test fun okxChannels() {
        assertTrue(WsParse.okx("pong").isEmpty())
        assertTrue(WsParse.okx("""{"event":"subscribe","arg":{"channel":"tickers","instId":"BTC-USDT"}}""").isEmpty())
        val t = (WsParse.okx("""{"arg":{"channel":"tickers","instId":"BTC-USDT"},"data":[{"last":"110","open24h":"100","high24h":"111","low24h":"99","vol24h":"5"}]}""").single() as MarketEvent.Tick).t
        assertEquals(0, t.changePct.compareTo(BigDecimal(10)))
        val b = (WsParse.okx("""{"arg":{"channel":"books5","instId":"BTC-USDT"},"data":[{"asks":[["101","1","0","1"]],"bids":[["100","2","0","1"]],"ts":"1"}]}""").single() as MarketEvent.Book).b
        assertEquals(BigDecimal("100"), b.bids[0].price)
        val tr = (WsParse.okx("""{"arg":{"channel":"trades","instId":"BTC-USDT"},"data":[{"px":"100","sz":"0.5","side":"buy","ts":"7"}]}""").single() as MarketEvent.Trades).t
        assertEquals(Side.BUY, tr[0].side); assertEquals(7L, tr[0].time)
        val c = (WsParse.okx("""{"arg":{"channel":"candle1H","instId":"BTC-USDT"},"data":[["1700000000000","1","3","0.5","2","10","20","20","0"]]}""").single() as MarketEvent.Bar).k
        assertEquals(Kline(1700000000000, 1.0, 3.0, 0.5, 2.0, 10.0), c)
        assertEquals("candle1H", WsParse.okxBar("1h")); assertEquals("candle1Dutc", WsParse.okxBar("1d")); assertEquals("candle15m", WsParse.okxBar("15m"))
    }
    @Test fun hyperliquidChannels() {
        assertTrue(WsParse.hyperliquid("""{"channel":"pong"}""").isEmpty())
        val b = (WsParse.hyperliquid("""{"channel":"l2Book","data":{"coin":"@142","time":1,"levels":[[{"px":"80693.0","sz":"0.6","n":2}],[{"px":"80700.0","sz":"1","n":1}]]}}""").single() as MarketEvent.Book).b
        assertEquals(BigDecimal("80700.0"), b.asks[0].price)
        val t = (WsParse.hyperliquid("""{"channel":"trades","data":[{"coin":"@142","side":"A","px":"1","sz":"2","time":3}]}""").single() as MarketEvent.Trades).t
        assertEquals(Side.SELL, t[0].side)
        val k = (WsParse.hyperliquid("""{"channel":"candle","data":{"t":60000,"T":119999,"s":"@142","i":"1m","o":"1","c":"2","h":"3","l":"0.5","v":"9","n":4}}""").single() as MarketEvent.Bar).k
        assertEquals(Kline(60000, 1.0, 3.0, 0.5, 2.0, 9.0), k)
    }
    @Test fun applyBarReplacesOrAppends() {
        val base = listOf(Kline(0, 1.0, 1.0, 1.0, 1.0), Kline(60, 1.0, 1.0, 1.0, 1.0))
        val r = AppVM.applyBar(base, Kline(60, 1.0, 2.0, 1.0, 2.0)); assertEquals(2, r.size); assertEquals(2.0, r.last().c, 0.0)
        val a = AppVM.applyBar(r, Kline(120, 2.0, 2.0, 2.0, 2.0)); assertEquals(3, a.size)
        val m = AppVM.applyBar(a, Kline(0, 5.0, 5.0, 5.0, 5.0)); assertEquals(3, m.size); assertEquals(5.0, m.first().c, 0.0)
    }
}
