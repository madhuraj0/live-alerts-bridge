package com.livealert.bridge

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.livealert.bridge.databinding.ActivityOverlayPlayerBinding
import kotlinx.coroutines.launch

class OverlayPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveAlertBridge"
    }

    private lateinit var binding: ActivityOverlayPlayerBinding
    private lateinit var preferences: BridgePreferences

    private var targetPackage: String? = null
    private var targetSessionActivity: PendingIntent? = null
    private var isPlaying: Boolean = false
    private var autoOpenTimer: CountDownTimer? = null
    private var isCountdownCancelled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        // Make window edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityOverlayPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                insets.top + (8 * resources.displayMetrics.density).toInt(),
                view.paddingRight,
                insets.bottom + (16 * resources.displayMetrics.density).toInt()
            )
            windowInsets
        }

        preferences = BridgePreferences(this)

        setupUI()
        observeMediaState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelAutoOpen()
        updatePlaybackUI(MediaBridgeService.statusFlow.value)
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            finish()
        }

        binding.btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("open_settings", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        }

        binding.btnPrevious.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            sendMediaAction(MediaBridgeService.ACTION_PREV)
        }

        binding.btnPlayPause.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            val action = if (isPlaying) MediaBridgeService.ACTION_PAUSE else MediaBridgeService.ACTION_PLAY
            sendMediaAction(action)
        }

        binding.btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            sendMediaAction(MediaBridgeService.ACTION_NEXT)
        }

        binding.btnOpenApp.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            launchPlayingApp()
        }

        // Tap on background or content container cancels auto-open timer
        binding.overlayRoot.setOnClickListener {
            cancelAutoOpen()
        }
        binding.contentContainer.setOnClickListener {
            cancelAutoOpen()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // If user interacts with anything on screen, immediately cancel auto countdown
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            cancelAutoOpen()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun observeMediaState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaBridgeService.statusFlow.collect { status ->
                    updatePlaybackUI(status)
                }
            }
        }
    }

    private fun updatePlaybackUI(status: MediaBridgeService.Companion.BridgeStatus) {
        val pkg = status.activePlayerPackage
            ?: MediaBridgeService.lastKnownTargetPackage
            ?: intent?.getStringExtra("target_package")

        targetPackage = pkg
        targetSessionActivity = MediaBridgeService.lastKnownTargetSessionActivity
        isPlaying = status.isPlaying

        val appName = if (!pkg.isNullOrBlank()) getTargetAppName(pkg) else getString(R.string.title_live_alerts)
        val appIconDrawable = if (!pkg.isNullOrBlank()) getTargetAppIcon(pkg) else null

        // Header app badge
        binding.tvTargetAppBadge.text = getString(R.string.playing_in, appName)
        if (appIconDrawable != null) {
            binding.imgTargetAppBadge.setImageDrawable(appIconDrawable)
            binding.cardTargetAppBadge.visibility = View.VISIBLE
        } else {
            binding.cardTargetAppBadge.visibility = View.GONE
        }

        // Open in [App Name] button
        binding.btnOpenApp.text = getString(R.string.open_in, appName)
        if (appIconDrawable != null) {
            binding.btnOpenApp.icon = appIconDrawable
            binding.btnOpenApp.iconTint = null
        }

        // Track title & artist
        val title = status.trackTitle ?: getString(R.string.no_active_media)
        val artist = status.trackArtist ?: ""
        binding.tvTrackTitle.text = title
        binding.tvTrackTitle.isSelected = true // Enable marquee if long text
        binding.tvTrackArtist.text = artist

        // Album Art
        if (status.albumArt != null) {
            binding.imgAlbumArt.setImageBitmap(status.albumArt)
        } else if (appIconDrawable != null) {
            binding.imgAlbumArt.setImageDrawable(appIconDrawable)
        } else {
            binding.imgAlbumArt.setImageResource(R.drawable.ic_launcher_foreground)
        }

        // Play/Pause icon
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setIconResource(playPauseIconRes)
        binding.btnPlayPause.contentDescription = if (isPlaying) getString(R.string.play_pause) else getString(R.string.play_pause)

        // Setup auto-open countdown if enabled and not cancelled
        checkAndStartAutoOpen()
    }

    private fun checkAndStartAutoOpen() {
        if (!preferences.isAutoOpenPlayerEnabled || isCountdownCancelled || autoOpenTimer != null) {
            return
        }

        val pkg = targetPackage
        if (pkg.isNullOrBlank() || pkg == packageName) {
            return
        }

        val totalMs = (preferences.autoOpenDelaySeconds * 1000L).coerceAtLeast(1000L)
        binding.progressAutoOpen.max = 100
        binding.progressAutoOpen.progress = 100
        binding.progressAutoOpen.visibility = View.VISIBLE
        binding.tvAutoOpenNotice.visibility = View.VISIBLE

        autoOpenTimer = object : CountDownTimer(totalMs, 50L) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((millisUntilFinished.toFloat() / totalMs.toFloat()) * 100).toInt()
                binding.progressAutoOpen.progress = progress
                val secondsRemaining = (millisUntilFinished / 1000L).toInt() + 1
                binding.tvAutoOpenNotice.text = getString(R.string.opening_in_seconds, secondsRemaining)
            }

            override fun onFinish() {
                binding.progressAutoOpen.visibility = View.GONE
                binding.tvAutoOpenNotice.visibility = View.GONE
                launchPlayingApp()
            }
        }.start()
    }

    private fun cancelAutoOpen() {
        if (!isCountdownCancelled) {
            isCountdownCancelled = true
            autoOpenTimer?.cancel()
            autoOpenTimer = null
            binding.progressAutoOpen.visibility = View.GONE
            binding.tvAutoOpenNotice.visibility = View.GONE
        }
    }

    private fun launchPlayingApp() {
        val pkg = targetPackage
        val sessionActivity = targetSessionActivity

        Log.d(TAG, "OverlayPlayer: Launching targetPkg=$pkg, hasSessionActivity=${sessionActivity != null}")

        var launched = false

        // 1. Primary: Launch genuine app task via getLaunchIntentForPackage
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    startActivity(launchIntent)
                    launched = true
                    Log.d(TAG, "OverlayPlayer: Successfully launched $pkg via getLaunchIntentForPackage")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OverlayPlayer: Failed to launch $pkg via launchIntent", e)
            }
        }

        // 2. High fidelity: Send sessionActivity PendingIntent to route to now-playing screen
        if (sessionActivity != null) {
            try {
                val options = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    options.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                sessionActivity.send(this, 0, null, null, null, null, options.toBundle())
                launched = true
                Log.d(TAG, "OverlayPlayer: Sent sessionActivity PendingIntent with BAL allowed")
            } catch (e: Exception) {
                Log.w(TAG, "OverlayPlayer: Failed sessionActivity.send()", e)
            }
        }

        if (launched) {
            finish()
        } else {
            android.widget.Toast.makeText(this, "No active playback to open", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMediaAction(action: String) {
        val intent = Intent(this, MediaBridgeService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun getTargetAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun getTargetAppIcon(pkg: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
    }

    override fun finish() {
        Log.d(TAG, "OverlayPlayerActivity finish() called")
        super.finish()
    }

    override fun onDestroy() {
        autoOpenTimer?.cancel()
        autoOpenTimer = null
        super.onDestroy()
    }
}
