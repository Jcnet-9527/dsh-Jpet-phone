package io.github.dshpet.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Wakes the overlay when the screen comes back on.
 *
 * A manifest receiver is used (not the service's dynamic one) because the
 * system may have killed our process while the screen was off; SCREEN_ON and
 * USER_PRESENT are exempt from the implicit-broadcast limits, so this fires in
 * a fresh process and starts the foreground service again. Starting an FGS
 * from the background is allowed here because the app holds
 * SYSTEM_ALERT_WINDOW.
 */
class ScreenWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return
        val prefs = PetPreferences(context)
        prefs.log("broadcast $action wasRunning=${prefs.wasRunning()}")
        if (!prefs.wasRunning()) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching { PetOverlayService.command(context, PetOverlayService.START) }
            .onFailure { prefs.log("wake start failed: ${it.message}") }
    }
}
