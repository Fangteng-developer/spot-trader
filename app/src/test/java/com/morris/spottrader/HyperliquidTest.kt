package com.morris.spottrader

import com.morris.spottrader.ex.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/** Signing vectors from hyperliquid-python-sdk tests/signing_test.py */
class HyperliquidTest {
    private val key = "0x0123456789012345678901234567890123456789012345678901234567890123"
    private fun h(s: String) = BigInteger(s.removePrefix("0x"), 16)

    @Test fun dummyActionSignature() {
        val a = linkedMapOf<String, Any?>("type" to "dummy", "num" to 100000000000L)
        val m = EthSign.signL1(a, key, 0, mainnet = true)
        assertEquals(h("0x53749d5b30552aeb2fca34b530185976545bb22d0b3ce6f62e31be961a59298"), m.r)
        assertEquals(h("0x755c40ba9bf05223521753995abb2f73ab3229be8ec921f350cb447e384d8ed8"), m.s); assertEquals(27, m.v)
        val t = EthSign.signL1(a, key, 0, mainnet = false)
        assertEquals(h("0x542af61ef1f429707e3c76c5293c80d01f74ef853e34b76efffcb57e574f9510"), t.r); assertEquals(28, t.v)
    }
    @Test fun orderActionSignature() {
        val a = Hyperliquid.orderAction(1, true, BigDecimal(100), BigDecimal(100), "Gtc")
        val m = EthSign.signL1(a, key, 0, mainnet = true)
        assertEquals(h("0xd65369825a9df5d80099e513cce430311d7d26ddf477f5b3a33d2806b100d78e"), m.r)
        assertEquals(h("0x2b54116ff64054968aa237c20ca9ff68000f977c93289157748a3162b6ea940e"), m.s); assertEquals(28, m.v)
        val t = EthSign.signL1(a, key, 0, mainnet = false)
        assertEquals(h("0x82b2ba28e76b3d761093aaded1b1cdad4960b3af30212b343fb2e6cdfa4e3d54"), t.r)
        assertEquals(h("0x6b53878fc99d26047f4d7e8c90eb98955a109f44209163f52d8dc4278cbbd9f5"), t.s); assertEquals(27, t.v)
    }
    @Test fun vaultAndScheduleCancel() {
        val a = linkedMapOf<String, Any?>("type" to "dummy", "num" to 100000000000L)
        val m = EthSign.signL1(a, key, 0, true, "0x1719884eb866cb12b2287399b15f7db5e7d775ea")
        assertEquals(h("0x3c548db75e479f8012acf3000ca3a6b05606bc2ec0c29c50c515066a326239"), m.r); assertEquals(28, m.v)
        val s = EthSign.signL1(linkedMapOf("type" to "scheduleCancel", "time" to 123456789L), key, 0, true)
        assertEquals(h("0x609cb20c737945d070716dcc696ba030e9976fcf5edad87afa7d877493109d55"), s.r); assertEquals(28, s.v)
    }
    @Test fun wireAndPriceRounding() {
        assertEquals("100", Hyperliquid.wire(BigDecimal("100.000")))
        assertEquals("0.0147", Hyperliquid.wire(BigDecimal("0.0147")))
        assertEquals(BigDecimal("80123"), Hyperliquid.roundPx(BigDecimal("80123.456"), 5))
        assertEquals(BigDecimal("1.2346"), Hyperliquid.roundPx(BigDecimal("1.234567"), 2))
        assertEquals(BigDecimal("0.001235"), Hyperliquid.roundPx(BigDecimal("0.00123456"), 2))
        assertEquals(BigDecimal("0.0012"), Hyperliquid.roundPx(BigDecimal("0.00123456"), 4))
    }
    @Test fun metaParsing() {
        val m = Hyperliquid.parseMeta(JSONObject("""{"tokens":[{"name":"USDC","szDecimals":8,"index":0},{"name":"PURR","szDecimals":0,"index":1},{"name":"UBTC","szDecimals":5,"index":197}],
            "universe":[{"tokens":[1,0],"name":"PURR/USDC","index":0},{"tokens":[197,0],"name":"@142","index":142}]}"""))
        assertEquals(SymPair("UBTC", "USDC"), m[1].pair); assertEquals("@142", m[1].coin); assertEquals(5, m[1].szDecimals)
    }
    @Test fun addressDerivation() { // well-known key 0x…01 -> 0x7e5f4552091a69125d5dfcb7b8c2659029395bdf
        assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", EthSign.address("0x" + "0".repeat(63) + "1")) }
    @Test fun livePublic() = runBlocking {
        for (id in listOf("hyperliquid", "aster")) { val ex = Exchanges.create(id, null)
            val syms = try { ex.symbols() } catch (e: Throwable) { println("$id unreachable ${e.message}"); continue }
            val p = if (id == "hyperliquid") SymPair("UBTC", "USDC") else SymPair("BTC", "USDT")
            assertTrue("$id has $p", syms.contains(p))
            assertTrue(ex.ticker(p).last.signum() > 0); assertTrue(ex.orderBook(p, 10).bids.isNotEmpty())
            assertTrue(ex.klines(p, "15m").size > 20); assertTrue(ex.recentTrades(p).isNotEmpty())
            val i = ex.symbolInfo(p); println("$id $p tick=${i.tick} step=${i.step} syms=${syms.size}") }
    }
    @Test fun liveStreams() = runBlocking {
        for ((id, p) in listOf("hyperliquid" to SymPair("UBTC", "USDC"), "aster" to SymPair("BTC", "USDT"), "binance" to SymPair("BTC", "USDT"), "okx" to SymPair("BTC", "USDT"))) {
            val ex = Exchanges.create(id, null); runCatching { ex.symbols() }
            val evs = ArrayList<MarketEvent>()
            val err = runCatching { kotlinx.coroutines.withTimeoutOrNull(12_000) { ex.stream(p, "1m")!!.take(15).collect { evs.add(it) } } }.exceptionOrNull()
            println("WS $id: ${evs.groupingBy { it::class.simpleName }.eachCount()} ${err?.message ?: ""}")
        }
    }
}
