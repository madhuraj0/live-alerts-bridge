package com.livealert.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaBridgeService : NotificationListenerService() {

    companion object {
        private const val TAG = "LiveAlertBridge"
        const val CHANNEL_ID = "live_alerts_media_bridge_channel"
        const val NOTIFICATION_ID = 40401

        const val ACTION_TEST_CAPSULE = "com.livealert.bridge.ACTION_TEST_CAPSULE"
        const val ACTION_PLAY = "com.livealert.bridge.ACTION_PLAY"
        const val ACTION_PAUSE = "com.livealert.bridge.ACTION_PAUSE"
        const val ACTION_NEXT = "com.livealert.bridge.ACTION_NEXT"
        const val ACTION_PREV = "com.livealert.bridge.ACTION_PREV"
        const val ACTION_SEEK = "com.livealert.bridge.ACTION_SEEK"
        const val ACTION_CUSTOM_ACTION = "com.livealert.bridge.ACTION_CUSTOM_ACTION"
        const val ACTION_RELOAD_STATE = "com.livealert.bridge.ACTION_RELOAD_STATE"

        data class BridgeStatus(
            val isConnected: Boolean = false,
            val isEnabled: Boolean = true,
            val activePlayerPackage: String? = null,
            val trackTitle: String? = null,
            val trackArtist: String? = null,
            val trackAlbum: String? = null,
            val albumArt: Bitmap? = null,
            val isPlaying: Boolean = false,
            val positionMs: Long = 0L,
            val durationMs: Long = 0L,
            val speed: Float = 1.0f,
            val customActions: List<PlaybackState.CustomAction> = emptyList()
        )

        private val _statusFlow = MutableStateFlow(BridgeStatus())
        val statusFlow = _statusFlow.asStateFlow()

        @Volatile
        var lastKnownTargetPackage: String? = null
        @Volatile
        var lastKnownTargetSessionActivity: PendingIntent? = null
    }

    private lateinit var preferences: BridgePreferences
    private var mediaSessionManager: MediaSessionManager? = null
    private var bridgeSession: MediaSessionCompat? = null
    private var activeTargetController: MediaController? = null

    private var lastRelayedPkg: String? = null
    private var lastRelayedTitle: String? = null
    private var lastRelayedArtist: String? = null
    private var lastRelayedIsPlaying: Boolean? = null
    private var lastRelayedArtBitmap: Bitmap? = null
    private var pendingTeardownRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTesting = false

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncActiveSessions(controllers)
    }

    private val trackedCallbacks = java.util.concurrent.ConcurrentHashMap<MediaSession.Token, TrackedControllerCallback>()

    private inner class TrackedControllerCallback(val controller: MediaController) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val stateVal = state?.state ?: PlaybackState.STATE_NONE
            val isPlaying = stateVal == PlaybackState.STATE_PLAYING ||
                            stateVal == PlaybackState.STATE_BUFFERING ||
                            stateVal == PlaybackState.STATE_CONNECTING ||
                            stateVal == PlaybackState.STATE_FAST_FORWARDING ||
                            stateVal == PlaybackState.STATE_REWINDING

            Log.i(TAG, "TrackedCallback onPlaybackStateChanged: pkg=${controller.packageName}, state=$stateVal, isPlaying=$isPlaying")

            if (isPlaying && activeTargetController?.sessionToken != controller.sessionToken) {
                Log.i(TAG, "TrackedCallback: switching target to actively playing pkg=${controller.packageName}")
                activeTargetController = controller
                relayMediaUpdate(controller)
                return
            }

            if (activeTargetController?.sessionToken == controller.sessionToken) {
                relayMediaUpdate(controller)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (activeTargetController?.sessionToken == controller.sessionToken) {
                Log.i(TAG, "TrackedCallback onMetadataChanged: pkg=${controller.packageName}, title=${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                relayMediaUpdate(controller)
            }
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "TrackedCallback onSessionDestroyed: pkg=${controller.packageName}")
            trackedCallbacks.remove(controller.sessionToken)
            if (activeTargetController?.sessionToken == controller.sessionToken) {
                activeTargetController = null
                mediaSessionManager?.let { mgr ->
                    val component = ComponentName(this@MediaBridgeService, MediaBridgeService::class.java)
                    syncActiveSessions(mgr.getActiveSessions(component))
                } ?: scheduleTeardown("sessionDestroyed", 2000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = BridgePreferences(this)
        createNotificationChannel()
        initBridgeMediaSession()
    }

    private inline fun safeTransportAction(actionName: String, block: (MediaController.TransportControls) -> Unit) {
        try {
            activeTargetController?.transportControls?.let(block)
        } catch (e: Exception) {
            Log.w(TAG, "Failed executing transport action '$actionName'", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_CAPSULE -> runTestCapsule()
            ACTION_PLAY -> safeTransportAction("play") { it.play() }
            ACTION_PAUSE -> safeTransportAction("pause") { it.pause() }
            ACTION_NEXT -> safeTransportAction("next") { it.skipToNext() }
            ACTION_PREV -> safeTransportAction("prev") { it.skipToPrevious() }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra("seek_position", -1L)
                if (pos >= 0) safeTransportAction("seekTo") { it.seekTo(pos) }
            }
            ACTION_CUSTOM_ACTION -> {
                val actionName = intent.getStringExtra("custom_action_name")
                val extras = intent.getBundleExtra("custom_action_extras")
                if (!actionName.isNullOrBlank()) {
                    safeTransportAction("customAction:$actionName") { it.sendCustomAction(actionName, extras) }
                }
            }
            ACTION_RELOAD_STATE -> {
                if (!preferences.isBridgeEnabled) {
                    teardownBridgePlayback("master_switch_disabled")
                } else {
                    mediaSessionManager?.let { mgr ->
                        val component = ComponentName(this, MediaBridgeService::class.java)
                        syncActiveSessions(mgr.getActiveSessions(component))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _statusFlow.value = _statusFlow.value.copy(
            isConnected = true,
            isEnabled = preferences.isBridgeEnabled
        )

        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaBridgeService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            syncActiveSessions(mediaSessionManager?.getActiveSessions(component))
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching MediaSessionManager listener", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cancelPendingTeardown()
        _statusFlow.value = BridgeStatus(isConnected = false)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing listener", e)
        }
        cleanupTrackedControllers()
        activeTargetController = null
        teardownBridgePlayback("onListenerDisconnected")
    }

    private fun initBridgeMediaSession() {
        bridgeSession = MediaSessionCompat(this, "OxygenOSLiveAlertBridge").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { safeTransportAction("play") { it.play() } }
                override fun onPause() { safeTransportAction("pause") { it.pause() } }
                override fun onSkipToNext() { safeTransportAction("next") { it.skipToNext() } }
                override fun onSkipToPrevious() { safeTransportAction("prev") { it.skipToPrevious() } }
                override fun onSeekTo(pos: Long) { safeTransportAction("seekTo") { it.seekTo(pos) } }
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action != null) safeTransportAction("customAction:$action") { it.sendCustomAction(action, extras) }
                }
            })
        }
    }

    private fun updateTrackedControllers(eligibleControllers: List<MediaController>) {
        val currentTokens = eligibleControllers.map { it.sessionToken }.toSet()

        // Unregister removed controllers
        val iterator = trackedCallbacks.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentTokens) {
                try {
                    entry.value.controller.unregisterCallback(entry.value)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering callback", e)
                }
                iterator.remove()
            }
        }

        // Register new controllers
        for (controller in eligibleControllers) {
            val token = controller.sessionToken
            if (!trackedCallbacks.containsKey(token)) {
                val cb = TrackedControllerCallback(controller)
                try {
                    controller.registerCallback(cb)
                    trackedCallbacks[token] = cb
                    Log.d(TAG, "Registered callback for pkg=${controller.packageName}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error registering callback on ${controller.packageName}", e)
                }
            }
        }
    }

    private fun cleanupTrackedControllers() {
        for ((_, cb) in trackedCallbacks) {
            try {
                cb.controller.unregisterCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback", e)
            }
        }
        trackedCallbacks.clear()
    }

    private fun syncActiveSessions(controllers: List<MediaController>?) {
        if (isTesting) {
            Log.i(TAG, "syncActiveSessions skipped: isTesting=true")
            return
        }

        if (!preferences.isBridgeEnabled) {
            Log.i(TAG, "syncActiveSessions: master bridge switch is OFF, cleaning up")
            cleanupTrackedControllers()
            activeTargetController = null
            teardownBridgePlayback("master_switch_disabled")
            return
        }

        if (controllers.isNullOrEmpty()) {
            Log.i(TAG, "syncActiveSessions: controllers is empty, scheduling teardown")
            cleanupTrackedControllers()
            activeTargetController = null
            scheduleTeardown("syncActiveSessions.emptyControllers", 2500L)
            return
        }

        val allDescriptions = controllers.map { c ->
            "${c.packageName}(state=${c.playbackState?.state})"
        }.joinToString(", ")
        Log.i(TAG, "syncActiveSessions: count=${controllers.size}, list=[$allDescriptions]")

        // Match ANY media app that is NOT this bridge app and NOT blacklisted
        val eligibleControllers = controllers.filter { controller ->
            val pkg = controller.packageName
            pkg != packageName && !preferences.isBlacklisted(pkg)
        }

        if (eligibleControllers.isEmpty()) {
            Log.i(TAG, "syncActiveSessions: eligible controllers empty, scheduling teardown")
            cleanupTrackedControllers()
            activeTargetController = null
            scheduleTeardown("syncActiveSessions.noEligible", 2500L)
            return
        }

        updateTrackedControllers(eligibleControllers)

        val currentTarget = activeTargetController
        val playingController = eligibleControllers.firstOrNull {
            val s = it.playbackState?.state
            s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
        } ?: if (currentTarget != null && eligibleControllers.any { it.sessionToken == currentTarget.sessionToken }) {
            currentTarget
        } else {
            eligibleControllers.firstOrNull()
        }

        Log.i(TAG, "syncActiveSessions: active target=${playingController?.packageName}(state=${playingController?.playbackState?.state})")

        if (playingController != null) {
            cancelPendingTeardown()
            activeTargetController = playingController
            relayMediaUpdate(playingController)
        } else {
            scheduleTeardown("syncActiveSessions.noPick", 2500L)
        }
    }

    private fun relayMediaUpdate(target: MediaController) {
        if (!preferences.isBridgeEnabled) {
            Log.i(TAG, "relayMediaUpdate: master bridge switch is OFF")
            teardownBridgePlayback("master_switch_disabled")
            return
        }

        val state = target.playbackState
        val metadata = target.metadata

        val stateVal = state?.state ?: PlaybackState.STATE_NONE
        val isPlaying = stateVal == PlaybackState.STATE_PLAYING ||
                        stateVal == PlaybackState.STATE_BUFFERING ||
                        stateVal == PlaybackState.STATE_CONNECTING ||
                        stateVal == PlaybackState.STATE_FAST_FORWARDING ||
                        stateVal == PlaybackState.STATE_REWINDING
        val isPaused = stateVal == PlaybackState.STATE_PAUSED

        Log.i(TAG, "relayMediaUpdate: pkg=${target.packageName}, stateVal=$stateVal, isPlaying=$isPlaying, isPaused=$isPaused")

        if (!isPlaying && !isPaused) {
            Log.i(TAG, "relayMediaUpdate: not playing & not paused ($stateVal), scheduling teardown")
            scheduleTeardown("relayMediaUpdate.notPlayingNotPaused($stateVal)", 3000L)
            return
        }

        cancelPendingTeardown()

        // Keep target package and its sessionActivity readily available for overlay/redirect
        lastKnownTargetPackage = target.packageName
        lastKnownTargetSessionActivity = target.sessionActivity

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Audio Playback"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: target.packageName
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1.0f

        val targetAppName = getTargetAppName(target.packageName)
        val targetAppBitmap = getTargetAppBitmap(target.packageName)

        val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: targetAppBitmap

        val metadataChanged = (title != lastRelayedTitle) ||
                              (artist != lastRelayedArtist) ||
                              (target.packageName != lastRelayedPkg) ||
                              (artBitmap.generationId != lastRelayedArtBitmap?.generationId)

        val stateChanged = (isPlaying != lastRelayedIsPlaying)

        if (metadataChanged) {
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, targetAppName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, targetAppBitmap)

            bridgeSession?.setMetadata(metaBuilder.build())
            lastRelayedTitle = title
            lastRelayedArtist = artist
            lastRelayedArtBitmap = artBitmap
        }

        val targetState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(targetState, position, speed)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
        bridgeSession?.setPlaybackState(stateBuilder.build())

        // Build explicit PendingIntent targeting OverlayPlayerActivity
        val playerIntent = Intent(this, OverlayPlayerActivity::class.java).apply {
            putExtra("target_package", target.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            playerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        bridgeSession?.setSessionActivity(launchPendingIntent)
        bridgeSession?.isActive = true

        val customActionsList = state?.customActions ?: emptyList()

        _statusFlow.value = _statusFlow.value.copy(
            isEnabled = preferences.isBridgeEnabled,
            activePlayerPackage = target.packageName,
            trackTitle = title,
            trackArtist = artist,
            trackAlbum = album,
            albumArt = artBitmap,
            isPlaying = isPlaying,
            positionMs = position,
            durationMs = duration,
            speed = speed,
            customActions = customActionsList
        )

        if (metadataChanged || stateChanged || lastRelayedPkg != target.packageName) {
            lastRelayedIsPlaying = isPlaying
            lastRelayedPkg = target.packageName
            postBridgeNotification(target, title, artist, artBitmap, isPlaying, launchPendingIntent)
        }
    }

    private fun postBridgeNotification(
        target: MediaController,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        launchPendingIntent: PendingIntent
    ) {
        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, MediaBridgeService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPausePendingIntent = PendingIntent.getService(
            this, 2, Intent(this, MediaBridgeService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, MediaBridgeService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val targetAppName = getTargetAppName(target.packageName)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(albumArt)
            .setContentIntent(launchPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(bridgeSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .apply {
                extras.putString("android.substName", targetAppName)
            }
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getTargetAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun getTargetAppIcon(pkg: String): IconCompat {
        return try {
            val bitmap = getTargetAppBitmap(pkg)
            IconCompat.createWithBitmap(bitmap)
        } catch (e: Exception) {
            IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground)
        }
    }

    private fun getTargetAppBitmap(pkg: String): Bitmap {
        val drawable = packageManager.getApplicationIcon(pkg)
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 144
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 144
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun scheduleTeardown(reason: String, delayMs: Long = 2500L) {
        if (pendingTeardownRunnable != null) {
            Log.d(TAG, "scheduleTeardown already pending: reason='$reason'")
            return
        }
        Log.i(TAG, "scheduleTeardown in ${delayMs}ms: reason='$reason'")
        pendingTeardownRunnable = Runnable {
            pendingTeardownRunnable = null
            teardownBridgePlayback(reason)
        }.also {
            mainHandler.postDelayed(it, delayMs)
        }
    }

    private fun cancelPendingTeardown() {
        pendingTeardownRunnable?.let {
            Log.i(TAG, "cancelPendingTeardown: cancelling scheduled teardown")
            mainHandler.removeCallbacks(it)
            pendingTeardownRunnable = null
        }
    }

    private fun teardownBridgePlayback(reason: String = "") {
        cancelPendingTeardown()
        Log.i(TAG, "teardownBridgePlayback: reason='$reason'")

        lastRelayedPkg = null
        lastRelayedTitle = null
        lastRelayedArtist = null
        lastRelayedIsPlaying = null
        lastRelayedArtBitmap = null

        _statusFlow.value = _statusFlow.value.copy(
            isEnabled = preferences.isBridgeEnabled,
            activePlayerPackage = null,
            trackTitle = null,
            trackArtist = null,
            trackAlbum = null,
            albumArt = null,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            customActions = emptyList()
        )
        bridgeSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Keep lastKnown references briefly to ensure any in-flight island clicks still redirect
        mainHandler.postDelayed({
            if (activeTargetController == null) {
                lastKnownTargetPackage = null
                lastKnownTargetSessionActivity = null
            }
        }, 4000)
    }

    private fun runTestCapsule() {
        isTesting = true
        val title = "Live Alert Demo"
        val artist = "Test Audio"
        val art = createFallbackAlbumArt("LIVE")

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 60000L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)

        bridgeSession?.setMetadata(metaBuilder.build())
        bridgeSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 15000L, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
        bridgeSession?.isActive = true

        _statusFlow.value = _statusFlow.value.copy(
            activePlayerPackage = "Demo Player",
            trackTitle = title,
            trackArtist = artist,
            albumArt = art,
            isPlaying = true
        )

        val selfIntent = PendingIntent.getActivity(
            this, 0, Intent(this, OverlayPlayerActivity::class.java).apply {
                putExtra("target_package", "com.google.android.apps.youtube.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        bridgeSession?.setSessionActivity(selfIntent)

        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, MediaBridgeService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 2, Intent(this, MediaBridgeService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, MediaBridgeService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(art)
            .setContentIntent(selfIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(bridgeSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        mainHandler.postDelayed({
            isTesting = false
            teardownBridgePlayback()
            mediaSessionManager?.let { mgr ->
                val component = ComponentName(this, MediaBridgeService::class.java)
                syncActiveSessions(mgr.getActiveSessions(component))
            }
        }, 8000)
    }

    private fun createFallbackAlbumArt(text: String): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.parseColor("#1A73E8")
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 64f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val initial = if (text.isNotBlank()) text.take(1).uppercase() else "M"
        canvas.drawText(initial, size / 2f, (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f), textPaint)
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Alerts Media Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Relays active media playback to OxygenOS Live Alerts"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cancelPendingTeardown()
        cleanupTrackedControllers()
        super.onDestroy()
        bridgeSession?.release()
        bridgeSession = null
    }
}
