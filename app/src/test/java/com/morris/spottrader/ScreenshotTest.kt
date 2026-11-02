package com.morris.spottrader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.morris.spottrader.ex.*
import com.morris.spottrader.sec.KeyStoreRepo
import com.morris.spottrader.ui.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

class ScreenshotTest {
    // Paparazzi downsizes images taller than 1000px, so render at 2x density in sections that fit.
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 824, screenHeight = 1000, density = com.android.resources.Density.XHIGH, xdpi = 320, ydpi = 320), maxPercentDifference = 1.0)

    fun demoVm(): AppVM {
        val vm = AppVM(KeyStoreRepo(null), null); vm.demo = true; KChartEnv.webViewAvailable = false
        val r = Random(7); var p = 81000.0; val now = 1792000000000L
        vm.klines = (0 until 120).map { i -> val o = p; p += r.nextDouble(-380.0, 400.0); val h = maxOf(o, p) + r.nextDouble(0.0, 160.0); val l = minOf(o, p) - r.nextDouble(0.0, 160.0)
            Kline(now + i * 900_000L, o, h, l, p, r.nextDouble(40.0, 420.0)) }
        val last = BigDecimal(p).setScale(2, java.math.RoundingMode.HALF_UP)
        vm.ticker = Ticker(last, BigDecimal("1.37"), BigDecimal("83168.20"), BigDecimal("80680.50"), BigDecimal("9786.58"))
        vm.info = SymbolInfo(BigDecimal("0.01"), BigDecimal("0.00001"), BigDecimal("0.00001"), BigDecimal("5"))
        vm.book = OrderBook((1..40).map { Level(last - BigDecimal(it * 0.7).setScale(2, java.math.RoundingMode.HALF_UP), BigDecimal(r.nextDouble(0.01, 2.5)).setScale(5, java.math.RoundingMode.HALF_UP)) },
            (1..40).map { Level(last + BigDecimal(it * 0.7).setScale(2, java.math.RoundingMode.HALF_UP), BigDecimal(r.nextDouble(0.01, 1.8)).setScale(5, java.math.RoundingMode.HALF_UP)) })
        vm.trades = (0 until 30).map { PublicTrade(last + BigDecimal(r.nextDouble(-5.0, 5.0)).setScale(2, java.math.RoundingMode.HALF_UP), BigDecimal(r.nextDouble(0.001, 0.6)).setScale(5, java.math.RoundingMode.HALF_UP), if (r.nextBoolean()) Side.BUY else Side.SELL, now - it * 3000L) }
        vm.balances = listOf(Balance("USDT", BigDecimal("12480.55"), BigDecimal("1200")), Balance("BTC", BigDecimal("0.1832"), BigDecimal.ZERO), Balance("ETH", BigDecimal("2.5"), BigDecimal("0.5")), Balance("SOL", BigDecimal("40"), BigDecimal.ZERO))
        vm.openOrders = listOf(Order("1", "BTCUSDT", Side.BUY, "LIMIT", BigDecimal("79800"), BigDecimal("0.015"), BigDecimal.ZERO, "NEW", now),
            Order("2", "ETHUSDT", Side.SELL, "LIMIT", BigDecimal("3420"), BigDecimal("0.5"), BigDecimal("0.1"), "PARTIALLY_FILLED", now - 600000),
            Order("3", "BTCUSDT", Side.SELL, "STOP_LOSS_LIMIT @78000", BigDecimal("77900"), BigDecimal("0.05"), BigDecimal.ZERO, "NEW", now - 900000))
        vm.positions = listOf(AppVM.position(Balance("BTC", BigDecimal("0.1832"), BigDecimal.ZERO), last, BigDecimal("76250.4")),
            AppVM.position(Balance("ETH", BigDecimal("2.5"), BigDecimal("0.5")), BigDecimal("3312.4"), BigDecimal("3501.9")),
            AppVM.position(Balance("SOL", BigDecimal("40"), BigDecimal.ZERO), BigDecimal("176.21"), BigDecimal("158.02")),
            AppVM.position(Balance("USDT", BigDecimal("12480.55"), BigDecimal("1200")), BigDecimal.ONE, null))
        return vm
    }

    @Test fun trading_1_chart() { val vm = demoVm(); paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) { ExchangeBar(vm); PairBar(vm); StatsStrip(vm); ChartCard(vm) } } } }
    @Test fun trading_2_book_form() { val vm = demoVm(); paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) { Box(Modifier.weight(0.45f)) { BookPanel(vm) }; Spacer(Modifier.width(6.dp)); Box(Modifier.weight(0.55f)) { OrderForm(vm) } } } } } }
    @Test fun trading_3_bottom_panel() { val vm = demoVm(); paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) { BottomPanel(vm) } } } }
    @Test fun chase_order_form() { val vm = demoVm()
        val t1 = com.morris.spottrader.chase.ChaseTask(1, "Binance", com.morris.spottrader.chase.ChaseParams(SymPair("BTC", "USDT"), Side.BUY, BigDecimal("0.05"), maxDurationSec = 600)).apply {
            filled = BigDecimal("0.021"); quoteFilled = BigDecimal("0.021") * BigDecimal("83092.40"); replaces = 7; rejects = 1; currentPrice = BigDecimal("83098.36"); status = "挂单中 @ 83098.36 (部分成交)" }
        val t2 = com.morris.spottrader.chase.ChaseTask(2, "OKX", com.morris.spottrader.chase.ChaseParams(SymPair("ETH", "USDT"), Side.SELL, BigDecimal("1.2"), maxDurationSec = 300)).apply {
            filled = BigDecimal("1.2"); quoteFilled = BigDecimal("1.2") * BigDecimal("3318.75"); replaces = 3; currentPrice = BigDecimal("3318.9"); active = false; status = "已完成" }
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) { Box(Modifier.weight(0.45f)) { BookPanel(vm) }; Spacer(Modifier.width(6.dp)); Box(Modifier.weight(0.55f)) { OrderForm(vm, initialChase = true) } }
            } } }
        chaseTasks = listOf(t1, t2) }
    private var chaseTasks: List<com.morris.spottrader.chase.ChaseTask> = emptyList()
    @Test fun chase_panel() { chase_order_form_data(); paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(top = 8.dp)) { ChasePanel(chaseTasks) } } } }
    private fun chase_order_form_data() {
        val t1 = com.morris.spottrader.chase.ChaseTask(1, "Binance", com.morris.spottrader.chase.ChaseParams(SymPair("BTC", "USDT"), Side.BUY, BigDecimal("0.05"), maxDurationSec = 600)).apply {
            filled = BigDecimal("0.021"); quoteFilled = BigDecimal("0.021") * BigDecimal("83092.40"); replaces = 7; rejects = 1; currentPrice = BigDecimal("83098.36"); status = "挂单中 @ 83098.36 (部分成交)" }
        val t2 = com.morris.spottrader.chase.ChaseTask(2, "OKX", com.morris.spottrader.chase.ChaseParams(SymPair("ETH", "USDT"), Side.SELL, BigDecimal("1.2"), maxDurationSec = 300)).apply {
            filled = BigDecimal("1.2"); quoteFilled = BigDecimal("1.2") * BigDecimal("3318.75"); replaces = 3; currentPrice = BigDecimal("3318.9"); active = false; status = "已完成" }
        chaseTasks = listOf(t1, t2) }
    @Test fun positions() { val vm = demoVm(); paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) { ExchangeBar(vm); PositionsScreen(vm) } } } }
    private fun usdc(vm: AppVM) {
        vm.pair = SymPair("BTC", "USDC")
        vm.symbols = (Quotes.POPULAR.map { SymPair(it, "USDT") } + Quotes.POPULAR.take(22).map { SymPair(it, "USDC") } + listOf(SymPair("ETH", "BTC"), SymPair("SOL", "BTC"), SymPair("BTC", "EUR"), SymPair("ETH", "EUR"), SymPair("BNB", "FDUSD"), SymPair("BTC", "USD1")))
        vm.info = SymbolInfo(BigDecimal("0.01"), BigDecimal("0.000001"), BigDecimal("0.000001"), BigDecimal("1"), BigDecimal.ZERO, BigDecimal("0.001"))
        vm.balances = listOf(Balance("USDC", BigDecimal("5320.40"), BigDecimal.ZERO), Balance("USDT", BigDecimal("12480.55"), BigDecimal("1200")), Balance("BTC", BigDecimal("0.1832"), BigDecimal.ZERO))
        vm.favorites = setOf("BTC/USDT", "BTC/USDC", "ETH/USDC", "SOL/USDC")
    }
    @Test fun maker_order_form() { val vm = demoVm(); usdc(vm); vm.pair = SymPair("BTC", "USDT"); vm.toggleMaker(true)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) { Box(Modifier.weight(0.45f)) { BookPanel(vm) }; Spacer(Modifier.width(6.dp)); Box(Modifier.weight(0.55f)) { OrderForm(vm) } } } } } }
    @Test fun maker_stop_form() { val vm = demoVm(); usdc(vm); vm.pair = SymPair("BTC", "USDT"); vm.toggleMaker(true)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(6.dp)) { OrderForm(vm, initialType = OType.STOP_LIMIT) } } } }
    @Test fun maker_off_order_form() { val vm = demoVm(); usdc(vm); vm.pair = SymPair("BTC", "USDT")
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(6.dp)) { OrderForm(vm) } } } }
    @Test fun pairPicker_usdc() { val vm = demoVm(); usdc(vm)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(16.dp)) { PairPickerContent(vm, {}, initialTab = "USDC") } } } }
    @Test fun pairPicker_other() { val vm = demoVm(); usdc(vm)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(16.dp)) { PairPickerContent(vm, {}, initialTab = "OTHER") } } } }
    @Test fun pairPicker() { val vm = demoVm(); usdc(vm)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg).padding(16.dp)) { PairPickerContent(vm, {}, initialTab = "FAV") } } } }
    @Test fun usdc_trading() { val vm = demoVm(); usdc(vm)
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) { PairBar(vm); StatsStrip(vm)
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) { Box(Modifier.weight(0.45f)) { BookPanel(vm) }; Spacer(Modifier.width(6.dp)); Box(Modifier.weight(0.55f)) { OrderForm(vm) } } } } } }
    @Test fun usdc_positions() { val vm = demoVm(); usdc(vm); vm.posQuote = "USDC"
        vm.positions = listOf(AppVM.position(Balance("BTC", BigDecimal("0.1832"), BigDecimal.ZERO), BigDecimal("80612.40"), BigDecimal("77150.2"), "USDC", BigDecimal("0.15")),
            AppVM.position(Balance("ETH", BigDecimal("2.5"), BigDecimal("0.5")), BigDecimal("3318.2"), BigDecimal("3401.9"), "USDC", BigDecimal("2.5")),
            AppVM.position(Balance("USDC", BigDecimal("5320.40"), BigDecimal.ZERO), BigDecimal.ONE, null, "USDC"), AppVM.position(Balance("USDT", BigDecimal("13680.55"), BigDecimal.ZERO), BigDecimal("0.9998"), null, "USDC"))
        paparazzi.snapshot { AppTheme { Column(Modifier.fillMaxSize().background(Palette.bg)) { PositionsScreen(vm) } } } }
}
