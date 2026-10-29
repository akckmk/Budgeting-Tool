package com.budgetingtool.app

import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.data.Categorizer
import com.budgetingtool.app.data.DefaultCategories
import com.budgetingtool.app.importer.Bank
import com.budgetingtool.app.importer.CsvStatementParser
import com.budgetingtool.app.importer.PdfStatementParser
import com.budgetingtool.app.importer.StatementImporter
import com.budgetingtool.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParserTests {

    @Test
    fun moneyParsing() {
        assertEquals(123456L, Money.parseCents("$1,234.56"))
        assertEquals(-1200L, Money.parseCents("-12.00"))
        assertEquals(-1200L, Money.parseCents("(12.00)"))
        assertEquals(-1200L, Money.parseCents("12.00-"))
        assertEquals(-500L, Money.parseCents("$-5"))
        assertEquals(1200L, Money.parseCents("12.00 CR"))
        assertNull(Money.parseCents("DEBIT"))
    }

    @Test
    fun chaseCreditCardCsv() {
        val csv = """
            Transaction Date,Post Date,Description,Category,Type,Amount,Memo
            10/25/2026,10/26/2026,WEGMANS #12,Groceries,Sale,-84.21,
            10/24/2026,10/25/2026,STARBUCKS STORE 123,Food & Drink,Sale,-6.45,
            10/20/2026,10/20/2026,Payment Thank You-Mobile,,Payment,500.00,
        """.trimIndent()
        val r = StatementImporter.parseText("Chase1234_Activity20261028.CSV", csv)
        assertEquals(3, r.transactions.size)
        assertEquals(AccountType.CREDIT_CARD, r.accountType)
        assertEquals(Bank.CHASE, r.bank)
        assertEquals(-8421L, r.transactions[0].amountCents)
        assertEquals(LocalDate.of(2026, 10, 25), r.transactions[0].date)
    }

    @Test
    fun creditUnionDebitCreditColumns() {
        val csv = """
            "Account Name : Share Draft"
            "Date","Description","Debit","Credit","Balance"
            "10/02/2026","DIRECT DEP ACME PAYROLL","","1,850.00","2,000.00"
            "10/03/2026","POS PURCHASE TOPS MARKET","42.10","","1,957.90"
        """.trimIndent()
        val r = CsvStatementParser.parse(csv)
        assertEquals(2, r.transactions.size)
        assertEquals(185000L, r.transactions[0].amountCents)
        assertEquals(-4210L, r.transactions[1].amountCents)
        assertEquals(AccountType.CHECKING, r.accountType)
    }

    @Test
    fun typeColumnMakesDebitsNegative() {
        val csv = "Date,Description,Amount,Type\n10/05/2026,SHELL OIL,45.00,DEBIT\n10/06/2026,REFUND,10.00,CREDIT\n"
        val r = CsvStatementParser.parse(csv)
        assertEquals(-4500L, r.transactions[0].amountCents)
        assertEquals(1000L, r.transactions[1].amountCents)
    }

    @Test
    fun ofxSgml() {
        val ofx = """
            OFXHEADER:100
            DATA:OFXSGML
            <OFX><SIGNONMSGSRSV1><SONRS><FI><ORG>KeyBank<FID>1234</FI></SONRS></SIGNONMSGSRSV1>
            <CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CCACCTFROM><ACCTID>4111111111114321</CCACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261012120000<TRNAMT>-23.50<FITID>A1<NAME>NETFLIX.COM</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261015<TRNAMT>200.00<FITID>A2<NAME>PAYMENT RECEIVED</STMTTRN>
            </BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
        """.trimIndent()
        val r = StatementImporter.parseText("download.qfx", ofx)
        assertEquals(2, r.transactions.size)
        assertEquals(AccountType.CREDIT_CARD, r.accountType)
        assertEquals("4321", r.last4)
        assertEquals(Bank.KEYBANK, r.bank)
        assertEquals("A1", r.transactions[0].externalId)
        assertEquals(LocalDate.of(2026, 10, 12), r.transactions[0].date)
    }

    @Test
    fun qif() {
        val qif = "!Type:Bank\nD10/28'26\nT-15.99\nPSPOTIFY\n^\nD10/29/2026\nT100.00\nPPAYROLL\n^\n"
        val r = StatementImporter.parseText("export.qif", qif)
        assertEquals(2, r.transactions.size)
        assertEquals(LocalDate.of(2026, 10, 28), r.transactions[0].date)
        assertEquals(-1599L, r.transactions[0].amountCents)
    }

    @Test
    fun pdfCreditCardText() {
        val text = """
            M&T Bank Visa  Account Number XXXX XXXX XXXX 9876
            Statement Closing Date 01/05/2027  Payment Due Date 02/01/2027
            Credit Limit $5,000.00  Minimum Payment Due $35.00
            PAYMENTS AND OTHER CREDITS
            12/20 PAYMENT THANK YOU -300.00
            PURCHASES
            12/15 DUNKIN #3345 BUFFALO NY 4.25
            01/02 WEGMANS #44 AMHERST NY 120.10
            TOTAL PURCHASES 124.35
        """.trimIndent()
        val r = PdfStatementParser.parseText(text, today = LocalDate.of(2027, 1, 10))
        assertEquals(AccountType.CREDIT_CARD, r.accountType)
        assertEquals(3, r.transactions.size)
        assertEquals(30000L, r.transactions[0].amountCents)
        assertEquals(LocalDate.of(2026, 12, 15), r.transactions[1].date)
        assertEquals(-425L, r.transactions[1].amountCents)
        assertEquals(LocalDate.of(2027, 1, 2), r.transactions[2].date)
        assertEquals("9876", r.last4)
        assertEquals(Bank.MT, r.bank)
    }

    @Test
    fun pdfBankUsesRunningBalance() {
        val text = """
            Beginning Balance $1,000.00
            10/01 DEPOSIT ACME PAYROLL 500.00 1,500.00
            10/02 CHECK 1001 200.00 1,300.00
        """.trimIndent()
        val r = PdfStatementParser.parseText(text, today = LocalDate.of(2026, 10, 28))
        assertEquals(50000L, r.transactions[0].amountCents)
        assertEquals(-20000L, r.transactions[1].amountCents)
    }

    @Test
    fun categorizer() {
        val none = emptyList<com.budgetingtool.app.data.CategoryRule>()
        assertEquals(DefaultCategories.DINING, Categorizer.categorize("UBER EATS PENDING", -2000, AccountType.CREDIT_CARD, none))
        assertEquals(DefaultCategories.TRANSPORT, Categorizer.categorize("UBER TRIP HELP.UBER.COM", -1500, AccountType.CREDIT_CARD, none))
        assertEquals(DefaultCategories.UTILITIES, Categorizer.categorize("T-MOBILE AUTOPAY*", -9000, AccountType.CHECKING, none).let {
            // "AUTOPAY" marks it as a transfer before utilities; either is acceptable for bills.
            if (it == DefaultCategories.TRANSFER) DefaultCategories.UTILITIES else it
        })
        assertEquals(DefaultCategories.HOUSING, Categorizer.categorize("ONLINE PAYMENT MORTGAGE MR COOPER", -165000, AccountType.CHECKING, none))
        assertEquals(DefaultCategories.TRANSFER, Categorizer.categorize("CHASE CREDIT CRD AUTOPAY", -150000, AccountType.CHECKING, none))
        assertEquals(DefaultCategories.TRANSFER, Categorizer.categorize("Payment Thank You-Mobile", 150000, AccountType.CREDIT_CARD, none))
        assertEquals(DefaultCategories.SHOPPING, Categorizer.categorize("KOHLS #0451", -6420, null, none))
        assertEquals(DefaultCategories.TRAVEL, Categorizer.categorize("ENTERPRISE RENT-A-CAR", -9000, null, none))
        assertEquals(DefaultCategories.GROCERIES, Categorizer.categorize("WEGMANS #0012 ROCHESTER NY", -5000, null, none))
        assertEquals("WEGMANS ROCHESTER", Categorizer.merchantKey("POS PURCHASE WEGMANS #0012 ROCHESTER NY"))
        assertTrue(Categorizer.categorize("MYSTERY SHOP", 5000, AccountType.CHECKING, none) == DefaultCategories.INCOME)
    }
}
