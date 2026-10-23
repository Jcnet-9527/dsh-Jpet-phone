package io.github.dshpet.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the pet back after a reboot, but only when the user left it running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PetPreferences(context).wasRunning()) return
        runCatching {
            PetOverlayService.command(context, PetOverlayService.START)
        }
    }
}
