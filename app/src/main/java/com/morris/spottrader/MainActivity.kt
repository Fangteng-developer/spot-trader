package com.morris.spottrader

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.morris.spottrader.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels { object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            AppVM(com.morris.spottrader.sec.KeyStoreRepo.create(applicationContext), getSharedPreferences("ui", MODE_PRIVATE)) as T } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) // no screenshots of keys
        com.morris.spottrader.chase.ChaseManager.appContext = applicationContext
        com.morris.spottrader.pos.TpSlManager.init(applicationContext) { id -> com.morris.spottrader.ex.Exchanges.create(id, vm.repo.creds(id)) }
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        val control = object : ScreenControl {
            override fun immersive(on: Boolean) { val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                if (on) { c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
                else { c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()); requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
            override fun toggleLandscape(): Boolean { val land = requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                requestedOrientation = if (land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; return land } }
        setContent { AppTheme { Root(vm, control) } }
        vm.bootstrap()
    }
    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { vm.onBackground(); super.onStop() }
}

@Composable
fun Root(vm: AppVM, control: ScreenControl? = null, startTab: Int = 0) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(startTab) }
    var full by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 图表 (MT5-style chart) first; it replaces the old 行情 entry so the nav has no duplicate chart tab.
    val tabs = listOf("图表", "交易", "持仓", "API密钥", "IP白名单")
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    if (full) { androidx.activity.compose.BackHandler { full = false }
        Box(Modifier.fillMaxSize()) { FullChartScreen(vm, { full = false }, control); SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) }
        return }
    val land = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val hideNav = tab == 0 && land // MT5: landscape chart uses the whole screen
    Scaffold(containerColor = Palette.bg, snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (!hideNav) NavigationBar(containerColor = Palette.panel) { tabs.forEachIndexed { i, t ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("📈", "◆", "▤", "⚿", "⌖")[i]) }, label = { Text(t) },
                colors = NavigationBarItemDefaults.colors(selectedTextColor = Palette.accent, selectedIconColor = Palette.accent, indicatorColor = Color(0x3300C2A8), unselectedTextColor = Palette.dim, unselectedIconColor = Palette.dim)) } } }
    ) { pad -> Column(Modifier.padding(pad).fillMaxSize()) {
        if (tab == 0) { if (hideNav) androidx.activity.compose.BackHandler { control?.toggleLandscape() }
            Mt5ChartScreen(vm, null, control); return@Column }
        ExchangeBar(vm)
        CompositionLocalProvider(LocalOpenFullChart provides { full = true }) {
            when (tab) { 1 -> TradeScreen(vm); 2 -> PositionsScreen(vm); 3 -> KeysScreen(vm); else -> NetScreen() } }
    } }
}
