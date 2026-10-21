package com.gazw89.goodhive.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gazw89.goodhive.data.prefs.ReportPreferences
import com.gazw89.goodhive.data.repository.StoredHistoryStats
import com.gazw89.goodhive.ui.components.WheelPicker
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.Instant
import java.util.Locale

/** "Reports" settings: cold-night threshold and stored history. */
@Composable
internal fun ReportsSection(
    coldNightThresholdC: Double,
    storedHistory: StoredHistoryStats?,
    onColdNightThresholdChange: (Double) -> Unit,
    onSyncNow: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var showThresholdDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)

    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(text = "Reports", style = MaterialTheme.typography.titleMedium)

        ListItem(
            headlineContent = { Text("Cold night threshold") },
            supportingContent = { Text("A night counts as cold when the room drops below this temperature.") },
            trailingContent = {
                Text(
                    text = formatThreshold(coldNightThresholdC),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            colors = rowColors,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showThresholdDialog = true },
        )

        ListItem(
            headlineContent = { Text("Stored history") },
            supportingContent = { Text(storedHistorySummary(storedHistory, Instant.now())) },
            trailingContent = { TextButton(onClick = onSyncNow) { Text("Sync now") } },
            colors = rowColors,
            modifier = Modifier.fillMaxWidth(),
        )

        ListItem(
            headlineContent = {
                Text("Clear stored history", color = MaterialTheme.colorScheme.error)
            },
            supportingContent = { Text("Deletes the saved readings. They download again on the next sync.") },
            colors = rowColors,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearDialog = true },
        )
    }

    if (showThresholdDialog) {
        ColdNightThresholdDialog(
            initialC = coldNightThresholdC,
            onDismiss = { showThresholdDialog = false },
            onConfirm = {
                onColdNightThresholdChange(it)
                showThresholdDialog = false
            },
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear stored history?") },
            text = {
                Text(
                    "This removes the temperature readings saved on this phone. " +
                        "Your Hive account is not changed, and Reports can download the history again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearDialog = false
                    },
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColdNightThresholdDialog(
    initialC: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val choices = ReportPreferences.coldNightChoices
    val labels = remember { choices.map { formatThreshold(it) } }
    var index by remember {
        mutableStateOf(choices.indexOf(ReportPreferences.normalizeColdNightThreshold(initialC)).coerceAtLeast(0))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cold night threshold") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md)) {
                Text(
                    text = "Nights colder than this show up in Reports. Between 12° and 18°.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WheelPicker(
                    values = labels,
                    selectedIndex = index,
                    onSelectedIndexChange = { index = it },
                    contentDescription = "Cold night threshold",
                    modifier = Modifier.semantics { stateDescription = labels[index] },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choices[index]) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatThreshold(valueC: Double): String = String.format(Locale.UK, "%.1f°", valueC)

internal fun formatApproxSize(bytes: Long): String = when {
    bytes < 1_000_000L -> "${maxOf(1L, (bytes + 500) / 1_000)} KB"
    else -> String.format(Locale.UK, "%.1f MB", bytes / 1_000_000.0)
}

internal fun storedHistorySummary(stats: StoredHistoryStats?, now: Instant): String {
    if (stats == null || stats.rowCount == 0) {
        return if (stats?.lastSyncMs == null) "Nothing stored yet. Sync to download your temperature history."
        else "Nothing stored. Last sync ${lastSyncText(stats.lastSyncMs, now)}."
    }
    val rows = String.format(Locale.UK, "%,d", stats.rowCount)
    val size = "estimated reading storage ${formatApproxSize(stats.approxBytes)}"
    val last = stats.lastSyncMs?.let { "Last sync ${lastSyncText(it, now)}." } ?: "Not synced yet."
    return "$rows readings, $size. $last"
}

private fun lastSyncText(epochMs: Long, now: Instant): String {
    val minutes = (now.toEpochMilli() - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> GoodHiveTime.formatForDisplay(Instant.ofEpochMilli(epochMs))
    }
}
