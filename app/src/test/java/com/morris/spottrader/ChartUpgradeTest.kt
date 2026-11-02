package com.morris.spottrader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.morris.spottrader.ex.*
import com.morris.spottrader.pos.*
import com.morris.spottrader.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

private fun withTpSl(vm: AppVM): AppVM { val last = vm.ticker!!.last
    TpSlManager.rules.clear()
    TpSlManager.rules.add(TpSlRule("t1", "binance", "BTC", "USDT", BigDecimal("0.1832"), (last + BigDecimal(1400)).setScale(2, RoundingMode.HALF_UP), (last - BigDecimal(1700)).setScale(2, RoundingMode.HALF_UP), native = true, nativeId = "9001", status = "交易所OCO #9001"))
    return vm }

class RiskAndPositionLogicTest {
    private val info = SymbolInfo(BigDecimal("0.01"), BigDecimal("0.00001"), BigDecimal("0.00001"), BigDecimal("5"), BigDecimal("0.001"), BigDecimal("0.001"))
    @Test fun riskToQty() { // 10000 * 1% = 100 risk; per unit = 1000 + (50000+49000)*0.001 = 1099 -> 0.09099
        val r = RiskCalc.size(BigDecimal(10000), BigDecimal(1), BigDecimal(50000), BigDecimal(49000), info, BigDecimal(53000))
        assertNull(r.error); assertEquals(BigDecimal("0.09099"), r.qty); assertTrue(r.actualRisk <= r.riskAmount); assertEquals(BigDecimal("3.00"), r.rr) }
    @Test fun cappedByBalance() { val r = RiskCalc.size(BigDecimal(1000), BigDecimal(50), BigDecimal(100), BigDecimal("99.9"), info.copy(step = BigDecimal("0.01")))
        assertTrue(r.cappedByBalance); assertTrue(r.notional <= BigDecimal(1000)) }
    @Test fun stopAboveEntryRejected() { assertNotNull(RiskCalc.size(BigDecimal(1000), BigDecimal(1), BigDecimal(100), BigDecimal(101), info).error) }
    @Test fun minNotional() { assertNotNull(RiskCalc.size(BigDecimal(100), BigDecimal("0.1"), BigDecimal(100), BigDecimal(50), info).error) }
    @Test fun spotPositionPnl() {
        val t = listOf(Trade(Side.BUY, BigDecimal(100), BigDecimal(2), BigDecimal.ZERO, "USDT", 1), Trade(Side.BUY, BigDecimal(130), BigDecimal(1), BigDecimal.ZERO, "USDT", 2),
            Trade(Side.SELL, BigDecimal(150), BigDecimal(1), BigDecimal("0.15"), "USDT", 3))
        val p = SpotPosition.of(t, "BTC", "USDT")
        assertEquals(0, p.qty.compareTo(BigDecimal(2))); assertEquals(0, p.avgEntry.compareTo(BigDecimal(110))); assertEquals(0, p.realized.compareTo(BigDecimal("39.85")))
        assertEquals(0, p.unrealized(BigDecimal(120)).compareTo(BigDecimal(20))) }
    @Test fun tpSlTrigger() { val r = TpSlRule("1", "okx", "BTC", "USDT", BigDecimal.ONE, BigDecimal(110), BigDecimal(90))
        assertEquals("TP", TpSlRule.hit(r, BigDecimal(110))); assertEquals("SL", TpSlRule.hit(r, BigDecimal("89.9"))); assertNull(TpSlRule.hit(r, BigDecimal(100)))
        assertEquals(r, TpSlRule.fromJson(r.toJson())) }
    @Test fun modifyOrderPlanRespectsTickAndPostOnly() { val o = Order("1", "BTCUSDT", Side.BUY, "LIMIT", BigDecimal("100.00"), BigDecimal("0.5"), BigDecimal("0.1"), "NEW", 0)
        val (req, _) = AppVM.modifyPlan(o, BigDecimal("101.234"), info, true, BigDecimal("101.5"), BigDecimal("101.6"), SymPair("BTC", "USDT"))
        assertEquals(BigDecimal("101.23"), req!!.price); assertEquals(TIF.POST_ONLY, req.tif); assertEquals(0, req.qty!!.compareTo(BigDecimal("0.4")))
        assertNull(AppVM.modifyPlan(o, BigDecimal("101.7"), info, true, BigDecimal("101.5"), BigDecimal("101.6"), SymPair("BTC", "USDT")).first) // would cross ask
        assertEquals(TIF.GTC, AppVM.modifyPlan(o, BigDecimal("101.7"), info, false, null, null, SymPair("BTC", "USDT")).first!!.tif) }
    @Test fun chartCfgRoundTrip() { val c = ChartCfg().toggle("MACD").withParams("MA", listOf(5.0, 10.0)).copy(type = "ohlc")
        val p = ChartCfg.parse(c.toJson()); assertEquals(c, p); assertEquals(listOf(5.0, 10.0), ChartCfg.parseParams("5, 10")); assertNull(ChartCfg.parseParams("a,1")) }
    @Test fun mergeKlinesPrependsHistory() { val a = listOf(Kline(3, 1.0, 1.0, 1.0, 1.0)); val b = listOf(Kline(1, 1.0, 1.0, 1.0, 1.0), Kline(3, 2.0, 2.0, 2.0, 2.0))
        val m = AppVM.mergeKlines(a, b); assertEquals(listOf(1L, 3L), m.map { it.t }); assertEquals(2.0, m[1].c, 0.0) }
    @Test fun chartLinesIncludeTpSlAndDraggableOrders() { val vm = withTpSl(fullChartVm()); val l = chartLines(vm, true, true, false)
        assertTrue(l.any { it.id == "tp" && it.drag }); assertTrue(l.any { it.id == "sl" }); assertTrue(l.first { it.id == "ord:11" }.drag); assertTrue(l.any { it.id == "pos" }) }
}

class ChartUpgradePortraitTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 470, screenHeight = 1000, density = Density.HIGH, xdpi = 240, ydpi = 240,
        orientation = ScreenOrientation.PORTRAIT), maxPercentDifference = 1.0)
    @Test fun charttab_drawbar_tpsl() { val vm = withTpSl(fullChartVm()); vm.saveChartCfg(ChartCfg().toggle("BOLL").toggle("MACD"))
        paparazzi.snapshot { AppTheme { Mt5ChartScreen(vm, null, initialDraw = true) } } }
    @Test fun oneclick_risk_tpsl_panel() { val vm = withTpSl(fullChartVm())
        paparazzi.snapshot { AppTheme { Box(Modifier.fillMaxSize().background(Palette.bg).padding(8.dp)) { OneClickPanel(vm, 440.dp, initialRiskOpen = true) } } } }
    @Test fun positions_risk_calculator() { val vm = withTpSl(fullChartVm()); vm.positions = vm.positions.map { if (it.asset == "BTC") it.copy(realized = BigDecimal("312.44")) else it }
        val last = vm.ticker!!.last
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) {
            Column(Modifier.padding(10.dp).background(Palette.panel).padding(8.dp)) { androidx.compose.material3.Text("仓位计算器 · BTC/USDT", color = Palette.text)
                RiskTpSlSection(vm, initStop = (last - BigDecimal(1700)).setScale(2, RoundingMode.HALF_UP).toPlainString(), initTp = (last + BigDecimal(2500)).setScale(2, RoundingMode.HALF_UP).toPlainString()) }
            vm.positions.take(2).forEach { PositionCard(it, onTpSl = {}, rule = TpSlManager.forPair("binance", SymPair(it.asset, it.quote))) } } } } }
    @Test fun trading_small_chart_kline() { val vm = withTpSl(fullChartVm()); paparazzi.snapshot { AppTheme { CompositionLocalProvider(LocalOpenFullChart provides {}) {
        Column(Modifier.fillMaxSize().background(Palette.bg)) { ExchangeBar(vm); PairBar(vm); StatsStrip(vm); ChartCard(vm) } } } } }
    @Test fun licenses_page() { paparazzi.snapshot { AppTheme { LicensesScreen() } } }
}
