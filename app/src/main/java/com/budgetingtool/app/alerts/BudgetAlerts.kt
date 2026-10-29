package com.budgetingtool.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.budgetingtool.app.MainActivity
import com.budgetingtool.app.R
import com.budgetingtool.app.data.BudgetRepository
import com.budgetingtool.app.data.Prefs
import com.budgetingtool.app.insights.BudgetMath
import com.budgetingtool.app.insights.BudgetStatus
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * Notifies once when a budget crosses the warning threshold and once when it goes over,
 * per budget per month. Runs after manual entries, imports, and purchases synced from a spouse.
 */
class BudgetAlerts(private val context: Context, private val repo: BudgetRepository, private val prefs: Prefs) {

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns when spending gets close to or goes over a budget"
            },
        )
    }

    suspend fun check(triggeredBy: String? = null, today: LocalDate = LocalDate.now()) {
        val month = YearMonth.from(today)
        val cats = repo.categoryMap()
        val statuses = BudgetMath.statuses(repo.activeBudgets(), repo.txnsInMonth(month), cats, month)
        val warnAt = prefs.warnPercent / 100.0
        for (s in statuses) {
            val level = when {
                s.fraction > 1.0 -> 2
                s.fraction >= warnAt -> 1
                else -> 0
            }
            val key = "${month}_${s.budget.id}"
            val previous = prefs.alertLevel(key)
            if (level > previous) notify(s, level, triggeredBy)
            if (level < previous) NotificationManagerCompat.from(context).cancel(s.budget.id.hashCode())
            // Dropping back (e.g. after a refund) re-arms the alert.
            if (level != previous) prefs.setAlertLevel(key, level)
        }
    }

    private fun notify(s: BudgetStatus, level: Int, triggeredBy: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pct = (s.fraction * 100).toInt()
        val title = if (level == 2) "Over budget: ${s.label}" else "${s.label} is at $pct%"
        val body = buildString {
            append("${Money.format(s.spentCents)} of ${Money.format(s.limitCents)} this month")
            if (level == 2) append(" — ${Money.format(-s.remainingCents)} over.") else append(" — ${Money.format(s.remainingCents)} left.")
            if (!triggeredBy.isNullOrBlank()) append(" Latest purchase by $triggeredBy.")
        }
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(s.budget.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val CHANNEL = "budget_alerts"
    }
}
