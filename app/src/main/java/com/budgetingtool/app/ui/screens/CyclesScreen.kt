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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.insights.BillingCycles
import com.budgetingtool.app.insights.CycleSummary
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BubbleCard
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.ui.theme.Bubble
import com.budgetingtool.app.ui.theme.OkColor
import com.budgetingtool.app.ui.theme.OverColor
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate

@Composable
fun CyclesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val statements by vm.statements.collectAsStateWithLifecycle()
    val txns by vm.txns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var editing by remember { mutableStateOf<Account?>(null) }
    // Cards first; bank accounts only once they have statements or a closing day.
    val shown = accounts.filter { it.type == AccountType.CREDIT_CARD || it.closingDay != null || statements.any { s -> s.accountId == it.id } }
        .sortedBy { if (it.type == AccountType.CREDIT_CARD) 0 else 1 }
    val bills = remember(statements) { BillingCycles.upcomingBills(statements, today) }

    ScreenScaffold("Cards & billing cycles", onBack = onBack) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BubbleCard(Bubble.Sky, light = true) {
                    Text("How billing cycles fit in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Every card bills on its own cycle, like the 15th to the 14th. Your budgets count each purchase in the month you made it, " +
                            "so different cycles never double-count or skew a month. Here the same purchases are grouped by each card's cycle, " +
                            "so you can see what the next bill will be and check it against the statement.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (bills.isNotEmpty()) {
                item { SectionTitle("Bills coming up") }
                items(bills, key = { "b" + it.id }) { st ->
                    val acct = accounts.firstOrNull { it.id == st.accountId }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(acct?.name ?: "Card", style = MaterialTheme.typography.bodyLarge)
                            Text("Due ${LocalDate.ofEpochDay(st.dueDate!!).format(Dates.longFormat)}" + (st.minPaymentCents?.let { " · minimum ${Money.format(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Money.format(st.closingBalanceCents ?: 0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text("No cards yet. Import a credit card statement (PDF statements include the billing period and due date).", Modifier.padding(8.dp)) }
            }
            items(shown, key = { it.id }) { acct ->
                AccountCycles(acct, statements, txns.filter { it.accountId == acct.id }, cats, today, onSetDays = { editing = acct })
            }
        }
    }

    editing?.let { a ->
        var closing by remember(a.id) { mutableStateOf(a.closingDay?.toString() ?: "") }
        var due by remember(a.id) { mutableStateOf(a.dueDay?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(a.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Find these on a statement: the closing date and the payment due date.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(closing, { closing = it.filter(Char::isDigit).take(2) }, label = { Text("Statement closes on day (1–31)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(due, { due = it.filter(Char::isDigit).take(2) }, label = { Text("Payment due on day (1–31)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setCycleDays(a, closing.toIntOrNull(), due.toIntOrNull()); editing = null }, enabled = closing.toIntOrNull() in 1..31) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountCycles(
    acct: Account,
    statements: List<com.budgetingtool.app.data.Statement>,
    accountTxns: List<com.budgetingtool.app.data.Txn>,
    cats: Map<String, com.budgetingtool.app.data.Category>,
    today: LocalDate,
    onSetDays: () -> Unit,
) {
    val cycles = remember(acct, statements, today) { BillingCycles.cycles(acct, statements, today) }
    val mine = statements.filter { it.accountId == acct.id }.sortedBy { it.periodEnd }
    val summaries = remember(cycles, accountTxns, cats) {
        cycles.map { c ->
            val prev = c.statement?.let { st -> mine.lastOrNull { it.periodEnd < st.periodEnd } }
            BillingCycles.summarize(c, accountTxns, cats, prev)
        }
    }
    val gaps = remember(accountTxns) { BillingCycles.gaps(accountTxns).takeLast(3) }

    BubbleCard(if (acct.type == AccountType.CREDIT_CARD) Bubble.Lilac else Bubble.Mint, light = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("💳 ${acct.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        acct.closingDay != null -> "Closes on the ${BillingCycles.ordinal(acct.closingDay)}" + (acct.dueDay?.let { " · due on the ${BillingCycles.ordinal(it)}" } ?: "")
                        cycles.isNotEmpty() -> "Cycle taken from its statements"
                        else -> "Closing day not known yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onSetDays) { Text(if (acct.closingDay == null) "Set" else "Edit") }
        }
        if (cycles.isEmpty()) {
            Text("Import a PDF statement for this card, or tap Set and enter the closing day printed on your statement.", style = MaterialTheme.typography.bodyMedium)
            return@BubbleCard
        }
        val current = summaries.first()
        Text(
            "This cycle: ${current.cycle.start.format(Dates.shortFormat)} – ${current.cycle.end.format(Dates.shortFormat)}",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp),
        )
        Text(Money.format(current.chargesCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        if (today in current.cycle && today < current.cycle.end) {
            Text(
                "charged so far · on pace for about ${Money.format(BillingCycles.projectedCharges(current, today), false)} by ${current.cycle.end.format(Dates.shortFormat)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Past cycles", style = MaterialTheme.typography.labelLarge)
        summaries.drop(1).forEach { CycleRow(it) }
        if (gaps.isNotEmpty()) {
            gaps.forEach { g ->
                Text(
                    "⚠️ Nothing imported ${g.from.format(Dates.shortFormat)} – ${g.to.format(Dates.shortFormat)}. Import that statement to complete this card's history.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CycleRow(s: CycleSummary) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text("${s.cycle.start.format(Dates.shortFormat)} – ${s.cycle.end.format(Dates.shortFormat)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(Money.format(s.chargesCents, false) + " charged", style = MaterialTheme.typography.bodyMedium)
        }
        val bits = buildList {
            if (s.paymentsCents > 0) add("paid ${Money.format(s.paymentsCents, false)}")
            if (s.creditsCents > 0) add("credits ${Money.format(s.creditsCents, false)}")
            s.cycle.statement?.closingBalanceCents?.let { add("statement ${Money.format(it)}") }
            s.cycle.statement?.dueDate?.let { add("due ${LocalDate.ofEpochDay(it).format(Dates.shortFormat)}") }
            if (s.count == 0) add("no transactions imported")
        }
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        s.unexplainedCents?.let { diff ->
            if (kotlin.math.abs(diff) <= 100) {
                Text("✓ Matches the statement", style = MaterialTheme.typography.bodySmall, color = OkColor)
            } else {
                Text("⚠️ ${Money.format(kotlin.math.abs(diff))} on the statement isn't in your imported transactions. A file may be missing.", style = MaterialTheme.typography.bodySmall, color = OverColor)
            }
        }
    }
}
