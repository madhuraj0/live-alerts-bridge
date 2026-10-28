package com.livealert.bridge

import android.content.Context
import android.content.SharedPreferences

class BridgePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_alerts_bridge_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MIRROR_ALL = "key_mirror_all"
        private const val KEY_ALLOWED_PACKAGES = "key_allowed_packages"

        val DEFAULT_TARGET_PACKAGES = setOf(
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.maxmpz.audioplayer",            // Poweramp
            "com.bitchord.app",                  // BitChord
            "com.lastwave.app",                  // LastWave
            "com.vanced.android.apps.youtube.music",
            "org.videolan.vlc"
        )
    }

    var mirrorAll: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ALL, true)
        set(value) = prefs.edit().putBoolean(KEY_MIRROR_ALL, value).apply()

    var allowedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_PACKAGES, DEFAULT_TARGET_PACKAGES) ?: DEFAULT_TARGET_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, value).apply()

    fun isAppAllowed(packageName: String): Boolean {
        if (mirrorAll) return true
        return allowedPackages.contains(packageName)
    }

    fun setAppAllowed(packageName: String, allowed: Boolean) {
        val current = allowedPackages.toMutableSet()
        if (allowed) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        allowedPackages = current
    }
}
