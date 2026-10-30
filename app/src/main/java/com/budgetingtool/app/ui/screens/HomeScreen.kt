package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import com.budgetingtool.app.insights.SavingsCoach
import com.budgetingtool.app.ui.components.BubbleCard
import com.budgetingtool.app.ui.theme.Bubble
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BudgetBar
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.ui.components.TxnRow
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onImport: () -> Unit,
    onSeeAll: () -> Unit,
    onSharing: () -> Unit,
    onCoach: () -> Unit,
    onSettings: () -> Unit,
    onCycles: () -> Unit,
    onEdit: (Txn) -> Unit,
) {
    val statements by vm.statements.collectAsStateWithLifecycle()
    val allBills = remember(statements) { com.budgetingtool.app.insights.BillingCycles.upcomingBills(statements, LocalDate.now()) }
    var showOwners by remember { mutableStateOf(false) }
    val txns by vm.txns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val month = YearMonth.now()
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val statuses = remember(txns, cats, budgets) { BudgetMath.statuses(budgets, txns, cats, month) }
    // Budgets are household-wide; spending, tips and activity follow the Combined / person view.
    val visible by vm.visibleTxns.collectAsStateWithLifecycle()
    val person by vm.viewPerson.collectAsStateWithLifecycle()
    val bills = allBills.filter { b -> person == null || accounts.firstOrNull { it.id == b.accountId }?.owner.equals(person, ignoreCase = true) }
    val spent = remember(visible, cats) { BudgetMath.totalSpending(visible, cats, month) }
    val householdSpent = remember(txns, cats) { BudgetMath.totalSpending(txns, cats, month) }
    val total = statuses.firstOrNull { it.isTotal }
    val warnAt = vm.warnPercent / 100.0
    val greeting = vm.userName.takeIf { it.isNotBlank() }?.let { "Hi, $it 👋" } ?: "Hi there 👋"
    val coachTips = remember(visible, cats) { SavingsCoach(visible, cats).tips() }
    val coachMonthly = coachTips.sumOf { it.monthlyCents }
    val topTip = coachTips.firstOrNull()?.let { "Biggest win: ${it.title.replaceFirstChar { c -> c.lowercase() }}." }

    ScreenScaffold(greeting, actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)) {
            item { PersonSwitcher(vm, Modifier.padding(bottom = 10.dp)) }
            val unowned = accounts.filter { it.owner.isBlank() }
            if (unowned.isNotEmpty() || vm.userName.isBlank()) {
                item {
                    BubbleCard(Bubble.Coral, Modifier.padding(bottom = 10.dp).clickable { showOwners = true }, light = true) {
                        Text("👥 Whose accounts are these?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (unowned.isNotEmpty()) "${unowned.size} account(s) have no owner yet, so their spending isn't in anyone's personal view. Tap to assign them."
                            else "Add your name so your spending shows under your own view.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                BubbleCard(Bubble.Violet) {
                    Text(
                        (person?.let { "$it spent in " } ?: "Spent in ") + month.format(Dates.monthFormat),
                        style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f),
                    )
                    Text(Money.format(spent), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    if (total != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { total.fraction.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(14.dp).clip(CircleShape),
                            color = if (total.fraction > 1.0) Color(0xFFFFC2CF) else Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.height(6.dp))
                        val daysLeft = month.lengthOfMonth() - LocalDate.now().dayOfMonth + 1
                        Text(
                            if (total.remainingCents >= 0) {
                                "🎯 ${Money.format(total.remainingCents, false)} left of ${Money.format(total.limitCents, false)}. That's about ${Money.format(total.remainingCents / daysLeft, false)} a day for the next $daysLeft days."
                            } else {
                                "🚨 ${Money.format(-total.remainingCents, false)} over your ${Money.format(total.limitCents, false)} limit. Try a no-spend day or two to recover."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (person != null && householdSpent > 0) {
                            Text(
                                "The bar is the household total (${Money.format(householdSpent, false)}). $person's share: ${spent * 100 / householdSpent}%.",
                                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    } else {
                        Text("Set a monthly limit on the Budgets tab to see how you're pacing.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (bills.isNotEmpty()) {
                item {
                    BubbleCard(Bubble.Sky, Modifier.padding(top = 10.dp).clickable(onClick = onCycles), light = true) {
                        Text("💳 Card bills coming up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        bills.take(3).forEach { st ->
                            val name = accounts.firstOrNull { it.id == st.accountId }?.name ?: "Card"
                            Text(
                                "$name: ${Money.format(st.closingBalanceCents ?: 0)} due ${LocalDate.ofEpochDay(st.dueDate!!).format(Dates.shortFormat)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (coachMonthly > 0) {
                item {
                    BubbleCard(Bubble.Sun, Modifier.padding(top = 10.dp).clickable(onClick = onCoach), light = true) {
                        Text("💡 You could save ${Money.format(coachMonthly, false)} a month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "That's ${Money.format(coachMonthly * 12, false)} a year. ${topTip ?: ""} Tap to see your habits and easy wins.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = onSharing,
                        label = {
                            Text(
                                when {
                                    sync.peers.isNotEmpty() -> "Live with ${sync.peers.joinToString { it.name }}"
                                    sync.configured -> "Sharing on · waiting for spouse"
                                    else -> "Share with spouse"
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.People, null) },
                    )
                    AssistChip(onClick = onImport, label = { Text("Import statement") }, leadingIcon = { Icon(Icons.Default.UploadFile, null) })
                }
            }
            if (txns.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Get started", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Import a CSV, QFX/OFX, QIF or PDF statement from Chase, KeyBank, M&T, Empower FCU, Beginning FCU, First Niagara or any other bank. " +
                                    "Everything stays on this phone.",
                            )
                            Button(onClick = onImport) { Text("Import a statement") }
                            OutlinedButton(onClick = onSharing) { Text("Pair with your spouse's phone") }
                        }
                    }
                }
            }
            val categoryStatuses = statuses.filter { !it.isTotal }
            if (categoryStatuses.isNotEmpty()) {
                item { SectionTitle("Budgets this month") }
                items(categoryStatuses.take(6), key = { it.budget.id }) { BudgetBar(it, warnAt) }
            }
            if (visible.isNotEmpty()) {
                item { SectionTitle("Recent activity") { TextButton(onClick = onSeeAll) { Text("See all") } } }
                items(visible.take(10), key = { it.id }) { TxnRow(it, cats, accountMap, onEdit) }
            }
        }
    }

    if (showOwners) OwnersDialog(vm, accounts, onDismiss = { showOwners = false })
}

/** Assigns an owner to each account (and asks for this phone's name), so person views are complete. */
@Composable
private fun OwnersDialog(vm: MainViewModel, accounts: List<com.budgetingtool.app.data.Account>, onDismiss: () -> Unit) {
    val people by vm.people.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(vm.userName) }
    val choices = (listOf(name.trim()) + people + "Joint").filter { it.isNotBlank() && it != com.budgetingtool.app.insights.People.UNASSIGNED }.distinct()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Whose accounts are these?") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    androidx.compose.material3.OutlinedTextField(
                        name, { name = it }, label = { Text("Your name on this phone") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(accounts, key = { it.id }) { a ->
                    Column {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { who ->
                                androidx.compose.material3.FilterChip(
                                    selected = a.owner.equals(who, ignoreCase = true),
                                    onClick = { vm.saveAccount(a, a.name, a.institution, a.type, a.last4 ?: "", who) },
                                    label = { Text(who) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { if (name.isNotBlank()) vm.updateUserName(name.trim()); onDismiss() }) { Text("Done") }
        },
    )
}
