package com.budgetingtool.app.insights

import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.data.TxnSource

/**
 * Whose spending is this? Both phones use the same rule, so "Andrew" and "Caitlin" views agree.
 * - A purchase typed in by hand belongs to whoever entered it (even on the other person's card).
 * - A statement line belongs to the account's owner (set when the statement is imported).
 */
object People {
    const val UNASSIGNED = "Unassigned"

    fun personOf(t: Txn, accounts: Map<String, Account>): String {
        if (t.source == TxnSource.MANUAL && t.enteredBy.isNotBlank()) return t.enteredBy.trim()
        val owner = t.accountId?.let { accounts[it]?.owner }?.trim()
        return when {
            !owner.isNullOrBlank() -> owner
            t.enteredBy.isNotBlank() -> t.enteredBy.trim()
            else -> UNASSIGNED
        }
    }

    /** Everyone who appears in the data, plus this phone's user, in a stable order. */
    fun everyone(txns: List<Txn>, accounts: List<Account>, me: String): List<String> {
        val map = accounts.associateBy { it.id }
        val names = LinkedHashSet<String>()
        if (me.isNotBlank()) names += me.trim()
        accounts.mapNotNullTo(names) { it.owner.trim().takeIf { o -> o.isNotBlank() } }
        txns.mapTo(names) { personOf(it, map) }
        val (real, unassigned) = names.partition { it != UNASSIGNED }
        return real.sortedWith(compareBy<String> { it != me.trim() }.thenBy { it.lowercase() }) + unassigned
    }

    fun filter(txns: List<Txn>, accounts: Map<String, Account>, person: String?): List<Txn> =
        if (person == null) txns else txns.filter { personOf(it, accounts).equals(person, ignoreCase = true) }
}
