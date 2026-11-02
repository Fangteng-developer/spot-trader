package com.morris.spottrader.pos

import com.morris.spottrader.ex.*
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk-based position sizing and spot position accounting.
 *
 * The logic is a Kotlin rewrite inspired by Jesse (https://github.com/jesse-ai/jesse, MIT License,
 * Copyright (c) 2020 Jesse.Trade): `utils.risk_to_qty` / `risk_to_size` and the average-entry /
 * realized-PnL bookkeeping of `models.Position`. No Jesse code is copied verbatim.
 */
object RiskCalc {
    data class Result(
        val qty: BigDecimal,            // rounded down to lot step
        val notional: BigDecimal,       // qty * entry (quote)
        val riskAmount: BigDecimal,     // intended loss at stop (balance * risk%)
        val actualRisk: BigDecimal,     // loss at stop for the rounded qty incl. fees
        val stopDistPct: BigDecimal,
        val rr: BigDecimal?,            // reward:risk if a take-profit is given
        val cappedByBalance: Boolean,
        val error: String? = null)

    /**
     * qty = (balance * risk%) / (|entry - stop| + fees per unit) — like jesse risk_to_qty with fee_rate —
     * then capped so that the notional never exceeds the balance (spot: no leverage), floored to the lot step,
     * and validated against min qty / min notional.
     */
    fun size(balance: BigDecimal, riskPct: BigDecimal, entry: BigDecimal, stop: BigDecimal, info: SymbolInfo,
             takeProfit: BigDecimal? = null, feeRate: BigDecimal = info.taker): Result {
        val z = BigDecimal.ZERO
        fun err(m: String) = Result(z, z, z, z, z, null, false, m)
        if (balance.signum() <= 0) return err("可用余额为 0")
        if (riskPct.signum() <= 0 || riskPct > BigDecimal(100)) return err("风险% 需在 0~100 之间")
        if (entry.signum() <= 0 || stop.signum() <= 0) return err("请输入入场价与止损价")
        if (stop >= entry) return err("现货做多：止损价必须低于入场价")
        val riskAmount = balance * riskPct.movePointLeft(2)
        val perUnit = (entry - stop) + (entry + stop) * feeRate          // price risk + round-trip fees
        var qty = riskAmount.divide(perUnit, 18, RoundingMode.DOWN)
        val maxQty = balance.divide(entry * (BigDecimal.ONE + feeRate), 18, RoundingMode.DOWN)
        val capped = qty > maxQty; if (capped) qty = maxQty
        qty = info.roundQty(qty)
        val notional = qty * entry
        val stopPct = (entry - stop).multiply(BigDecimal(100)).divide(entry, 4, RoundingMode.HALF_UP)
        val rr = takeProfit?.takeIf { it > entry }?.let { (it - entry).divide(entry - stop, 2, RoundingMode.HALF_UP) }
        val res = Result(qty, notional, riskAmount, qty * perUnit, stopPct, rr, capped)
        return when {
            qty.signum() <= 0 -> res.copy(error = "数量过小（步长 ${info.step.plain()}）")
            info.minQty.signum() > 0 && qty < info.minQty -> res.copy(error = "低于最小下单量 ${info.minQty.plain()}")
            info.minNotional.signum() > 0 && notional < info.minNotional -> res.copy(error = "低于最小下单金额 ${info.minNotional.plain()}")
            else -> res
        }
    }
}

/** Spot long position from fills: moving-average entry, realized PnL on sells (fees included), unrealized vs. a mark price. */
class SpotPosition(val base: String) {
    var qty: BigDecimal = BigDecimal.ZERO; private set
    var avgEntry: BigDecimal = BigDecimal.ZERO; private set
    var realized: BigDecimal = BigDecimal.ZERO; private set
    var fees: BigDecimal = BigDecimal.ZERO; private set
    fun apply(t: Trade, quote: String) {
        val feeQuote = when { t.feeAsset.equals(quote, true) -> t.fee; t.feeAsset.equals(base, true) -> t.fee * t.price; else -> BigDecimal.ZERO }
        val feeBase = if (t.feeAsset.equals(base, true)) t.fee else BigDecimal.ZERO
        fees += feeQuote
        if (t.side == Side.BUY) {
            val newQty = qty + t.qty - feeBase
            if (newQty.signum() > 0) avgEntry = (avgEntry * qty + t.price * t.qty + (if (feeBase.signum() == 0) feeQuote else BigDecimal.ZERO)).divide(newQty, 12, RoundingMode.HALF_UP)
            qty = newQty
        } else {
            val sold = t.qty.min(qty)
            realized += (t.price - avgEntry) * sold - feeQuote
            qty -= sold; if (qty.signum() <= 0) { qty = BigDecimal.ZERO; avgEntry = BigDecimal.ZERO }
        }
    }
    fun unrealized(mark: BigDecimal): BigDecimal = (mark - avgEntry) * qty
    fun pnlPct(mark: BigDecimal): BigDecimal? = if (avgEntry.signum() > 0) (mark - avgEntry).multiply(BigDecimal(100)).divide(avgEntry, 2, RoundingMode.HALF_UP) else null
    companion object { fun of(trades: List<Trade>, base: String, quote: String) = SpotPosition(base).apply { trades.sortedBy { it.time }.forEach { apply(it, quote) } } }
}
