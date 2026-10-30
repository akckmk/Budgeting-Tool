package com.budgetingtool.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Every entity carries `updatedAt` and a `deleted` tombstone so two phones can
 * merge their copies with last-writer-wins, without any server in the middle.
 */

enum class AccountType(val label: String) {
    CHECKING("Checking"),
    SAVINGS("Savings"),
    CREDIT_CARD("Credit card"),
    CASH("Cash"),
    OTHER("Other"),
}

enum class CategoryKind { SPENDING, INCOME, TRANSFER }

enum class TxnSource { IMPORT, MANUAL }

@Serializable
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,
    val name: String,
    val institution: String,
    val type: AccountType,
    val last4: String? = null,
    /** Whose account this is, e.g. "Andrew" or "Joint". */
    val owner: String = "",
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Day of the month the statement closes (cards), learned from a statement or set by hand. */
    val closingDay: Int? = null,
    /** Day of the month the payment is due. */
    val dueDay: Int? = null,
)

@Serializable
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val color: Long,
    val kind: CategoryKind = CategoryKind.SPENDING,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    val label: String get() = "$emoji $name"
}

/** A monthly limit. `categoryId == null` is the overall spending limit. */
@Serializable
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey val id: String,
    val categoryId: String?,
    val monthlyLimitCents: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        /** Deterministic ids so both spouses setting a Dining budget edit the same row. */
        fun idFor(categoryId: String?) = "budget_" + (categoryId ?: "total")
    }
}

@Serializable
@Entity(
    tableName = "transactions",
    indices = [Index("date"), Index("categoryId"), Index("importKey")],
)
data class Txn(
    @PrimaryKey val id: String,
    val accountId: String?,
    /** LocalDate.toEpochDay() */
    val date: Long,
    val description: String,
    val merchant: String,
    /** Negative = money out (purchase), positive = money in (income, refund, payment received). */
    val amountCents: Long,
    val categoryId: String?,
    val source: TxnSource,
    /** Dedupe key for imported rows so re-importing a statement never doubles up. */
    val importKey: String? = null,
    val enteredBy: String = "",
    val note: String = "",
    /** Manual purchases stay pending until a statement import matches them. */
    val pending: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * One statement as printed by the bank: its exact billing period, balance and due date.
 * Id is deterministic per account and closing date, so re-imports and both phones agree.
 */
@Serializable
@Entity(tableName = "statements", indices = [Index("accountId")])
data class Statement(
    @PrimaryKey val id: String,
    val accountId: String,
    /** LocalDate.toEpochDay() of the first day in the billing period. */
    val periodStart: Long,
    /** LocalDate.toEpochDay() of the closing date. */
    val periodEnd: Long,
    /** For cards: amount owed at close (positive). For bank accounts: the ending balance. */
    val closingBalanceCents: Long? = null,
    val dueDate: Long? = null,
    val minPaymentCents: Long? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        fun idFor(accountId: String, periodEnd: Long) = "stmt_${accountId}_$periodEnd"
    }
}

/** "Anything containing WEGMANS is Groceries" */
@Serializable
@Entity(tableName = "rules")
data class CategoryRule(
    @PrimaryKey val id: String,
    val pattern: String,
    val categoryId: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        fun idFor(pattern: String) = "rule_" + pattern.trim().uppercase()
    }
}

/** Everything that travels between phones. */
@Serializable
data class SyncBundle(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val transactions: List<Txn> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
    val statements: List<Statement> = emptyList(),
) {
    val size: Int get() = accounts.size + categories.size + budgets.size + transactions.size + rules.size + statements.size
    fun isEmpty() = size == 0
}
