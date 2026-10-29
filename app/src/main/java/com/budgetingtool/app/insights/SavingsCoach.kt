package com.budgetingtool.app.insights

import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.DefaultCategories
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.pow
import kotlin.math.roundToLong

/** A spending pattern noticed in the data, e.g. "You buy coffee 18 times a month". */
data class Habit(val emoji: String, val title: String, val detail: String)

/** A concrete change and what it is worth per month. */
data class SavingsTip(
    val emoji: String,
    val title: String,
    val why: String,
    val monthlyCents: Long,
)

/** What a monthly saving turns into over time. */
data class Projection(val label: String, val savedCents: Long, val investedCents: Long)

/**
 * Looks at roughly the last three months of spending, describes habits in plain words, and
 * suggests realistic cuts with a dollar value attached, so "spend less" becomes
 * "make coffee at home twice a week = $41/month = $2,600 in 5 years".
 */
class SavingsCoach(private val txns: List<Txn>, private val cats: Map<String, Category>, private val today: LocalDate = LocalDate.now()) {

    /** Up to the last three complete months, plus the current month if there's nothing older. */
    private val window: List<YearMonth> = run {
        val current = YearMonth.from(today)
        val past = (1..3).map { current.minusMonths(it.toLong()) }.filter { m -> BudgetMath.inMonth(txns, m).isNotEmpty() }
        past.ifEmpty { listOf(current) }
    }

    /** Months of data behind the averages (a partial current month counts as its fraction). */
    private val months: Double = if (window.size == 1 && window[0] == YearMonth.from(today)) {
        today.dayOfMonth.toDouble() / window[0].lengthOfMonth()
    } else {
        window.size.toDouble()
    }

    private val sample: List<Txn> = window.flatMap { BudgetMath.inMonth(txns, it) }.filter { BudgetMath.spending(it, cats) > 0 }

    private fun perMonth(cents: Long) = (cents / months).roundToLong()
    private fun perMonthCount(n: Int) = (n / months).roundToLong()
    private fun spend(t: Txn) = BudgetMath.spending(t, cats)
    private fun matches(t: Txn, words: List<String>): Boolean {
        val up = (t.description + " " + t.merchant).uppercase()
        return words.any { up.contains(it) }
    }

    val hasData: Boolean get() = sample.size >= 5

    val monthlySpend: Long get() = perMonth(sample.sumOf(::spend))

    private val coffee = sample.filter { matches(it, listOf("STARBUCKS", "DUNKIN", "TIM HORTONS", "COFFEE", "CAFE", "PEET")) }
    private val delivery = sample.filter { matches(it, listOf("DOORDASH", "UBER EATS", "UBEREATS", "GRUBHUB", "POSTMATES", "INSTACART")) }
    private val dining = sample.filter { it.categoryId == DefaultCategories.DINING }
    private val shopping = sample.filter { it.categoryId == DefaultCategories.SHOPPING }
    private val online = sample.filter { matches(it, listOf("AMAZON", "AMZN", "ETSY", "EBAY", "TEMU", "SHEIN", "WALMART.COM", "TARGET.COM")) }
    private val fees = sample.filter { it.categoryId == DefaultCategories.FEES }
    private val small = sample.filter { spend(it) in 1..1500 }
    private val weekend = sample.filter { LocalDate.ofEpochDay(it.date).dayOfWeek.value >= 6 }
    private val subscriptions = InsightsEngine(txns, cats).recurring(YearMonth.from(today))
        .filter { it.categoryId == DefaultCategories.SUBSCRIPTIONS || (it.categoryId == DefaultCategories.ENTERTAINMENT && it.avgCents <= 3000) }

    fun habits(): List<Habit> {
        if (!hasData) return emptyList()
        val out = ArrayList<Habit>()
        if (coffee.isNotEmpty()) {
            val n = perMonthCount(coffee.size)
            out += Habit("☕", "About $n coffee runs a month", "Averaging ${Money.format(coffee.sumOf(::spend) / coffee.size)} each, ${Money.format(perMonth(coffee.sumOf(::spend)), false)} a month.")
        }
        if (delivery.isNotEmpty()) {
            out += Habit("🛵", "Food delivery ${perMonthCount(delivery.size)}× a month", "${Money.format(perMonth(delivery.sumOf(::spend)), false)} a month on delivery apps; fees and tips usually add 20–30%.")
        }
        if (dining.size >= 3) {
            out += Habit("🍽️", "Eating out ${perMonthCount(dining.size)}× a month", "${Money.format(perMonth(dining.sumOf(::spend)), false)} a month at restaurants and cafés.")
        }
        if (online.size >= 3) {
            out += Habit("📦", "${perMonthCount(online.size)} online orders a month", "${Money.format(perMonth(online.sumOf(::spend)), false)} a month. Lots of small orders often means impulse buys.")
        }
        if (small.size >= 10) {
            out += Habit("🪙", "${perMonthCount(small.size)} small purchases a month", "Under $15 each, but together ${Money.format(perMonth(small.sumOf(::spend)), false)} a month.")
        }
        val total = sample.sumOf(::spend)
        val weekendShare = if (total > 0) weekend.sumOf(::spend) * 100 / total else 0
        if (weekendShare >= 40) {
            out += Habit("🎉", "Weekends are your big spending days", "$weekendShare% of spending lands on Saturday and Sunday.")
        }
        if (subscriptions.isNotEmpty()) {
            out += Habit("🔁", "${subscriptions.size} subscriptions renewing", "${Money.format(subscriptions.sumOf { it.avgCents }, false)} a month, every month, whether you use them or not.")
        }
        if (fees.isNotEmpty()) {
            out += Habit("⚠️", "Paying bank fees and interest", "${Money.format(perMonth(fees.sumOf(::spend)), false)} a month that buys nothing.")
        }
        return out
    }

    fun tips(): List<SavingsTip> {
        if (!hasData) return emptyList()
        val out = ArrayList<SavingsTip>()
        if (coffee.size >= 4) {
            val avg = coffee.sumOf(::spend) / coffee.size
            val cut = minOf(perMonthCount(coffee.size), 8L) // two fewer a week
            out += SavingsTip("☕", "Make coffee at home twice a week", "Skipping $cut café visits a month at about ${Money.format(avg)} each.", cut * avg * 85 / 100)
        }
        if (delivery.isNotEmpty()) {
            out += SavingsTip("🛵", "Swap half of delivery orders for pickup or cooking", "Delivery adds fees, menu markups and tips.", perMonth(delivery.sumOf(::spend)) / 2 * 60 / 100)
        }
        val diningMonthly = perMonth(dining.filterNot { it in coffee || it in delivery }.sumOf(::spend))
        if (diningMonthly >= 5000) {
            out += SavingsTip("🍽️", "Eat out 25% less", "Plan one more home-cooked dinner a week.", diningMonthly / 4)
        }
        if (subscriptions.size >= 2) {
            val cheapest = subscriptions.sortedBy { it.avgCents }.take(maxOf(1, subscriptions.size / 3))
            out += SavingsTip("🔁", "Cancel ${cheapest.size} subscription${if (cheapest.size > 1) "s" else ""} you use least", cheapest.joinToString { it.name }, cheapest.sumOf { it.avgCents })
        }
        val shoppingMonthly = perMonth(shopping.sumOf(::spend))
        if (shoppingMonthly >= 10000) {
            out += SavingsTip("🛍️", "Try a 48-hour wait on non-essentials", "Put it in the cart, decide in two days. Most people skip about 1 in 5 buys.", shoppingMonthly / 5)
        }
        if (small.size >= 10) {
            out += SavingsTip("🪙", "Cap small treats at 3 a week", "Little purchases are the easiest to forget.", perMonth(small.sumOf(::spend)) / 3)
        }
        if (fees.isNotEmpty()) {
            out += SavingsTip("⚠️", "Stop paying fees and interest", "Turn on autopay for the full card balance and low-balance alerts.", perMonth(fees.sumOf(::spend)))
        }
        return out.filter { it.monthlyCents >= 500 }.sortedByDescending { it.monthlyCents }
    }

    companion object {
        /** Assumed yearly return if savings are put to work (e.g. high-yield savings / index fund). */
        const val GROWTH_RATE = 0.05

        fun projections(monthlyCents: Long): List<Projection> = listOf(
            Projection("1 year", monthlyCents * 12, futureValue(monthlyCents, 1)),
            Projection("5 years", monthlyCents * 60, futureValue(monthlyCents, 5)),
            Projection("10 years", monthlyCents * 120, futureValue(monthlyCents, 10)),
            Projection("20 years", monthlyCents * 240, futureValue(monthlyCents, 20)),
        )

        /** Monthly deposits compounded monthly. */
        fun futureValue(monthlyCents: Long, years: Int, annualRate: Double = GROWTH_RATE): Long {
            val r = annualRate / 12
            val n = years * 12
            return (monthlyCents * (((1 + r).pow(n) - 1) / r)).roundToLong()
        }
    }
}
