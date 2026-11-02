package com.morris.spottrader.ex

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Minimal MessagePack encoder (same output as python msgpack.packb for str/int/bool/nil/map/list), used for Hyperliquid action hashing. */
object MsgPack {
    fun pack(v: Any?): ByteArray = ByteArrayOutputStream().also { w(it, v) }.toByteArray()
    private fun be(o: ByteArrayOutputStream, x: Long, n: Int) { for (i in n - 1 downTo 0) o.write(((x ushr (8 * i)) and 0xff).toInt()) }
    private fun w(o: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> o.write(0xc0)
            is Boolean -> o.write(if (v) 0xc3 else 0xc2)
            is Int -> w(o, v.toLong())
            is Long -> when {
                v in 0..127 -> o.write(v.toInt())
                v in -32..-1 -> o.write((v and 0xff).toInt())
                v > 0 && v <= 0xff -> { o.write(0xcc); be(o, v, 1) }
                v > 0 && v <= 0xffff -> { o.write(0xcd); be(o, v, 2) }
                v > 0 && v <= 0xffffffffL -> { o.write(0xce); be(o, v, 4) }
                v > 0 -> { o.write(0xcf); be(o, v, 8) }
                v >= -128 -> { o.write(0xd0); be(o, v, 1) }
                v >= -32768 -> { o.write(0xd1); be(o, v, 2) }
                v >= Int.MIN_VALUE -> { o.write(0xd2); be(o, v, 4) }
                else -> { o.write(0xd3); be(o, v, 8) } }
            is String -> { val b = v.toByteArray(Charsets.UTF_8); val n = b.size
                when { n < 32 -> o.write(0xa0 or n); n <= 0xff -> { o.write(0xd9); be(o, n.toLong(), 1) }; n <= 0xffff -> { o.write(0xda); be(o, n.toLong(), 2) }; else -> { o.write(0xdb); be(o, n.toLong(), 4) } }
                o.write(b) }
            is Map<*, *> -> { val n = v.size
                if (n < 16) o.write(0x80 or n) else if (n <= 0xffff) { o.write(0xde); be(o, n.toLong(), 2) } else { o.write(0xdf); be(o, n.toLong(), 4) }
                v.forEach { (k, x) -> w(o, k); w(o, x) } }
            is List<*> -> { val n = v.size
                if (n < 16) o.write(0x90 or n) else if (n <= 0xffff) { o.write(0xdc); be(o, n.toLong(), 2) } else { o.write(0xdd); be(o, n.toLong(), 4) }
                v.forEach { w(o, it) } }
            else -> error("msgpack: unsupported ${v::class}")
        }
    }
}

/** Ethereum-style crypto: keccak256, secp256k1 RFC6979 signing with recovery id, EIP-712 for Hyperliquid L1 actions. */
object EthSign {
    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val DOM = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
    data class Sig(val r: BigInteger, val s: BigInteger, val v: Int) {
        fun json(): JSONObject = JSONObject().put("r", "0x" + r.toString(16)).put("s", "0x" + s.toString(16)).put("v", v)
    }
    fun keccak(vararg parts: ByteArray): ByteArray { val d = KeccakDigest(256); parts.forEach { d.update(it, 0, it.size) }; return ByteArray(32).also { d.doFinal(it, 0) } }
    fun hexToBytes(h: String): ByteArray { val s = h.removePrefix("0x").let { if (it.length % 2 == 1) "0$it" else it }; return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    fun pad32(b: BigInteger): ByteArray { val raw = b.toByteArray(); val out = ByteArray(32); val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw; System.arraycopy(src, 0, out, 32 - src.size, src.size); return out }
    private fun pub(d: BigInteger): ECPoint = DOM.g.multiply(d).normalize()
    fun address(privHex: String): String { val q = pub(BigInteger(1, hexToBytes(privHex))).getEncoded(false)
        return "0x" + Sign.hex(keccak(q.copyOfRange(1, q.size)).copyOfRange(12, 32)) }

    fun sign(hash: ByteArray, privHex: String): Sig {
        val d = BigInteger(1, hexToBytes(privHex))
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest())); signer.init(true, ECPrivateKeyParameters(d, DOM))
        val rs = signer.generateSignature(hash); val r = rs[0]; var s = rs[1]
        if (s > DOM.n.shiftRight(1)) s = DOM.n - s
        val q = pub(d)
        for (rec in 0..1) if (recover(rec, r, s, hash)?.equals(q) == true) return Sig(r, s, 27 + rec)
        error("recovery id not found")
    }
    private fun recover(recId: Int, r: BigInteger, s: BigInteger, hash: ByteArray): ECPoint? {
        val n = DOM.n; val x = r
        val conv = X9IntegerConverter(); val enc = conv.integerToBytes(x, 1 + conv.getByteLength(DOM.curve))
        enc[0] = (if (recId and 1 == 1) 0x03 else 0x02).toByte()
        val R = runCatching { DOM.curve.decodePoint(enc) }.getOrNull() ?: return null
        if (!R.multiply(n).isInfinity) return null
        val e = BigInteger(1, hash); val eInv = BigInteger.ZERO.subtract(e).mod(n); val rInv = r.modInverse(n)
        return ECAlgorithms.sumOfTwoMultiplies(DOM.g, rInv.multiply(eInv).mod(n), R, rInv.multiply(s).mod(n)).normalize()
    }

    /** Hyperliquid action hash = keccak(msgpack(action) ‖ nonce(8 BE) ‖ vault flag[+addr] [‖ 0x00 ‖ expiresAfter]). */
    fun actionHash(action: Map<String, Any?>, vault: String?, nonce: Long, expiresAfter: Long? = null): ByteArray {
        val o = ByteArrayOutputStream(); o.write(MsgPack.pack(action))
        for (i in 7 downTo 0) o.write(((nonce ushr (8 * i)) and 0xff).toInt())
        if (vault == null) o.write(0) else { o.write(1); o.write(hexToBytes(vault)) }
        if (expiresAfter != null) { o.write(0); for (i in 7 downTo 0) o.write(((expiresAfter ushr (8 * i)) and 0xff).toInt()) }
        return keccak(o.toByteArray())
    }
    /** EIP-712 typed hash of Agent{source,connectionId} on domain Exchange/1/chainId 1337/0x0. */
    fun agentDigest(connectionId: ByteArray, mainnet: Boolean): ByteArray {
        val domType = keccak("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".toByteArray())
        val domSep = keccak(domType, keccak("Exchange".toByteArray()), keccak("1".toByteArray()), pad32(BigInteger.valueOf(1337)), ByteArray(32))
        val agentType = keccak("Agent(string source,bytes32 connectionId)".toByteArray())
        val struct = keccak(agentType, keccak((if (mainnet) "a" else "b").toByteArray()), connectionId)
        return keccak(byteArrayOf(0x19, 0x01), domSep, struct)
    }
    fun signL1(action: Map<String, Any?>, privHex: String, nonce: Long, mainnet: Boolean = true, vault: String? = null): Sig =
        sign(agentDigest(actionHash(action, vault, nonce), mainnet), privHex)
}

/**
 * Hyperliquid spot (L1 order book). Creds: apiKey = main account address (0x…), secret = API-wallet (agent) private key.
 * Spot asset id = 10000 + universe index; coin name for info/ws = "PURR/USDC" or "@{index}".
 */
class Hyperliquid(private val c: Creds?) : SpotExchange {
    override val id = "hyperliquid"; override val display = "Hyperliquid"
    override val features = setOf(Feature.POST_ONLY, Feature.IOC)
    data class Mkt(val index: Int, val coin: String, val pair: SymPair, val szDecimals: Int)
    companion object {
        private const val API = "https://api.hyperliquid.xyz"
        @Volatile private var cache: List<Mkt>? = null
        /** parses spotMeta -> markets */
        fun parseMeta(o: JSONObject): List<Mkt> {
            val toks = o.getJSONArray("tokens"); val byIdx = HashMap<Int, JSONObject>()
            for (i in 0 until toks.length()) toks.getJSONObject(i).let { byIdx[it.getInt("index")] = it }
            val u = o.getJSONArray("universe")
            return (0 until u.length()).mapNotNull { val x = u.getJSONObject(it); val t = x.getJSONArray("tokens")
                val b = byIdx[t.getInt(0)] ?: return@mapNotNull null; val q = byIdx[t.getInt(1)] ?: return@mapNotNull null
                Mkt(x.getInt("index"), x.getString("name"), SymPair(b.getString("name"), q.getString("name")), b.optInt("szDecimals", 2)) }
        }
        /** float_to_wire: ≤8 decimals, trailing zeros stripped */
        fun wire(x: BigDecimal): String = x.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
        /** spot price rule: ≤5 significant figures and ≤ (8 - szDecimals) decimals (integers always allowed) */
        fun roundPx(px: BigDecimal, szDecimals: Int): BigDecimal {
            if (px.signum() <= 0) return px
            val sig = if (px >= BigDecimal(100000)) px.setScale(0, RoundingMode.HALF_UP) else px.round(MathContext(5, RoundingMode.HALF_UP))
            return sig.setScale(minOf(maxOf(sig.scale(), 0), 8 - szDecimals), RoundingMode.HALF_UP).stripTrailingZeros()
        }
        fun orderAction(asset: Int, buy: Boolean, px: BigDecimal, sz: BigDecimal, tif: String, reduceOnly: Boolean = false): Map<String, Any?> =
            linkedMapOf("type" to "order", "orders" to listOf(linkedMapOf("a" to asset, "b" to buy, "p" to wire(px), "s" to wire(sz), "r" to reduceOnly,
                "t" to linkedMapOf("limit" to linkedMapOf("tif" to tif)))), "grouping" to "na")
        fun toJson(v: Any?): Any? = when (v) { is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k as String, toJson(x) ?: JSONObject.NULL) } }
            is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }; else -> v }
    }

    private suspend fun info(body: JSONObject): String = Http.call("POST", "$API/info", body = body.toString())
    private fun q(type: String, vararg kv: kotlin.Pair<String, Any>) = JSONObject().put("type", type).also { o -> kv.forEach { o.put(it.first, it.second) } }
    private suspend fun markets(): List<Mkt> = cache ?: parseMeta(JSONObject(info(q("spotMeta")))).also { cache = it }
    private suspend fun mkt(p: SymPair): Mkt = markets().firstOrNull { it.pair == p } ?: throw ApiException("Hyperliquid 无此现货币对 $p")
    private suspend fun byCoin(coin: String): Mkt? = markets().firstOrNull { it.coin == coin }
    private fun user(): String { val cr = c ?: throw ApiException("未配置钱包"); return cr.apiKey.ifBlank { EthSign.address(cr.secret) }.lowercase() }

    override suspend fun ticker(p: SymPair): Ticker {
        val m = mkt(p); val a = JSONArray(info(q("spotMetaAndAssetCtxs"))).getJSONArray(1)
        val x = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("coin") == m.coin } ?: throw ApiException("no ctx")
        val last = x.optString("midPx").takeIf { it.isNotEmpty() && it != "null" }?.bd() ?: x.getString("markPx").bd()
        val prev = x.optString("prevDayPx").bd()
        val day = runCatching { klinesRaw(m, "1d", System.currentTimeMillis() - 86_400_000L * 2, System.currentTimeMillis()).lastOrNull() }.getOrNull()
        return Ticker(last, if (prev.signum() > 0) (last - prev) * BigDecimal(100) / prev else BigDecimal.ZERO,
            day?.h?.toBigDecimal() ?: last, day?.l?.toBigDecimal() ?: last, x.optString("dayBaseVlm").bd())
    }
    override suspend fun orderBook(p: SymPair, depth: Int): OrderBook = (WsParse.hyperliquid(JSONObject().put("channel", "l2Book").put("data", JSONObject(info(q("l2Book", "coin" to mkt(p).coin)))).toString()).first() as MarketEvent.Book).b
        .let { OrderBook(it.bids.take(depth), it.asks.take(depth)) }
    private suspend fun klinesRaw(m: Mkt, iv: String, start: Long, end: Long): List<Kline> {
        val a = JSONArray(info(q("candleSnapshot", "req" to JSONObject().put("coin", m.coin).put("interval", iv).put("startTime", start).put("endTime", end))))
        return (0 until a.length()).map { val x = a.getJSONObject(it); Kline(x.getLong("t"), x.getString("o").toDouble(), x.getString("h").toDouble(), x.getString("l").toDouble(), x.getString("c").toDouble(), x.optString("v").toDoubleOrNull() ?: 0.0) }.sortedBy { it.t }
    }
    override suspend fun klines(p: SymPair, interval: String, endTime: Long?): List<Kline> {
        val end = endTime?.minus(1) ?: System.currentTimeMillis(); return klinesRaw(mkt(p), interval, end - TF.ms(interval) * 200, end)
    }
    override suspend fun symbolInfo(p: SymPair): SymbolInfo { val m = mkt(p)
        val px = runCatching { JSONObject(info(q("allMids"))).optString(m.coin).bd() }.getOrDefault(BigDecimal.ZERO)
        // tick from the 5-significant-figure rule at the current price, capped by 8 - szDecimals decimals
        val dp = if (px.signum() > 0) (5 - (px.setScale(0, RoundingMode.DOWN).toPlainString().trimStart('-').let { if (it == "0") 0 else it.length })).coerceIn(0, 8 - m.szDecimals) else 8 - m.szDecimals
        return SymbolInfo(SymbolInfo.dp(dp), SymbolInfo.dp(m.szDecimals), SymbolInfo.dp(m.szDecimals), BigDecimal(10), BigDecimal("0.0004"), BigDecimal("0.0007")) }
    override suspend fun symbols() = markets().map { it.pair }.distinct()
    override suspend fun lastPrices(): Map<String, BigDecimal> { val o = JSONObject(info(q("allMids"))); val ms = markets().associateBy { it.coin }
        return o.keys().asSequence().mapNotNull { k -> ms[k]?.let { Quotes.key(it.pair.toString()) to o.getString(k).bd() } }.toMap() }
    override suspend fun recentTrades(p: SymPair): List<PublicTrade> = (WsParse.hyperliquid(JSONObject().put("channel", "trades").put("data", JSONArray(info(q("recentTrades", "coin" to mkt(p).coin)))).toString()).first() as MarketEvent.Trades).t
    override suspend fun balances(): List<Balance> { val a = JSONObject(info(q("spotClearinghouseState", "user" to user()))).optJSONArray("balances") ?: JSONArray()
        return (0 until a.length()).map { val x = a.getJSONObject(it); val tot = x.getString("total").bd(); val hold = x.optString("hold").bd(); Balance(x.getString("coin"), tot - hold, hold) }.filter { it.total.signum() > 0 } }

    private suspend fun exchange(action: Map<String, Any?>): JSONObject {
        val cr = c ?: throw ApiException("未配置钱包"); val nonce = System.currentTimeMillis()
        val sig = EthSign.signL1(action, cr.secret, nonce)
        val body = JSONObject().put("action", toJson(action)).put("nonce", nonce).put("signature", sig.json()).put("vaultAddress", JSONObject.NULL)
        val o = JSONObject(Http.call("POST", "$API/exchange", body = body.toString()))
        if (o.optString("status") != "ok") throw ApiException("Hyperliquid: ${o.opt("response")}")
        return o
    }
    override suspend fun place(r: OrderReq): String {
        val m = mkt(r.pair); val buy = r.side == Side.BUY
        val (px, tif) = when (r.type) {
            OType.LIMIT -> r.price!! to when (r.tif) { TIF.POST_ONLY -> "Alo"; TIF.IOC, TIF.FOK -> "Ioc"; else -> "Gtc" }
            // market = aggressive IOC limit 5% through the mid (Hyperliquid has no native market order)
            OType.MARKET -> { val mid = r.lastPrice ?: JSONObject(info(q("allMids"))).getString(m.coin).bd()
                mid.multiply(if (buy) BigDecimal("1.05") else BigDecimal("0.95")) to "Ioc" }
            OType.STOP_LIMIT -> throw ApiException("Hyperliquid 现货暂不支持条件单（本应用）")
        }
        val pxR = roundPx(px, m.szDecimals)
        val sz = (r.qty ?: r.quoteQty?.divide(pxR, m.szDecimals, RoundingMode.DOWN) ?: throw ApiException("缺少数量")).setScale(m.szDecimals, RoundingMode.DOWN)
        val st = exchange(orderAction(10000 + m.index, buy, pxR, sz, tif)).getJSONObject("response").getJSONObject("data").getJSONArray("statuses").getJSONObject(0)
        st.optString("error").takeIf { it.isNotEmpty() }?.let { throw ApiException(it) }
        return (st.optJSONObject("resting") ?: st.optJSONObject("filled"))?.get("oid")?.toString() ?: throw ApiException("Hyperliquid: $st")
    }
    override suspend fun cancelOrder(p: SymPair, orderId: String) { val m = mkt(p)
        val st = exchange(linkedMapOf("type" to "cancel", "cancels" to listOf(linkedMapOf("a" to 10000 + m.index, "o" to orderId.toLong()))))
            .getJSONObject("response").getJSONObject("data").getJSONArray("statuses").get(0)
        if (st is JSONObject && st.has("error")) throw ApiException(st.getString("error")) }
    private suspend fun ord(x: JSONObject, status: String): Order? { val m = byCoin(x.getString("coin")) ?: return null
        val orig = x.optString("origSz").bd().takeIf { it.signum() > 0 } ?: x.getString("sz").bd(); val left = x.getString("sz").bd()
        return Order(x.get("oid").toString(), "${m.pair.base}/${m.pair.quote}", if (x.optString("side") == "B") Side.BUY else Side.SELL, x.optString("orderType", "Limit") + (x.optString("tif").takeIf { it.isNotEmpty() && it != "null" }?.let { "/$it" } ?: ""),
            x.getString("limitPx").bd(), orig, orig - left, status, x.optLong("timestamp")) }
    override suspend fun openOrders(p: SymPair?): List<Order> { val a = JSONArray(info(q("frontendOpenOrders", "user" to user())))
        return (0 until a.length()).mapNotNull { ord(a.getJSONObject(it), "open") }.filter { p == null || it.symbol == "${p.base}/${p.quote}" } }
    override suspend fun orderHistory(p: SymPair): List<Order> { val a = JSONArray(info(q("historicalOrders", "user" to user())))
        return (0 until a.length()).mapNotNull { val x = a.getJSONObject(it); ord(x.getJSONObject("order"), x.optString("status")) }.filter { it.symbol == "${p.base}/${p.quote}" }.sortedByDescending { it.time }.take(100) }
    override suspend fun myTrades(p: SymPair): List<Trade> { val m = mkt(p); val a = JSONArray(info(q("userFills", "user" to user())))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("coin") == m.coin }.map {
            Trade(if (it.optString("side") == "B") Side.BUY else Side.SELL, it.getString("px").bd(), it.getString("sz").bd(), it.optString("fee").bd(), it.optString("feeToken"), it.getLong("time")) } }
    override suspend fun getOrder(p: SymPair, orderId: String): Order {
        val o = JSONObject(info(q("orderStatus", "user" to user(), "oid" to orderId.toLong())))
        val w = o.optJSONObject("order") ?: throw ApiException("Hyperliquid: 订单不存在 $orderId")
        val base = ord(w.getJSONObject("order"), w.optString("status")) ?: throw ApiException("unknown coin")
        val avg = if (base.filled.signum() > 0) runCatching { val a = JSONArray(info(q("userFills", "user" to user())))
            val f = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.opt("oid")?.toString() == orderId }
            val qty = f.fold(BigDecimal.ZERO) { s, x -> s + x.getString("sz").bd() }
            if (qty.signum() > 0) f.fold(BigDecimal.ZERO) { s, x -> s + x.getString("sz").bd() * x.getString("px").bd() }.divide(qty, 12, RoundingMode.HALF_UP) else BigDecimal.ZERO }.getOrDefault(BigDecimal.ZERO) else BigDecimal.ZERO
        return base.copy(avg = if (avg.signum() > 0) avg else base.price)
    }
    /** WS: l2Book + trades + candle on one socket; {"method":"ping"} every 30s (server drops idle >60s). */
    override fun stream(p: SymPair, interval: String): kotlinx.coroutines.flow.Flow<MarketEvent>? {
        val m = cache?.firstOrNull { it.pair == p } ?: return null   // meta loaded by REST bootstrap
        fun sub(o: JSONObject) = JSONObject().put("method", "subscribe").put("subscription", o).toString()
        return Ws.connect("wss://api.hyperliquid.xyz/ws", listOf(sub(JSONObject().put("type", "l2Book").put("coin", m.coin)), sub(JSONObject().put("type", "trades").put("coin", m.coin)),
            sub(JSONObject().put("type", "candle").put("coin", m.coin).put("interval", interval))), """{"method":"ping"}""", 30_000, WsParse::hyperliquid)
    }
}
