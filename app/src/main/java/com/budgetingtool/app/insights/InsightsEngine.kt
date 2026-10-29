package com.budgetingtool.app.insights

import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.Budget
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.util.Money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

data class CategorySlice(val categoryId: String?, val category: Category?, val cents: Long)
data class MerchantTotal(val name: String, val cents: Long, val count: Int)
data class Recurring(val name: String, val avgCents: Long, val months: Int, val categoryId: String?) {
    val annualCents: Long get() = avgCents * 12
}
data class Insight(val emoji: String, val title: String, val detail: String)

/** Pure spending analysis over the transactions in memory; no I/O so it is easy to test. */
class InsightsEngine(
    private val txns: List<Txn>,
    private val cats: Map<String, Category>,
    private val accounts: Map<String, Account> = emptyMap(),
) {
    private fun spend(t: Txn) = BudgetMath.spending(t, cats)
    private fun month(m: YearMonth) = BudgetMath.inMonth(txns, m)

    fun total(m: YearMonth): Long = month(m).sumOf(::spend)

    fun income(m: YearMonth): Long = month(m)
        .filter { t -> t.amountCents > 0 && t.categoryId?.let { cats[it]?.kind } == CategoryKind.INCOME }
        .sumOf { it.amountCents }

    fun categoryBreakdown(m: YearMonth): List<CategorySlice> =
        month(m).groupBy { it.categoryId }
            .map { (id, l) -> CategorySlice(id, id?.let { cats[it] }, l.sumOf(::spend)) }
            .filter { it.cents > 0 }
            .sortedByDescending { it.cents }

    fun monthlyTotals(end: YearMonth, count: Int): List<Pair<YearMonth, Long>> =
        (count - 1 downTo 0).map { end.minusMonths(it.toLong()) }.map { it to total(it) }

    fun topMerchants(m: YearMonth, limit: Int = 8): List<MerchantTotal> =
        month(m).filter { spend(it) > 0 }
            .groupBy { it.merchant.ifBlank { it.description }.uppercase() }
            .map { (_, l) -> MerchantTotal(l.first().merchant.ifBlank { l.first().description }, l.sumOf(::spend), l.size) }
            .sortedByDescending { it.cents }
            .take(limit)

    /** Merchants charging a similar amount in at least 3 of the last 6 months. */
    fun recurring(asOf: YearMonth): List<Recurring> {
        val start = asOf.minusMonths(5).atDay(1).toEpochDay()
        val end = asOf.atEndOfMonth().toEpochDay()
        return txns.asSequence()
            .filter { !it.deleted && it.date in start..end && spend(it) > 0 }
            .groupBy { it.merchant.uppercase() }
            .mapNotNull { (_, l) ->
                val months = l.map { YearMonth.from(LocalDate.ofEpochDay(it.date)) }.toSet()
                if (months.size < 3 || l.size > months.size * 1.5) return@mapNotNull null
                val amounts = l.map { spend(it) }.sorted()
                val median = amounts[amounts.size / 2]
                val similar = amounts.count { abs(it - median) <= median * 0.2 }
                if (similar < l.size * 0.75) return@mapNotNull null
                Recurring(l.first().merchant, amounts.average().roundToLong(), months.size, l.first().categoryId)
            }
            .sortedByDescending { it.avgCents }
    }

    /** Spending per person: who entered it, else whose account it is. */
    fun bySpender(m: YearMonth): List<Pair<String, Long>> =
        month(m).filter { spend(it) != 0L }
            .groupBy { t -> t.enteredBy.ifBlank { t.accountId?.let { accounts[it]?.owner }.orEmpty() }.ifBlank { "Unassigned" } }
            .map { (who, l) -> who to l.sumOf(::spend) }
            .sortedByDescending { it.second }

    /** Index 0 = Monday. */
    fun byDayOfWeek(m: YearMonth): List<Long> {
        val out = LongArray(7)
        month(m).forEach { out[LocalDate.ofEpochDay(it.date).dayOfWeek.value - 1] += spend(it) }
        return out.toList()
    }

    fun insights(m: YearMonth, today: LocalDate, budgets: List<Budget>): List<Insight> {
        val out = ArrayList<Insight>()
        val spent = total(m)
        val isCurrent = YearMonth.from(today) == m
        val days = m.lengthOfMonth()
        val elapsed = if (isCurrent) today.dayOfMonth else days
        val totalBudget = budgets.firstOrNull { !it.deleted && it.categoryId == null }?.monthlyLimitCents

        if (isCurrent && spent > 0 && elapsed < days) {
            val projected = spent * days / elapsed
            val detail = when {
                totalBudget == null -> "Based on ${Money.format(spent, false)} spent in the first $elapsed days."
                projected > totalBudget -> "That's ${Money.format(projected - totalBudget, false)} over your ${Money.format(totalBudget, false)} monthly limit. Slow down to about ${Money.format(maxOf(0, totalBudget - spent) / maxOf(1, days - elapsed), false)}/day to stay on budget."
                else -> "That leaves about ${Money.format(totalBudget - projected, false)} under your ${Money.format(totalBudget, false)} limit."
            }
            out += Insight(if (totalBudget != null && projected > totalBudget) "🚨" else "📈", "On pace for ${Money.format(projected, false)} this month", detail)
        }

        // Category changes vs the previous three months (pro-rated for a month in progress).
        val history = (1..3).map { m.minusMonths(it.toLong()) }
        val historyHasData = history.any { total(it) > 0 }
        if (historyHasData) {
            val now = categoryBreakdown(m).associate { it.categoryId to it.cents }
            val avg = history.flatMap { categoryBreakdown(it) }.groupBy { it.categoryId }
                .mapValues { (_, l) -> l.sumOf { it.cents } / 3 * elapsed / days }
            val changes = (now.keys + avg.keys)
                .filter { it != null && it != com.budgetingtool.app.data.DefaultCategories.OTHER }
                .map { id -> Triple(id, now[id] ?: 0L, avg[id] ?: 0L) }
            changes.filter { (_, cur, base) -> base > 0 && cur - base >= 2500 && cur >= base * 1.25 }
                .maxByOrNull { (_, cur, base) -> cur - base }
                ?.let { (id, cur, base) ->
                    val name = id?.let { cats[it]?.label } ?: "Uncategorized"
                    out += Insight("⬆️", "$name is up ${pct(cur, base)}%", "${Money.format(cur, false)} so far vs. a typical ${Money.format(base, false)} by this point in the month.")
                }
            changes.filter { (_, cur, base) -> base >= 2500 && base - cur >= 2500 && cur <= base * 0.75 }
                .maxByOrNull { (_, cur, base) -> base - cur }
                ?.let { (id, cur, base) ->
                    val name = id?.let { cats[it]?.label } ?: "Uncategorized"
                    out += Insight("👏", "$name is down ${pct(base, cur)}%", "${Money.format(cur, false)} vs. a typical ${Money.format(base, false)}. Nice work.")
                }
        }

        topMerchants(m, 1).firstOrNull()?.takeIf { it.count >= 3 }?.let {
            out += Insight("🏪", "${Money.format(it.cents, false)} at ${it.name}", "Across ${it.count} purchases this month, your most-visited merchant.")
        }

        val small = month(m).filter { val s = spend(it); s in 1..999 }
        val smallTotal = small.sumOf(::spend)
        if (small.size >= 8 && smallTotal >= 5000) {
            out += Insight("☕", "Small purchases add up", "${small.size} purchases under $10 totaled ${Money.format(smallTotal, false)}.")
        }

        val dow = byDayOfWeek(m)
        val weekend = dow[5] + dow[6]
        if (spent > 0 && weekend * 100 / spent >= 40) {
            out += Insight("🗓️", "${weekend * 100 / spent}% of spending happened on weekends", "Weekends are only about 29% of the days.")
        }

        month(m).filter { spend(it) > 0 }.maxByOrNull { spend(it) }?.let {
            out += Insight("💳", "Largest purchase: ${Money.format(spend(it))}", "${it.merchant} on ${LocalDate.ofEpochDay(it.date).format(com.budgetingtool.app.util.Dates.shortFormat)}.")
        }

        val rec = recurring(m)
        if (rec.isNotEmpty()) {
            val monthly = rec.sumOf { it.avgCents }
            out += Insight("🔁", "${rec.size} recurring charges ≈ ${Money.format(monthly, false)}/month", "That's about ${Money.format(monthly * 12, false)} a year. Review them below for anything you no longer use.")
        }

        val inc = income(m)
        if (inc > 0 && spent > 0) {
            val rate = (inc - spent) * 100 / inc
            out += if (rate >= 0) {
                Insight("💰", "You've kept $rate% of this month's income", "${Money.format(inc, false)} in, ${Money.format(spent, false)} spent.")
            } else {
                Insight("⚠️", "Spending is ${-rate}% above income", "${Money.format(inc, false)} in, ${Money.format(spent, false)} spent this month.")
            }
        }
        return out
    }

    private fun pct(a: Long, b: Long): Long = if (b == 0L) 0 else (a - b) * 100 / b
}

/** Day labels for [InsightsEngine.byDayOfWeek]. */
val WEEKDAY_LABELS: List<String> = DayOfWeek.entries.map { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
