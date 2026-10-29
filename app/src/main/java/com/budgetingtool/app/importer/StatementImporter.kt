package com.budgetingtool.app.importer

import android.content.Context
import android.net.Uri
import com.budgetingtool.app.data.AccountType

/** Picks the right reader for a picked file. Everything runs on-device. */
object StatementImporter {
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun parse(context: Context, uri: Uri, fileName: String): ParseResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return ParseResult.error("?", "Couldn't open $fileName.")
        if (bytes.size > MAX_BYTES) return ParseResult.error("?", "$fileName is larger than 25 MB.")

        val lower = fileName.lowercase()
        val isPdf = lower.endsWith(".pdf") || (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF")
        if (isPdf) {
            val r = PdfStatementParser.parse(context, bytes)
            return r.copy(bank = r.bank ?: Bank.detect(fileName))
        }
        return parseText(fileName, String(bytes, Charsets.UTF_8).removePrefix("﻿"))
    }

    fun parseText(fileName: String, text: String): ParseResult {
        val lower = fileName.lowercase()
        val head = text.take(2000).uppercase()
        val r = when {
            lower.endsWith(".ofx") || lower.endsWith(".qfx") || head.contains("OFXHEADER") || head.contains("<OFX>") ->
                OfxParser.parse(text)
            lower.endsWith(".qif") || head.trimStart().startsWith("!TYPE") -> QifParser.parse(text)
            else -> CsvStatementParser.parse(text)
        }
        val last4FromName = Regex("[A-Za-z](\\d{4})(?!\\d)").find(fileName)?.groupValues?.get(1)
        return r.copy(bank = r.bank ?: Bank.detect(fileName), last4 = r.last4 ?: last4FromName)
    }

    /**
     * Some card exports list purchases as positive numbers. If most non-payment rows on a credit
     * card are positive, suggest flipping so purchases become money out.
     */
    fun suggestFlip(result: ParseResult, type: AccountType?): Boolean {
        if (type != AccountType.CREDIT_CARD) return false
        val rows = result.transactions.filterNot { it.description.uppercase().contains("PAYMENT") }
        if (rows.isEmpty()) return false
        return rows.count { it.amountCents > 0 } > rows.size * 0.6
    }
}
