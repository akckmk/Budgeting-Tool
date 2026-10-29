package com.budgetingtool.app

import com.budgetingtool.app.receipt.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTests {
    private val today = LocalDate.of(2026, 10, 29)

    @Test
    fun groceryReceipt() {
        val text = """
            WEGMANS
            1500 Jefferson Rd
            Rochester NY 14623
            (585) 427-0010
            BANANAS          1.29
            MILK 2%          3.49
            CHICKEN BREAST  11.84
            SUBTOTAL        16.62
            TAX              0.00
            TOTAL           16.62
            VISA ****4321   16.62
            10/28/2026 6:41 PM
            THANK YOU FOR SHOPPING
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals("Wegmans", r.merchant)
        assertEquals(1662L, r.totalCents)
        assertEquals(LocalDate.of(2026, 10, 28), r.date)
    }

    @Test
    fun restaurantWithTipUsesTotalNotSubtotal() {
        val text = """
            Welcome to
            Olive Garden Italian Kitchen
            Server: Amy   Table 12
            Subtotal 52.40
            Tax 4.19
            Tip 10.00
            Total $66.59
            Oct 27, 2026
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals("Olive Garden Italian Kitchen", r.merchant)
        assertEquals(6659L, r.totalCents)
        assertEquals(LocalDate.of(2026, 10, 27), r.date)
    }

    @Test
    fun ocrNoiseAndAmountOnNextLine() {
        val text = """
            STEWART'S SHOPS
            Store #212
            REG UNL  12.0O GAL
            AMOUNT DUE
            $ 45. 10
            1O/2O/26
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals("Stewart's Shops", r.merchant)
        assertEquals(4510L, r.totalCents)
        assertEquals(LocalDate.of(2026, 10, 20), r.date)
    }

    @Test
    fun noLabelFallsBackToLargestAmount() {
        val r = ReceiptParser.parse("CORNER CAFE\nLatte 5.25\nMuffin 3.50\n8.75", today)
        assertEquals(875L, r.totalCents)
    }
}
