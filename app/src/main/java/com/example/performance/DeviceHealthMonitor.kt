package com.example.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import java.io.File

data class DeviceHealthSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean,
    val temperatureCelsius: Float?,
    val availableStorageBytes: Long,
    val lowStorage: Boolean,
    val overheating: Boolean
)

class DeviceHealthMonitor(private val context: Context) {
    fun snapshot(serverRoot: File): DeviceHealthSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temperatureTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperature = temperatureTenths.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val storage = StatFs(serverRoot.absolutePath).availableBytes
        return DeviceHealthSnapshot(
            batteryPercent = percent,
            charging = charging,
            temperatureCelsius = temperature,
            availableStorageBytes = storage,
            lowStorage = storage < 512L * 1024 * 1024,
            overheating = temperature != null && temperature >= 45f
        )
    }
}
