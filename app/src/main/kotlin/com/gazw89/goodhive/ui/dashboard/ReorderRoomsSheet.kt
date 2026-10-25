package com.gazw89.goodhive.ui.dashboard

import androidx.compose.runtime.Composable
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.ui.components.ReorderItem
import com.gazw89.goodhive.ui.components.ReorderSheet
import com.gazw89.goodhive.ui.components.reconcileOrder

/** Preserve the draft across polls, removing missing rooms and appending newly discovered ones. */
internal fun reconcileRoomOrder(draft: List<String>, available: List<String>): List<String> =
    reconcileOrder(draft, available)

@Composable
internal fun ReorderRoomsSheet(
    rooms: List<HeatingZone>,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
) {
    ReorderSheet(
        title = "Reorder rooms",
        description = "Drag the handles to arrange your rooms. The thermostat stays at the top.",
        items = rooms.map { ReorderItem(it.id, it.name) },
        saveErrorMessage = "Couldn't save room order. Try again.",
        onDismiss = onDismiss,
        onSave = onSave,
    )
}
