package com.budgetingtool.app

import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.data.DefaultCategories
import com.budgetingtool.app.data.Statement
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.data.TxnSource
import com.budgetingtool.app.importer.PdfStatementParser
import com.budgetingtool.app.insights.BillingCycles
import com.budgetingtool.app.sync.SyncJson
import com.budgetingtool.app.sync.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BillingCycleTests {
    private val cats = DefaultCategories.all.associateBy { it.id }
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun txn(date: LocalDate, cents: Long, cat: String = DefaultCategories.SHOPPING) = Txn(
        id = "$date$cents", accountId = "card", date = date.toEpochDay(), description = "X", merchant = "X",
        amountCents = cents, categoryId = cat, source = TxnSource.IMPORT, updatedAt = 1,
    )

    @Test
    fun cycleBoundaries() {
        val c = BillingCycles.cycleContaining(d(2026, 10, 20), 14)
        assertEquals(d(2026, 10, 15), c.start)
        assertEquals(d(2026, 11, 14), c.end)
        val onClose = BillingCycles.cycleContaining(d(2026, 10, 14), 14)
        assertEquals(d(2026, 10, 14), onClose.end)
        // A card closing on the 31st closes on Feb 28 in February.
        val feb = BillingCycles.cycleContaining(d(2027, 2, 10), 31)
        assertEquals(d(2027, 2, 1), feb.start)
        assertEquals(d(2027, 2, 28), feb.end)
    }

    @Test
    fun statementDatesWinAndReconcile() {
        val acct = Account("card", "Chase", "Chase", AccountType.CREDIT_CARD, updatedAt = 1, closingDay = 14)
        // Bank shifted this close to the 13th (a weekend).
        val sep = Statement("s1", "card", d(2026, 8, 15).toEpochDay(), d(2026, 9, 14).toEpochDay(), closingBalanceCents = 50000, updatedAt = 1)
        val oct = Statement("s2", "card", d(2026, 9, 15).toEpochDay(), d(2026, 10, 13).toEpochDay(), closingBalanceCents = 62000, dueDate = d(2026, 11, 8).toEpochDay(), updatedAt = 1)
        val cycles = BillingCycles.cycles(acct, listOf(sep, oct), today = d(2026, 10, 29), count = 3)
        assertEquals(d(2026, 11, 14), cycles[0].end)
        assertEquals(d(2026, 10, 13), cycles[1].end) // printed date, not the calculated 14th
        assertEquals(oct, cycles[1].statement)
        // 62,000 - 50,000 = 12,000 change; charges 62,000 - payment 50,000 → explained exactly.
        val txns = listOf(
            txn(d(2026, 9, 20), -40000), txn(d(2026, 10, 1), -22000),
            txn(d(2026, 10, 5), 50000, DefaultCategories.TRANSFER),
        )
        val s = BillingCycles.summarize(cycles[1], txns, cats, previous = sep)
        assertEquals(62000L, s.chargesCents)
        assertEquals(50000L, s.paymentsCents)
        assertEquals(0L, s.unexplainedCents)
        // Missing purchase shows up as unexplained.
        val missing = BillingCycles.summarize(cycles[1], txns.drop(1), cats, previous = sep)
        assertEquals(40000L, missing.unexplainedCents)
        assertEquals(listOf(oct), BillingCycles.upcomingBills(listOf(sep, oct), d(2026, 10, 29)))
    }

    @Test
    fun gapsFindMissingStatements() {
        val g = BillingCycles.gaps(listOf(txn(d(2026, 7, 1), -1), txn(d(2026, 7, 10), -1), txn(d(2026, 9, 1), -1)))
        assertEquals(1, g.size)
        assertEquals(d(2026, 7, 11), g[0].from)
    }

    @Test
    fun pdfStatementDetails() {
        val text = """
            CHASE FREEDOM UNLIMITED
            Opening/Closing Date 09/15/26 - 10/14/26
            New Balance ${'$'}1,234.56
            Payment Due Date: 11/08/26
            Minimum Payment Due: ${'$'}35.00
            Credit Limit ${'$'}10,000
            10/02 STARBUCKS STORE 123 5.25
        """.trimIndent()
        val info = PdfStatementParser.statementInfo(text.uppercase(), d(2026, 10, 29))
        assertNotNull(info)
        assertEquals(d(2026, 9, 15), info!!.periodStart)
        assertEquals(d(2026, 10, 14), info.periodEnd)
        assertEquals(123456L, info.closingBalanceCents)
        assertEquals(d(2026, 11, 8), info.dueDate)
        assertEquals(3500L, info.minPaymentCents)
        val parsed = PdfStatementParser.parseText(text, d(2026, 10, 29))
        assertEquals(d(2026, 10, 14), parsed.statement?.periodEnd)

        val bank = PdfStatementParser.statementInfo("STATEMENT PERIOD: SEPTEMBER 29, 2026 THROUGH OCTOBER 27, 2026\nENDING BALANCE \$3,341.98", d(2026, 10, 29))
        assertEquals(d(2026, 9, 29), bank?.periodStart)
        assertEquals(334198L, bank?.closingBalanceCents)
        assertNull(PdfStatementParser.statementInfo("NO DATES HERE", d(2026, 10, 29)))
    }

    @Test
    fun olderAppsHelloStillDecodes() {
        val old = """{"t":"hello","deviceId":"a","deviceName":"Sam","protocol":1}"""
        val msg = SyncJson.decodeFromString(SyncMessage.serializer(), old) as SyncMessage.Hello
        assertEquals(0, msg.port)
    }
}
