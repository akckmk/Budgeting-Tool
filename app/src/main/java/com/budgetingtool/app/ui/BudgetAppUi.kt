package com.budgetingtool.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.ui.screens.AccountsScreen
import com.budgetingtool.app.ui.screens.AddEntrySheet
import com.budgetingtool.app.ui.screens.BudgetsScreen
import com.budgetingtool.app.ui.screens.CoachScreen
import com.budgetingtool.app.ui.screens.CyclesScreen
import com.budgetingtool.app.ui.screens.EditTxnDialog
import com.budgetingtool.app.ui.screens.HomeScreen
import com.budgetingtool.app.ui.screens.ImportScreen
import com.budgetingtool.app.ui.screens.InsightsScreen
import com.budgetingtool.app.ui.screens.MoreScreen
import com.budgetingtool.app.ui.screens.RulesScreen
import com.budgetingtool.app.ui.screens.SyncScreen
import com.budgetingtool.app.ui.screens.TransactionsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("txns", "Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("budgets", "Budgets", Icons.Default.Savings),
    Tab("coach", "Coach", Icons.Default.Lightbulb),
    Tab("insights", "Insights", Icons.Default.Insights),
)

@Composable
fun BudgetAppUi(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }

    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(vm.secureScreen) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (vm.secureScreen) activity?.window?.addFlags(flag) else activity?.window?.clearFlags(flag)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == "home" || route == "txns") {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add purchase") },
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    vm,
                    onImport = { nav.navigate("import") },
                    onSeeAll = { nav.navigate("txns") },
                    onSharing = { nav.navigate("sync") },
                    onCoach = { nav.navigate("coach") { launchSingleTop = true } },
                    onSettings = { nav.navigate("more") },
                    onCycles = { nav.navigate("cycles") },
                    onEdit = { editing = it },
                )
            }
            composable("txns") { TransactionsScreen(vm, onEdit = { editing = it }) }
            composable("budgets") { BudgetsScreen(vm) }
            composable("coach") { CoachScreen(vm) }
            composable("insights") { InsightsScreen(vm) }
            composable("more") { MoreScreen(vm, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable("import") { ImportScreen(onBack = { nav.popBackStack() }) }
            composable("accounts") { AccountsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("sync") { SyncScreen(vm, onBack = { nav.popBackStack() }) }
            composable("rules") { RulesScreen(vm, onBack = { nav.popBackStack() }) }
            composable("cycles") { CyclesScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }

    if (showAdd) AddEntrySheet(vm, onDismiss = { showAdd = false })
    editing?.let { t -> EditTxnDialog(vm, t, onDismiss = { editing = null }) }
}
