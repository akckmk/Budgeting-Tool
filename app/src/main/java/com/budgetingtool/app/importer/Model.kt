package com.budgetingtool.app.importer

import com.budgetingtool.app.data.AccountType
import java.time.LocalDate

/** One row read from a statement, before it is assigned to an account. */
data class ParsedTxn(
    val date: LocalDate,
    val description: String,
    /** Negative = money out, using the account holder's point of view. */
    val amountCents: Long,
    /** Bank-provided unique id (OFX FITID) when available. */
    val externalId: String? = null,
)

data class ParseResult(
    val format: String,
    val transactions: List<ParsedTxn>,
    val bank: Bank? = null,
    val accountType: AccountType? = null,
    val last4: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    companion object {
        fun error(format: String, message: String) = ParseResult(format, emptyList(), error = message)
    }
}

/**
 * Banks the app knows by name. Every bank works through the generic CSV / OFX / QFX / QIF /
 * PDF readers; the profile only helps name the account and explains where the export lives.
 */
enum class Bank(val display: String, private val keywords: List<String>, val howTo: String) {
    FIRST_NIAGARA(
        "First Niagara (now KeyBank)",
        listOf("FIRST NIAGARA"),
        "First Niagara accounts moved to KeyBank in 2016. Older First Niagara PDF statements import here; " +
            "for current activity use KeyBank's download.",
    ),
    EMPOWER(
        "Empower FCU",
        listOf("EMPOWER FEDERAL", "EMPOWER FCU", "EMPOWERFCU"),
        "In online banking, open the account's history and look for Export / Download. Choose CSV, " +
            "Quicken (QFX) or OFX. Monthly eStatements download as PDF.",
    ),
    BEGINNING(
        "Beginning FCU",
        listOf("BEGINNING FEDERAL", "BEGINNING FCU", "BEGINNINGFCU"),
        "In online banking, open the account's transaction history and use Export / Download " +
            "(CSV, QFX or OFX). eStatements download as PDF.",
    ),
    MT(
        "M&T Bank",
        listOf("M&T BANK", "M & T BANK", "MANUFACTURERS AND TRADERS", "MTB.COM"),
        "Sign in at mtb.com, open the account, and choose Download transactions. CSV or " +
            "Quicken/QFX both work. Statements under Documents download as PDF.",
    ),
    KEYBANK(
        "KeyBank",
        listOf("KEYBANK", "KEY BANK", "KEYCORP", "KEY.COM"),
        "Sign in at key.com, open the account, and use Download / Export transactions. Pick CSV or " +
            "Quicken (QFX). Statements download as PDF.",
    ),
    CHASE(
        "Chase",
        listOf("JPMORGAN CHASE", "CHASE BANK", "CHASE.COM", "CHASE FREEDOM", "CHASE SAPPHIRE", "CHASE"),
        "Sign in at chase.com, open the account, and tap the download icon (Download account activity). " +
            "Choose a date range and CSV or Quicken Web Connect (QFX). Monthly statements are PDFs.",
    ),
    OTHER(
        "Other bank or card",
        emptyList(),
        "Almost every bank has Download, Export or Statements in online banking. CSV, QFX/OFX, QIF and " +
            "PDF all import. CSV or QFX give the most accurate results.",
    );

    companion object {
        /** [sample] should be a file name or header, not the transaction list: other banks' rows mention Chase. */
        fun detect(sample: String): Bank? {
            val up = sample.uppercase()
            return entries.firstOrNull { b -> b.keywords.any { up.contains(it) } }
        }
    }
}
