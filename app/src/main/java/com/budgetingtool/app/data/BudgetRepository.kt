package com.budgetingtool.app.data

import androidx.room.withTransaction
import com.budgetingtool.app.importer.ParsedTxn
import com.budgetingtool.app.util.newId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max

data class ImportSummary(val added: Int, val duplicates: Int, val matchedManual: Int)

data class MergeResult(val applied: Int, val newSpending: List<Txn>)

class BudgetRepository(private val db: AppDatabase, private val prefs: Prefs) {
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val budgetDao = db.budgetDao()
    private val ruleDao = db.ruleDao()
    private val txnDao = db.txnDao()

    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires after every local edit; the sync engine pushes deltas to the spouse's phone. */
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    val transactions: Flow<List<Txn>> = txnDao.observeActive()
    val categories: Flow<List<Category>> = categoryDao.observeActive()
    val accounts: Flow<List<Account>> = accountDao.observeActive()
    val budgets: Flow<List<Budget>> = budgetDao.observeActive()
    val rules: Flow<List<CategoryRule>> = ruleDao.observeActive()

    /**
     * A new edit is always stamped later than the version it replaces, even if this
     * phone's clock is behind the spouse's, so last-writer-wins keeps the edit.
     */
    private fun stamp(previous: Long? = null) = max(System.currentTimeMillis(), (previous ?: 0L) + 1)

    private fun notifyChanged() {
        _localChanges.tryEmit(Unit)
    }

    suspend fun seedDefaults() {
        if (categoryDao.count() == 0) categoryDao.upsert(DefaultCategories.all)
    }

    // ---- reads used by alerts / import ----

    suspend fun activeBudgets() = budgetDao.active()
    suspend fun activeAccounts() = accountDao.active()
    suspend fun activeRules() = ruleDao.active()
    suspend fun categoryMap() = categoryDao.active().associateBy { it.id }
    suspend fun txnsInMonth(month: YearMonth) =
        txnDao.between(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())

    // ---- transactions ----

    suspend fun addManual(
        amountCents: Long,
        isIncome: Boolean,
        merchant: String,
        categoryId: String?,
        accountId: String?,
        date: LocalDate,
        note: String,
    ): Txn {
        val t = Txn(
            id = newId(),
            accountId = accountId,
            date = date.toEpochDay(),
            description = merchant.trim(),
            merchant = merchant.trim(),
            amountCents = if (isIncome) abs(amountCents) else -abs(amountCents),
            categoryId = categoryId,
            source = TxnSource.MANUAL,
            enteredBy = prefs.userName,
            note = note.trim(),
            pending = !isIncome,
            updatedAt = stamp(),
        )
        txnDao.upsert(listOf(t))
        notifyChanged()
        return t
    }

    suspend fun updateTxn(t: Txn) {
        txnDao.upsert(listOf(t.copy(updatedAt = stamp(t.updatedAt))))
        notifyChanged()
    }

    suspend fun deleteTxn(t: Txn) {
        txnDao.upsert(listOf(t.copy(deleted = true, updatedAt = stamp(t.updatedAt))))
        notifyChanged()
    }

    /** Re-categorizes a transaction, optionally remembering the choice for that merchant. */
    suspend fun recategorize(t: Txn, categoryId: String, rememberForMerchant: Boolean) = db.withTransaction {
        val changed = mutableListOf(t.copy(categoryId = categoryId, updatedAt = stamp(t.updatedAt)))
        if (rememberForMerchant) {
            val key = Categorizer.merchantKey(t.description)
            val ruleId = CategoryRule.idFor(key)
            val existing = ruleDao.byIds(listOf(ruleId)).firstOrNull()
            ruleDao.upsert(listOf(CategoryRule(ruleId, key, categoryId, stamp(existing?.updatedAt))))
            txnDao.active()
                .filter { it.id != t.id && it.categoryId != categoryId && Categorizer.merchantKey(it.description) == key }
                .mapTo(changed) { it.copy(categoryId = categoryId, updatedAt = stamp(it.updatedAt)) }
        }
        txnDao.upsert(changed)
        notifyChanged()
    }

    /**
     * Imports parsed statement rows into [account].
     * - Rows already imported (same FITID, or same date/amount/merchant) are skipped.
     * - A manual "live" purchase with the same amount within a week is treated as the same
     *   purchase: the statement row keeps the category you picked, and the manual entry is retired.
     */
    suspend fun importParsed(account: Account, items: List<ParsedTxn>, flipSign: Boolean): ImportSummary =
        db.withTransaction {
            val rules = ruleDao.active()
            val occurrences = HashMap<String, Int>()
            val keyed = items.map { p ->
                val amount = if (flipSign) -p.amountCents else p.amountCents
                val key = if (!p.externalId.isNullOrBlank()) {
                    "${account.id}|fit|${p.externalId}"
                } else {
                    val base = "${account.id}|${p.date.toEpochDay()}|$amount|${Categorizer.merchantKey(p.description)}"
                    val n = (occurrences[base] ?: 0) + 1
                    occurrences[base] = n
                    "$base|$n"
                }
                Triple(p, amount, key)
            }
            val existing = keyed.map { it.third }.chunked(500).flatMap { txnDao.existingImportKeys(it) }.toHashSet()
            val pending = txnDao.pendingManual().toMutableList()
            val out = ArrayList<Txn>()
            var dupes = 0
            var matched = 0
            for ((p, amount, key) in keyed) {
                if (key in existing) {
                    dupes++
                    continue
                }
                existing += key
                val day = p.date.toEpochDay()
                var categoryId = Categorizer.categorize(p.description, amount, account.type, rules)
                var note = ""
                var enteredBy = ""
                val match = pending.firstOrNull {
                    it.amountCents == amount && day in (it.date - 3)..(it.date + 7) &&
                        (it.accountId == null || it.accountId == account.id)
                }
                if (match != null) {
                    pending.remove(match)
                    categoryId = match.categoryId ?: categoryId
                    note = match.note
                    enteredBy = match.enteredBy
                    out += match.copy(deleted = true, updatedAt = stamp(match.updatedAt))
                    matched++
                }
                out += Txn(
                    id = newId(),
                    accountId = account.id,
                    date = day,
                    description = p.description,
                    merchant = Categorizer.displayMerchant(p.description),
                    amountCents = amount,
                    categoryId = categoryId,
                    source = TxnSource.IMPORT,
                    importKey = key,
                    enteredBy = enteredBy,
                    note = note,
                    updatedAt = stamp(),
                )
            }
            txnDao.upsert(out)
            notifyChanged()
            ImportSummary(added = items.size - dupes, duplicates = dupes, matchedManual = matched)
        }

    // ---- accounts / categories / budgets / rules ----

    suspend fun saveAccount(a: Account): Account {
        val saved = a.copy(updatedAt = stamp(a.updatedAt))
        accountDao.upsert(listOf(saved))
        notifyChanged()
        return saved
    }

    suspend fun deleteAccount(a: Account) {
        accountDao.upsert(listOf(a.copy(deleted = true, updatedAt = stamp(a.updatedAt))))
        notifyChanged()
    }

    suspend fun addCategory(name: String, emoji: String, color: Long, kind: CategoryKind) {
        categoryDao.upsert(listOf(Category("cat_" + newId(), name.trim(), emoji.ifBlank { "🏷️" }, color, kind, stamp())))
        notifyChanged()
    }

    suspend fun setBudget(categoryId: String?, limitCents: Long) {
        val id = Budget.idFor(categoryId)
        val existing = budgetDao.byIds(listOf(id)).firstOrNull()
        budgetDao.upsert(listOf(Budget(id, categoryId, limitCents, stamp(existing?.updatedAt))))
        notifyChanged()
    }

    suspend fun removeBudget(b: Budget) {
        budgetDao.upsert(listOf(b.copy(deleted = true, updatedAt = stamp(b.updatedAt))))
        notifyChanged()
    }

    suspend fun addRule(pattern: String, categoryId: String) {
        val id = CategoryRule.idFor(pattern)
        val existing = ruleDao.byIds(listOf(id)).firstOrNull()
        ruleDao.upsert(listOf(CategoryRule(id, pattern.trim().uppercase(), categoryId, stamp(existing?.updatedAt))))
        notifyChanged()
    }

    suspend fun deleteRule(r: CategoryRule) {
        ruleDao.upsert(listOf(r.copy(deleted = true, updatedAt = stamp(r.updatedAt))))
        notifyChanged()
    }

    // ---- sync ----

    /** Every row (including tombstones) changed after [since]. */
    suspend fun changesSince(since: Long) = SyncBundle(
        accounts = accountDao.changedSince(since),
        categories = categoryDao.changedSince(since),
        budgets = budgetDao.changedSince(since),
        transactions = txnDao.changedSince(since),
        rules = ruleDao.changedSince(since),
    )

    /** Last-writer-wins merge of the spouse's changes. Does not echo back as a local change. */
    suspend fun mergeRemote(b: SyncBundle): MergeResult = db.withTransaction {
        val accounts = newer(b.accounts, chunked(b.accounts.map { it.id }) { accountDao.byIds(it) }, { it.id }, { it.updatedAt })
        val categories = newer(b.categories, chunked(b.categories.map { it.id }) { categoryDao.byIds(it) }, { it.id }, { it.updatedAt })
        val budgets = newer(b.budgets, chunked(b.budgets.map { it.id }) { budgetDao.byIds(it) }, { it.id }, { it.updatedAt })
        val rules = newer(b.rules, chunked(b.rules.map { it.id }) { ruleDao.byIds(it) }, { it.id }, { it.updatedAt })
        val existingTxns = chunked(b.transactions.map { it.id }) { txnDao.byIds(it) }
        val knownIds = existingTxns.mapTo(HashSet()) { it.id }
        val txns = newer(b.transactions, existingTxns, { it.id }, { it.updatedAt })
        accountDao.upsert(accounts)
        categoryDao.upsert(categories)
        budgetDao.upsert(budgets)
        ruleDao.upsert(rules)
        txnDao.upsert(txns)
        MergeResult(
            applied = accounts.size + categories.size + budgets.size + rules.size + txns.size,
            newSpending = txns.filter { !it.deleted && it.amountCents < 0 && it.id !in knownIds },
        )
    }

    private inline fun <T> newer(incoming: List<T>, existing: List<T>, id: (T) -> String, ts: (T) -> Long): List<T> {
        val map = existing.associateBy(id)
        return incoming.filter { val e = map[id(it)]; e == null || ts(it) > ts(e) }
    }

    private suspend inline fun <T> chunked(ids: List<String>, load: (List<String>) -> List<T>): List<T> {
        val out = ArrayList<T>()
        for (chunk in ids.chunked(500)) out += load(chunk)
        return out
    }
}
