package com.morris.spottrader.pos

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.morris.spottrader.chase.ChaseManager
import com.morris.spottrader.ex.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** TP/SL for a spot long: sell [qty] when price >= tp (take profit) or <= sl (stop loss). */
data class TpSlRule(val id: String, val exchangeId: String, val base: String, val quote: String, val qty: BigDecimal,
                    val tp: BigDecimal?, val sl: BigDecimal?, val native: Boolean = false, val nativeId: String? = null,
                    val makerOnly: Boolean = false, val active: Boolean = true, val status: String = "监控中", val created: Long = System.currentTimeMillis()) {
    val pair get() = SymPair(base, quote)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("ex", exchangeId).put("b", base).put("q", quote).put("qty", qty.toPlainString())
        .put("tp", tp?.toPlainString()).put("sl", sl?.toPlainString()).put("n", native).put("nid", nativeId).put("mk", makerOnly).put("a", active).put("st", status).put("c", created)
    companion object {
        fun fromJson(o: JSONObject) = TpSlRule(o.getString("id"), o.getString("ex"), o.getString("b"), o.getString("q"), o.getString("qty").bd(),
            o.optString("tp").takeIf { it.isNotBlank() && it != "null" }?.bd(), o.optString("sl").takeIf { it.isNotBlank() && it != "null" }?.bd(),
            o.optBoolean("n"), o.optString("nid").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("mk"), o.optBoolean("a"), o.optString("st"), o.optLong("c"))
        /** pure trigger check (unit-tested): "TP" / "SL" / null */
        fun hit(r: TpSlRule, last: BigDecimal): String? = when {
            r.tp != null && last >= r.tp -> "TP"
            r.sl != null && last <= r.sl -> "SL"
            else -> null }
    }
}

/**
 * Runs inside the app process; the foreground [com.morris.spottrader.chase.ChaseService] keeps it alive while any rule is active.
 * Binance: both TP+SL -> exchange-native OCO (survives the app being killed). Other exchanges / single-sided rules: app-side monitor,
 * polls last price every ~2s and places the exit order when hit (market, or post-only limit at best ask when 只做Maker is on).
 */
object TpSlManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rules = mutableStateListOf<TpSlRule>()
    var exFactory: ((String) -> SpotExchange)? = null
    private var ctx: Context? = null
    private var loop: Job? = null
    private val infoCache = HashMap<String, SymbolInfo>()

    fun activeCount() = rules.count { it.active }
    fun init(c: Context?, factory: (String) -> SpotExchange) {
        exFactory = factory
        if (c != null && ctx == null) { ctx = c.applicationContext
            val s = ctx!!.getSharedPreferences("tpsl", Context.MODE_PRIVATE).getString("rules", null)
            if (s != null) runCatching { val a = JSONArray(s); rules.clear(); for (i in 0 until a.length()) rules.add(TpSlRule.fromJson(a.getJSONObject(i))) } }
        ensureLoop()
    }
    private fun save() { val a = JSONArray(); rules.forEach { a.put(it.toJson()) }; ctx?.getSharedPreferences("tpsl", Context.MODE_PRIVATE)?.edit()?.putString("rules", a.toString())?.apply() }
    fun forPair(ex: String, p: SymPair) = rules.firstOrNull { it.active && it.exchangeId == ex && it.base == p.base && it.quote == p.quote }
    private fun replace(r: TpSlRule) { val i = rules.indexOfFirst { it.id == r.id }; if (i >= 0) rules[i] = r else rules.add(0, r); save() }

    /** Creates/replaces the pair's rule. Returns a user message. */
    suspend fun set(ex: SpotExchange, info: SymbolInfo, p: SymPair, qty0: BigDecimal, tp0: BigDecimal?, sl0: BigDecimal?, last: BigDecimal?, makerOnly: Boolean, preferNative: Boolean = true): String {
        val qty = info.roundQty(qty0); val tp = tp0?.let(info::roundPrice); val sl = sl0?.let(info::roundPrice)
        if (qty.signum() <= 0) return "数量过小"
        if (tp == null && sl == null) return "请至少设置 TP 或 SL"
        if (tp != null && sl != null && tp <= sl) return "TP 必须高于 SL"
        if (last != null) { if (tp != null && tp <= last) return "TP 须高于现价 ${last.plain()}"; if (sl != null && sl >= last) return "SL 须低于现价 ${last.plain()}" }
        if (info.minNotional.signum() > 0 && sl != null && qty * sl < info.minNotional) return "低于最小下单金额 ${info.minNotional.plain()}（按 SL 价计算）"
        forPair(ex.id, p)?.let { cancel(it, quiet = true) }
        var r = TpSlRule("${System.currentTimeMillis()}", ex.id, p.base, p.quote, qty, tp, sl, makerOnly = makerOnly)
        if (preferNative && ex.supportsOco && tp != null && sl != null) {
            // stop-limit leg: limit slightly below trigger (0.5%) so it fills on a gap, rounded to tick
            val slLimit = info.roundPrice(sl * BigDecimal("0.995"))
            val id = runCatching { ex.placeOcoSell(p, qty, tp, sl, slLimit) }
            id.onSuccess { if (it != null) r = r.copy(native = true, nativeId = it, status = "交易所OCO #$it") }
            id.onFailure { r = r.copy(status = "OCO失败，改为本地监控: ${it.message?.take(80)}") }
        }
        replace(r); ensureLoop(); ChaseManager.updateService()
        return if (r.native) "已提交交易所 OCO 止盈止损" else "TP/SL 已启用（App 后台监控）"
    }
    suspend fun cancel(r: TpSlRule, quiet: Boolean = false): String {
        if (r.native && r.nativeId != null) exFactory?.invoke(r.exchangeId)?.let { e -> runCatching { e.cancelOco(r.pair, r.nativeId) } }
        replace(r.copy(active = false, status = "已取消")); ChaseManager.updateService(); return "TP/SL 已取消"
    }
    /** drag on chart: move TP or SL; native OCO -> cancel + re-place */
    suspend fun move(ex: SpotExchange, info: SymbolInfo, r: TpSlRule, which: String, price: BigDecimal, last: BigDecimal?): String =
        set(ex, info, r.pair, r.qty, if (which == "tp") price else r.tp, if (which == "sl") price else r.sl, last, r.makerOnly, preferNative = r.native || r.tp == null || r.sl == null)
    fun clearInactive() { rules.removeAll { !it.active }; save() }

    private fun ensureLoop() { if (loop?.isActive == true || activeCount() == 0) return
        loop = scope.launch { var n = 0
            while (isActive && activeCount() > 0) {
                val f = exFactory
                if (f != null) rules.filter { it.active }.groupBy { it.exchangeId to it.pair }.forEach { (k, list) ->
                    val e = runCatching { f(k.first) }.getOrNull() ?: return@forEach
                    for (r in list) {
                        if (r.native) { if (n % 5 == 0 && runCatching { e.ocoDone(r.pair, r.nativeId!!) }.getOrNull() == true) { replace(r.copy(active = false, status = "交易所OCO已完成")); ChaseManager.updateService() }; continue }
                        val last = runCatching { e.ticker(r.pair).last }.getOrNull() ?: continue
                        val h = TpSlRule.hit(r, last) ?: continue
                        replace(r.copy(status = "触发 $h @ ${last.plain()}，下单中…")); exit(e, r, h, last)
                    } }
                n++; delay(2000)
            }
            withContext(Dispatchers.Main) { ChaseManager.updateService() } } }

    private suspend fun exit(e: SpotExchange, r: TpSlRule, kind: String, last: BigDecimal) {
        val key = e.id + r.pair; val info = infoCache[key] ?: runCatching { e.symbolInfo(r.pair) }.getOrNull()?.also { infoCache[key] = it } ?: SymbolInfo.DEFAULT
        try {
            val free = runCatching { e.balances().firstOrNull { it.asset == r.base }?.free }.getOrNull()
            val qty = info.roundQty(if (free != null) r.qty.min(free) else r.qty)
            if (qty.signum() <= 0) { replace(r.copy(active = false, status = "触发 $kind，但无可用 ${r.base}")); return }
            val req = if (r.makerOnly) {
                val ask = e.orderBook(r.pair, 5).asks.minByOrNull { it.price }?.price ?: last
                OrderReq(r.pair, Side.SELL, OType.LIMIT, qty, null, info.roundPrice(ask), tif = TIF.POST_ONLY)
            } else OrderReq(r.pair, Side.SELL, OType.MARKET, qty, null, null)
            val id = e.place(req)
            replace(r.copy(active = false, status = "已触发 $kind @ ${last.setScale(info.priceDp, RoundingMode.HALF_UP).plain()} → ${if (r.makerOnly) "只做Maker限价" else "市价"}卖出 #$id"))
        } catch (t: Throwable) { replace(r.copy(status = "触发 $kind 下单失败，将重试: ${t.message?.take(80)}")) }
    }
}
