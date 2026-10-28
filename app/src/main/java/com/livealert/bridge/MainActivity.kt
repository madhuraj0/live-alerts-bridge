package com.livealert.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.livealert.bridge.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: BridgePreferences

    private val requestPostNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BridgePreferences(this)

        setupUI()
        observeBridgeStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupUI() {
        binding.tvIdentityBadge.text = "Active Spoofed Package: $packageName"

        binding.btnGrantNotifAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Notification Listener settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnIgnoreBattery.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Battery is already unrestricted!", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings Toggles
        binding.switchMirrorAll.isChecked = preferences.mirrorAll
        binding.switchMirrorAll.setOnCheckedChangeListener { _, isChecked ->
            preferences.mirrorAll = isChecked
            binding.cbYtMusic.isEnabled = !isChecked
            binding.cbPoweramp.isEnabled = !isChecked
            binding.cbBitChord.isEnabled = !isChecked
            binding.cbLastWave.isEnabled = !isChecked
        }

        binding.cbYtMusic.isEnabled = !preferences.mirrorAll
        binding.cbPoweramp.isEnabled = !preferences.mirrorAll
        binding.cbBitChord.isEnabled = !preferences.mirrorAll
        binding.cbLastWave.isEnabled = !preferences.mirrorAll

        val allowed = preferences.allowedPackages
        binding.cbYtMusic.isChecked = allowed.contains("com.google.android.apps.youtube.music")
        binding.cbPoweramp.isChecked = allowed.contains("com.maxmpz.audioplayer")
        binding.cbBitChord.isChecked = allowed.contains("com.bitchord.app")
        binding.cbLastWave.isChecked = allowed.contains("com.lastwave.app")

        binding.cbYtMusic.setOnCheckedChangeListener { _, isChecked ->
            preferences.setAppAllowed("com.google.android.apps.youtube.music", isChecked)
            preferences.setAppAllowed("app.revanced.android.apps.youtube.music", isChecked)
        }
        binding.cbPoweramp.setOnCheckedChangeListener { _, isChecked ->
            preferences.setAppAllowed("com.maxmpz.audioplayer", isChecked)
        }
        binding.cbBitChord.setOnCheckedChangeListener { _, isChecked ->
            preferences.setAppAllowed("com.bitchord.app", isChecked)
        }
        binding.cbLastWave.setOnCheckedChangeListener { _, isChecked ->
            preferences.setAppAllowed("com.lastwave.app", isChecked)
        }

        // Test Capsule Action
        binding.btnTestCapsule.setOnClickListener {
            val intent = Intent(this, MediaBridgeService::class.java).apply {
                action = MediaBridgeService.ACTION_TEST_CAPSULE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Triggered 8-second test Live Alert capsule!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPostNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateStatus() {
        val hasNotifAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        binding.tvNotifAccessStatus.text = if (hasNotifAccess) getString(R.string.granted) else getString(R.string.missing)
        binding.tvNotifAccessStatus.setTextColor(
            ContextCompat.getColor(this, if (hasNotifAccess) R.color.status_green else R.color.status_red)
        )
        binding.btnGrantNotifAccess.isEnabled = !hasNotifAccess

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryStatus.text = if (isBatteryIgnored) getString(R.string.granted) else getString(R.string.missing)
        binding.tvBatteryStatus.setTextColor(
            ContextCompat.getColor(this, if (isBatteryIgnored) R.color.status_green else R.color.status_red)
        )
        binding.btnIgnoreBattery.isEnabled = !isBatteryIgnored
    }

    private fun observeBridgeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaBridgeService.statusFlow.collect { status ->
                    if (status.isPlaying && status.trackTitle != null) {
                        binding.tvActiveMediaStatus.text = "Active: ${status.trackTitle} — ${status.trackArtist}\nSource: ${status.activePlayerPackage}"
                        binding.tvActiveMediaStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                    } else if (status.isConnected) {
                        binding.tvActiveMediaStatus.text = "Connected & listening for playback..."
                        binding.tvActiveMediaStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    } else {
                        binding.tvActiveMediaStatus.text = getString(R.string.no_media_detected)
                        binding.tvActiveMediaStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
                    }
                }
            }
        }
    }
}
