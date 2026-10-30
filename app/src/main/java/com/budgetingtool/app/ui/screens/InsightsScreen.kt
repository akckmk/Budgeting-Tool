package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.insights.InsightsEngine
import com.budgetingtool.app.insights.WEEKDAY_LABELS
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BarChart
import com.budgetingtool.app.ui.components.BubbleCard
import com.budgetingtool.app.ui.theme.Bubble
import com.budgetingtool.app.ui.components.DonutChart
import com.budgetingtool.app.ui.components.MonthSwitcher
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun InsightsScreen(vm: MainViewModel) {
    val txns by vm.visibleTxns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    val engine = remember(txns, cats, accounts) { InsightsEngine(txns, cats, accounts.associateBy { it.id }) }
    val breakdown = remember(engine, month) { engine.categoryBreakdown(month) }
    val totals = remember(engine, month) { engine.monthlyTotals(month, 6) }
    val insights = remember(engine, month, budgets) { engine.insights(month, LocalDate.now(), budgets) }
    val merchants = remember(engine, month) { engine.topMerchants(month) }
    val recurring = remember(engine, month) { engine.recurring(month) }
    val spenders = remember(engine, month) { engine.bySpender(month) }
    val dow = remember(engine, month) { engine.byDayOfWeek(month) }
    val total = breakdown.sumOf { it.cents }
    val overallLimit = budgets.firstOrNull { it.categoryId == null }?.monthlyLimitCents

    ScreenScaffold("Insights") { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { PersonSwitcher(vm) }
            item { MonthSwitcher(month, { month = it }) }

            if (total == 0L && totals.all { it.second == 0L }) {
                item { Text("Import statements or add purchases to see where your money goes.", Modifier.padding(24.dp)) }
                return@LazyColumn
            }

            items(insights) { ins ->
                BubbleCard(Bubble.forKey(ins.emoji + ins.title.take(6)), Modifier.padding(vertical = 4.dp), light = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ins.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(ins.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(ins.detail, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item { SectionTitle("Where it went") }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DonutChart(
                        slices = breakdown.map { it.cents to Color(it.category?.color ?: 0xFF9E9E9E) },
                        centerValue = Money.format(total, false),
                        centerLabel = "spent",
                    )
                }
            }
            items(breakdown, key = { "b" + it.categoryId }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(s.category?.color ?: 0xFF9E9E9E)))
                    Text(s.category?.label ?: "Uncategorized", Modifier.weight(1f).padding(start = 10.dp))
                    Text("${if (total > 0) s.cents * 100 / total else 0}%", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Text(Money.format(s.cents, false), fontWeight = FontWeight.Medium)
                }
            }

            item { SectionTitle("Last 6 months") }
            item {
                BarChart(
                    values = totals.map { it.second },
                    labels = totals.map { it.first.format(Dates.monthShort) },
                    highlight = totals.lastIndex,
                    limit = overallLimit,
                )
                val avg = totals.dropLast(1).map { it.second }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
                if (avg > 0) Text("Average of earlier months: ${Money.format(avg, false)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            if (merchants.isNotEmpty()) {
                item { SectionTitle("Top merchants") }
                items(merchants, key = { "m" + it.name }) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(m.name, Modifier.weight(1f))
                        Text("${m.count}×", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                        Text(Money.format(m.cents, false), fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (recurring.isNotEmpty()) {
                item { SectionTitle("Recurring charges & subscriptions") }
                items(recurring, key = { "r" + it.name }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name)
                            Text("Seen in ${r.months} of the last 6 months · ${Money.format(r.annualCents, false)}/yr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${Money.format(r.avgCents)}/mo", fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (spenders.size > 1) {
                item { SectionTitle("Who spent what") }
                items(spenders, key = { "s" + it.first }) { (who, cents) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(who, Modifier.weight(1f))
                        Text("${if (total > 0) cents * 100 / total else 0}%", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                        Text(Money.format(cents, false), fontWeight = FontWeight.Medium)
                    }
                }
            }

            item { SectionTitle("By day of week") }
            item {
                val maxDay = dow.indices.maxByOrNull { dow[it] } ?: -1
                BarChart(values = dow, labels = WEEKDAY_LABELS, highlight = maxDay)
            }
        }
    }
}
