package com.livealert.bridge

import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.livealert.bridge.databinding.ActivityOverlayPlayerBinding
import kotlinx.coroutines.delay
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

    private var isUserTrackingSeekBar: Boolean = false
    private var currentTrackDurationMs: Long = 0L
    private var lastKnownPositionMs: Long = 0L
    private var lastPositionUpdateTime: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var lastRenderedActionsHash: Int = 0

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
                insets.top + (24 * resources.displayMetrics.density).toInt(),
                view.paddingRight,
                insets.bottom + (16 * resources.displayMetrics.density).toInt()
            )
            windowInsets
        }

        preferences = BridgePreferences(this)

        setupUI()
        observeMediaState()
        startPositionTicker()
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

        binding.btnAudioOutput.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cancelAutoOpen()
            val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", targetPackage)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Media output switch not supported", Toast.LENGTH_SHORT).show()
                }
            }
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

        // SeekBar change listener
        binding.seekBarPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentTrackDurationMs > 0) {
                    val previewMs = (progress.toFloat() / 1000f * currentTrackDurationMs).toLong()
                    binding.tvCurrentPosition.text = formatTime(previewMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
                cancelAutoOpen()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (currentTrackDurationMs > 0) {
                    val targetMs = (progress.toFloat() / 1000f * currentTrackDurationMs).toLong()
                    sendSeekAction(targetMs)
                    lastKnownPositionMs = targetMs
                    lastPositionUpdateTime = SystemClock.elapsedRealtime()
                }
                isUserTrackingSeekBar = false
            }
        })

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

    private fun startPositionTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(500L)
                    if (isPlaying && !isUserTrackingSeekBar && currentTrackDurationMs > 0) {
                        val currentPos = computeCurrentPosition()
                        binding.tvCurrentPosition.text = formatTime(currentPos)
                        val progress = ((currentPos.toFloat() / currentTrackDurationMs.toFloat()) * 1000).toInt().coerceIn(0, 1000)
                        binding.seekBarPlayback.progress = progress
                    }
                }
            }
        }
    }

    private fun computeCurrentPosition(): Long {
        if (!isPlaying || playbackSpeed <= 0f) return lastKnownPositionMs
        val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
        val estimated = lastKnownPositionMs + (elapsed * playbackSpeed).toLong()
        return if (currentTrackDurationMs > 0) estimated.coerceIn(0L, currentTrackDurationMs) else estimated
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
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

        // Blurred Album Art Frosted Background
        if (status.albumArt != null) {
            binding.imgBlurredBackground.setImageBitmap(status.albumArt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.imgBlurredBackground.setRenderEffect(
                    RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP)
                )
            }
            binding.imgBlurredBackground.visibility = View.VISIBLE
            binding.scrimBlurredBackground.visibility = View.VISIBLE
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.imgBlurredBackground.setRenderEffect(null)
            }
            binding.imgBlurredBackground.setImageDrawable(null)
            binding.imgBlurredBackground.visibility = View.GONE
            binding.scrimBlurredBackground.visibility = View.GONE
        }

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

        // Seek Bar & Timing
        currentTrackDurationMs = status.durationMs
        lastKnownPositionMs = status.positionMs
        lastPositionUpdateTime = SystemClock.elapsedRealtime()
        playbackSpeed = if (status.speed > 0f) status.speed else 1.0f

        if (currentTrackDurationMs > 0) {
            binding.seekBarPlayback.visibility = View.VISIBLE
            binding.tvCurrentPosition.visibility = View.VISIBLE
            binding.tvTotalDuration.visibility = View.VISIBLE
            binding.tvTotalDuration.text = formatTime(currentTrackDurationMs)

            if (!isUserTrackingSeekBar) {
                val currentPos = computeCurrentPosition()
                binding.tvCurrentPosition.text = formatTime(currentPos)
                val progress = ((currentPos.toFloat() / currentTrackDurationMs.toFloat()) * 1000).toInt().coerceIn(0, 1000)
                binding.seekBarPlayback.progress = progress
            }
        } else {
            binding.seekBarPlayback.visibility = View.GONE
            binding.tvCurrentPosition.visibility = View.GONE
            binding.tvTotalDuration.visibility = View.GONE
        }

        // Custom session actions (Star/Like/Shuffle/Repeat)
        renderCustomActions(pkg, status.customActions)

        // Setup auto-open countdown if enabled and not cancelled
        checkAndStartAutoOpen()
    }

    private fun renderCustomActions(pkg: String?, actions: List<android.media.session.PlaybackState.CustomAction>) {
        val actionsHash = actions.map { it.action to it.icon }.hashCode()
        if (actionsHash == lastRenderedActionsHash) return
        lastRenderedActionsHash = actionsHash

        binding.customActionsContainer.removeAllViews()
        if (actions.isEmpty() || pkg.isNullOrBlank()) {
            binding.scrollCustomActions.visibility = View.GONE
            return
        }

        var addedCount = 0
        val targetAppResources = try {
            packageManager.getResourcesForApplication(pkg)
        } catch (e: Exception) {
            null
        }

        for (action in actions) {
            val drawable: Drawable? = if (targetAppResources != null && action.icon != 0) {
                try {
                    targetAppResources.getDrawable(action.icon, null)
                } catch (e: Exception) {
                    null
                }
            } else null

            if (drawable != null) {
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    val size = (42 * this@OverlayPlayerActivity.resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        val margin = (6 * this@OverlayPlayerActivity.resources.displayMetrics.density).toInt()
                        setMargins(margin, 0, margin, 0)
                    }
                    cornerRadius = (21 * this@OverlayPlayerActivity.resources.displayMetrics.density).toInt()
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#24808080"))
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#38FFFFFF"))
                    strokeWidth = (1.2f * this@OverlayPlayerActivity.resources.displayMetrics.density).toInt()
                    icon = drawable
                    iconSize = (22 * this@OverlayPlayerActivity.resources.displayMetrics.density).toInt()
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    iconTint = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = action.name
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        cancelAutoOpen()
                        sendCustomAction(action.action, action.extras)
                    }
                }
                binding.customActionsContainer.addView(btn)
                addedCount++
            }
        }

        binding.scrollCustomActions.visibility = if (addedCount > 0) View.VISIBLE else View.GONE
    }

    private fun checkAndStartAutoOpen() {
        if (!preferences.isAutoOpenPlayerEnabled || isCountdownCancelled || autoOpenTimer != null) {
            return
        }

        val pkg = targetPackage
        if (pkg.isNullOrBlank() || pkg == packageName) {
            return
        }

        val totalMs = preferences.autoOpenDelayMs.coerceAtLeast(500L)
        binding.progressAutoOpen.max = 100
        binding.progressAutoOpen.progress = 100
        binding.progressAutoOpen.visibility = View.VISIBLE
        binding.tvAutoOpenNotice.visibility = View.VISIBLE

        autoOpenTimer = object : CountDownTimer(totalMs, 40L) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((millisUntilFinished.toFloat() / totalMs.toFloat()) * 100).toInt()
                binding.progressAutoOpen.progress = progress
                if (totalMs < 1000L) {
                    val secondsRemaining = millisUntilFinished / 1000f
                    binding.tvAutoOpenNotice.text = getString(R.string.opening_in_decimal_seconds, secondsRemaining)
                } else {
                    val secondsRemaining = (millisUntilFinished + 999L) / 1000L
                    binding.tvAutoOpenNotice.text = getString(R.string.opening_in_seconds, secondsRemaining)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
            Toast.makeText(this, "No active playback to open", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMediaAction(action: String) {
        val intent = Intent(this, MediaBridgeService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun sendSeekAction(positionMs: Long) {
        val intent = Intent(this, MediaBridgeService::class.java).apply {
            action = MediaBridgeService.ACTION_SEEK
            putExtra("seek_position", positionMs)
        }
        startService(intent)
    }

    private fun sendCustomAction(actionName: String, extras: Bundle?) {
        val intent = Intent(this, MediaBridgeService::class.java).apply {
            action = MediaBridgeService.ACTION_CUSTOM_ACTION
            putExtra("custom_action_name", actionName)
            if (extras != null) putExtra("custom_action_extras", extras)
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
