package com.budgetingtool.app.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budgetingtool.app.container
import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.data.Budget
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.data.CategoryRule
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.insights.BudgetStatus
import com.budgetingtool.app.util.newId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.repo

    val txns: StateFlow<List<Txn>> = repo.transactions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<Category>> = repo.categories
        .map { l -> l.sortedWith(compareBy<Category> { it.kind.ordinal }.thenBy { it.name }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categoryMap: StateFlow<Map<String, Category>> = repo.categories.map { l -> l.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val accounts: StateFlow<List<Account>> = repo.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val budgets: StateFlow<List<Budget>> = repo.budgets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rules: StateFlow<List<CategoryRule>> = repo.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val statements: StateFlow<List<com.budgetingtool.app.data.Statement>> =
        repo.statements.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncState = c.sync.state

    // ---- whose view: null = combined, else one person's name ----
    private val _viewPerson = kotlinx.coroutines.flow.MutableStateFlow(c.prefs.viewPerson)
    val viewPerson: StateFlow<String?> = _viewPerson
    fun selectPerson(name: String?) {
        _viewPerson.value = name
        c.prefs.viewPerson = name
    }

    val people: StateFlow<List<String>> = kotlinx.coroutines.flow.combine(txns, accounts, _viewPerson) { t, a, _ ->
        com.budgetingtool.app.insights.People.everyone(t, a, c.prefs.userName)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Transactions for the chosen view. Budgets and alerts always use the whole household ([txns]). */
    val visibleTxns: StateFlow<List<Txn>> = kotlinx.coroutines.flow.combine(txns, accounts, _viewPerson) { t, a, p ->
        com.budgetingtool.app.insights.People.filter(t, a.associateBy { it.id }, p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pairingState = c.pairing.state

    var userName by mutableStateOf(c.prefs.userName)
        private set
    var warnPercent by mutableIntStateOf(c.prefs.warnPercent)
        private set
    var secureScreen by mutableStateOf(c.prefs.secureScreen)
        private set
    var appLock by mutableStateOf(c.prefs.appLock)
        private set

    fun updateSecureScreen(v: Boolean) {
        secureScreen = v
        c.prefs.secureScreen = v
    }

    fun updateAppLock(v: Boolean) {
        appLock = v
        c.prefs.appLock = v
    }

    fun updateUserName(v: String) {
        userName = v
        c.prefs.userName = v
    }

    fun updateWarnPercent(v: Int) {
        warnPercent = v
        c.prefs.warnPercent = v
    }

    fun statuses(month: YearMonth): List<BudgetStatus> =
        BudgetMath.statuses(budgets.value, txns.value, categoryMap.value, month)

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()

    // ---- transactions ----

    fun addEntry(cents: Long, isIncome: Boolean, merchant: String, categoryId: String?, accountId: String?, date: LocalDate, note: String) {
        viewModelScope.launch {
            repo.addManual(cents, isIncome, merchant, categoryId, accountId, date, note)
            c.alerts.check()
        }
    }

    fun saveTxn(original: Txn, edited: Txn, rememberCategory: Boolean) {
        viewModelScope.launch {
            val newCategory = edited.categoryId
            if (rememberCategory && newCategory != null) {
                repo.recategorize(edited, newCategory, rememberForMerchant = true)
            } else {
                repo.updateTxn(edited)
            }
            c.alerts.check()
        }
    }

    fun deleteTxn(t: Txn) = viewModelScope.launch { repo.deleteTxn(t); c.alerts.check() }

    // ---- budgets ----

    fun setBudget(categoryId: String?, cents: Long) = viewModelScope.launch { repo.setBudget(categoryId, cents); c.alerts.check() }
    fun removeBudget(b: Budget) = viewModelScope.launch { repo.removeBudget(b) }

    // ---- accounts / categories / rules ----

    fun saveAccount(
        existing: Account?, name: String, institution: String, type: AccountType, last4: String, owner: String,
        closingDay: Int? = existing?.closingDay, dueDay: Int? = existing?.dueDay,
    ) = viewModelScope.launch {
        val base = existing ?: Account(newId(), name, institution, type, updatedAt = 0)
        repo.saveAccount(
            base.copy(
                name = name.trim(), institution = institution.trim(), type = type, last4 = last4.trim().ifBlank { null },
                owner = owner.trim(), closingDay = closingDay?.takeIf { it in 1..31 }, dueDay = dueDay?.takeIf { it in 1..31 },
            ),
        )
    }

    fun setCycleDays(a: Account, closingDay: Int?, dueDay: Int?) =
        saveAccount(a, a.name, a.institution, a.type, a.last4 ?: "", a.owner, closingDay, dueDay)

    fun deleteAccount(a: Account) = viewModelScope.launch { repo.deleteAccount(a) }
    fun addCategory(name: String, emoji: String, kind: CategoryKind) = viewModelScope.launch {
        val palette = listOf(0xFF26A69A, 0xFF7E57C2, 0xFF42A5F5, 0xFFFFA726, 0xFF8D6E63, 0xFFEF5350, 0xFF66BB6A)
        repo.addCategory(name, emoji, palette[(name.hashCode() and 0x7fffffff) % palette.size], kind)
    }
    fun addRule(pattern: String, categoryId: String) = viewModelScope.launch { repo.addRule(pattern, categoryId) }
    fun deleteRule(r: CategoryRule) = viewModelScope.launch { repo.deleteRule(r) }

    // ---- sharing ----

    fun createInvite() = c.pairing.createInvite()
    fun cancelInvite() = c.pairing.cancelInvite()
    fun answerJoin(allow: Boolean) = c.pairing.answer(allow)
    fun joinWithInvite(code: String, host: String?) = c.pairing.join(code, host)
    fun cancelJoin() = c.pairing.cancelJoin()
    fun leaveHousehold() {
        c.pairing.cancelInvite()
        c.sync.leaveHousehold()
    }
    fun fullResync() = c.sync.fullResync()
    fun syncNow() = c.sync.syncNow()

    var backgroundSync by mutableStateOf(c.prefs.backgroundSync)
        private set

    fun updateBackgroundSync(v: Boolean) {
        backgroundSync = v
        c.prefs.backgroundSync = v
        if (v) com.budgetingtool.app.sync.SyncService.start(getApplication()) else com.budgetingtool.app.sync.SyncService.stop(getApplication())
    }
    fun connectManually(host: String, port: Int) = c.sync.connectManually(host, port)

    fun exportSync(uri: Uri) = viewModelScope.launch {
        try {
            val n = getApplication<Application>().contentResolver.openOutputStream(uri)?.use { c.sync.exportTo(it) } ?: 0
            toast("Saved $n records to an encrypted file. Send it to your spouse's phone and import it there.")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    fun importSync(uri: Uri) = viewModelScope.launch {
        try {
            val n = getApplication<Application>().contentResolver.openInputStream(uri)?.use { c.sync.importFrom(it) } ?: 0
            toast("Merged $n updates.")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }
}
