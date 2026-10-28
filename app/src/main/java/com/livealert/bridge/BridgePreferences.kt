package com.livealert.bridge

import android.content.Context
import android.content.SharedPreferences

class BridgePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_alerts_bridge_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BLACKLISTED_PACKAGES = "key_blacklisted_packages"
    }

    var blacklistedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLISTED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLACKLISTED_PACKAGES, value).apply()

    fun isBlacklisted(packageName: String): Boolean {
        return blacklistedPackages.contains(packageName)
    }

    fun addToBlacklist(packageName: String) {
        val current = blacklistedPackages.toMutableSet()
        current.add(packageName.trim())
        blacklistedPackages = current
    }

    fun removeFromBlacklist(packageName: String) {
        val current = blacklistedPackages.toMutableSet()
        current.remove(packageName.trim())
        blacklistedPackages = current
    }
}
