package com.budgetingtool.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetingtool.app.insights.People
import com.budgetingtool.app.ui.MainViewModel

/** "Combined | Andrew | Caitlin": whose spending the screen shows. Shared by every screen on this phone. */
@Composable
fun PersonSwitcher(vm: MainViewModel, modifier: Modifier = Modifier) {
    val people by vm.people.collectAsStateWithLifecycle()
    val selected by vm.viewPerson.collectAsStateWithLifecycle()
    // A name that no longer appears (renamed owner, left household) falls back to Combined.
    LaunchedEffect(people, selected) {
        if (selected != null && people.isNotEmpty() && people.none { it.equals(selected, ignoreCase = true) }) vm.selectPerson(null)
    }
    if (people.size < 2) return
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("👪 Combined", selected == null) { vm.selectPerson(null) }
            people.forEach { p ->
                Chip(if (p == People.UNASSIGNED) "❔ $p" else "👤 $p", selected.equals(p, ignoreCase = true)) { vm.selectPerson(p) }
            }
        }
        if (selected == People.UNASSIGNED) {
            Text(
                "These come from accounts with no owner. Set the owner in Settings → Accounts.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
