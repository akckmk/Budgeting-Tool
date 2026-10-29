package com.budgetingtool.app.receipt

import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate

/** What could be read off a receipt. Any field may be missing; the user confirms before saving. */
data class ReceiptInfo(
    val merchant: String?,
    val totalCents: Long?,
    val date: LocalDate?,
)

/**
 * Pulls the store name, total and date out of OCR text. OCR output is noisy (O for 0, stray
 * symbols, split lines), so every rule is forgiving and falls back to something sensible.
 */
object ReceiptParser {
    private val amountRegex = Regex("""(?<![\d.])\$?\s?(\d{1,5}(?:,\d{3})*[.,]\d{2})(?!\d)""")
    private val dateRegexes = listOf(
        Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\b"""),
        Regex("""\b(\d{4}-\d{2}-\d{2})\b"""),
        Regex("""\b((?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.? \d{1,2},? \d{4})\b""", RegexOption.IGNORE_CASE),
    )

    // Strongest first. "SUBTOTAL" and "TOTAL SAVINGS" are deliberately excluded below.
    private val totalLabels = listOf("GRAND TOTAL", "AMOUNT DUE", "BALANCE DUE", "TOTAL DUE", "TOTAL", "AMOUNT", "BALANCE", "SALE")
    private val notTotal = listOf("SUBTOTAL", "SUB TOTAL", "SUB-TOTAL", "SAVINGS", "SAVED", "DISCOUNT", "TAX", "TIP", "CHANGE", "CASH TEND", "ITEMS", "POINTS")
    private val notMerchant = listOf(
        "RECEIPT", "WELCOME", "THANK", "STORE #", "STORE NO", "TEL", "PHONE", "WWW", ".COM", "HTTP", "CASHIER", "REGISTER",
        "TRANS", "ORDER", "INVOICE", "DATE", "TIME", "CUSTOMER COPY", "MERCHANT COPY", "SALE",
    )

    fun parse(ocrText: String, today: LocalDate = LocalDate.now()): ReceiptInfo {
        val lines = ocrText.lines().map { cleanLine(it) }.filter { it.isNotBlank() }
        return ReceiptInfo(findMerchant(lines), findTotal(lines), findDate(lines, today))
    }

    /** Common OCR slips inside numbers: O→0, l/I→1, S→5 when surrounded by digits. */
    private fun cleanLine(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ")
        s = s.replace(Regex("(?<=\\d)[Oo](?=[\\d/.\\s]|$)"), "0").replace(Regex("(?<=\\d)[lI](?=\\d)"), "1")
        s = s.replace(Regex("(?<=\\d) ?[.,] ?(?=\\d{2}\\b)"), ".")
        return s
    }

    private fun amountsIn(line: String): List<Long> =
        amountRegex.findAll(line).mapNotNull { Money.parseCents(it.groupValues[1].replace(',', '.').let(::fixThousands)) }.toList()

    /** "1.234.56" after comma→dot replacement becomes "1234.56". */
    private fun fixThousands(s: String): String {
        val parts = s.split('.')
        return if (parts.size > 2) parts.dropLast(1).joinToString("") + "." + parts.last() else s
    }

    fun findTotal(lines: List<String>): Long? {
        for (label in totalLabels) {
            for ((i, line) in lines.withIndex()) {
                val up = line.uppercase()
                if (!up.contains(label) || notTotal.any { up.contains(it) && !up.contains("TOTAL DUE") }) continue
                // The amount is usually on the same line, sometimes on the next one.
                val amount = amountsIn(line).lastOrNull() ?: lines.getOrNull(i + 1)?.let { amountsIn(it).lastOrNull() }
                if (amount != null && amount > 0) return amount
            }
        }
        // No label survived OCR: the largest amount on a receipt is almost always the total.
        return lines.flatMap(::amountsIn).filter { it in 1..10_000_00 }.maxOrNull()
    }

    fun findMerchant(lines: List<String>): String? =
        lines.take(8).firstOrNull { line ->
            val up = line.uppercase()
            val letters = line.count { it.isLetter() }
            letters >= 3 && letters >= line.length / 2 &&
                notMerchant.none { up.contains(it) } &&
                amountsIn(line).isEmpty() &&
                !Regex("""\d{3}[-. )]\d{3}[-. ]\d{4}""").containsMatchIn(line) && // phone number
                !Regex("""^\d+ \w+ (ST|AVE|RD|BLVD|DR|LN|WAY|HWY|PKWY)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) // street address
        }?.let { name ->
            name.replace(Regex("[^A-Za-z0-9&' .-]"), "").replace(Regex("\\s+"), " ").trim()
                .lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                .take(40)
        }

    fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            for (r in dateRegexes) {
                val raw = r.find(line)?.groupValues?.get(1) ?: continue
                val d = Dates.parse(raw.replace('-', '/')) ?: Dates.parse(raw) ?: continue
                // A receipt from the future, or decades ago, is an OCR misread.
                if (!d.isAfter(today.plusDays(1)) && d.isAfter(today.minusYears(2))) return d
            }
        }
        return null
    }
}
