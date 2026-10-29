package com.budgetingtool.app.importer

import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import kotlin.math.abs

/**
 * Reads CSV exports from any bank by recognizing column names instead of hard-coding layouts.
 * Handles: a signed Amount column (Chase), separate Debit/Credit columns (many credit unions,
 * M&T, KeyBank), a Type column with DEBIT/CREDIT, and files with no header row at all.
 */
object CsvStatementParser {

    fun parse(text: String): ParseResult {
        val delimiter = guessDelimiter(text)
        val rows = readRows(text, delimiter).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ParseResult.error("CSV", "The file is empty.")
        val headerIdx = rows.take(25).indexOfFirst { looksLikeHeader(it) }
        return if (headerIdx >= 0) parseWithHeader(rows, headerIdx) else parseHeaderless(rows)
    }

    private fun guessDelimiter(text: String): Char {
        val sample = text.lineSequence().take(10).joinToString("\n")
        return listOf(',', ';', '\t', '|').maxBy { c -> sample.count { it == c } }
    }

    /** RFC 4180: quoted fields, doubled quotes, newlines inside quotes. */
    fun readRows(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val ch = s[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delimiter -> { row.add(cell.toString().trim()); cell.clear() }
                    '\r' -> {}
                    '\n' -> {
                        row.add(cell.toString().trim()); cell.clear()
                        rows.add(row); row = ArrayList()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString().trim())
            rows.add(row)
        }
        return rows
    }

    private fun norm(h: String) = h.lowercase().replace(Regex("[^a-z/# ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun looksLikeHeader(row: List<String>): Boolean {
        val h = row.map(::norm)
        val hasDate = h.any { it.contains("date") || it == "posted" }
        val hasMoney = h.any { it.contains("amount") || it in DEBIT_NAMES || it in CREDIT_NAMES }
        return hasDate && hasMoney
    }

    private val DATE_NAMES = listOf("transaction date", "trans date", "date", "posted date", "posting date", "post date", "effective date", "date posted")
    private val DESC_NAMES = listOf("description", "transaction description", "payee", "merchant", "merchant name", "name", "original description", "memo", "transaction")
    private val AMOUNT_NAMES = listOf("amount", "transaction amount", "amt", "amount usd", "net amount")
    private val DEBIT_NAMES = listOf("debit", "debits", "withdrawal", "withdrawals", "debit amount", "withdrawal amount", "money out", "amount debit")
    private val CREDIT_NAMES = listOf("credit", "credits", "deposit", "deposits", "credit amount", "deposit amount", "money in", "amount credit")
    private val TYPE_NAMES = listOf("type", "transaction type", "details", "credit/debit", "debit/credit", "dr/cr", "cr/dr", "debit or credit")
    private val DEBIT_WORDS = setOf("DEBIT", "DR", "D", "WITHDRAWAL", "DBIT")

    private fun parseWithHeader(rows: List<List<String>>, headerIdx: Int): ParseResult {
        val h = rows[headerIdx].map(::norm)
        fun find(names: List<String>, exclude: Set<Int> = emptySet()): Int =
            names.firstNotNullOfOrNull { n -> h.indices.firstOrNull { it !in exclude && h[it] == n } } ?: -1

        var dateCol = find(DATE_NAMES)
        if (dateCol < 0) dateCol = h.indexOfFirst { it.contains("date") }
        val descCol = find(DESC_NAMES).let { if (it >= 0) it else h.indexOfFirst { c -> c.contains("description") || c.contains("payee") } }
        val memoCol = find(listOf("memo", "notes", "additional info"), setOfNotNull(descCol))
        var amountCol = find(AMOUNT_NAMES)
        if (amountCol < 0) amountCol = h.indexOfFirst { it.contains("amount") && !it.contains("balance") }
        val debitCol = find(DEBIT_NAMES)
        val creditCol = find(CREDIT_NAMES)
        val typeCol = find(TYPE_NAMES, setOf(descCol, dateCol))
        val cardCol = h.indexOfFirst { it.contains("card no") || it.contains("card number") || it == "card" || it.contains("account number") }

        if (dateCol < 0 || (amountCol < 0 && debitCol < 0 && creditCol < 0)) {
            return ParseResult.error("CSV", "Couldn't find date and amount columns in this CSV.")
        }

        val out = ArrayList<ParsedTxn>()
        var skipped = 0
        var paymentLike = 0
        for (r in rows.drop(headerIdx + 1)) {
            fun cell(i: Int) = if (i in r.indices) r[i] else ""
            val date = Dates.parse(cell(dateCol))
            if (date == null) { skipped++; continue }
            val amount: Long = if (amountCol >= 0 && Money.parseCents(cell(amountCol)) != null) {
                var a = Money.parseCents(cell(amountCol))!!
                if (typeCol >= 0 && a > 0 && cell(typeCol).trim().uppercase() in DEBIT_WORDS) a = -a
                a
            } else {
                val d = if (debitCol >= 0) Money.parseCents(cell(debitCol)) else null
                val c = if (creditCol >= 0) Money.parseCents(cell(creditCol)) else null
                if (d == null && c == null) { skipped++; continue }
                abs(c ?: 0L) - abs(d ?: 0L)
            }
            var desc = cell(descCol).ifBlank { cell(memoCol) }
            if (desc.isBlank()) desc = "Unknown"
            if (amount > 0 && desc.uppercase().contains("PAYMENT") && desc.uppercase().contains("THANK")) paymentLike++
            out += ParsedTxn(date, desc.replace(Regex("\\s+"), " ").trim(), amount)
        }

        val header = h.joinToString(",")
        val type = when {
            header.contains("post date") && header.contains("category") -> AccountType.CREDIT_CARD // Chase card layout
            cardCol >= 0 && h[cardCol].contains("card") -> AccountType.CREDIT_CARD
            paymentLike > 0 -> AccountType.CREDIT_CARD
            header.contains("balance") -> AccountType.CHECKING
            else -> null
        }
        val last4 = if (cardCol >= 0) rows.drop(headerIdx + 1).firstNotNullOfOrNull { r ->
            r.getOrNull(cardCol)?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }
        } else null
        val warnings = if (skipped > 0) listOf("$skipped rows without a readable date or amount were skipped.") else emptyList()
        return ParseResult("CSV", out, accountType = type, last4 = last4, warnings = warnings)
    }

    /** No header row: find the date column, the amount column, and the longest text column. */
    private fun parseHeaderless(rows: List<List<String>>): ParseResult {
        val width = rows.maxOf { it.size }
        val sample = rows.take(50)
        fun ratio(col: Int, test: (String) -> Boolean) = sample.count { test(it.getOrElse(col) { "" }) }.toDouble() / sample.size
        val dateCol = (0 until width).firstOrNull { ratio(it) { s -> Dates.parse(s) != null } > 0.8 }
            ?: return ParseResult.error("CSV", "This CSV has no header and no recognizable date column.")
        val amountCol = (0 until width).firstOrNull { c ->
            c != dateCol && ratio(c) { s -> Money.parseCents(s) != null && Dates.parse(s) == null } > 0.8
        } ?: return ParseResult.error("CSV", "Couldn't find an amount column in this CSV.")
        val descCol = (0 until width).filter { it != dateCol && it != amountCol }
            .maxByOrNull { c -> sample.sumOf { it.getOrElse(c) { "" }.length } } ?: -1
        val out = rows.mapNotNull { r ->
            val d = Dates.parse(r.getOrElse(dateCol) { "" }) ?: return@mapNotNull null
            val a = Money.parseCents(r.getOrElse(amountCol) { "" }) ?: return@mapNotNull null
            ParsedTxn(d, r.getOrElse(descCol) { "Unknown" }.ifBlank { "Unknown" }, a)
        }
        return ParseResult("CSV", out, warnings = listOf("No header row found; columns were guessed. Check the preview."))
    }
}
