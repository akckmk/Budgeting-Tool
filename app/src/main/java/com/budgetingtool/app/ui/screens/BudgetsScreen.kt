package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.Budget
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.insights.InsightsEngine
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BudgetBar
import com.budgetingtool.app.ui.components.CategoryDot
import com.budgetingtool.app.ui.components.MonthSwitcher
import com.budgetingtool.app.ui.components.Picker
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.util.Money
import java.time.YearMonth

private data class BudgetEdit(val categoryId: String?, val existing: Budget?)

@Composable
fun BudgetsScreen(vm: MainViewModel) {
    val txns by vm.txns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val catList by vm.categories.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editing by remember { mutableStateOf<BudgetEdit?>(null) }
    val statuses = remember(txns, cats, budgets, month) { BudgetMath.statuses(budgets, txns, cats, month) }
    val engine = remember(txns, cats) { InsightsEngine(txns, cats) }
    val unbudgeted = remember(engine, statuses, month) {
        val budgeted = statuses.mapNotNull { it.budget.categoryId }.toSet()
        engine.categoryBreakdown(month).filter { it.categoryId != null && it.categoryId !in budgeted }
    }
    val warnAt = vm.warnPercent / 100.0
    val total = statuses.firstOrNull { it.isTotal }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val split = remember(txns, accounts, cats, month) {
        val map = accounts.associateBy { it.id }
        BudgetMath.inMonth(txns, month).groupBy { com.budgetingtool.app.insights.People.personOf(it, map) }
            .mapValues { (_, l) -> l.sumOf { BudgetMath.spending(it, cats) } }
            .filterValues { it != 0L }
            .toList().sortedByDescending { it.second }
    }

    ScreenScaffold("Budgets") { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { MonthSwitcher(month, { month = it }) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        if (total != null) {
                            BudgetBar(total, warnAt) { editing = BudgetEdit(null, total.budget) }
                            if (split.size > 1) {
                                Text(
                                    "Who spent it: " + split.joinToString(" · ") { (who, c) -> "$who ${Money.format(c, false)}" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Text("Overall monthly limit", style = MaterialTheme.typography.titleMedium)
                            Text("Caps all spending. Transfers and card payments don't count.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { editing = BudgetEdit(null, null) }, modifier = Modifier.padding(top = 8.dp)) { Text("Set overall limit") }
                        }
                    }
                }
            }
            item {
                SectionTitle("Category budgets") {
                    TextButton(onClick = { editing = BudgetEdit(DEFAULT_PICK, null) }) {
                        Icon(Icons.Default.Add, null)
                        Text("Add")
                    }
                }
            }
            val catStatuses = statuses.filter { !it.isTotal }
            if (catStatuses.isEmpty()) {
                item { Text("No category budgets yet. Add one for Dining, Groceries, Shopping...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(catStatuses, key = { it.budget.id }) { s -> BudgetBar(s, warnAt) { editing = BudgetEdit(s.budget.categoryId, s.budget) } }
            if (unbudgeted.isNotEmpty()) {
                item { SectionTitle("Spending without a budget") }
                items(unbudgeted, key = { "u" + it.categoryId }) { slice ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(slice.category, 32)
                        Text(slice.category?.name ?: "?", Modifier.weight(1f).padding(start = 12.dp))
                        Text(Money.format(slice.cents, false))
                        TextButton(onClick = { editing = BudgetEdit(slice.categoryId, null) }) { Text("Set") }
                    }
                }
            }
            item {
                Text(
                    "You'll get a notification at ${vm.warnPercent}% and when a budget goes over, including for purchases your spouse enters. Change the threshold in More → Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    editing?.let { e ->
        BudgetDialog(
            edit = e,
            spendingCats = catList.filter { it.kind == CategoryKind.SPENDING },
            averageFor = { id -> threeMonthAverage(engine, id, YearMonth.now()) },
            onSave = { catId, cents -> vm.setBudget(catId, cents); editing = null },
            onRemove = { b -> vm.removeBudget(b); editing = null },
            onDismiss = { editing = null },
        )
    }
}

private const val DEFAULT_PICK = "__pick__"

private fun threeMonthAverage(engine: InsightsEngine, categoryId: String?, now: YearMonth): Long {
    val months = (1..3).map { now.minusMonths(it.toLong()) }
    val total = months.sumOf { m ->
        if (categoryId == null) engine.total(m) else engine.categoryBreakdown(m).firstOrNull { it.categoryId == categoryId }?.cents ?: 0L
    }
    return total / 3
}

@Composable
private fun BudgetDialog(
    edit: BudgetEdit,
    spendingCats: List<Category>,
    averageFor: (String?) -> Long,
    onSave: (String?, Long) -> Unit,
    onRemove: (Budget) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember { mutableStateOf(if (edit.categoryId == DEFAULT_PICK) spendingCats.firstOrNull()?.id else edit.categoryId) }
    var amount by remember { mutableStateOf(edit.existing?.let { (it.monthlyLimitCents / 100).toString() } ?: "") }
    val avg = averageFor(categoryId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.categoryId == null) "Overall monthly limit" else "Category budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (edit.categoryId != null && edit.existing == null) {
                    Picker("Category", spendingCats, spendingCats.firstOrNull { it.id == categoryId }, { it.label }, { categoryId = it.id })
                }
                OutlinedTextField(
                    amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Monthly limit") }, prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (avg > 0) {
                    Text("Your 3-month average: ${Money.format(avg, false)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { amount = ((avg + 99) / 100).toString() }) { Text("Use average") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { Money.parseCents(amount)?.takeIf { it > 0 }?.let { onSave(categoryId, it) } },
                enabled = Money.parseCents(amount)?.let { it > 0 } == true && (edit.categoryId == null || categoryId != null),
            ) { Text("Save") }
        },
        dismissButton = {
            if (edit.existing != null) {
                TextButton(onClick = { onRemove(edit.existing) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
