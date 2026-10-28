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
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livealert.bridge.databinding.ActivityMainBinding
import com.livealert.bridge.databinding.DialogBlacklistBinding
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
        val isApple = packageName == "com.apple.android.music"
        binding.tvIdentityChip.text = if (isApple) "Apple Music ID" else "Spotify ID"

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
            showBlacklistDialog()
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
                        binding.tvTrackArtist.text = "${status.trackArtist ?: ""} • ${status.activePlayerPackage ?: ""}"
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
            "0 apps excluded (all apps supported)"
        } else {
            "$count app${if (count > 1) "s" else ""} excluded"
        }
    }

    private fun showBlacklistDialog() {
        val dialogBinding = DialogBlacklistBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.cancel_button) { d, _ -> d.dismiss() }
            .create()

        fun refreshList() {
            val list = preferences.blacklistedPackages.toList().sorted()
            dialogBinding.tvEmptyBlacklist.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            dialogBinding.layoutBlacklistItems.removeAllViews()

            for (pkg in list) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setPadding(12, 16, 12, 16)
                    }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val textView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = pkg
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
                }

                val deleteBtn = ImageView(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_missing))
                    setPadding(16, 16, 16, 16)
                    setOnClickListener {
                        preferences.removeFromBlacklist(pkg)
                        refreshList()
                        updateBlacklistCount()
                    }
                }

                itemRow.addView(textView)
                itemRow.addView(deleteBtn)
                dialogBinding.layoutBlacklistItems.addView(itemRow)
            }
        }

        dialogBinding.btnAddPackage.setOnClickListener {
            val input = dialogBinding.etPackageInput.text?.toString()?.trim() ?: ""
            if (input.isNotBlank()) {
                preferences.addToBlacklist(input)
                dialogBinding.etPackageInput.text?.clear()
                refreshList()
                updateBlacklistCount()
            }
        }

        refreshList()
        dialog.show()
    }
}
