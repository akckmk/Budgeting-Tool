package com.budgetingtool.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.importer.Bank
import com.budgetingtool.app.ui.ImportPreview
import com.budgetingtool.app.ui.ImportViewModel
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.Picker
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.ui.theme.IncomeColor
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money

@Composable
fun ImportScreen(onBack: () -> Unit, vm: ImportViewModel = viewModel(), main: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    val preview = state.preview

    ScreenScaffold(if (preview != null) "Review import" else "Import statements", onBack = onBack) { p ->
        Box(Modifier.fillMaxSize().padding(p)) {
            when {
                preview != null -> PreviewPane(preview, vm, main, state.busy, state.queue.size)
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Bring in bank and card activity", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Pick one or more files downloaded from online banking: CSV, QFX/OFX (Quicken), QIF or PDF statements. " +
                                        "Files are read on this phone and never uploaded. Re-importing the same statement won't create duplicates.",
                                )
                                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                                    Icon(Icons.Default.UploadFile, null)
                                    Text("  Choose files")
                                }
                                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                                    Text("Reading statement…")
                                }
                            }
                        }
                    }
                    if (state.messages.isNotEmpty()) {
                        item { SectionTitle("Results") }
                        items(state.messages) { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    item { SectionTitle("Where to find the download") }
                    items(Bank.entries) { bank -> BankGuide(bank) }
                    item {
                        Text(
                            "Tip: CSV or QFX give exact results. PDFs work for most text-based statements; scanned/image PDFs can't be read.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BankGuide(bank: Bank) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()) {
        Column(Modifier.padding(14.dp)) {
            Text(bank.display, style = MaterialTheme.typography.titleSmall)
            if (open) Text(bank.howTo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PreviewPane(p: ImportPreview, vm: ImportViewModel, main: MainViewModel, busy: Boolean, queued: Int) {
    val accounts by main.accounts.collectAsStateWithLifecycle()
    val rows = p.result.transactions
    val chosen = rows.filterIndexed { i, _ -> i in p.selected }
    val sign = if (p.flip) -1 else 1
    val out = chosen.filter { it.amountCents * sign < 0 }.sumOf { -it.amountCents * sign }
    val inn = chosen.filter { it.amountCents * sign > 0 }.sumOf { it.amountCents * sign }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(p.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.result.format} · ${rows.size} rows" + (p.result.last4?.let { " · account ••$it" } ?: "") +
                        (if (queued > 0) " · $queued more file(s) waiting" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                p.result.warnings.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            item {
                SectionTitle("Import into")
                accounts.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = p.accountId == a.id, onClick = { vm.chooseAccount(a.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p.accountId == a.id, onClick = { vm.chooseAccount(a.id) })
                        Text("${a.name} (${a.institution})")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = p.accountId == null, onClick = { vm.chooseAccount(null) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p.accountId == null, onClick = { vm.chooseAccount(null) })
                    Text("New account")
                }
                if (p.accountId == null) {
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Picker("Bank", Bank.entries, p.bank, { it.display }, vm::setBank)
                        Picker("Type", AccountType.entries, p.newAccountType, { it.label }, vm::setNewAccountType)
                        OutlinedTextField(p.newAccountName, vm::setNewAccountName, label = { Text("Account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val people by main.people.collectAsStateWithLifecycle()
                        Text("Whose account is this?", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (people.filter { it != com.budgetingtool.app.insights.People.UNASSIGNED } + "Joint").distinct().forEach { who ->
                                FilterChip(selected = p.newAccountOwner.equals(who, ignoreCase = true), onClick = { vm.setNewAccountOwner(who) }, label = { Text(who) })
                            }
                        }
                        OutlinedTextField(p.newAccountOwner, vm::setNewAccountOwner, label = { Text("Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reverse amounts")
                        Text("Turn on if purchases show in green as money in.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = p.flip, onCheckedChange = vm::setFlip)
                }
                Text(
                    "${chosen.size} selected · money out ${Money.format(out)} · money in ${Money.format(inn)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row {
                    TextButton(onClick = { vm.selectAll(true) }) { Text("Select all") }
                    TextButton(onClick = { vm.selectAll(false) }) { Text("Select none") }
                }
                HorizontalDivider()
            }
            itemsIndexed(rows) { i, t ->
                val amt = t.amountCents * sign
                Row(Modifier.fillMaxWidth().clickable { vm.toggle(i) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = i in p.selected, onCheckedChange = { vm.toggle(i) })
                    Column(Modifier.weight(1f)) {
                        Text(t.description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(t.date.format(Dates.longFormat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Money.formatSigned(amt), color = if (amt > 0) IncomeColor else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::skip, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Skip file") }
            Button(onClick = vm::confirm, enabled = !busy && p.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Import ${p.selected.size}")
            }
        }
    }
}
