package com.morris.spottrader.ui

import org.json.JSONArray
import org.json.JSONObject

/** One indicator with user-editable parameters (KLineChart calcParams). */
data class IndCfg(val name: String, val params: List<Double>, val main: Boolean, val on: Boolean) {
    val paramText get() = params.joinToString(",") { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
}

/** Chart settings remembered per symbol across restarts (drawings are stored separately as KLineChart overlay JSON). */
data class ChartCfg(val type: String = "candle_solid", val inds: List<IndCfg> = DEFAULT_INDS, val bidAsk: Boolean = true) {
    fun toJson(): String = JSONObject().put("type", type).put("bidAsk", bidAsk).put("inds", JSONArray().apply { inds.forEach { i ->
        put(JSONObject().put("n", i.name).put("m", i.main).put("on", i.on).put("p", JSONArray().apply { i.params.forEach { put(it) } })) } }).toString()
    fun jsIndicators(): String = JSONArray().apply { inds.filter { it.on }.forEach { i -> put(JSONObject().put("name", i.name).put("main", i.main).put("params", JSONArray().apply { i.params.forEach { put(it) } })) } }.toString()
    fun toggle(name: String) = copy(inds = inds.map { if (it.name == name) it.copy(on = !it.on) else it })
    fun withParams(name: String, p: List<Double>) = copy(inds = inds.map { if (it.name == name) it.copy(params = p) else it })
    companion object {
        val TYPES = listOf("candle_solid" to "蜡烛图", "candle_stroke" to "空心蜡烛", "ohlc" to "美国线", "area" to "折线/面积")
        val DEFAULT_INDS = listOf(
            IndCfg("MA", listOf(7.0, 25.0, 99.0), true, true), IndCfg("EMA", listOf(12.0, 26.0), true, false),
            IndCfg("BOLL", listOf(20.0, 2.0), true, false), IndCfg("SAR", listOf(2.0, 2.0, 20.0), true, false),
            IndCfg("VOL", listOf(5.0, 10.0, 20.0), false, true), IndCfg("MACD", listOf(12.0, 26.0, 9.0), false, false),
            IndCfg("RSI", listOf(14.0), false, false), IndCfg("KDJ", listOf(9.0, 3.0, 3.0), false, false),
            IndCfg("WR", listOf(14.0), false, false), IndCfg("CCI", listOf(20.0), false, false), IndCfg("OBV", listOf(30.0), false, false))
        fun parse(s: String?): ChartCfg { if (s.isNullOrBlank()) return ChartCfg()
            return runCatching { val o = JSONObject(s); val a = o.getJSONArray("inds")
                val saved = (0 until a.length()).map { val x = a.getJSONObject(it); val p = x.getJSONArray("p")
                    IndCfg(x.getString("n"), (0 until p.length()).map { j -> p.getDouble(j) }, x.getBoolean("m"), x.getBoolean("on")) }.associateBy { it.name }
                ChartCfg(o.optString("type", "candle_solid"), DEFAULT_INDS.map { saved[it.name] ?: it }, o.optBoolean("bidAsk", true)) }.getOrDefault(ChartCfg()) }
        fun parseParams(t: String): List<Double>? = t.split(",", "，", " ").filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: return null }.takeIf { it.isNotEmpty() }
    }
}
