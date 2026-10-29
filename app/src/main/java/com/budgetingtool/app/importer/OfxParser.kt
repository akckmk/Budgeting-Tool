package com.budgetingtool.app.importer

import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money

/**
 * OFX / QFX (Quicken Web Connect) reader. Works for both SGML OFX 1.x (no closing tags on
 * leaf elements) and XML OFX 2.x. Chase, KeyBank, M&T and most credit unions offer this.
 */
object OfxParser {
    private val blockRegex = Regex("<STMTTRN>(.*?)</STMTTRN>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(text: String): ParseResult {
        val upper = text.uppercase()
        val isCard = upper.contains("<CCSTMTRS>") || upper.contains("<CREDITCARDMSGSRSV1>")
        val type = when {
            isCard -> AccountType.CREDIT_CARD
            else -> when (tag(text, "ACCTTYPE")?.uppercase()) {
                "SAVINGS", "MONEYMRKT" -> AccountType.SAVINGS
                "CREDITLINE" -> AccountType.CREDIT_CARD
                else -> AccountType.CHECKING
            }
        }
        val last4 = tag(text, "ACCTID")?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }
        val bankSample = listOfNotNull(tag(text, "ORG"), tag(text, "FID")).joinToString(" ")

        var blocks = blockRegex.findAll(text).map { it.groupValues[1] }.toList()
        if (blocks.isEmpty()) {
            // Some exporters even omit </STMTTRN>; split on the opening tag instead.
            blocks = text.split(Regex("<STMTTRN>", RegexOption.IGNORE_CASE)).drop(1)
                .map { it.substringBefore("</BANKTRANLIST>").substringBefore("</banktranlist>") }
        }
        val out = blocks.mapNotNull { b ->
            val dateRaw = tag(b, "DTUSER") ?: tag(b, "DTPOSTED") ?: return@mapNotNull null
            val date = Dates.parse(dateRaw.take(8)) ?: return@mapNotNull null
            val amount = tag(b, "TRNAMT")?.let { Money.parseCents(it) } ?: return@mapNotNull null
            val name = tag(b, "NAME")
            val memo = tag(b, "MEMO")
            val desc = when {
                name.isNullOrBlank() -> memo ?: "Unknown"
                // Chase truncates NAME at 32 chars and puts the rest in MEMO.
                memo != null && name.length >= 30 && !name.contains(memo) -> "$name $memo"
                else -> name
            }
            ParsedTxn(date, decode(desc), amount, tag(b, "FITID"))
        }
        if (out.isEmpty()) return ParseResult.error("OFX", "No transactions were found in this OFX/QFX file.")
        return ParseResult("OFX/QFX", out, Bank.detect(bankSample), type, last4)
    }

    private fun tag(src: String, name: String): String? =
        Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&apos;", "'").replace("&quot;", "\"")
}

/** QIF (older Quicken format some credit unions still offer). */
object QifParser {
    fun parse(text: String): ParseResult {
        val header = text.lineSequence().firstOrNull { it.startsWith("!Type", ignoreCase = true) }?.uppercase() ?: ""
        val type = when {
            header.contains("CCARD") -> AccountType.CREDIT_CARD
            header.contains("CASH") -> AccountType.CASH
            else -> AccountType.CHECKING
        }
        val out = ArrayList<ParsedTxn>()
        var date: String? = null
        var amount: String? = null
        var payee: String? = null
        var memo: String? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            when (line[0]) {
                'D' -> date = line.substring(1)
                'T', 'U' -> amount = line.substring(1)
                'P' -> payee = line.substring(1)
                'M' -> memo = line.substring(1)
                '^' -> {
                    val d = date?.let { Dates.parse(it.replace("'", "/").replace(" ", "")) }
                    val a = amount?.let { Money.parseCents(it) }
                    if (d != null && a != null) out += ParsedTxn(d, (payee ?: memo ?: "Unknown").trim(), a)
                    date = null; amount = null; payee = null; memo = null
                }
            }
        }
        if (out.isEmpty()) return ParseResult.error("QIF", "No transactions were found in this QIF file.")
        return ParseResult("QIF", out, accountType = type)
    }
}
