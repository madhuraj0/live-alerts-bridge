package com.livealert.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class RedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect()
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect()
        finish()
    }

    private fun handleRedirect() {
        val targetPkg = intent?.getStringExtra("target_package")
            ?: MediaBridgeService.lastKnownTargetPackage
            ?: MediaBridgeService.statusFlow.value.activePlayerPackage

        val targetPendingIntent = MediaBridgeService.lastKnownTargetSessionActivity

        Log.d("LiveAlertBridge", "RedirectActivity: targetPkg=$targetPkg, hasPendingIntent=${targetPendingIntent != null}")

        var launched = false

        // Attempt 1: Trigger the target player's genuine sessionActivity PendingIntent
        if (targetPendingIntent != null) {
            try {
                targetPendingIntent.send()
                launched = true
                Log.d("LiveAlertBridge", "Redirected via target sessionActivity PendingIntent")
            } catch (e: Exception) {
                Log.w("LiveAlertBridge", "Failed to send target sessionActivity PendingIntent", e)
            }
        }

        // Attempt 2: Fallback to packageManager.getLaunchIntentForPackage
        if (!launched && !targetPkg.isNullOrBlank() && targetPkg != packageName) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    startActivity(launchIntent)
                    launched = true
                    Log.d("LiveAlertBridge", "Redirected via getLaunchIntentForPackage: $targetPkg")
                }
            } catch (e: Exception) {
                Log.e("LiveAlertBridge", "Failed to launch $targetPkg via launchIntent", e)
            }
        }
    }
}
