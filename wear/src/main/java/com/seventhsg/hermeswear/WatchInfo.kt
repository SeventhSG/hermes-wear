package com.seventhsg.hermeswear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the agent is told about the watch on each turn. Deliberately small and non-identifying:
 * battery, charging, local time and zone, model. No location and no health data.
 */
object WatchInfo {
    fun describe(context: Context): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "unknown"
        val tz = TimeZone.getDefault()
        val time = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault()).apply { timeZone = tz }.format(Date())
        return "device ${Build.MANUFACTURER} ${Build.MODEL}, battery $pct${if (charging) " (charging)" else ""}, local time $time (${tz.id})"
    }
}
