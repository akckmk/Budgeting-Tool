package com.budgetingtool.app

import com.budgetingtool.app.data.Budget
import com.budgetingtool.app.data.DefaultCategories
import com.budgetingtool.app.data.SyncBundle
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.data.TxnSource
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.insights.SavingsCoach
import com.budgetingtool.app.sync.SyncCrypto
import com.budgetingtool.app.sync.SyncJson
import com.budgetingtool.app.sync.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import java.time.LocalDate
import java.time.YearMonth

class LogicTests {
    private val cats = DefaultCategories.all.associateBy { it.id }

    private fun txn(day: LocalDate, cents: Long, cat: String, merchant: String) = Txn(
        id = "${day}_${merchant}_$cents", accountId = null, date = day.toEpochDay(), description = merchant,
        merchant = merchant, amountCents = cents, categoryId = cat, source = TxnSource.IMPORT, updatedAt = 1,
    )

    @Test
    fun budgetsIgnoreTransfersAndCountRefunds() {
        val m = YearMonth.of(2026, 10)
        val txns = listOf(
            txn(m.atDay(2), -10000, DefaultCategories.DINING, "Pizza"),
            txn(m.atDay(3), 2500, DefaultCategories.DINING, "Refund"),
            txn(m.atDay(4), -50000, DefaultCategories.TRANSFER, "Card payment"),
        )
        val budgets = listOf(Budget(Budget.idFor(null), null, 50000, 1), Budget(Budget.idFor(DefaultCategories.DINING), DefaultCategories.DINING, 7000, 1))
        val s = BudgetMath.statuses(budgets, txns, cats, m)
        assertEquals(7500L, s.first { it.isTotal }.spentCents)
        val dining = s.first { !it.isTotal }
        assertEquals(7500L, dining.spentCents)
        assertTrue(dining.fraction > 1.0)
        val impact = BudgetMath.impactOf(s, DefaultCategories.DINING, 1000)
        assertTrue(impact.first { !it.status.isTotal }.goesOver)
    }

    @Test
    fun syncCryptoRoundTripAndWrongCodeFails() {
        val code = SyncCrypto.generateCode()
        assertTrue(SyncCrypto.isValidHouseholdKey(code))
        assertTrue(SyncCrypto.isValidInvite(SyncCrypto.generateInviteCode()))
        val a = SyncCrypto(code)
        val b = SyncCrypto(code.lowercase().replace("-", " "))
        assertEquals(a.householdTag, b.householdTag)
        val msg = SyncMessage.Changes(SyncBundle(transactions = listOf(txn(LocalDate.of(2026, 10, 1), -500, DefaultCategories.DINING, "Cafe"))))
        val sealed = a.seal(SyncJson.encodeToString(SyncMessage.serializer(), msg).encodeToByteArray())
        val back = SyncJson.decodeFromString(SyncMessage.serializer(), b.open(sealed).decodeToString())
        assertEquals(msg, back)
        val other = SyncCrypto(SyncCrypto.generateCode())
        assertFalse(runCatching { other.open(sealed) }.isSuccess)
    }

    /** The real wire format over a real socket: a join request under the invite code, the key back. */
    @Test
    fun pairingHandshakeOverSocket() = kotlinx.coroutines.runBlocking {
        val invite = SyncCrypto.generateInviteCode()
        val householdKey = SyncCrypto.generateCode()
        val server = java.net.ServerSocket(0)
        val inviter = async(kotlinx.coroutines.Dispatchers.IO) {
            val ch = com.budgetingtool.app.sync.FramedChannel(server.accept(), SyncCrypto(invite))
            val req = ch.receive() as SyncMessage.JoinRequest
            ch.send(SyncMessage.JoinAccepted(householdKey, "Alex"))
            ch.close()
            req.deviceName
        }
        val joiner = com.budgetingtool.app.sync.FramedChannel(java.net.Socket("127.0.0.1", server.localPort), SyncCrypto(invite.lowercase()))
        joiner.send(SyncMessage.JoinRequest("dev-2", "Sam"))
        val reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { joiner.receive() } as SyncMessage.JoinAccepted
        assertEquals(householdKey, reply.householdKey)
        assertEquals("Sam", inviter.await())

        // A wrong code is rejected before anything is revealed.
        val server2 = java.net.ServerSocket(0)
        val guarded = async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.budgetingtool.app.sync.FramedChannel(server2.accept(), SyncCrypto(invite)).receive() }.isFailure
        }
        com.budgetingtool.app.sync.FramedChannel(java.net.Socket("127.0.0.1", server2.localPort), SyncCrypto(SyncCrypto.generateInviteCode()))
            .send(SyncMessage.JoinRequest("evil", "Mallory"))
        assertTrue(guarded.await())
    }

    @Test
    fun coachFindsHabitsAndProjectsSavings() {
        val today = LocalDate.of(2026, 10, 28)
        val txns = (1..3).flatMap { back ->
            val m = YearMonth.from(today).minusMonths(back.toLong())
            (1..12).map { d -> txn(m.atDay(d), -650, DefaultCategories.DINING, "STARBUCKS") } +
                (1..4).map { d -> txn(m.atDay(d + 12), -3500, DefaultCategories.DINING, "DOORDASH") } +
                listOf(txn(m.atDay(20), -1599, DefaultCategories.SUBSCRIPTIONS, "NETFLIX"), txn(m.atDay(21), -1199, DefaultCategories.SUBSCRIPTIONS, "SPOTIFY"))
        }
        val coach = SavingsCoach(txns, cats, today)
        assertTrue(coach.hasData)
        val habits = coach.habits().map { it.title }
        assertTrue(habits.any { it.contains("coffee") })
        assertTrue(habits.any { it.contains("delivery") })
        val tips = coach.tips()
        assertTrue(tips.isNotEmpty())
        assertTrue(tips.any { it.title.contains("coffee") })
        // $100/month for 10 years at 5% ≈ $15,528
        val fv = SavingsCoach.futureValue(10000, 10)
        assertTrue(fv in 1_540_000..1_565_000)
    }
}
