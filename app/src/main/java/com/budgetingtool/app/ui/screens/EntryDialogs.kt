package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.data.Categorizer
import com.budgetingtool.app.data.DefaultCategories
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.Picker
import com.budgetingtool.app.ui.theme.Bubble
import com.budgetingtool.app.ui.theme.budgetColor
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.abs

/** Enter a purchase as it happens; warns before saving if it would break a budget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cats by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val txns by vm.txns.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()

    var isIncome by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var categoryTouched by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var confirmOver by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var fromReceipt by remember { mutableStateOf(false) }

    LaunchedEffect(merchant, isIncome) {
        if (!categoryTouched && merchant.length >= 3) {
            val guess = Categorizer.categorize(merchant, if (isIncome) 1 else -1, null, rules)
            categoryId = guess.takeIf { it != DefaultCategories.OTHER }
        }
    }

    val cents = Money.parseCents(amountText)?.let(::abs)?.takeIf { it > 0 }
    val month = YearMonth.from(date)
    val impacts = remember(cents, categoryId, isIncome, txns, budgets, month) {
        val spendingCat = categoryId?.let { id -> cats.firstOrNull { it.id == id }?.kind } != CategoryKind.TRANSFER
        if (cents == null || isIncome || !spendingCat) emptyList()
        else BudgetMath.impactOf(vm.statuses(month), categoryId, cents)
    }
    val goesOver = impacts.any { it.goesOver }
    val choosable = cats.filter { if (isIncome) true else it.kind != CategoryKind.INCOME }

    // The sheet steps aside while the camera is open; everything typed so far is kept.
    if (!showScanner) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isIncome) "Add income or refund" else "Add a purchase", style = MaterialTheme.typography.titleLarge)
            if (!isIncome) ScanReceiptButton(onClick = { showScanner = true })
            if (fromReceipt) {
                Text(
                    "✅ Filled in from your receipt. Please check the amount, store and date before saving.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Bubble.Mint.first(), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isIncome, onClick = { isIncome = false; confirmOver = false }, label = { Text("Purchase") })
                FilterChip(selected = isIncome, onClick = { isIncome = true }, label = { Text("Income / refund") })
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; confirmOver = false },
                label = { Text("Amount") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(if (isIncome) "From" else "Where") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Picker(
                label = "Category",
                options = choosable,
                selected = choosable.firstOrNull { it.id == categoryId },
                optionLabel = { it.label },
                onSelect = { categoryId = it.id; categoryTouched = true; confirmOver = false },
                noneLabel = "Uncategorized",
                onNone = { categoryId = null; categoryTouched = true },
            )
            Picker(
                label = "Paid with",
                options = accounts,
                selected = accounts.firstOrNull { it.id == accountId },
                optionLabel = { it.name },
                onSelect = { accountId = it.id },
                noneLabel = "Cash / not specified",
                onNone = { accountId = null },
            )
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, null)
                Spacer(Modifier.padding(4.dp))
                Text(if (date == LocalDate.now()) "Today" else date.format(Dates.longFormat))
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

            impacts.filter { it.afterFraction >= vm.warnPercent / 100.0 }.forEach { imp ->
                val color = budgetColor(imp.afterFraction, vm.warnPercent / 100.0)
                Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = color)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (imp.goesOver) {
                                "This puts ${imp.status.label} ${Money.format(imp.overByCents)} over its ${Money.format(imp.status.limitCents, false)} budget."
                            } else {
                                "${imp.status.label} will be at ${(imp.afterFraction * 100).toInt()}% (${Money.format(imp.status.limitCents - imp.afterCents)} left)."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (goesOver && !confirmOver) {
                        confirmOver = true
                    } else {
                        vm.addEntry(cents ?: 0, isIncome, merchant.ifBlank { "Purchase" }, categoryId, accountId, date, note)
                        onDismiss()
                    }
                },
                enabled = cents != null,
                colors = if (goesOver && confirmOver) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (goesOver && confirmOver) "Save anyway (over budget)" else "Save")
            }
            if (goesOver && confirmOver) {
                Text("Tap again to confirm. Your spouse's phone will get the same alert.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showScanner) {
        ReceiptScanner(
            onResult = { info ->
                info.totalCents?.let { amountText = "%d.%02d".format(it / 100, it % 100) }
                info.merchant?.let { merchant = it; categoryTouched = false }
                info.date?.let { date = it }
                fromReceipt = true
                confirmOver = false
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
    }

    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
}

@Composable
fun EditTxnDialog(vm: MainViewModel, original: Txn, onDismiss: () -> Unit) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var merchant by remember { mutableStateOf(original.merchant) }
    var amountText by remember { mutableStateOf(Money.format(abs(original.amountCents)).removePrefix("$")) }
    var isIncome by remember { mutableStateOf(original.amountCents > 0) }
    var categoryId by remember { mutableStateOf(original.categoryId) }
    var note by remember { mutableStateOf(original.note) }
    var rememberRule by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val merchantKey = remember(original) { Categorizer.merchantKey(original.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${LocalDate.ofEpochDay(original.date).format(Dates.longFormat)}\n${original.description}" +
                        (original.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.let { "\n${it.name}" } } ?: "") +
                        (if (original.enteredBy.isNotBlank()) "\nEntered by ${original.enteredBy}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    amountText, { amountText = it },
                    label = { Text("Amount") }, prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isIncome, onClick = { isIncome = false }, label = { Text("Money out") })
                    FilterChip(selected = isIncome, onClick = { isIncome = true }, label = { Text("Money in") })
                }
                Picker(
                    label = "Category",
                    options = cats,
                    selected = cats.firstOrNull { it.id == categoryId },
                    optionLabel = { it.label },
                    onSelect = { categoryId = it.id },
                )
                if (categoryId != original.categoryId) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberRule, { rememberRule = it })
                        Text("Always use this for \"$merchantKey\"", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note") })
                if (original.pending) {
                    Text("Entered by hand. It will merge with the matching row when you import the statement.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseCents(amountText)?.let(::abs) ?: abs(original.amountCents)
                vm.saveTxn(
                    original,
                    original.copy(
                        merchant = merchant.trim().ifBlank { original.merchant },
                        amountCents = if (isIncome) cents else -cents,
                        categoryId = categoryId,
                        note = note.trim(),
                    ),
                    rememberCategory = rememberRule,
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (confirmDelete) {
                    vm.deleteTxn(original)
                    onDismiss()
                } else {
                    confirmDelete = true
                }
            }) { Text(if (confirmDelete) "Tap to confirm delete" else "Delete", color = MaterialTheme.colorScheme.error) }
        },
    )
}
