package com.livealert.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("LiveAlertBridge", "Boot / PackageReplaced received: ${intent.action}")
        // NotificationListenerService is automatically bound by the Android system
        // once permission is granted, but this receiver ensures app registration on boot.
    }
}
