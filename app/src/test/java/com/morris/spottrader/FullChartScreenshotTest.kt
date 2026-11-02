package com.morris.spottrader

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.morris.spottrader.ex.*
import com.morris.spottrader.ui.*
import org.junit.Rule
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

internal fun fullChartVm(maker: Boolean = false, oneClick: Boolean = false): AppVM {
    KChartEnv.webViewAvailable = false
    val vm = ScreenshotTest().demoVm(); val last = vm.ticker!!.last; val now = 1792000000000L
    vm.openOrders = listOf(
        Order("11", "BTCUSDT", Side.BUY, "LIMIT", (last - BigDecimal(650)).setScale(2, RoundingMode.HALF_UP), BigDecimal("0.015"), BigDecimal.ZERO, "NEW", now),
        Order("12", "BTCUSDT", Side.SELL, "LIMIT", (last + BigDecimal(520)).setScale(2, RoundingMode.HALF_UP), BigDecimal("0.02"), BigDecimal("0.005"), "PARTIALLY_FILLED", now),
        Order("13", "ETHUSDT", Side.SELL, "LIMIT", BigDecimal("3420"), BigDecimal("0.5"), BigDecimal.ZERO, "NEW", now))
    vm.myTrades = listOf(Trade(Side.BUY, last - BigDecimal(1400), BigDecimal("0.12"), BigDecimal.ZERO, "USDT", now - 86_400_000),
        Trade(Side.BUY, last - BigDecimal(700), BigDecimal("0.0632"), BigDecimal.ZERO, "USDT", now - 3_600_000))
    vm.toggleMaker(maker); if (oneClick) vm.enableOneClick(true)
    return vm
}

class FullChartLandscapeTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1000, screenHeight = 470, density = Density.HIGH, xdpi = 240, ydpi = 240,
        orientation = ScreenOrientation.LANDSCAPE), maxPercentDifference = 1.0)
    @Test fun fullchart_landscape_market() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme { FullChartScreen(vm, {}) } } }
    @Test fun fullchart_landscape_maker() { val vm = fullChartVm(maker = true, oneClick = true); paparazzi.snapshot { AppTheme { FullChartScreen(vm, {}) } } }
}

class FullChartPortraitTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 470, screenHeight = 1000, density = Density.HIGH, xdpi = 240, ydpi = 240,
        orientation = ScreenOrientation.PORTRAIT), maxPercentDifference = 1.0)
    @Test fun fullchart_portrait() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme { FullChartScreen(vm, {}) } } }
}

class OneClickLogicTest {
    @Test fun marketUsesAskForBuyAndBidForSell() { val vm = fullChartVm()
        val b = vm.buildQuick(Side.BUY, BigDecimal("0.0012345"), now = 0)!!; val s = vm.buildQuick(Side.SELL, BigDecimal("0.001"), now = 0)!!
        org.junit.Assert.assertEquals(OType.MARKET, b.req.type); org.junit.Assert.assertEquals(vm.bestAsk, b.price); org.junit.Assert.assertEquals(vm.bestBid, s.price)
        org.junit.Assert.assertEquals(BigDecimal("0.00123"), b.req.qty) } // rounded down to lot step
    @Test fun makerPostsAtOwnSide() { val vm = fullChartVm(maker = true)
        val b = vm.buildQuick(Side.BUY, BigDecimal("0.001"), now = 0)!!; val s = vm.buildQuick(Side.SELL, BigDecimal("0.001"), now = 0)!!
        org.junit.Assert.assertEquals(TIF.POST_ONLY, b.req.tif); org.junit.Assert.assertEquals(OType.LIMIT, b.req.type)
        org.junit.Assert.assertEquals(vm.bestBid, b.req.price); org.junit.Assert.assertEquals(vm.bestAsk, s.req.price) }
    @Test fun validatesNotionalAndBalances() { val vm = fullChartVm()
        org.junit.Assert.assertNull(vm.buildQuick(Side.BUY, BigDecimal("0.00001"), now = 0)) // < 5 USDT min notional
        org.junit.Assert.assertNull(vm.buildQuick(Side.SELL, BigDecimal("1"), now = 0))      // only 0.1832 BTC free
        org.junit.Assert.assertNull(vm.buildQuick(Side.BUY, BigDecimal("1"), now = 0)) }     // ~81k > 12.5k USDT free
    @Test fun gateMarketBuyUsesQuoteAmount() { val vm = fullChartVm(); vm.exchangeId = "gate"
        val b = vm.buildQuick(Side.BUY, BigDecimal("0.001"), now = 0)!!; org.junit.Assert.assertNull(b.req.qty); org.junit.Assert.assertNotNull(b.req.quoteQty) }
    @Test fun staleBookBlocks() { val vm = fullChartVm(); vm.bookTs = 1000; org.junit.Assert.assertNull(vm.buildQuick(Side.BUY, BigDecimal("0.001"), now = 10_000)) }
    @Test fun avgCostFromPairTrades() { val vm = fullChartVm(); val c = vm.pairCost!!; org.junit.Assert.assertEquals(0, c.qty.compareTo(BigDecimal("0.1832"))) }
}

class ChartTabPortraitTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 470, screenHeight = 1000, density = Density.HIGH, xdpi = 240, ydpi = 240,
        orientation = ScreenOrientation.PORTRAIT), maxPercentDifference = 1.0)
    @Test fun charttab_portrait() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme { Root(vm) } } }
    @Test fun charttab_portrait_macd_rsi() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme {
        val vp = remember { ChartViewport(60f).apply { cross = androidx.compose.ui.geometry.Offset(380f, 300f) } }
        Column(Modifier.fillMaxSize().background(Palette.bg)) { ProChart(vm, vp, ChartType.BAR, setOf("BOLL", "EMA"), listOf("MACD", "RSI"), Modifier.fillMaxSize()) } } } }
    @Test fun trading_small_chart() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme { CompositionLocalProvider(LocalOpenFullChart provides {}) {
        Column(Modifier.fillMaxSize().background(Palette.bg)) { ExchangeBar(vm); PairBar(vm); StatsStrip(vm); ChartCard(vm) } } } } }
}

class ChartTabLandscapeTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1000, screenHeight = 470, density = Density.HIGH, xdpi = 240, ydpi = 240,
        orientation = ScreenOrientation.LANDSCAPE), maxPercentDifference = 1.0)
    @Test fun charttab_landscape() { val vm = fullChartVm(); paparazzi.snapshot { AppTheme { Root(vm) } } }
}
