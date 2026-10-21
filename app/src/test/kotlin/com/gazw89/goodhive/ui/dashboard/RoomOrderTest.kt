package com.gazw89.goodhive.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomOrderTest {
    @Test fun pollingDoesNotResetAnUnsavedReorder() {
        assertEquals(listOf("c", "a", "b"), reconcileRoomOrder(listOf("c", "a", "b"), listOf("a", "b", "c")))
    }

    @Test fun deviceChangesRemoveMissingRoomsAndAppendNewRooms() {
        assertEquals(listOf("c", "a", "d"), reconcileRoomOrder(listOf("c", "b", "a"), listOf("a", "c", "d")))
    }

    @Test fun noRoomsAndDuplicateIdsCannotLeaveInvalidDraftEntries() {
        assertEquals(emptyList(), reconcileRoomOrder(listOf("a"), emptyList()))
        assertEquals(listOf("b", "a", "c"), reconcileRoomOrder(listOf("b", "b", "a"), listOf("a", "b", "c", "c")))
    }
}
