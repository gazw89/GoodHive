package com.gazw89.goodhive.data.model

const val LOW_BATTERY_THRESHOLD_PERCENT = 15

/** A battery reading is last-known information; offline alone never proves a flat battery. */
enum class DeviceHealth(val title: String, val message: String) {
    BATTERY_EMPTY("Battery empty", "Hive reports 0% battery. Replace the batteries, then wait for the device to reconnect."),
    OFFLINE_LOW_BATTERY("Offline · battery low", "The device is not responding and its last reported battery was low. Check the batteries first."),
    OFFLINE("Offline", "The device is not responding. Check its batteries and connection to the Hive hub."),
    LOW_BATTERY("Battery low", "Replace the batteries soon to keep the device connected."),
}

val HeatingZone.deviceHealth: DeviceHealth?
    get() = when {
        !reportedByHive -> null
        batteryLevel == 0 -> DeviceHealth.BATTERY_EMPTY
        !online && batteryLevel != null && batteryLevel <= LOW_BATTERY_THRESHOLD_PERCENT -> DeviceHealth.OFFLINE_LOW_BATTERY
        !online -> DeviceHealth.OFFLINE
        batteryLevel != null && batteryLevel <= LOW_BATTERY_THRESHOLD_PERCENT -> DeviceHealth.LOW_BATTERY
        else -> null
    }

/** Commands cannot be applied by an unreachable or depleted device. */
val HeatingZone.canControl: Boolean
    get() = reportedByHive && online && batteryLevel != 0
