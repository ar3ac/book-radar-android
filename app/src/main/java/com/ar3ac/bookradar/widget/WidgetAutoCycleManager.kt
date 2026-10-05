package com.ar3ac.bookradar.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

object WidgetAutoCycleManager {

    const val PREF_AUTO_CYCLE_ENABLED = "pref_auto_cycle_enabled"
    const val PREF_CYCLE_INTERVAL_SEC = "pref_cycle_interval_sec"
    const val ACTION_CYCLE_TICK = "com.ar3ac.bookradar.ACTION_CYCLE_TICK"

    fun scheduleNextTick(context: Context, resetDelay: Boolean = false) {
        val prefs = context.getSharedPreferences("book_radar_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(PREF_AUTO_CYCLE_ENABLED, true)
        if (!isEnabled) {
            cancelCycling(context)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true

        // Se lo schermo è spento, rinviamo il check a 3 minuti per non consumare batteria
        val intervalSec = if (!isInteractive) {
            180
        } else {
            prefs.getInt(PREF_CYCLE_INTERVAL_SEC, 30).coerceAtLeast(10)
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WidgetReceiver::class.java).apply {
            action = ACTION_CYCLE_TICK
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = SystemClock.elapsedRealtime() + (intervalSec * 1000L)

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
        } catch (_: Exception) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
        }
    }

    fun cancelCycling(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WidgetReceiver::class.java).apply {
            action = ACTION_CYCLE_TICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
