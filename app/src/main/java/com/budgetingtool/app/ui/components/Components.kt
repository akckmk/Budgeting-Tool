package com.budgetingtool.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.budgetingtool.app.data.Account
import com.budgetingtool.app.data.Category
import com.budgetingtool.app.data.Txn
import com.budgetingtool.app.insights.BudgetStatus
import com.budgetingtool.app.ui.theme.IncomeColor
import com.budgetingtool.app.ui.theme.budgetColor
import com.budgetingtool.app.util.Dates
import com.budgetingtool.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

/** Top-level screens sit above the bottom bar; sub-screens own the navigation-bar inset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = actions,
            )
        },
        // Include the keyboard so focused text fields are never hidden behind it.
        contentWindowInsets = if (onBack == null) WindowInsets.ime else WindowInsets.navigationBars.union(WindowInsets.ime),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
        Text(month.format(Dates.monthFormat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
            Icon(Icons.Default.ChevronRight, "Next month")
        }
    }
}

@Composable
fun CategoryDot(category: Category?, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Color(category?.color ?: 0xFF9E9E9E).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(category?.emoji ?: "❓", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun TxnRow(t: Txn, cats: Map<String, Category>, accounts: Map<String, Account>, onClick: (Txn) -> Unit) {
    val cat = t.categoryId?.let { cats[it] }
    Row(
        Modifier.fillMaxWidth().clickable { onClick(t) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(cat)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchant.ifBlank { t.description }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                LocalDate.ofEpochDay(t.date).format(Dates.shortFormat),
                cat?.name ?: "Uncategorized",
                t.accountId?.let { accounts[it]?.name },
                t.enteredBy.takeIf { it.isNotBlank() },
                if (t.pending) "pending" else null,
            ).joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Money.formatSigned(t.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (t.amountCents > 0) IncomeColor else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun BudgetBar(status: BudgetStatus, warnAt: Double, onClick: (() -> Unit)? = null) {
    val color = budgetColor(status.fraction, warnAt)
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Money.format(status.spentCents, false)} / ${Money.format(status.limitCents, false)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { status.fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = color,
            trackColor = color.copy(alpha = 0.18f),
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (status.remainingCents >= 0) "${Money.format(status.remainingCents, false)} left" else "${Money.format(-status.remainingCents, false)} over",
            style = MaterialTheme.typography.bodySmall,
            color = if (status.remainingCents >= 0) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
    }
}

/** Generic read-only dropdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
    onNone: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: (noneLabel ?: ""),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (noneLabel != null && onNone != null) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onNone(); expanded = false })
            }
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/**
 * Rounded gradient card used for the friendly "bubble" look. [light] gradients get dark text so
 * they stay readable in dark mode too.
 */
@Composable
fun BubbleCard(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    light: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val textColor = if (light) Color(0xFF1C1A2B) else Color.White
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides textColor) {
        Column(
            modifier.fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(colors))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}
