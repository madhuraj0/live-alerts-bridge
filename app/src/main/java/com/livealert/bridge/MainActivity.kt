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
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livealert.bridge.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: BridgePreferences

    private val requestPostNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        if (routeToPlayerIfActive(intent)) {
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BridgePreferences(this)

        setupUI()
        observeBridgeStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeToPlayerIfActive(intent)
    }

    private fun routeToPlayerIfActive(intent: Intent?): Boolean {
        val isExplicitSettings = intent?.getBooleanExtra("open_settings", false) ?: false
        val activePkg = MediaBridgeService.statusFlow.value.activePlayerPackage
            ?: MediaBridgeService.lastKnownTargetPackage

        if (!isExplicitSettings && !activePkg.isNullOrBlank()) {
            val playerIntent = Intent(this, OverlayPlayerActivity::class.java).apply {
                putExtra("target_package", activePkg)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(playerIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            finish()
            return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateBlacklistCount()
    }

    private fun setupUI() {
        val identity = if (packageName.contains("apple", ignoreCase = true)) {
            "Apple Music ID"
        } else {
            "Spotify ID"
        }
        binding.tvIdentityChip.text = identity

        binding.btnNotificationAccess.setOnClickListener {
            openNotificationListenerSettings()
        }

        binding.btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.btnManageBlacklist.setOnClickListener {
            showBlacklistBottomSheet()
        }

        // Master Switch
        val isMasterEnabled = preferences.isBridgeEnabled
        binding.switchMaster.isChecked = isMasterEnabled
        binding.tvMasterStatusDesc.text = if (isMasterEnabled) {
            "Active • Relaying media playback to island"
        } else {
            "Disabled • Media playback bridge is turned off"
        }

        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            preferences.isBridgeEnabled = isChecked
            binding.tvMasterStatusDesc.text = if (isChecked) {
                "Active • Relaying media playback to island"
            } else {
                "Disabled • Media playback bridge is turned off"
            }
            val intent = Intent(this, MediaBridgeService::class.java).apply {
                action = MediaBridgeService.ACTION_RELOAD_STATE
            }
            startService(intent)
            val msg = if (isChecked) "Bridge enabled" else "Bridge disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Tapping Now Playing card opens the Overlay Player directly
        binding.cardNowPlaying.setOnClickListener {
            val intent = Intent(this, OverlayPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }

        // Auto-open toggle switch and delay customization
        val isAutoOpen = preferences.isAutoOpenPlayerEnabled
        binding.switchAutoOpen.isChecked = isAutoOpen
        binding.layoutAutoOpenDelay.visibility = if (isAutoOpen) View.VISIBLE else View.GONE
        updateDelayPills()

        binding.switchAutoOpen.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAutoOpenPlayerEnabled = isChecked
            binding.layoutAutoOpenDelay.visibility = if (isChecked) View.VISIBLE else View.GONE
            val delayText = if (preferences.autoOpenDelayMs < 1000L) {
                String.format(Locale.US, "%.1fs", preferences.autoOpenDelayMs / 1000f)
            } else {
                "${preferences.autoOpenDelayMs / 1000L}s"
            }
            val message = if (isChecked) {
                "Auto-redirect enabled ($delayText delay)"
            } else {
                "Auto-redirect disabled: Overlay player stays open until tapped"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        binding.toggleDelayGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnDelay05s -> {
                        preferences.autoOpenDelayMs = 500L
                        binding.btnDelayCustom.text = "Custom"
                        Toast.makeText(this, "Redirect delay set to 0.5s", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnDelay1s -> {
                        preferences.autoOpenDelayMs = 1000L
                        binding.btnDelayCustom.text = "Custom"
                        Toast.makeText(this, "Redirect delay set to 1s", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnDelay2s -> {
                        preferences.autoOpenDelayMs = 2000L
                        binding.btnDelayCustom.text = "Custom"
                        Toast.makeText(this, "Redirect delay set to 2s", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnDelay3s -> {
                        preferences.autoOpenDelayMs = 3000L
                        binding.btnDelayCustom.text = "Custom"
                        Toast.makeText(this, "Redirect delay set to 3s", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnDelay5s -> {
                        preferences.autoOpenDelayMs = 5000L
                        binding.btnDelayCustom.text = "Custom"
                        Toast.makeText(this, "Redirect delay set to 5s", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnDelayCustom -> {
                        showCustomDelayDialog()
                    }
                }
            }
        }

        binding.btnDelayCustom.setOnClickListener {
            showCustomDelayDialog()
        }

        binding.btnTestCapsule.setOnClickListener {
            if (!isNotificationListenerEnabled()) {
                Toast.makeText(this, "Enable notification access first", Toast.LENGTH_SHORT).show()
                openNotificationListenerSettings()
                return@setOnClickListener
            }
            val intent = Intent(this, MediaBridgeService::class.java).apply {
                action = MediaBridgeService.ACTION_TEST_CAPSULE
            }
            startService(intent)
            Toast.makeText(this, "Test Live Alert capsule triggered!", Toast.LENGTH_SHORT).show()
        }

        checkPostNotificationsPermission()
    }

    private fun observeBridgeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaBridgeService.statusFlow.collect { status ->
                    if (status.activePlayerPackage != null) {
                        binding.tvTrackTitle.text = status.trackTitle ?: "Audio Playback"
                        binding.tvTrackArtist.text = "${status.trackArtist} • Tap for controls"
                        if (status.albumArt != null) {
                            binding.ivAlbumArt.setImageBitmap(status.albumArt)
                        } else {
                            binding.ivAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
                        }
                    } else {
                        binding.tvTrackTitle.setText(R.string.status_idle)
                        binding.tvTrackArtist.setText(R.string.status_listening)
                        binding.ivAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                }
            }
        }
    }

    private fun updateDelayPills() {
        when (preferences.autoOpenDelayMs) {
            500L -> {
                binding.toggleDelayGroup.check(R.id.btnDelay05s)
                binding.btnDelayCustom.text = "Custom"
            }
            1000L -> {
                binding.toggleDelayGroup.check(R.id.btnDelay1s)
                binding.btnDelayCustom.text = "Custom"
            }
            2000L -> {
                binding.toggleDelayGroup.check(R.id.btnDelay2s)
                binding.btnDelayCustom.text = "Custom"
            }
            3000L -> {
                binding.toggleDelayGroup.check(R.id.btnDelay3s)
                binding.btnDelayCustom.text = "Custom"
            }
            5000L -> {
                binding.toggleDelayGroup.check(R.id.btnDelay5s)
                binding.btnDelayCustom.text = "Custom"
            }
            else -> {
                binding.toggleDelayGroup.check(R.id.btnDelayCustom)
                val sec = preferences.autoOpenDelayMs / 1000f
                binding.btnDelayCustom.text = String.format(Locale.US, "%.1fs", sec)
            }
        }
    }

    private fun showCustomDelayDialog() {
        val currentSec = preferences.autoOpenDelayMs / 1000f
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", currentSec))
            setSelection(text.length)
            hint = "Seconds (e.g. 0.8)"
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Custom Redirect Delay")
            .setMessage("Enter the delay in seconds before auto-redirecting to the player app:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().toFloatOrNull()
                if (entered != null && entered >= 0.1f && entered <= 60f) {
                    val ms = (entered * 1000f).toLong()
                    preferences.autoOpenDelayMs = ms
                    updateDelayPills()
                    Toast.makeText(this, "Redirect delay set to ${entered}s", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Enter a valid time between 0.1s and 60s", Toast.LENGTH_SHORT).show()
                    updateDelayPills()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                updateDelayPills()
            }
            .setOnCancelListener {
                updateDelayPills()
            }
            .show()
    }

    private fun updateStatus() {
        val hasNotifAccess = isNotificationListenerEnabled()
        if (hasNotifAccess) {
            binding.btnNotificationAccess.visibility = View.GONE
            binding.badgeNotificationActive.visibility = View.VISIBLE
        } else {
            binding.btnNotificationAccess.visibility = View.VISIBLE
            binding.badgeNotificationActive.visibility = View.GONE
        }

        val isBatteryUnrestricted = isBatteryOptimizationsIgnored()
        if (isBatteryUnrestricted) {
            binding.btnBatteryOptimization.visibility = View.GONE
            binding.badgeBatteryActive.visibility = View.VISIBLE
        } else {
            binding.btnBatteryOptimization.visibility = View.VISIBLE
            binding.badgeBatteryActive.visibility = View.GONE
        }
    }

    private fun updateBlacklistCount() {
        val count = preferences.blacklistedPackages.size
        binding.tvBlacklistCount.text = when (count) {
            0 -> "No apps excluded"
            1 -> "1 app excluded"
            else -> "$count apps excluded"
        }
    }

    private fun showBlacklistBottomSheet() {
        val bottomSheet = BlacklistBottomSheet {
            updateBlacklistCount()
        }
        bottomSheet.show(supportFragmentManager, "BlacklistBottomSheet")
    }

    private fun checkPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPostNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open notification listener settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isBatteryOptimizationsIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(this, "Please disable battery optimization in System Settings", Toast.LENGTH_LONG).show()
            }
        }
    }
}
