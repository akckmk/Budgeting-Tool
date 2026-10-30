package com.budgetingtool.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budgetingtool.app.container
import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.importer.Bank
import com.budgetingtool.app.importer.ParseResult
import com.budgetingtool.app.importer.StatementImporter
import com.budgetingtool.app.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingFile(val uri: Uri, val name: String)

data class ImportPreview(
    val fileName: String,
    val result: ParseResult,
    val selected: Set<Int>,
    val flip: Boolean,
    /** null = create a new account */
    val accountId: String?,
    val bank: Bank,
    val newAccountName: String,
    val newAccountType: AccountType,
)

data class ImportUiState(
    val busy: Boolean = false,
    val queue: List<PendingFile> = emptyList(),
    val preview: ImportPreview? = null,
    val messages: List<String> = emptyList(),
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun addFiles(uris: List<Uri>) {
        val files = uris.map { PendingFile(it, displayName(it)) }
        _state.update { it.copy(queue = it.queue + files) }
        if (_state.value.preview == null && !_state.value.busy) next()
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "statement"

    private fun next() {
        val file = _state.value.queue.firstOrNull() ?: return
        _state.update { it.copy(busy = true, queue = it.queue.drop(1)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { StatementImporter.parse(getApplication(), file.uri, file.name) }
            if (result.error != null || result.transactions.isEmpty()) {
                addMessage("${file.name}: ${result.error ?: "no transactions found"}")
                _state.update { it.copy(busy = false) }
                next()
                return@launch
            }
            val accounts = c.repo.activeAccounts()
            val bank = result.bank ?: Bank.OTHER
            val type = result.accountType ?: AccountType.CHECKING
            val match = accounts.firstOrNull { a ->
                result.last4 != null && a.last4 == result.last4 && a.institution == bank.display
            } ?: accounts.firstOrNull { a -> a.institution == bank.display && a.type == type && bank != Bank.OTHER }
                ?.takeIf { accounts.count { a -> a.institution == bank.display && a.type == type } == 1 }
            _state.update {
                it.copy(
                    busy = false,
                    preview = ImportPreview(
                        fileName = file.name,
                        result = result,
                        selected = result.transactions.indices.toSet(),
                        flip = StatementImporter.suggestFlip(result, type),
                        accountId = match?.id,
                        bank = bank,
                        newAccountName = defaultName(bank, type, result.last4),
                        newAccountType = type,
                    ),
                )
            }
        }
    }

    private fun defaultName(bank: Bank, type: AccountType, last4: String?): String {
        val b = if (bank == Bank.OTHER) "" else bank.display.substringBefore(" (") + " "
        return (b + type.label + (last4?.let { " ••$it" } ?: "")).trim()
    }

    private fun editPreview(f: (ImportPreview) -> ImportPreview) = _state.update { s -> s.copy(preview = s.preview?.let(f)) }

    fun toggle(i: Int) = editPreview { p -> p.copy(selected = if (i in p.selected) p.selected - i else p.selected + i) }
    fun selectAll(all: Boolean) = editPreview { p -> p.copy(selected = if (all) p.result.transactions.indices.toSet() else emptySet()) }
    fun setFlip(v: Boolean) = editPreview { it.copy(flip = v) }
    fun chooseAccount(id: String?) = editPreview { it.copy(accountId = id) }
    fun setBank(b: Bank) = editPreview { it.copy(bank = b, newAccountName = defaultName(b, it.newAccountType, it.result.last4)) }
    fun setNewAccountName(n: String) = editPreview { it.copy(newAccountName = n) }
    fun setNewAccountType(t: AccountType) = editPreview {
        it.copy(newAccountType = t, flip = StatementImporter.suggestFlip(it.result, t), newAccountName = defaultName(it.bank, t, it.result.last4))
    }

    fun confirm() {
        val p = _state.value.preview ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val account = c.repo.activeAccounts().firstOrNull { it.id == p.accountId }
                ?: c.repo.saveAccount(
                    Account(
                        id = newId(),
                        name = p.newAccountName.ifBlank { p.bank.display },
                        institution = p.bank.display,
                        type = p.newAccountType,
                        last4 = p.result.last4,
                        owner = c.prefs.userName,
                        updatedAt = 0,
                    ),
                )
            val rows = p.result.transactions.filterIndexed { i, _ -> i in p.selected }
            val summary = c.repo.importParsed(account, rows, p.flip)
            p.result.statement?.let { c.repo.saveStatement(account, it) }
            c.alerts.check()
            addMessage(
                buildString {
                    append("${p.fileName}: imported ${summary.added - summary.matchedManual} new")
                    if (summary.matchedManual > 0) append(", matched ${summary.matchedManual} purchases you entered by hand")
                    if (summary.duplicates > 0) append(", skipped ${summary.duplicates} already imported")
                    if (summary.matchedOtherStatement > 0) append(" (${summary.matchedOtherStatement} of them from another file of the same account)")
                    append(" into ${account.name}.")
                    p.result.statement?.let { st ->
                        append(" Statement ${st.periodStart?.format(com.budgetingtool.app.util.Dates.shortFormat) ?: "?"} – ${st.periodEnd.format(com.budgetingtool.app.util.Dates.shortFormat)}")
                        st.closingBalanceCents?.let { append(", balance ${com.budgetingtool.app.util.Money.format(it)}") }
                        st.dueDate?.let { append(", due ${it.format(com.budgetingtool.app.util.Dates.shortFormat)}") }
                        append(".")
                    }
                },
            )
            _state.update { it.copy(busy = false, preview = null) }
            next()
        }
    }

    fun skip() {
        _state.value.preview?.let { addMessage("${it.fileName}: skipped.") }
        _state.update { it.copy(preview = null) }
        next()
    }

    private fun addMessage(m: String) = _state.update { it.copy(messages = listOf(m) + it.messages) }
}
