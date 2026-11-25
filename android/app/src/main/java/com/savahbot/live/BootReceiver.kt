package com.savahbot.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart or an update, puts the "Start" notification back (only if "Controls in notifications" is on); also handles that notification's "Off" button. Starts nothing else. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Controls.ACTION_OFF) {
            Controls.turnOff(context)         // the notification's "Off" button
            return
        }
        Controls.refreshIdle(context)
    }
}
