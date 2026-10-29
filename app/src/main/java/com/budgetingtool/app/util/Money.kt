package com.budgetingtool.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** All money is stored as Long cents. Negative = money leaving the account. */
object Money {
    private val amountPattern = Regex("""\d+(\.\d{1,2})?|\.\d{1,2}""")

    fun format(cents: Long, showCents: Boolean = true): String {
        val f = NumberFormat.getCurrencyInstance(Locale.US)
        if (!showCents) {
            f.maximumFractionDigits = 0
            f.minimumFractionDigits = 0
        }
        return f.format(cents / 100.0)
    }

    fun formatSigned(cents: Long): String = if (cents > 0) "+" + format(cents) else format(cents)

    /**
     * Parses bank-style amounts: "$1,234.56", "-12.00", "(12.00)", "12.00 CR", "12.00-", "$-5".
     * Returns null when the text is not an amount. "CR" is treated as a plain positive marker.
     */
    fun parseCents(raw: String): Long? {
        var s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        var negative = false
        if (s.endsWith("CR")) s = s.removeSuffix("CR").trim()
        if (s.endsWith("DR")) {
            s = s.removeSuffix("DR").trim()
            negative = true
        }
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        if (s.endsWith("-")) {
            negative = true
            s = s.dropLast(1)
        }
        s = s.replace("$", "").replace(",", "").replace(" ", "")
        if (s.startsWith("+")) s = s.drop(1)
        if (s.startsWith("-")) {
            negative = true
            s = s.drop(1)
        }
        if (!amountPattern.matches(s)) return null
        val v = BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        return if (negative) -v else v
    }
}

object Dates {
    private val patterns = listOf(
        "M/d/yyyy", "M/d/yy", "yyyy-MM-dd", "M-d-yyyy", "M-d-yy", "yyyy/MM/dd", "M.d.yyyy",
        "MMM d, yyyy", "MMM d yyyy", "MMMM d, yyyy", "d-MMM-yyyy", "d MMM yyyy", "d-MMM-yy", "yyyyMMdd",
    )
    private val formatters: List<DateTimeFormatter> = patterns.map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.US)
    }

    fun parse(raw: String): LocalDate? {
        val s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        tryParse(s)?.let { return it }
        // "10/28/2026 12:00:00 AM" or "2026-10-28T00:00:00"
        val head = s.substringBefore(' ').substringBefore('T')
        return if (head != s) tryParse(head) else null
    }

    private fun tryParse(s: String): LocalDate? {
        for (f in formatters) {
            try {
                return LocalDate.parse(s, f)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    val shortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    val longFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    val monthShort: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
}
