package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.AccountType
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.importer.Bank
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.Picker
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import kotlin.math.roundToInt

@Composable
fun MoreScreen(vm: MainViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var warn by remember { mutableFloatStateOf(vm.warnPercent.toFloat()) }
    ScreenScaffold("Settings & more", onBack = onBack) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { NavRow(Icons.Default.UploadFile, "Import statements", "CSV, QFX/OFX, QIF, PDF") { onNavigate("import") } }
            item { NavRow(Icons.Default.AccountBalance, "Accounts", "Cards and bank accounts") { onNavigate("accounts") } }
            item {
                NavRow(
                    Icons.Default.People, "Share with spouse",
                    when {
                        sync.peers.isNotEmpty() -> "Live now"
                        sync.configured -> "On · waiting for the other phone"
                        else -> "Off"
                    },
                ) { onNavigate("sync") }
            }
            item { NavRow(Icons.Default.Category, "Categories & rules", "Auto-categorize merchants") { onNavigate("rules") } }
            item { HorizontalDivider() }
            item {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Settings")
                    OutlinedTextField(vm.userName, vm::updateUserName, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Warn me at ${warn.roundToInt()}% of a budget", Modifier.padding(top = 16.dp))
                    Slider(
                        value = warn,
                        onValueChange = { warn = it },
                        onValueChangeFinished = { vm.updateWarnPercent(warn.roundToInt()) },
                        valueRange = 50f..95f,
                        steps = 8,
                    )
                    SectionTitle("Privacy & security")
                    val context = androidx.compose.ui.platform.LocalContext.current
                    ToggleRow("Privacy screen", "Block screenshots and hide balances in the recent-apps view.", vm.secureScreen, vm::updateSecureScreen)
                    ToggleRow("App lock", "Require fingerprint, face or your phone PIN to open.", vm.appLock) { on ->
                        val ok = androidx.biometric.BiometricManager.from(context).canAuthenticate(
                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                        if (on && !ok) {
                            android.widget.Toast.makeText(context, "Set up a screen lock on this phone first.", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            vm.updateAppLock(on)
                        }
                    }
                    Text(
                        "All data is stored in this app's private storage on this phone and is excluded from cloud backups. " +
                            "Statements are read on the device. Sharing goes directly phone-to-phone, encrypted with your household code. " +
                            "Uninstalling the app deletes the data, so keep your spouse's phone paired or save a sync file as a backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun AccountsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val txns by vm.txns.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    val counts = remember(txns) { txns.groupingBy { it.accountId }.eachCount() }

    ScreenScaffold("Accounts", onBack = onBack) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (accounts.isEmpty()) {
                item { Text("Accounts are created when you import a statement, or add one here.", Modifier.padding(16.dp)) }
            }
            items(accounts, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = {
                        Text(listOfNotNull(a.institution, a.type.label, a.last4?.let { "••$it" }, a.owner.ifBlank { null }, "${counts[a.id] ?: 0} transactions").joinToString(" · "))
                    },
                    modifier = Modifier.clickable { editing = a },
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(p).padding(24.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add account") }
        }
    }

    if (adding || editing != null) {
        AccountDialog(
            existing = editing,
            defaultOwner = vm.userName,
            onSave = { name, inst, type, last4, owner -> vm.saveAccount(editing, name, inst, type, last4, owner); adding = false; editing = null },
            onDelete = editing?.let { a -> { vm.deleteAccount(a); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
private fun AccountDialog(
    existing: Account?,
    defaultOwner: String,
    onSave: (String, String, AccountType, String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var institution by remember { mutableStateOf(existing?.institution ?: Bank.CHASE.display) }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.CREDIT_CARD) }
    var last4 by remember { mutableStateOf(existing?.last4 ?: "") }
    var owner by remember { mutableStateOf(existing?.owner ?: defaultOwner) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Picker("Bank", Bank.entries.map { it.display }, institution.takeIf { i -> Bank.entries.any { it.display == i } }, { it }, { institution = it })
                Picker("Type", AccountType.entries, type, { it.label }, { type = it })
                OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits (optional)") }, singleLine = true)
                OutlinedTextField(owner, { owner = it }, label = { Text("Owner (e.g. your name or Joint)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, institution, type, last4, owner) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap to confirm" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun RulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val catMap by vm.categoryMap.collectAsStateWithLifecycle()
    var addingRule by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }

    ScreenScaffold("Categories & rules", onBack = onBack) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp)) {
            item {
                SectionTitle("Merchant rules") { TextButton(onClick = { addingRule = true }) { Text("Add rule") } }
                Text(
                    "When a description contains the text, it gets that category. Rules are also created when you edit a transaction and tick \"Always use this\".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (rules.isEmpty()) item { Text("No rules yet.", Modifier.padding(vertical = 8.dp)) }
            items(rules, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("\"${r.pattern}\" → ${catMap[r.categoryId]?.label ?: "?"}", Modifier.weight(1f))
                    IconButton(onClick = { vm.deleteRule(r) }) { Icon(Icons.Default.Delete, "Delete rule") }
                }
            }
            item { SectionTitle("Categories") { TextButton(onClick = { addingCategory = true }) { Text("Add category") } } }
            items(cats, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        c.label + when (c.kind) {
                            CategoryKind.SPENDING -> ""
                            CategoryKind.INCOME -> "  (income — not spending)"
                            CategoryKind.TRANSFER -> "  (not counted as spending)"
                        },
                        Modifier.padding(12.dp),
                    )
                }
            }
        }
    }

    if (addingRule) {
        var pattern by remember { mutableStateOf("") }
        var catId by remember { mutableStateOf(cats.firstOrNull()?.id) }
        AlertDialog(
            onDismissRequest = { addingRule = false },
            title = { Text("New rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("Description contains") }, singleLine = true)
                    Picker("Category", cats, cats.firstOrNull { it.id == catId }, { it.label }, { catId = it.id })
                }
            },
            confirmButton = {
                TextButton(onClick = { catId?.let { vm.addRule(pattern, it) }; addingRule = false }, enabled = pattern.isNotBlank() && catId != null) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addingRule = false }) { Text("Cancel") } },
        )
    }

    if (addingCategory) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(CategoryKind.SPENDING) }
        AlertDialog(
            onDismissRequest = { addingCategory = false },
            title = { Text("New category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(emoji, { emoji = it.take(2) }, label = { Text("Emoji") }, singleLine = true)
                    Picker("Counts as", CategoryKind.entries, kind, {
                        when (it) {
                            CategoryKind.SPENDING -> "Spending"
                            CategoryKind.INCOME -> "Income"
                            CategoryKind.TRANSFER -> "Transfer (ignored)"
                        }
                    }, { kind = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addCategory(name, emoji, kind); addingCategory = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addingCategory = false }) { Text("Cancel") } },
        )
    }
}
