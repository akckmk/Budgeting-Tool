package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.MonthSwitcher
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.TxnRow
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TransactionsScreen(vm: MainViewModel, onEdit: (Txn) -> Unit) {
    val txns by vm.txns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    var query by rememberSaveable { mutableStateOf("") }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var allTime by rememberSaveable { mutableStateOf(false) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var accountFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val inRange = remember(txns, month, allTime) { if (allTime) txns else BudgetMath.inMonth(txns, month) }
    val filtered = remember(inRange, query, categoryFilter, accountFilter) {
        val q = query.trim().uppercase()
        inRange.filter { t ->
            (q.isEmpty() || t.description.uppercase().contains(q) || t.merchant.uppercase().contains(q) || t.note.uppercase().contains(q)) &&
                (categoryFilter == null || t.categoryId == categoryFilter) &&
                (accountFilter == null || t.accountId == accountFilter)
        }
    }
    val presentCats = remember(inRange, cats) { inRange.mapNotNull { it.categoryId }.distinct().mapNotNull { cats[it] }.sortedBy { it.name } }
    val spent = filtered.sumOf { BudgetMath.spending(it, cats) }
    val inflow = filtered.filter { it.amountCents > 0 && BudgetMath.spending(it, cats) == 0L }.sumOf { it.amountCents }
    val grouped = remember(filtered) { filtered.groupBy { it.date }.toSortedMap(reverseOrder()) }

    ScreenScaffold("Activity") { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search merchant, description, note") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                if (!allTime) MonthSwitcher(month, { month = it }, Modifier.weight(1f)) else Text("All time", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                FilterChip(selected = allTime, onClick = { allTime = !allTime }, label = { Text("All") })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(accounts, key = { "a" + it.id }) { a ->
                    FilterChip(selected = accountFilter == a.id, onClick = { accountFilter = if (accountFilter == a.id) null else a.id }, label = { Text(a.name) })
                }
                items(presentCats, key = { "c" + it.id }) { c ->
                    FilterChip(selected = categoryFilter == c.id, onClick = { categoryFilter = if (categoryFilter == c.id) null else c.id }, label = { Text(c.label) })
                }
            }
            Text(
                "${filtered.size} transactions · spent ${Money.format(spent)} · money in ${Money.format(inflow)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                grouped.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(
                            LocalDate.ofEpochDay(day).format(Dates.longFormat),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(list, key = { it.id }) { TxnRow(it, cats, accountMap, onEdit) }
                }
                if (filtered.isEmpty()) {
                    item { Text("Nothing here yet.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
