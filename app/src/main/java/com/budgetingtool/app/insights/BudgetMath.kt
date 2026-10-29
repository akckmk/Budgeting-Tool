package com.budgetingtool.app.insights

import com.budgetingtool.app.data.Budget
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.data.Txn
import java.time.YearMonth

data class BudgetStatus(val budget: Budget, val category: Category?, val spentCents: Long) {
    val limitCents: Long get() = budget.monthlyLimitCents
    val fraction: Double get() = if (limitCents <= 0) 0.0 else spentCents.toDouble() / limitCents
    val remainingCents: Long get() = limitCents - spentCents
    val label: String get() = category?.label ?: "💵 Total spending"
    val isTotal: Boolean get() = budget.categoryId == null
}

/** How a not-yet-saved purchase would change one budget. */
data class BudgetImpact(val status: BudgetStatus, val afterCents: Long) {
    val afterFraction: Double get() = if (status.limitCents <= 0) 0.0 else afterCents.toDouble() / status.limitCents
    val goesOver: Boolean get() = afterCents > status.limitCents
    val overByCents: Long get() = afterCents - status.limitCents
}

object BudgetMath {
    /** What a transaction contributes to spending: purchases positive, refunds negative, transfers/income zero. */
    fun spending(t: Txn, cats: Map<String, Category>): Long {
        if (t.deleted) return 0
        val kind = t.categoryId?.let { cats[it]?.kind } ?: CategoryKind.SPENDING
        return if (kind == CategoryKind.SPENDING) -t.amountCents else 0
    }

    fun monthRange(m: YearMonth): LongRange = m.atDay(1).toEpochDay()..m.atEndOfMonth().toEpochDay()

    fun inMonth(txns: List<Txn>, m: YearMonth): List<Txn> {
        val r = monthRange(m)
        return txns.filter { !it.deleted && it.date in r }
    }

    fun totalSpending(txns: List<Txn>, cats: Map<String, Category>, m: YearMonth): Long =
        inMonth(txns, m).sumOf { spending(it, cats) }

    fun statuses(budgets: List<Budget>, txns: List<Txn>, cats: Map<String, Category>, m: YearMonth): List<BudgetStatus> {
        val month = inMonth(txns, m)
        val byCat = month.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { spending(it, cats) } }
        val total = month.sumOf { spending(it, cats) }
        return budgets.filter { !it.deleted && (it.categoryId == null || cats.containsKey(it.categoryId)) }
            .map { b -> BudgetStatus(b, b.categoryId?.let { cats[it] }, if (b.categoryId == null) total else byCat[b.categoryId] ?: 0L) }
            .sortedWith(compareByDescending<BudgetStatus> { it.isTotal }.thenByDescending { it.fraction })
    }

    /** Budgets touched by spending [cents] in [categoryId] this month (the category's own and the total). */
    fun impactOf(statuses: List<BudgetStatus>, categoryId: String?, cents: Long): List<BudgetImpact> =
        statuses.filter { it.isTotal || (categoryId != null && it.budget.categoryId == categoryId) }
            .map { BudgetImpact(it, it.spentCents + cents) }
}
