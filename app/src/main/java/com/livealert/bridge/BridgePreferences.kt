package com.livealert.bridge

import android.content.Context
import android.content.SharedPreferences

class BridgePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_alerts_bridge_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MASTER_BRIDGE_ENABLED = "key_master_bridge_enabled"
        private const val KEY_BLACKLISTED_PACKAGES = "key_blacklisted_packages"
        private const val KEY_AUTO_OPEN_PLAYER = "key_auto_open_player"
        private const val KEY_AUTO_OPEN_DELAY = "key_auto_open_delay"
        private const val KEY_AUTO_OPEN_DELAY_MS = "key_auto_open_delay_ms"
    }

    var isBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_BRIDGE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_BRIDGE_ENABLED, value).apply()

    var blacklistedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLISTED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLACKLISTED_PACKAGES, value).apply()

    var isAutoOpenPlayerEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OPEN_PLAYER, value).apply()

    var autoOpenDelayMs: Long
        get() {
            val ms = prefs.getLong(KEY_AUTO_OPEN_DELAY_MS, -1L)
            if (ms > 0) return ms
            return (prefs.getInt(KEY_AUTO_OPEN_DELAY, 1) * 1000L).coerceAtLeast(500L)
        }
        set(value) = prefs.edit().putLong(KEY_AUTO_OPEN_DELAY_MS, value).apply()

    var autoOpenDelaySeconds: Int
        get() = (autoOpenDelayMs / 1000L).toInt().coerceAtLeast(1)
        set(value) {
            autoOpenDelayMs = value * 1000L
        }

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
