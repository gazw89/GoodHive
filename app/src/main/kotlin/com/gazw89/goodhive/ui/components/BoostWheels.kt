package com.gazw89.goodhive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** A labelled temperature wheel over [steps] (°C). */
@Composable
fun TemperatureWheel(
    steps: List<Double>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Temperature",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WheelPicker(
            values = steps.map { "%.1f°".format(it) },
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            contentDescription = contentDescription,
        )
    }
}

/** A labelled wheel over [boostDurationsMinutes]; [selectedIndex] indexes that list. */
@Composable
fun BoostDurationWheel(
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Duration",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WheelPicker(
            values = boostDurationsMinutes.map(::formatBoostDuration),
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            contentDescription = contentDescription,
        )
    }
}

/** Temperature and duration side by side, as in the room screen's boost sheet. */
@Composable
fun BoostWheels(
    steps: List<Double>,
    tempIndex: Int,
    onTempIndexChange: (Int) -> Unit,
    durationIndex: Int,
    onDurationIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TemperatureWheel(steps, tempIndex, onTempIndexChange, contentDescription = "Boost temperature")
        BoostDurationWheel(durationIndex, onDurationIndexChange, contentDescription = "Boost duration")
    }
}
