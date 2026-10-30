package com.budgetingtool.app.insights

import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.data.Statement
import com.budgetingtool.app.data.Txn
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

data class Cycle(val start: LocalDate, val end: LocalDate, val statement: Statement?) {
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}

data class CycleSummary(
    val cycle: Cycle,
    /** Purchases, fees and interest (positive). */
    val chargesCents: Long,
    /** Refunds and other credits (positive). */
    val creditsCents: Long,
    /** Payments made to the card (positive). */
    val paymentsCents: Long,
    val count: Int,
    /** Difference between the bank's balance change and the imported transactions, when checkable. */
    val unexplainedCents: Long?,
)

/** A stretch with no imported activity, which usually means a statement wasn't imported. */
data class Gap(val from: LocalDate, val to: LocalDate)

/**
 * Credit cards bill on their own cycles (e.g. the 15th to the 14th), and every card is different.
 * Budgets count purchases by the day they happened; this groups the same purchases by each card's
 * billing cycle so they can be checked against the statement and the bill that's coming.
 */
object BillingCycles {

    fun closingDate(month: YearMonth, closingDay: Int): LocalDate = month.atDay(min(closingDay, month.lengthOfMonth()))

    /** The cycle that contains [date], for a card that closes on [closingDay]. */
    fun cycleContaining(date: LocalDate, closingDay: Int): Cycle {
        var end = closingDate(YearMonth.from(date), closingDay)
        if (date.isAfter(end)) end = closingDate(YearMonth.from(date).plusMonths(1), closingDay)
        val start = closingDate(YearMonth.from(end).minusMonths(1), closingDay).plusDays(1)
        return Cycle(start, end, null)
    }

    /**
     * The current cycle plus [count] - 1 previous ones, newest first. Where a statement was imported,
     * its printed dates win over the calculated ones (banks shift closing dates around weekends).
     */
    fun cycles(account: Account, statements: List<Statement>, today: LocalDate, count: Int = 6): List<Cycle> {
        val mine = statements.filter { it.accountId == account.id && !it.deleted }.sortedByDescending { it.periodEnd }
        val closingDay = account.closingDay ?: mine.firstOrNull()?.let { LocalDate.ofEpochDay(it.periodEnd).dayOfMonth } ?: return emptyList()
        val out = ArrayList<Cycle>()
        var cursor = today
        repeat(count) {
            val calc = cycleContaining(cursor, closingDay)
            val st = mine.firstOrNull { abs(it.periodEnd - calc.end.toEpochDay()) <= 4 }
            val cycle = if (st != null) Cycle(LocalDate.ofEpochDay(st.periodStart), LocalDate.ofEpochDay(st.periodEnd), st) else calc
            out += cycle
            cursor = cycle.start.minusDays(1)
        }
        return out
    }

    fun summarize(cycle: Cycle, accountTxns: List<Txn>, cats: Map<String, Category>, previous: Statement?): CycleSummary {
        val inCycle = accountTxns.filter { !it.deleted && LocalDate.ofEpochDay(it.date) in cycle }
        var charges = 0L
        var credits = 0L
        var payments = 0L
        for (t in inCycle) {
            when {
                t.amountCents < 0 -> charges += -t.amountCents
                t.categoryId?.let { cats[it]?.kind } == CategoryKind.TRANSFER -> payments += t.amountCents
                else -> credits += t.amountCents
            }
        }
        // New balance - previous balance should equal charges - credits - payments.
        val st = cycle.statement
        val unexplained = if (st?.closingBalanceCents != null && previous?.closingBalanceCents != null) {
            (st.closingBalanceCents - previous.closingBalanceCents) - (charges - credits - payments)
        } else {
            null
        }
        return CycleSummary(cycle, charges, credits, payments, inCycle.size, unexplained)
    }

    /** Projected charges for a cycle still in progress, at the current pace. */
    fun projectedCharges(summary: CycleSummary, today: LocalDate): Long {
        val elapsed = (ChronoUnit.DAYS.between(summary.cycle.start, today) + 1).coerceIn(1, summary.cycle.days.toLong())
        return summary.chargesCents * summary.cycle.days / elapsed
    }

    /** Stretches of 21+ days without imported activity between the first and last import. */
    fun gaps(accountTxns: List<Txn>, minDays: Long = 21): List<Gap> {
        val days = accountTxns.filter { !it.deleted }.map { it.date }.distinct().sorted()
        return days.zipWithNext().filter { (a, b) -> b - a > minDays }
            .map { (a, b) -> Gap(LocalDate.ofEpochDay(a + 1), LocalDate.ofEpochDay(b - 1)) }
    }

    /** Statements with a due date from a few days ago onward, soonest first. */
    fun upcomingBills(statements: List<Statement>, today: LocalDate): List<Statement> =
        statements.filter { !it.deleted && it.dueDate != null && it.dueDate >= today.minusDays(3).toEpochDay() && (it.closingBalanceCents ?: 0) > 0 }
            .sortedBy { it.dueDate }

    fun ordinal(day: Int): String = day.toString() + when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}
