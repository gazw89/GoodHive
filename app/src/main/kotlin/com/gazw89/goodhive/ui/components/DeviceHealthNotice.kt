package com.gazw89.goodhive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.gazw89.goodhive.data.model.DeviceHealth
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.deviceHealth
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

/** Static, labelled warning using M3 error roles in light, dark and dynamic colour schemes. */
@Composable
fun DeviceHealthNotice(zone: HeatingZone, modifier: Modifier = Modifier) {
    val health = zone.deviceHealth ?: return
    Card(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(GoodHiveSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (health == DeviceHealth.OFFLINE) Icons.Filled.CloudOff else Icons.Filled.BatteryAlert,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs)) {
                Text(
                    text = health.title + (zone.batteryLevel?.let {
                        if (zone.online) " · $it%" else " · last reported $it%"
                    } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(health.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
