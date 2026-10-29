package com.budgetingtool.app

import android.app.Application
import android.content.Context
import com.budgetingtool.app.alerts.BudgetAlerts
import com.budgetingtool.app.data.AppDatabase
import com.budgetingtool.app.data.BudgetRepository
import com.budgetingtool.app.data.Prefs
import com.budgetingtool.app.sync.PairingManager
import com.budgetingtool.app.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BudgetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val db = AppDatabase.build(context)
    val repo = BudgetRepository(db, prefs)
    val alerts = BudgetAlerts(context, repo, prefs)
    val sync = SyncManager(context, repo, prefs, alerts, scope)
    val pairing = PairingManager(context, prefs, sync, scope)

    fun start() {
        alerts.createChannel()
        scope.launch { repo.seedDefaults() }
        sync.startIfConfigured()
    }
}

val Context.container: AppContainer get() = (applicationContext as BudgetApp).container
