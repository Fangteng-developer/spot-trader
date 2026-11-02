package com.morris.spottrader.chase

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.*
import com.morris.spottrader.ex.*
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.math.RoundingMode

data class ChaseParams(val pair: SymPair, val side: Side, val qty: BigDecimal, val offsetTicks: Int = 0,
                       /** buy: never bid above; sell: never ask below */ val limitPrice: BigDecimal? = null,
                       /** max distance from start price, percent */ val maxDistancePct: BigDecimal? = null,
                       val maxDurationSec: Long = 600, val intervalMs: Long = 1000,
                       /** maker-only conditional order: wait locally until last price crosses this, then chase (post-only) capped at limitPrice */
                       val triggerPrice: BigDecimal? = null, val maxWaitSec: Long = 24 * 3600)

/** One running chase. All fields are Compose state so the UI updates live. */
class ChaseTask(val id: Int, val exchangeName: String, val p: ChaseParams) {
    var status by mutableStateOf("启动中")
    var active by mutableStateOf(true)
    var filled by mutableStateOf(BigDecimal.ZERO)
    var quoteFilled by mutableStateOf(BigDecimal.ZERO)
    var replaces by mutableIntStateOf(0)
    var rejects by mutableIntStateOf(0)
    var currentPrice by mutableStateOf<BigDecimal?>(null)
    var startPrice by mutableStateOf<BigDecimal?>(null)
    var triggered by mutableStateOf(false)
    val startedAt = System.currentTimeMillis()
    val avgPrice: BigDecimal? get() = if (filled.signum() > 0) quoteFilled.divide(filled, 12, RoundingMode.HALF_UP) else null
    val remaining: BigDecimal get() = (p.qty - filled).max(BigDecimal.ZERO)
    internal var job: Job? = null
    @Volatile internal var stopRequested = false
}

/**
 * Maker-only chase engine. Places a post-only limit at best bid (buy) / best ask (sell) ± offset ticks,
 * polls the book + order every ~1s, and when the best price moves away cancels and re-places the remaining qty.
 * Post-only rejections (immediate error or exchange-side cancel with no fill) step the price one tick further back.
 */
object ChaseManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tasks = mutableStateListOf<ChaseTask>()
    private var nextId = 1
    var appContext: Context? = null
    const val MAX_ACTIVE = 3

    fun activeCount() = tasks.count { it.active }

    fun start(ex: SpotExchange, info: SymbolInfo, params: ChaseParams): ChaseTask {
        require(activeCount() < MAX_ACTIVE) { "最多同时运行 $MAX_ACTIVE 个追逐单（频率限制）" }
        val t = ChaseTask(nextId++, ex.display, params)
        tasks.add(0, t)
        t.job = scope.launch { try { run(ex, info, t) } catch (e: CancellationException) { } catch (e: Throwable) { t.status = "异常停止: ${e.message?.take(120)}" } finally { t.active = false; updateService() } }
        updateService()
        return t
    }
    fun stop(t: ChaseTask) { t.stopRequested = true; t.status = "正在停止…" }
    fun clearFinished() { tasks.removeAll { !it.active } }

    private suspend fun run(ex: SpotExchange, info: SymbolInfo, t: ChaseTask) {
        val p = t.p; val buy = p.side == Side.BUY
        var orderId: String? = null; var orderPrice: BigDecimal? = null; var orderBaseFilled = BigDecimal.ZERO
        var bump = 0; var weCancelled = false; var errors = 0
        var deadline = t.startedAt + p.maxDurationSec * 1000
        val trig = p.triggerPrice
        if (trig != null) {   // local trigger watcher: native stop-limit orders cannot be post-only, so we trigger in-app and then place post-only
            var first: BigDecimal? = null; var errs = 0
            while (first == null) { first = runCatching { ex.ticker(p.pair).last }.getOrNull(); if (first == null) { if (++errs > 10) throw ApiException("无法获取价格"); delay(2000) } }
            val up = trig > first
            val waitDeadline = System.currentTimeMillis() + p.maxWaitSec * 1000
            t.status = "等待触发 ${if (up) "≥" else "≤"} ${trig.plain()}（当前 ${first.plain()}）"
            while (!(if (up) first!! >= trig else first!! <= trig)) {
                if (t.stopRequested) { t.status = "已手动停止"; return }
                if (System.currentTimeMillis() > waitDeadline) { t.status = "触发等待超时"; return }
                delay(maxOf(p.intervalMs, 1500))
                first = runCatching { ex.ticker(p.pair).last }.getOrNull() ?: first
                t.currentPrice = first
                t.status = "等待触发 ${if (up) "≥" else "≤"} ${trig.plain()}（当前 ${first!!.plain()}）"
            }
            t.triggered = true; t.status = "已触发，开始Maker挂单"
            deadline = System.currentTimeMillis() + p.maxDurationSec * 1000
        }

        /** Fold a finished order's fills into the totals. */
        suspend fun settle(id: String) {
            val o = runCatching { ex.getOrder(p.pair, id) }.getOrNull() ?: return
            val add = o.filled - orderBaseFilled
            if (add.signum() > 0) { t.filled += add; t.quoteFilled += add * (if (o.avg.signum() > 0) o.avg else o.price) }
        }
        suspend fun cancelCurrent() { val id = orderId ?: return
            weCancelled = true
            runCatching { ex.cancelOrder(p.pair, id) }
            delay(250); settle(id); orderId = null; orderPrice = null; orderBaseFilled = BigDecimal.ZERO }

        while (true) {
            if (t.stopRequested) { cancelCurrent(); t.status = "已手动停止"; return }
            if (System.currentTimeMillis() > deadline) { cancelCurrent(); t.status = "已超时停止"; return }
            val rem = info.roundQty(t.remaining)
            if (rem.signum() <= 0 || (info.minQty.signum() > 0 && rem < info.minQty)) { cancelCurrent(); t.status = if (t.filled.signum() > 0) "已完成" else "数量过小"; return }

            val book = try { ex.orderBook(p.pair, 5) } catch (e: Throwable) { if (++errors > 10) throw e; delay(2000); continue }
            val bestOrNull = (if (buy) book.bids.maxByOrNull { it.price } else book.asks.minByOrNull { it.price })?.price
            if (bestOrNull == null) { delay(p.intervalMs); continue }
            val best: BigDecimal = bestOrNull
            if (t.startPrice == null) t.startPrice = best
            val ticks = info.tick.multiply(BigDecimal(p.offsetTicks + bump))
            var target = info.roundPrice(if (buy) best - ticks else best + ticks)
            // limits
            val lim = p.limitPrice
            val distOk = p.maxDistancePct?.let { d -> val sp = t.startPrice!!; (target - sp).abs().multiply(BigDecimal(100)).divide(sp, 6, RoundingMode.HALF_UP) <= d } ?: true
            val limOk = lim == null || (if (buy) target <= lim else target >= lim)
            if (!limOk) target = info.roundPrice(lim!!)           // clamp to the max chase price, still post-only
            if (!distOk && orderId == null) { t.status = "超出最大偏离，等待回归"; delay(p.intervalMs); continue }

            val id = orderId
            if (id == null) {
                if (target * rem < info.minNotional) { t.status = if (t.filled.signum() > 0) "已完成(剩余低于最小额)" else "低于最小下单额"; return }
                try {
                    orderId = ex.place(OrderReq(p.pair, p.side, OType.LIMIT, rem, null, target, tif = TIF.POST_ONLY))
                    orderPrice = target; orderBaseFilled = BigDecimal.ZERO; weCancelled = false; errors = 0
                    t.currentPrice = target; t.status = "挂单中 @ ${target.plain()}"
                } catch (e: ApiException) {
                    t.rejects++; bump++; t.status = "Maker被拒，后退一档重试 (${e.message?.take(60)})"
                    if (t.rejects > 50) throw ApiException("被拒次数过多")
                }
            } else {
                val o = try { ex.getOrder(p.pair, id) } catch (e: Throwable) { if (++errors > 10) throw e; delay(p.intervalMs); continue }
                val newFill = o.filled - orderBaseFilled
                if (newFill.signum() > 0) { t.filled += newFill; t.quoteFilled += newFill * (if (o.avg.signum() > 0) o.avg else o.price); orderBaseFilled = o.filled }
                if (!o.isOpen) {
                    // closed by exchange: filled, or post-only rejection (cancelled with no fill)
                    if (!weCancelled && o.filled.signum() == 0) { t.rejects++; bump++; t.status = "Maker被拒，后退一档重试" } else bump = 0
                    orderId = null; orderPrice = null; orderBaseFilled = BigDecimal.ZERO
                } else {
                    val op = orderPrice!!
                    val movedAway = if (buy) target > op else target < op
                    if (movedAway && distOk) { cancelCurrent(); t.replaces++; bump = 0; t.status = "价格移动，撤单重挂…"; continue }
                    t.status = "挂单中 @ ${op.plain()}" + if (orderBaseFilled.signum() > 0) " (部分成交)" else ""
                }
            }
            delay(p.intervalMs)
        }
    }

    fun updateService() {
        val ctx = appContext ?: return
        val i = Intent(ctx, ChaseService::class.java)
        if (activeCount() + com.morris.spottrader.pos.TpSlManager.activeCount() > 0) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } else ctx.stopService(i)
    }
}

/** Keeps the process alive (and CPU awake) while chase orders run, e.g. with the screen off. */
class ChaseService : Service() {
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("chase", "追逐限价单", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "chase").setContentTitle("交易监控运行中").setContentText("追逐单 ${ChaseManager.activeCount()} · TP/SL ${com.morris.spottrader.pos.TpSlManager.activeCount()} · 点按返回应用")
            .setSmallIcon(android.R.drawable.ic_menu_rotate).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spottrader:chase").apply { acquire(6 * 60 * 60 * 1000L) }
        return START_NOT_STICKY
    }
    override fun onDestroy() { wl?.let { if (it.isHeld) it.release() }; wl = null; super.onDestroy() }
}
