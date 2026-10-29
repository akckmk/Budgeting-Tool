package com.budgetingtool.app.importer

import android.content.Context
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import kotlin.math.abs

/**
 * Best-effort reader for PDF statements, done entirely on the phone.
 *
 * Statement PDFs are laid out for printing, not for machines, so this looks for lines shaped like
 * `MM/DD [MM/DD] DESCRIPTION AMOUNT [BALANCE]`, then decides each amount's direction from
 * (in order) the card/bank statement type, the running balance, or the section heading the line
 * sits under ("Deposits and Additions", "Withdrawals", ...). The import preview lets the user fix
 * anything it gets wrong.
 */
object PdfStatementParser {

    fun parse(context: Context, bytes: ByteArray): ParseResult {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(bytes).use { doc ->
                val stripper = PDFTextStripper()
                stripper.setSortByPosition(true)
                parseText(stripper.getText(doc))
            }
        } catch (e: InvalidPasswordException) {
            ParseResult.error("PDF", "This PDF is password-protected. Open it in your bank's site and download an unprotected copy, or use CSV/QFX.")
        } catch (e: Exception) {
            ParseResult.error("PDF", "Couldn't read this PDF (${e.javaClass.simpleName}). Try the CSV or QFX download instead.")
        }
    }

    private const val AMT = """-?\(?-?\$?\d{1,3}(?:,\d{3})*\.\d{2}\)?(?:\s?(?:CR|DR|-))?"""
    private val lineRegex = Regex(
        """^(\d{1,2}/\d{1,2}(?:/\d{2,4})?)\s+(?:(\d{1,2}/\d{1,2}(?:/\d{2,4})?)\s+)?(.*?\S)\s+($AMT)(?:\s+($AMT))?$""",
    )
    private val openingBalance = Regex("""(?:BEGINNING|OPENING|PREVIOUS|STARTING) BALANCE.*?(-?\$?\d{1,3}(?:,\d{3})*\.\d{2}-?)""")
    private val fullDate = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})\b""")
    private val wordDate = Regex("""\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.? (\d{1,2}), (\d{4})\b""")
    private val last4Regex = Regex("""(?:ENDING IN|ACCOUNT NUMBER|ACCOUNT #|ACCOUNT NO\.?|CARD NUMBER)[:\s]*[X*\d\s-]*?(\d{4})\b""")

    private val skipWords = listOf("TOTAL", "BALANCE", "SUBTOTAL", "MINIMUM PAYMENT", "PAYMENT DUE", "CREDIT LIMIT", "AVAILABLE CREDIT")
    private val positiveSections = listOf("DEPOSIT", "ADDITION", "CREDITS", "PAYMENTS AND OTHER CREDITS", "INTEREST PAID", "INCOMING")
    private val negativeSections = listOf("WITHDRAWAL", "DEBITS", "CHECKS PAID", "CHECKS CLEARED", "PURCHASE", "FEES", "CHARGES", "ELECTRONIC PAYMENTS", "CARD TRANSACTIONS", "ATM", "OUTGOING", "SUBTRACTIONS")

    fun parseText(text: String, today: LocalDate = LocalDate.now()): ParseResult {
        val upper = text.uppercase()
        val cardMarkers = listOf("CREDIT LIMIT", "MINIMUM PAYMENT DUE", "PAYMENT DUE DATE", "CREDIT ACCESS LINE", "AVAILABLE CREDIT", "CASH ADVANCE")
        val isCard = cardMarkers.count { upper.contains(it) } >= 2
        val end = statementEndDate(upper, today)

        var section = 0
        var prevBalance: Long? = null
        var guessed = 0
        val out = ArrayList<ParsedTxn>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) continue
            val lineUp = line.uppercase()
            val m = lineRegex.find(line)
            if (m == null) {
                openingBalance.find(lineUp)?.let { prevBalance = Money.parseCents(it.groupValues[1]) }
                sectionOf(lineUp)?.let { section = it }
                continue
            }
            val desc = m.groupValues[3].trim()
            val descUp = desc.uppercase()
            val rawAmt = m.groupValues[4]
            val balance = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { Money.parseCents(it) }
            if (skipWords.any { descUp.contains(it) }) {
                if (descUp.contains("BALANCE")) prevBalance = balance ?: Money.parseCents(rawAmt)
                continue
            }
            val date = resolveDate(m.groupValues[1], end) ?: continue
            var raw = Money.parseCents(rawAmt) ?: continue
            if (rawAmt.uppercase().contains("CR")) raw = -abs(raw)

            val pb = prevBalance
            val amount = when {
                // Card statements print purchases as positive and payments/credits as negative.
                isCard -> -raw
                balance != null && pb != null && abs(abs(balance - pb) - abs(raw)) <= 1 ->
                    if (balance >= pb) abs(raw) else -abs(raw)
                raw < 0 -> raw
                section != 0 -> section * abs(raw)
                else -> { guessed++; -abs(raw) }
            }
            if (balance != null) prevBalance = balance
            out += ParsedTxn(date, desc, amount)
        }

        if (out.isEmpty()) {
            return ParseResult.error(
                "PDF",
                "No transaction lines were recognized in this PDF. Scanned (image) statements can't be read; use your bank's CSV or QFX download.",
            )
        }
        val warnings = buildList {
            add("PDF reading is best-effort. Check the preview, especially amounts marked as income.")
            if (guessed > 0) add("$guessed rows had no clear direction and were treated as purchases.")
        }
        val last4 = last4Regex.find(upper)?.groupValues?.get(1)
        return ParseResult(
            format = "PDF",
            transactions = out,
            bank = Bank.detect(upper.take(3000)),
            accountType = if (isCard) AccountType.CREDIT_CARD else AccountType.CHECKING,
            last4 = last4,
            warnings = warnings,
        )
    }

    private fun sectionOf(lineUp: String): Int? {
        if (lineUp.length > 70 || Regex("""\d+\.\d{2}""").containsMatchIn(lineUp)) return null
        val pos = positiveSections.any { lineUp.contains(it) }
        val neg = negativeSections.any { lineUp.contains(it) }
        return when {
            pos && !neg -> 1
            neg && !pos -> -1
            else -> null
        }
    }

    /** Latest full date printed on the statement (closing date, or the due date just after it). */
    fun statementEndDate(upper: String, today: LocalDate): LocalDate {
        val limit = today.plusDays(45)
        val dates = ArrayList<LocalDate>()
        fullDate.findAll(upper).forEach { m ->
            val (mo, d, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()?.let { dates += it }
        }
        wordDate.findAll(upper).forEach { m ->
            Dates.parse("${m.groupValues[1]} ${m.groupValues[2]}, ${m.groupValues[3]}")?.let { dates += it }
        }
        return dates.filter { it <= limit && it.year >= 2000 }.maxOrNull() ?: today
    }

    /** "10/12" on a statement ending 1/5/2027 is 10/12/2026. */
    fun resolveDate(token: String, end: LocalDate): LocalDate? {
        val parts = token.split("/")
        val month = parts[0].toIntOrNull() ?: return null
        val day = parts[1].toIntOrNull() ?: return null
        if (parts.size == 3) {
            val y = parts[2].toIntOrNull() ?: return null
            return runCatching { LocalDate.of(if (y < 100) 2000 + y else y, month, day) }.getOrNull()
        }
        val candidate = runCatching { LocalDate.of(end.year, month, day) }.getOrNull() ?: return null
        return if (candidate.isAfter(end.plusDays(5))) candidate.minusYears(1) else candidate
    }
}
