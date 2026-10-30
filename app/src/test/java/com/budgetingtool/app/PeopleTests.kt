package com.budgetingtool.app

import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.data.SyncBundle
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.data.TxnSource
import com.budgetingtool.app.insights.People
import com.budgetingtool.app.sync.SyncJson
import com.budgetingtool.app.sync.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTests {
    private val andrewCard = Account("a1", "Chase", "Chase", AccountType.CREDIT_CARD, owner = "Andrew", updatedAt = 1)
    private val caitlinCard = Account("c1", "M&T", "M&T", AccountType.CREDIT_CARD, owner = "Caitlin", updatedAt = 1)
    private val joint = Account("j1", "Key Checking", "KeyBank", AccountType.CHECKING, owner = "Joint", updatedAt = 1)
    private val oldImport = Account("o1", "Old card", "Other", AccountType.CREDIT_CARD, owner = "", updatedAt = 1)
    private val accounts = listOf(andrewCard, caitlinCard, joint, oldImport)
    private val map = accounts.associateBy { it.id }

    private fun t(id: String, acct: String?, source: TxnSource = TxnSource.IMPORT, by: String = "") =
        Txn(id, acct, 0, "X", "X", -100, null, source, enteredBy = by, updatedAt = 1)

    @Test
    fun whoseSpending() {
        assertEquals("Andrew", People.personOf(t("1", "a1"), map))
        assertEquals("Caitlin", People.personOf(t("2", "c1"), map))
        assertEquals("Joint", People.personOf(t("3", "j1"), map))
        // Caitlin typed in a purchase made on Andrew's card: it's Caitlin's spending.
        assertEquals("Caitlin", People.personOf(t("4", "a1", TxnSource.MANUAL, "Caitlin"), map))
        assertEquals(People.UNASSIGNED, People.personOf(t("5", "o1"), map))
    }

    @Test
    fun viewsFilterAndListEveryone() {
        val txns = listOf(t("1", "a1"), t("2", "c1"), t("3", "j1"), t("4", "a1", TxnSource.MANUAL, "Caitlin"))
        assertEquals(listOf("1"), People.filter(txns, map, "Andrew").map { it.id })
        assertEquals(listOf("2", "4"), People.filter(txns, map, "caitlin").map { it.id })
        assertEquals(4, People.filter(txns, map, null).size)
        // This phone's user first, then the others, unassigned last.
        assertEquals(listOf("Caitlin", "Andrew", "Joint", People.UNASSIGNED), People.everyone(txns + t("5", "o1"), accounts, "Caitlin"))
    }

    @Test
    fun ackProtocolRoundTripAndOldFormat() {
        val ack = SyncMessage.Ack(upTo = 123, transactions = 165, statements = 2, accounts = 3)
        assertEquals(ack, SyncJson.decodeFromString(SyncMessage.serializer(), SyncJson.encodeToString(SyncMessage.serializer(), ack)))
        val changes = SyncMessage.Changes(SyncBundle(), sentAt = 99, full = true)
        assertEquals(changes, SyncJson.decodeFromString(SyncMessage.serializer(), SyncJson.encodeToString(SyncMessage.serializer(), changes)))
        // A 1.2.0 phone's message (no sentAt/full) still decodes; sentAt 0 means "don't acknowledge".
        val old = SyncJson.decodeFromString(SyncMessage.serializer(), """{"t":"changes","bundle":{}}""") as SyncMessage.Changes
        assertEquals(0L, old.sentAt)
    }
}
