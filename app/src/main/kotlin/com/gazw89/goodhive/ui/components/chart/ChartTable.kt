package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.GoodHiveTheme

/**
 * Plain text alternative to any chart ("Show as table"). The first column is left-aligned (the
 * label: day, hour, room); the rest are end-aligned values. Screens own the toggle, e.g.:
 *
 * ```
 * var asTable by rememberSaveable { mutableStateOf(false) }
 * if (asTable) ChartTable(headers, rows) else RangeBarChart(...)
 * TextButton(onClick = { asTable = !asTable }) { Text(if (asTable) "Show chart" else "Show as table") }
 * ```
 *
 * Rows are plain strings so the caller formats units. Column weights are equal; the first column
 * gets [firstColumnWeight] times a value column.
 */
@Composable
fun ChartTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    firstColumnWeight: Float = 1.5f,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TableRow(headers, firstColumnWeight, header = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { row -> TableRow(row, firstColumnWeight, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<String>, firstColumnWeight: Float, header: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = GoodHiveSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
    ) {
        cells.forEachIndexed { i, text ->
            Text(
                text = text,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (header) FontWeight.Medium else null,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(if (i == 0) firstColumnWeight else 1f),
            )
        }
    }
}

@Composable
private fun TablePreviewContent() {
    Surface {
        ChartTable(
            headers = listOf("Day", "Low", "High", "Average"),
            rows = listOf(
                listOf("Mon 1 Oct", "15.2°", "19.4°", "17.6°"),
                listOf("Tue 2 Oct", "15.8°", "20.1°", "18.3°"),
                listOf("Wed 3 Oct", "16.1°", "20.6°", "18.7°"),
            ),
            modifier = Modifier.padding(GoodHiveSpacing.lg),
        )
    }
}

@Preview(name = "Table light", showBackground = true)
@Composable
private fun ChartTableLightPreview() {
    GoodHiveTheme(darkTheme = false) { TablePreviewContent() }
}

@Preview(name = "Table dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ChartTableDarkPreview() {
    GoodHiveTheme(darkTheme = true) { TablePreviewContent() }
}
