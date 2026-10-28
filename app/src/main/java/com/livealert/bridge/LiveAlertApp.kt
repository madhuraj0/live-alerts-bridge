package com.livealert.bridge

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.util.Log

class LiveAlertApp : Application() {

    companion object {
        private const val TAG = "LiveAlertBridge"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)

            try {
                // Remove any dangling Live Alert foreground notification to prevent island glitch
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(MediaBridgeService.NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel notification during crash recovery", e)
            }

            // Delegate to system default handler to complete process teardown cleanly
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
