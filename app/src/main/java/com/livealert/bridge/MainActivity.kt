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
import com.google.android.material.color.DynamicColors
import com.livealert.bridge.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: BridgePreferences

    private val requestPostNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material You Dynamic Theming
        DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)

        // Check if launched by OxygenOS Live Alert tap while media is active
        val isExplicitSettings = intent.getBooleanExtra("open_settings", false)
        val activePkg = MediaBridgeService.statusFlow.value.activePlayerPackage
        val isPlaying = MediaBridgeService.statusFlow.value.isPlaying

        if (!isExplicitSettings && isPlaying && !activePkg.isNullOrBlank() && activePkg != packageName) {
            val targetIntent = packageManager.getLaunchIntentForPackage(activePkg)
            if (targetIntent != null) {
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(targetIntent)
                    finish()
                    return
                } catch (e: Exception) {
                    // Fall back to settings UI
                }
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BridgePreferences(this)

        setupUI()
        observeBridgeStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateBlacklistCount()
    }

    private fun setupUI() {
        val isApple = packageName == "com.apple.android.music"
        binding.tvIdentityChip.text = if (isApple) "Apple Music ID" else "Spotify ID"

        // Tapping the hero card opens the currently playing app
        binding.cardNowPlaying.setOnClickListener {
            val activePkg = MediaBridgeService.statusFlow.value.activePlayerPackage
            if (!activePkg.isNullOrBlank() && activePkg != packageName) {
                val launchIntent = packageManager.getLaunchIntentForPackage(activePkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    try {
                        startActivity(launchIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open $activePkg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnNotificationAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        binding.btnManageBlacklist.setOnClickListener {
            val sheet = BlacklistBottomSheet {
                updateBlacklistCount()
            }
            sheet.show(supportFragmentManager, "BlacklistBottomSheet")
        }

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
            } catch (e: Exception) {
                Toast.makeText(this, "Service error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPostNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        updateBlacklistCount()
    }

    private fun updateStatus() {
        val hasNotifAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        binding.btnNotificationAccess.apply {
            if (hasNotifAccess) {
                text = getString(R.string.state_active)
                isEnabled = false
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_active_container))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
            } else {
                text = getString(R.string.action_grant)
                isEnabled = true
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.primary_container))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_primary_container))
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBatteryOptimization.apply {
            if (isBatteryIgnored) {
                text = getString(R.string.state_active)
                isEnabled = false
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_active_container))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
            } else {
                text = getString(R.string.action_unrestrict)
                isEnabled = true
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.primary_container))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_primary_container))
            }
        }
    }

    private fun observeBridgeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaBridgeService.statusFlow.collect { status ->
                    if (status.isPlaying && status.trackTitle != null) {
                        binding.tvTrackTitle.text = status.trackTitle
                        binding.tvTrackArtist.text = "${status.trackArtist ?: ""} • Tap to open ${status.activePlayerPackage ?: ""}"
                        if (status.albumArt != null) {
                            binding.ivAlbumArt.setImageBitmap(status.albumArt)
                        } else {
                            binding.ivAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
                        }
                    } else if (status.isConnected) {
                        binding.tvTrackTitle.text = getString(R.string.status_idle)
                        binding.tvTrackArtist.text = getString(R.string.status_listening)
                        binding.ivAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
                    } else {
                        binding.tvTrackTitle.text = getString(R.string.status_idle)
                        binding.tvTrackArtist.text = "Notification access required"
                        binding.ivAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                }
            }
        }
    }

    private fun updateBlacklistCount() {
        val count = preferences.blacklistedPackages.size
        binding.tvBlacklistCount.text = if (count == 0) {
            "0 apps excluded (all media apps supported)"
        } else {
            "$count app${if (count > 1) "s" else ""} excluded"
        }
    }
}
