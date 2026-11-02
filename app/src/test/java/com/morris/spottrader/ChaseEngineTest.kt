package com.morris.spottrader

import com.morris.spottrader.chase.*
import com.morris.spottrader.ex.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Simulated exchange: best bid rises over time, first post-only is rejected, one order partially fills before re-place. */
class FakeEx : SpotExchange {
    override val id = "fake"; override val display = "Fake"; override val features = setOf(Feature.POST_ONLY)
    var bid = BigDecimal("100.00"); val placed = mutableListOf<OrderReq>(); val cancelled = mutableListOf<String>()
    val orders = HashMap<String, Order>(); var n = 0; var rejectNext = true
    override suspend fun orderBook(p: SymPair, depth: Int) = OrderBook(listOf(Level(bid, BigDecimal.ONE)), listOf(Level(bid + BigDecimal("0.01"), BigDecimal.ONE)))
    override suspend fun place(r: OrderReq): String { assertEquals(TIF.POST_ONLY, r.tif); placed += r
        if (rejectNext) { rejectNext = false; throw ApiException("-2010 Order would immediately match and take.") }
        val id = "${++n}"; orders[id] = Order(id, "X", r.side, "LIMIT_MAKER", r.price!!, r.qty!!, BigDecimal.ZERO, "NEW", 0); return id }
    override suspend fun getOrder(p: SymPair, orderId: String) = orders[orderId]!!
    override suspend fun cancelOrder(p: SymPair, orderId: String) { cancelled += orderId; orders[orderId] = orders[orderId]!!.copy(status = "CANCELED") }
    fun fill(id: String, q: String) { val o = orders[id]!!; val f = o.filled + BigDecimal(q); orders[id] = o.copy(filled = f, avg = o.price, status = if (f >= o.qty) "FILLED" else "PARTIALLY_FILLED") }
    var lastPx = BigDecimal("100.00")
    override suspend fun ticker(p: SymPair) = Ticker(lastPx, BigDecimal.ZERO, lastPx, lastPx, BigDecimal.ONE); override suspend fun klines(p: SymPair, interval: String, endTime: Long?) = TODO(); override suspend fun symbolInfo(p: SymPair) = TODO(); override suspend fun symbols() = TODO(); override suspend fun lastPrices() = TODO()
    override suspend fun recentTrades(p: SymPair) = TODO(); override suspend fun balances() = TODO(); override suspend fun openOrders(p: SymPair?) = TODO()
    override suspend fun orderHistory(p: SymPair) = TODO(); override suspend fun myTrades(p: SymPair) = TODO()
}

class ChaseEngineTest {
    @Test fun chasesRepricesAndAccountsPartialFills() = runBlocking {
        val ex = FakeEx(); val info = SymbolInfo(BigDecimal("0.01"), BigDecimal("0.001"))
        val t = ChaseManager.start(ex, info, ChaseParams(SymPair("X", "USDT"), Side.BUY, BigDecimal("1.000"), offsetTicks = 0, limitPrice = BigDecimal("100.05"), maxDurationSec = 30, intervalMs = 20))
        delay(150)
        // first attempt rejected -> retried one tick back
        assertEquals(1, t.rejects); assertEquals(BigDecimal("99.99"), ex.placed[1].price)
        ex.fill("1", "0.400"); delay(200)
        assertEquals(0, BigDecimal("0.400").compareTo(t.filled))
        ex.bid = BigDecimal("100.03"); delay(600)             // market moves away -> cancel + re-place remaining
        assertTrue(ex.cancelled.contains("1")); assertEquals(0, BigDecimal("0.600").compareTo(ex.placed.last().qty)); assertEquals(BigDecimal("100.03"), ex.placed.last().price)
        assertTrue(t.replaces >= 1)
        ex.bid = BigDecimal("100.20"); delay(600)             // beyond max chase price -> clamped to 100.05
        assertEquals(BigDecimal("100.05"), ex.placed.last().price)
        ex.fill(ex.n.toString(), "0.600"); delay(600)
        assertFalse(t.active); assertEquals(0, BigDecimal("1.000").compareTo(t.filled))
        // avg = (0.4*99.99 + 0.6*100.05) = 100.026
        assertEquals(0, BigDecimal("100.026").compareTo(t.avgPrice!!.setScale(3, java.math.RoundingMode.HALF_UP)))
        assertEquals("已完成", t.status)
    }
    @Test fun stopCancelsWorkingOrder() = runBlocking {
        val ex = FakeEx().apply { rejectNext = false }
        val t = ChaseManager.start(ex, SymbolInfo(BigDecimal("0.01"), BigDecimal("0.001")), ChaseParams(SymPair("X", "USDT"), Side.SELL, BigDecimal("2"), offsetTicks = 2, intervalMs = 20))
        delay(100); assertEquals(BigDecimal("100.03"), ex.placed[0].price)   // best ask 100.01 + 2 ticks
        ChaseManager.stop(t); delay(600)
        assertFalse(t.active); assertEquals(listOf("1"), ex.cancelled); assertEquals("已手动停止", t.status)
    }
    @Test fun makerStopWaitsForTriggerThenPlacesPostOnlyCappedAtOrderPrice() = runBlocking {
        val ex = FakeEx().apply { rejectNext = false; lastPx = BigDecimal("100.00") }
        // buy-stop: trigger 101 (above market), order price 101.50 -> after trigger, chase best bid but never above 101.50
        val t = ChaseManager.start(ex, SymbolInfo(BigDecimal("0.01"), BigDecimal("0.001")), ChaseParams(SymPair("X", "USDT"), Side.BUY, BigDecimal("1"), limitPrice = BigDecimal("101.50"), triggerPrice = BigDecimal("101"), intervalMs = 20, maxDurationSec = 30))
        delay(300); assertTrue("not placed before trigger", ex.placed.isEmpty()); assertFalse(t.triggered)
        ex.lastPx = BigDecimal("101.02"); ex.bid = BigDecimal("101.00"); delay(2200)
        assertTrue(t.triggered); assertEquals(BigDecimal("101.00"), ex.placed[0].price); assertEquals(TIF.POST_ONLY, ex.placed[0].tif)
        ex.bid = BigDecimal("103.00"); delay(600)                       // market runs away -> capped at order price
        assertEquals(BigDecimal("101.50"), ex.placed.last().price)
        ChaseManager.stop(t); delay(300); assertFalse(t.active)
    }
}
