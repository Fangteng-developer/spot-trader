package com.morris.spottrader.ex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SymPair(val base: String, val quote: String) { override fun toString() = "$base/$quote" }
data class Balance(val asset: String, val free: BigDecimal, val locked: BigDecimal) { val total get() = free + locked }
data class Ticker(val last: BigDecimal, val changePct: BigDecimal, val high: BigDecimal, val low: BigDecimal, val vol: BigDecimal)
data class Level(val price: BigDecimal, val qty: BigDecimal)
data class OrderBook(val bids: List<Level>, val asks: List<Level>)
data class Kline(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0)
enum class Side { BUY, SELL }
enum class OType { LIMIT, MARKET, STOP_LIMIT }
enum class TIF { GTC, IOC, FOK, POST_ONLY }
enum class Feature { STOP_LIMIT, POST_ONLY, IOC, FOK }
data class PublicTrade(val price: BigDecimal, val qty: BigDecimal, val side: Side, val time: Long)
/** qty in base; quoteQty used for market buys by quote. trigger for STOP_LIMIT. */
data class OrderReq(val pair: SymPair, val side: Side, val type: OType, val qty: BigDecimal?, val quoteQty: BigDecimal?, val price: BigDecimal?,
                    val trigger: BigDecimal? = null, val tif: TIF = TIF.GTC, val lastPrice: BigDecimal? = null)
data class SymbolInfo(val tick: BigDecimal, val step: BigDecimal, val minQty: BigDecimal = BigDecimal.ZERO, val minNotional: BigDecimal = BigDecimal.ZERO,
                      val maker: BigDecimal = BigDecimal("0.001"), val taker: BigDecimal = BigDecimal("0.001")) {
    val priceDp get() = maxOf(0, tick.stripTrailingZeros().scale())
    val qtyDp get() = maxOf(0, step.stripTrailingZeros().scale())
    fun roundPrice(p: BigDecimal): BigDecimal = if (tick.signum() <= 0) p else p.divide(tick, 0, java.math.RoundingMode.HALF_UP).multiply(tick).setScale(priceDp, java.math.RoundingMode.HALF_UP)
    fun roundQty(q: BigDecimal): BigDecimal = if (step.signum() <= 0) q else q.divide(step, 0, java.math.RoundingMode.DOWN).multiply(step).setScale(qtyDp, java.math.RoundingMode.DOWN)
    companion object { fun dp(n: Int): BigDecimal = BigDecimal.ONE.movePointLeft(n); val DEFAULT = SymbolInfo(BigDecimal("0.01"), BigDecimal("0.000001")) }
}
data class Order(val id: String, val symbol: String, val side: Side, val type: String, val price: BigDecimal, val qty: BigDecimal, val filled: BigDecimal, val status: String, val time: Long, val avg: BigDecimal = BigDecimal.ZERO) {
    /** true while the order can still fill */
    val isOpen get() = status.lowercase().replace("_", "") in setOf("new", "live", "open", "partiallyfilled", "untriggered")
}
data class Trade(val side: Side, val price: BigDecimal, val qty: BigDecimal, val fee: BigDecimal, val feeAsset: String, val time: Long)

/** Credentials. For Binance RSA, rsaPrivatePkcs8 holds decoded private key bytes (in memory only). */
class Creds(val apiKey: String, val secret: String, val passphrase: String = "", val rsaPrivatePkcs8: ByteArray? = null)

/** Quote-currency helpers shared by UI, VM and exchange adapters. */
object Quotes {
    val STABLE = setOf("USDT", "USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USD1", "USDE", "PYUSD", "USDD", "RLUSD")
    /** known quote assets, longest first, used only when an exchange symbol string has no separator and is not in the loaded symbol list */
    val KNOWN = listOf("FDUSD", "PYUSD", "RLUSD", "USDT", "USDC", "BUSD", "TUSD", "USDE", "USDD", "USD1", "EUR", "TRY", "BRL", "GBP", "DAI", "BTC", "ETH", "BNB", "TRX").sortedByDescending { it.length }
    val POPULAR = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "TRX", "LINK", "SUI", "AVAX", "DOT", "LTC", "BCH", "NEAR", "APT", "ARB", "OP", "UNI",
        "AAVE", "PEPE", "SHIB", "WIF", "FIL", "ATOM", "INJ", "TIA", "SEI", "ENA", "WLD", "FET", "RNDR", "ETC", "XLM", "HBAR", "ICP", "ORDI", "JUP", "ASTER")
    /** offline fallback if the exchange symbol list cannot be loaded */
    fun fallback(): List<SymPair> = POPULAR.flatMap { listOf(SymPair(it, "USDT")) } + POPULAR.take(15).map { SymPair(it, "USDC") }
    fun key(symbol: String) = symbol.replace("-", "").replace("_", "").replace("/", "").uppercase()
    fun guess(symbol: String): SymPair? {
        val s = symbol.uppercase()
        for (sep in listOf("-", "_", "/")) if (s.contains(sep)) { val a = s.split(sep); if (a.size == 2 && a.all { it.isNotBlank() }) return SymPair(a[0], a[1]) }
        for (q in KNOWN) if (s.endsWith(q) && s.length > q.length) return SymPair(s.removeSuffix(q), q)
        return null
    }
}

class ApiException(msg: String) : Exception(msg)

interface SpotExchange {
    val id: String
    val display: String
    val needsPassphrase: Boolean get() = false
    val features: Set<Feature>
    suspend fun ticker(p: SymPair): Ticker
    suspend fun orderBook(p: SymPair, depth: Int = 20): OrderBook
    /** endTime (ms, exclusive) = load older bars ending before it (history paging) */
    suspend fun klines(p: SymPair, interval: String = "15m", endTime: Long? = null): List<Kline>
    suspend fun symbolInfo(p: SymPair): SymbolInfo
    /** all tradable spot pairs (real list from the exchange's symbol endpoint) */
    suspend fun symbols(): List<SymPair>
    /** bulk last prices keyed by Quotes.key(symbol), e.g. "BTCUSDC" */
    suspend fun lastPrices(): Map<String, BigDecimal>
    suspend fun recentTrades(p: SymPair): List<PublicTrade>
    suspend fun balances(): List<Balance>
    suspend fun place(r: OrderReq): String
    /** ids prefixed "algo:"/"stop:" denote conditional orders */
    suspend fun cancelOrder(p: SymPair, orderId: String)
    suspend fun cancelAll(p: SymPair) { openOrders(p).forEach { runCatching { cancelOrder(p, it.id) } } }
    suspend fun openOrders(p: SymPair?): List<Order>
    suspend fun orderHistory(p: SymPair): List<Order>
    suspend fun myTrades(p: SymPair): List<Trade>
    /** Single order incl. filled qty + avg fill price (used by chase engine). */
    suspend fun getOrder(p: SymPair, orderId: String): Order
    /** Exchange-native OCO sell (take-profit maker limit + stop-loss stop-limit). Returns list id, or null when the exchange has no spot OCO (app-side monitoring is used instead). */
    suspend fun placeOcoSell(p: SymPair, qty: BigDecimal, tp: BigDecimal, slTrigger: BigDecimal, slLimit: BigDecimal): String? = null
    suspend fun cancelOco(p: SymPair, listId: String) {}
    /** true = OCO finished (one leg filled / cancelled), false = still working, null = unknown */
    suspend fun ocoDone(p: SymPair, listId: String): Boolean? = null
    val supportsOco: Boolean get() = false
    /** Live market data over WebSocket; null = not supported (REST polling only). Completes/throws on disconnect; caller reconnects. */
    fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent>? = null
}

/** Map app timeframe to exchange-specific values. */
object TF { val all = listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w")
    /** MT5 timeframe labels -> exchange interval codes. */
    val mt5 = listOf("M1" to "1m", "M5" to "5m", "M15" to "15m", "M30" to "30m", "H1" to "1h", "H4" to "4h", "D1" to "1d", "W1" to "1w", "MN" to "1M")
    fun label(iv: String) = mt5.firstOrNull { it.second == iv }?.first ?: iv
    fun ms(iv: String): Long = when (iv) { "1m" -> 60_000L; "5m" -> 300_000L; "15m" -> 900_000L; "30m" -> 1_800_000L; "1h" -> 3_600_000L; "4h" -> 14_400_000L; "1d" -> 86_400_000L; "1w" -> 604_800_000L; "1M" -> 2_592_000_000L; else -> 900_000L } }

object Http {
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    suspend fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, form: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            val rb = body?.toRequestBody(if (form) "application/x-www-form-urlencoded".toMediaType() else JSON)
            when (method) {
                "GET" -> b.get()
                "DELETE" -> if (rb != null) b.delete(rb) else b.delete()
                else -> b.method(method, rb ?: "".toRequestBody(JSON))
            }
            client.newCall(b.build()).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException("HTTP ${r.code}: ${s.take(300)}")
                s
            }
        }
}

object Sign {
    fun hmac(alg: String, key: String, data: String): ByteArray =
        Mac.getInstance(alg).run { init(SecretKeySpec(key.toByteArray(), alg)); doFinal(data.toByteArray()) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun sha512Hex(s: String) = hex(MessageDigest.getInstance("SHA-512").digest(s.toByteArray()))
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun query(m: Map<String, String>) = m.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
}

fun String.bd(): BigDecimal = if (isBlank()) BigDecimal.ZERO else try { BigDecimal(this) } catch (e: Exception) { BigDecimal.ZERO }
fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()
fun jsonObj(vararg kv: kotlin.Pair<String, Any?>): String {
    val o = org.json.JSONObject(); kv.forEach { (k, v) -> if (v != null) o.put(k, v) }; return o.toString()
}

object Exchanges {
    val all: List<String> = listOf("binance", "okx", "gate", "bitget", "bybit", "mexc", "aster", "hyperliquid")
    val names = mapOf("binance" to "Binance", "okx" to "OKX", "gate" to "Gate.io", "bitget" to "Bitget", "bybit" to "Bybit", "mexc" to "MEXC", "aster" to "Aster", "hyperliquid" to "Hyperliquid")
    fun create(id: String, c: Creds?): SpotExchange = when (id) {
        "binance" -> BinanceLike("binance", "Binance", "https://api.binance.com", "X-MBX-APIKEY", c, wsBase = "wss://stream.binance.com:9443")
        "aster" -> BinanceLike("aster", "Aster", "https://sapi.asterdex.com", "X-MBX-APIKEY", c, pv = "/api/v1", wsBase = "wss://sstream.asterdex.com")
        "hyperliquid" -> Hyperliquid(c)
        "mexc" -> BinanceLike("mexc", "MEXC", "https://api.mexc.com", "X-MEXC-APIKEY", c)
        "okx" -> Okx(c)
        "gate" -> Gate(c)
        "bitget" -> Bitget(c)
        "bybit" -> Bybit(c)
        else -> error("unknown")
    }
    fun needsPassphrase(id: String) = id == "okx" || id == "bitget"
}

/** Average-cost from chronological trades (moving average; sells reduce qty at avg cost). */
object CostCalc {
    data class Res(val qty: BigDecimal, val avg: BigDecimal, val realized: BigDecimal)
    fun compute(trades: List<Trade>, baseAsset: String): Res {
        var q = BigDecimal.ZERO; var cost = BigDecimal.ZERO; var realized = BigDecimal.ZERO
        for (t in trades.sortedBy { it.time }) {
            val feeBase = if (t.feeAsset.equals(baseAsset, true)) t.fee else BigDecimal.ZERO
            if (t.side == Side.BUY) {
                cost += t.price * t.qty; q += t.qty - feeBase
            } else {
                val avg = if (q.signum() > 0) cost.divide(q, 18, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO
                val sold = t.qty.min(q)
                realized += (t.price - avg) * sold
                cost -= avg * sold; q -= sold
            }
        }
        val avg = if (q.signum() > 0) cost.divide(q, 12, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO
        return Res(q, avg, realized)
    }
}
