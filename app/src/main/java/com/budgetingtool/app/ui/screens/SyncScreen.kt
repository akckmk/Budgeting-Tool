package com.budgetingtool.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.sync.SyncManager
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BubbleCard
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.ui.theme.Bubble
import com.budgetingtool.app.ui.theme.OkColor
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.syncState.collectAsStateWithLifecycle()
    val pairing by vm.pairingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var joinCode by remember { mutableStateOf("") }
    var joinHost by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var spouseEmail by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(vm::exportSync) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSync) }

    fun inviteText(code: String) =
        "I'd like to share our budget with you in Budgeting Tool.\n\n" +
            "1. Install the app and open More → Share with spouse → Join.\n" +
            "2. Connect to our home Wi-Fi.\n" +
            "3. Enter this one-time invite code: $code\n\n" +
            "The code works once, expires in 24 hours, and I have to approve you on my phone. " +
            "None of our financial data is in this email or on any server; it only travels directly between our phones."

    ScreenScaffold("Share with spouse", onBack = onBack) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BubbleCard(Bubble.Violet) {
                    Text("🔒 Private by design", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "No account, no cloud. Your two phones talk directly over your Wi-Fi, encrypted with a key only they hold. " +
                            "While you're both on the same network, new purchases appear on the other phone within a second. " +
                            "Apart, each phone keeps working and they catch up when you're home.",
                    )
                }
            }
            item {
                OutlinedTextField(
                    vm.userName, vm::updateUserName,
                    label = { Text("Your name (shown to your spouse)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------- invite (works whether or not sharing is already on) ----------
            item { SectionTitle(if (s.configured) "Invite your spouse" else "Phone 1: send an invite") }
            val invite = pairing.invite
            if (invite == null) {
                item {
                    Text("Creates a one-time code you can send by Gmail or text. Your spouse must be on the same Wi-Fi to use it, and you approve them here.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::createInvite, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Create invite") }
                }
            } else {
                item {
                    BubbleCard(Bubble.Sun, light = true) {
                        Text("One-time invite code", style = MaterialTheme.typography.labelLarge)
                        Text(invite.code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Expires ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(invite.expiresAt))}. Keep this screen open on the same Wi-Fi while your spouse joins.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            spouseEmail, { spouseEmail = it.trim() },
                            label = { Text("Spouse's Gmail address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val email = Intent(Intent.ACTION_SENDTO, Uri.parse(
                                    "mailto:" + Uri.encode(spouseEmail) + "?subject=" + Uri.encode("Join our budget") + "&body=" + Uri.encode(inviteText(invite.code)),
                                ))
                                    .putExtra(Intent.EXTRA_SUBJECT, "Join our budget")
                                    .putExtra(Intent.EXTRA_TEXT, inviteText(invite.code))
                                try {
                                    context.startActivity(email.setPackage("com.google.android.gm"))
                                } catch (_: ActivityNotFoundException) {
                                    runCatching { context.startActivity(email.setPackage(null)) }
                                }
                            }) {
                                Icon(Icons.Default.Email, null)
                                Text("  Invite via Gmail")
                            }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText(invite.code))
                                context.startActivity(Intent.createChooser(send, "Send invite"))
                            }) {
                                Icon(Icons.Default.Share, null)
                                Text("  Other")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Waiting for your spouse… (${s.localAddress ?: "no Wi-Fi"})", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = vm::cancelInvite) { Text("Cancel invite") }
                    }
                }
            }

            // ---------- join ----------
            if (!s.configured) {
                item { SectionTitle("Phone 2: join with an invite") }
                item {
                    OutlinedTextField(
                        joinCode, { joinCode = it },
                        label = { Text("Invite code") },
                        placeholder = { Text("XXXX-XXXX-XXXX") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showManual = !showManual }) { Text(if (showManual) "Hide IP option" else "Can't find the other phone? Enter its IP") }
                    if (showManual) {
                        OutlinedTextField(joinHost, { joinHost = it.trim() }, label = { Text("Other phone's IP (shown on its invite)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Button(
                        onClick = { vm.joinWithInvite(joinCode, joinHost.ifBlank { null }) },
                        enabled = joinCode.isNotBlank() && !pairing.joining,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Join household") }
                    pairing.joinStatus?.let {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pairing.joining) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  $it", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            pairing.lastResult?.let { msg ->
                item { BubbleCard(Bubble.Mint, light = true) { Text("🎉 $msg", style = MaterialTheme.typography.bodyLarge) } }
            }

            // ---------- status ----------
            if (s.configured) {
                item { SectionTitle("Status") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLine(s.running, if (s.running) "Sharing is on · ${s.localAddress ?: "no Wi-Fi"}:${s.port}" else "Sharing is starting…")
                        if (s.peers.isEmpty()) {
                            StatusLine(false, "Waiting for your spouse's phone on this network")
                        } else {
                            s.peers.forEach { StatusLine(true, "Live with ${it.name}") }
                        }
                        if (s.lastSyncAt > 0) {
                            Text("Last synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastSyncAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::fullResync, enabled = s.peers.isNotEmpty()) { Text("Resend everything") }
                        OutlinedButton(onClick = { showManual = true }) { Text("Connect by IP") }
                    }
                }
                item { SectionTitle("Not on the same Wi-Fi?") }
                item {
                    Text(
                        "Save an encrypted sync file and pass it to the other phone with Quick Share, Bluetooth or a USB cable, then import it there. " +
                            "Only your two phones can open it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { exporter.launch("budget-sync.btsync") }) { Text("Save sync file") }
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import sync file") }
                    }
                }
                if (s.log.isNotEmpty()) {
                    item { SectionTitle("Activity") }
                    items(s.log) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    TextButton(onClick = { confirmLeave = true }) { Text("Stop sharing on this phone", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    pairing.pendingRequestFrom?.let { who ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Let $who join?") },
            text = { Text("$who used your invite code and wants to share this budget. Only allow if this is your spouse's phone. They'll see all accounts and transactions.") },
            confirmButton = { Button(onClick = { vm.answerJoin(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.answerJoin(false) }) { Text("Deny") } },
        )
    }

    if (showManual && s.configured) {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf(SyncManager.DEFAULT_PORT.toString()) }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Connect by IP address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use the address shown under Status on the other phone. Helpful if your router blocks device discovery.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(host, { host = it }, label = { Text("IP address") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.connectManually(host, port.toIntOrNull() ?: SyncManager.DEFAULT_PORT); showManual = false }, enabled = host.isNotBlank()) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cancel") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Stop sharing?") },
            text = { Text("This phone keeps its data but stops syncing and forgets the household key. To share again, create or accept a new invite.") },
            confirmButton = { TextButton(onClick = { vm.leaveHousehold(); confirmLeave = false }) { Text("Stop sharing") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) OkColor else Color.Gray))
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
