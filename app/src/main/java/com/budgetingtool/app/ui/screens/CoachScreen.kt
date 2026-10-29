package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.data.CategoryKind
import com.budgetingtool.app.insights.InsightsEngine
import com.budgetingtool.app.insights.SavingsCoach
import com.budgetingtool.app.ui.MainViewModel
import com.budgetingtool.app.ui.components.BubbleCard
import com.budgetingtool.app.ui.components.Picker
import com.budgetingtool.app.ui.components.ScreenScaffold
import com.budgetingtool.app.ui.components.SectionTitle
import com.budgetingtool.app.ui.theme.Bubble
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

@Composable
fun CoachScreen(vm: MainViewModel) {
    val txns by vm.txns.collectAsStateWithLifecycle()
    val cats by vm.categoryMap.collectAsStateWithLifecycle()
    val catList by vm.categories.collectAsStateWithLifecycle()
    val coach = remember(txns, cats) { SavingsCoach(txns, cats) }
    val habits = remember(coach) { coach.habits() }
    val tips = remember(coach) { coach.tips() }
    var skipped by rememberSaveable { mutableStateOf(setOf<String>()) }
    val chosenMonthly = tips.filter { it.title !in skipped }.sumOf { it.monthlyCents }

    ScreenScaffold("Savings coach") { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!coach.hasData) {
                item {
                    BubbleCard(Bubble.Violet) {
                        Text("👋 Let's find some savings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Import a statement or add a few purchases. The coach will spot your habits and show what small changes add up to.",
                            color = Color.White,
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                BubbleCard(Bubble.Violet) {
                    Text("You could free up", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Money.format(chosenMonthly, false)} a month",
                        color = Color.White,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "That's ${Money.format(chosenMonthly * 12, false)} a year, and about " +
                            "${Money.format(SavingsCoach.futureValue(chosenMonthly, 10), false)} in 10 years if you save it.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You spend about ${Money.format(coach.monthlySpend, false)} a month. These ${tips.size - skipped.size} changes would trim " +
                            "${if (coach.monthlySpend > 0) chosenMonthly * 100 / coach.monthlySpend else 0}%.",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (habits.isNotEmpty()) {
                item { SectionTitle("Your spending habits") }
                items(habits, key = { "h" + it.title }) { h ->
                    BubbleCard(Bubble.forKey(h.title), light = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.emoji, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(h.detail, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (tips.isNotEmpty()) {
                item { SectionTitle("Easy wins: tick the ones you'll try") }
                items(tips, key = { "t" + it.title }) { t ->
                    val on = t.title !in skipped
                    BubbleCard(if (on) Bubble.Mint else Bubble.Gray, light = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, onCheckedChange = { skipped = if (it) skipped - t.title else skipped + t.title })
                            Column(Modifier.weight(1f)) {
                                Text("${t.emoji}  ${t.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(t.why, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("+${Money.format(t.monthlyCents, false)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Bubble.MintDeep)
                                Text("per month", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item { SectionTitle("What that means over time") }
                item { ProjectionCard(chosenMonthly) }
            }

            item { SectionTitle("What if I cut back on…") }
            item {
                WhatIfCalculator(
                    categories = catList.filter { it.kind == CategoryKind.SPENDING },
                    monthlyFor = { id -> categoryMonthlyAverage(InsightsEngine(txns, cats), id) },
                )
            }
            item {
                Text(
                    "Growth assumes savings earn ${(SavingsCoach.GROWTH_RATE * 100).roundToInt()}% a year, compounded monthly (similar to a high-yield savings account or index fund over time). Not financial advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun categoryMonthlyAverage(engine: InsightsEngine, categoryId: String): Long {
    val now = YearMonth.from(LocalDate.now())
    val months = (1..3).map { now.minusMonths(it.toLong()) }
    val values = months.map { m -> engine.categoryBreakdown(m).firstOrNull { it.categoryId == categoryId }?.cents ?: 0L }
    val withData = months.count { engine.total(it) > 0 }
    return if (withData == 0) {
        engine.categoryBreakdown(now).firstOrNull { it.categoryId == categoryId }?.cents ?: 0L
    } else {
        values.sum() / withData
    }
}

@Composable
private fun ProjectionCard(monthly: Long) {
    val rows = SavingsCoach.projections(monthly)
    val max = rows.maxOf { it.investedCents }.coerceAtLeast(1)
    BubbleCard(Bubble.Sun, light = true) {
        rows.forEach { r ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(r.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
                    Text("${Money.format(r.savedCents, false)} saved", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Money.format(r.investedCents, false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Bubble.VioletDeep)
                }
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.7f))) {
                    Box(Modifier.fillMaxWidth((r.investedCents.toFloat() / max).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Bubble.Violet.first()))
                    Box(Modifier.fillMaxWidth((r.savedCents.toFloat() / max).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Bubble.Mint.first()))
                }
            }
        }
        Text("Green: what you set aside. Purple: with growth.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WhatIfCalculator(categories: List<com.budgetingtool.app.data.Category>, monthlyFor: (String) -> Long) {
    var catId by rememberSaveable { mutableStateOf(categories.firstOrNull { it.id == "cat_dining" }?.id ?: categories.firstOrNull()?.id) }
    var pct by remember { mutableFloatStateOf(20f) }
    val monthly = catId?.let(monthlyFor) ?: 0L
    val saving = (monthly * pct / 100).toLong()
    BubbleCard(Bubble.Sky, light = true) {
        Picker("Category", categories, categories.firstOrNull { it.id == catId }, { it.label }, { catId = it.id })
        Spacer(Modifier.height(8.dp))
        Text("You usually spend ${Money.format(monthly, false)} a month here.", style = MaterialTheme.typography.bodyMedium)
        Text("Cut it by ${pct.roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Slider(value = pct, onValueChange = { pct = it }, valueRange = 0f..50f, steps = 9)
        Text(
            "= ${Money.format(saving, false)} a month · ${Money.format(saving * 12, false)} a year · " +
                "${Money.format(SavingsCoach.futureValue(saving, 5), false)} in 5 years",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Bubble.VioletDeep,
        )
    }
}
