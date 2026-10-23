package io.github.dshpet.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * Self-healing alarm: while the screen is off the OEM may kill us at any time,
 * and a dead process cannot listen for the screen-on broadcast. An alarm keeps
 * firing regardless, so this re-arms until the screen is back and then starts
 * the overlay again. It does nothing once the user stops the pet.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PetPreferences(context)
        if (!prefs.wasRunning()) {
            prefs.log("watchdog: not running, stop")
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            prefs.log("watchdog: still asleep, re-arm")
            schedule(context, RECHECK_ASLEEP_MS)
            return
        }
        prefs.log("watchdog: awake, restart overlay")
        runCatching { PetOverlayService.command(context, PetOverlayService.START) }
            .onFailure { prefs.log("watchdog start failed: ${it.message}") }
        schedule(context, RECHECK_AWAKE_MS)
    }

    companion object {
        private const val REQUEST_CODE = 42
        private const val RECHECK_ASLEEP_MS = 120_000L
        private const val RECHECK_AWAKE_MS = 600_000L

        fun schedule(context: Context, delayMs: Long = RECHECK_ASLEEP_MS) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java).setAction("pet.WATCHDOG"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs, pending)
            }
        }
    }
}
