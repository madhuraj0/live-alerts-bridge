package com.livealert.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MediaActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LiveAlertBridge"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "MediaActionReceiver: onReceive action=$action")
        MediaBridgeService.handleMediaAction(context, action)
    }
}
