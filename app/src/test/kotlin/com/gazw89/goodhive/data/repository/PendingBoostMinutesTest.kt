package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingBoostMinutesTest {
    private val boosting = HeatingZone(
        id = "zone", productType = ProductType.TRV_CONTROL, name = "Room", online = true, currentTemperatureC = 18.0,
        targetTemperatureC = 21.0, mode = HeatingMode.BOOST, frostProtectionC = 7.0, maxEventsPerDay = 6,
        schedule = WeekSchedule(emptyMap()), heatOnDemand = null, boostMinutesLeft = 2,
    )

    @Test fun pendingBoostShowsItsOwnLengthNotTheOldBoostsRemainder() {
        assertEquals(60, applyPendingState(boosting, buildBoostPayload(60, 21.0)).boostMinutesLeft)
    }

    @Test fun pendingModeChangeClearsBoostTimeAndTargetChangeKeepsIt() {
        assertNull(applyPendingState(boosting, buildModePayload(HeatingMode.SCHEDULE, scheduleTargetC = 17.0)).boostMinutesLeft)
        assertEquals(2, applyPendingState(boosting, buildTargetPayload(22.0)).boostMinutesLeft)
    }
}
