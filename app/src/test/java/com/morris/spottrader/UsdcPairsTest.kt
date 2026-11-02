package com.morris.spottrader

import com.morris.spottrader.ex.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

/** Live public-endpoint checks for USDC pairs (skipped per exchange if unreachable/geo-blocked from the test machine). */
class UsdcPairsTest {
    private val btcUsdc = SymPair("BTC", "USDC")

    private fun live(id: String) = runBlocking {
        val ex = Exchanges.create(id, null)
        val syms = try { ex.symbols() } catch (e: Throwable) { assumeTrue("$id unreachable: ${e.message?.take(60)}", false); emptyList() }
        println("$id: ${syms.size} symbols, USDT=${syms.count { it.quote == "USDT" }}, USDC=${syms.count { it.quote == "USDC" }}, others=${syms.map { it.quote }.toSet().size - 2}")
        assertTrue("$id lists BTC/USDC", syms.contains(btcUsdc))
        assertTrue(syms.count { it.quote == "USDC" } > 10)
        val t = ex.ticker(btcUsdc); assertTrue(t.last.signum() > 0)
        val ob = ex.orderBook(btcUsdc, 10); assertTrue(ob.bids.isNotEmpty() && ob.asks.isNotEmpty())
        val ks = ex.klines(btcUsdc, "15m"); assertTrue(ks.size > 20)
        val info = ex.symbolInfo(btcUsdc); assertTrue(info.tick.signum() > 0 && info.step.signum() > 0)
        println("$id BTC/USDC last=${t.last} tick=${info.tick} step=${info.step} minNotional=${info.minNotional} maker=${info.maker} taker=${info.taker}")
        val px = ex.lastPrices(); assertTrue("$id bulk price BTCUSDC", (px["BTCUSDC"] ?: BigDecimal.ZERO).signum() > 0)
        assertTrue(ex.recentTrades(btcUsdc).isNotEmpty())
    }
    @Test fun mexc() = live("mexc")
    @Test fun okx() = live("okx")
    @Test fun gate() = live("gate")
    @Test fun bitget() = live("bitget")
    @Test fun binance() = live("binance")
    @Test fun bybit() = live("bybit")

    @Test fun symbolParsing() {
        assertEquals(SymPair("BTC", "USDC"), Quotes.guess("BTCUSDC")); assertEquals(SymPair("BTC", "USDC"), Quotes.guess("BTC-USDC")); assertEquals(SymPair("BTC", "USDC"), Quotes.guess("BTC_USDC"))
        assertEquals(SymPair("ETH", "FDUSD"), Quotes.guess("ETHFDUSD")); assertEquals(SymPair("SOL", "USD1"), Quotes.guess("SOLUSD1")); assertEquals(SymPair("ETH", "BTC"), Quotes.guess("ETHBTC"))
        assertEquals("BTCUSDC", Quotes.key("BTC-USDC")); assertEquals("BTCUSDC", Quotes.key("btc_usdc"))
    }

    @Test fun positionsUseSelectedQuote() {
        val b = Balance("BTC", BigDecimal("1"), BigDecimal.ZERO)
        val p = AppVM.position(b, BigDecimal("80000"), BigDecimal("70000"), "USDC", costQty = BigDecimal("0.5"))   // history only covers 0.5 BTC
        assertEquals("USDC", p.quote); assertEquals(0, BigDecimal("5000").compareTo(p.pnl!!)); assertEquals(BigDecimal("14.29"), p.pnlPct); assertEquals(0, BigDecimal("80000").compareTo(p.value!!))
    }

    @Test fun costBasisMergesQuotes() {
        // 1 BTC bought at 70000 USDT, 1 BTC bought at 72000 USDC (USDC ~ 1.0) -> avg 71000
        val t = listOf(Trade(Side.BUY, BigDecimal("70000"), BigDecimal.ONE, BigDecimal.ZERO, "USDT", 1), Trade(Side.BUY, BigDecimal("72000"), BigDecimal.ONE, BigDecimal.ZERO, "USDC", 2))
        assertEquals(0, BigDecimal("71000").compareTo(CostCalc.compute(t, "BTC").avg))
    }
}
