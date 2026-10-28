package com.livealert.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class RedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPkg = intent.getStringExtra("target_package")
            ?: MediaBridgeService.statusFlow.value.activePlayerPackage

        Log.d("LiveAlertBridge", "RedirectActivity invoked for package: $targetPkg")

        if (!targetPkg.isNullOrBlank() && targetPkg != packageName) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e("LiveAlertBridge", "Failed to launch target $targetPkg", e)
                }
            }
        }

        finish()
    }
}
