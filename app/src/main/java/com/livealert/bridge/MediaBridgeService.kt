package com.livealert.bridge

import android.app.Notification
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
import android.media.MediaMetadata
import android.media.session.MediaController
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

        data class BridgeStatus(
            val isConnected: Boolean = false,
            val activePlayerPackage: String? = null,
            val trackTitle: String? = null,
            val trackArtist: String? = null,
            val isPlaying: Boolean = false
        )

        private val _statusFlow = MutableStateFlow(BridgeStatus())
        val statusFlow = _statusFlow.asStateFlow()
    }

    private lateinit var preferences: BridgePreferences
    private var mediaSessionManager: MediaSessionManager? = null
    private var bridgeSession: MediaSessionCompat? = null
    private var activeTargetController: MediaController? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTesting = false

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active media sessions updated: ${controllers?.size ?: 0} active")
        syncActiveSessions(controllers)
    }

    private val targetCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Target playback state changed: ${state?.state}")
            activeTargetController?.let { relayMediaUpdate(it) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "Target metadata changed: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
            activeTargetController?.let { relayMediaUpdate(it) }
        }

        override fun onSessionDestroyed() {
            Log.d(TAG, "Target session destroyed")
            activeTargetController = null
            teardownBridgePlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = BridgePreferences(this)
        createNotificationChannel()
        initBridgeMediaSession()
        Log.i(TAG, "MediaBridgeService created. Package Identity: $packageName")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_CAPSULE -> runTestCapsule()
            ACTION_PLAY -> activeTargetController?.transportControls?.play()
            ACTION_PAUSE -> activeTargetController?.transportControls?.pause()
            ACTION_NEXT -> activeTargetController?.transportControls?.skipToNext()
            ACTION_PREV -> activeTargetController?.transportControls?.skipToPrevious()
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected successfully")
        _statusFlow.value = _statusFlow.value.copy(isConnected = true)

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
        Log.w(TAG, "Notification listener disconnected")
        _statusFlow.value = BridgeStatus(isConnected = false)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing active sessions listener", e)
        }
        teardownBridgePlayback()
    }

    private fun initBridgeMediaSession() {
        bridgeSession = MediaSessionCompat(this, "OxygenOSLiveAlertBridge").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "Live Alert dispatching PLAY to target")
                    activeTargetController?.transportControls?.play()
                }

                override fun onPause() {
                    Log.d(TAG, "Live Alert dispatching PAUSE to target")
                    activeTargetController?.transportControls?.pause()
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "Live Alert dispatching NEXT to target")
                    activeTargetController?.transportControls?.skipToNext()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "Live Alert dispatching PREVIOUS to target")
                    activeTargetController?.transportControls?.skipToPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "Live Alert dispatching SEEK to $pos")
                    activeTargetController?.transportControls?.seekTo(pos)
                }
            })
        }
    }

    private fun syncActiveSessions(controllers: List<MediaController>?) {
        if (isTesting) return

        if (controllers.isNullOrEmpty()) {
            activeTargetController?.unregisterCallback(targetCallback)
            activeTargetController = null
            teardownBridgePlayback()
            return
        }

        // Find the first playing controller that matches our allowed packages (skipping ourselves)
        val validControllers = controllers.filter { controller ->
            val pkg = controller.packageName
            pkg != packageName && preferences.isAppAllowed(pkg)
        }

        val playingController = validControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: validControllers.firstOrNull()

        if (playingController != null) {
            if (activeTargetController?.sessionToken != playingController.sessionToken) {
                activeTargetController?.unregisterCallback(targetCallback)
                activeTargetController = playingController
                playingController.registerCallback(targetCallback)
            }
            relayMediaUpdate(playingController)
        } else {
            activeTargetController?.unregisterCallback(targetCallback)
            activeTargetController = null
            teardownBridgePlayback()
        }
    }

    private fun relayMediaUpdate(target: MediaController) {
        val state = target.playbackState
        val metadata = target.metadata

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val isPaused = state?.state == PlaybackState.STATE_PAUSED

        if (!isPlaying && !isPaused) {
            teardownBridgePlayback()
            return
        }

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Audio Playback"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: target.packageName
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1.0f

        val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: createFallbackAlbumArt(title)

        // Clone metadata to bridge session
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)

        bridgeSession?.setMetadata(metaBuilder.build())

        // Clone playback state
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
        bridgeSession?.isActive = true

        _statusFlow.value = _statusFlow.value.copy(
            activePlayerPackage = target.packageName,
            trackTitle = title,
            trackArtist = artist,
            isPlaying = isPlaying
        )

        postBridgeNotification(title, artist, artBitmap, isPlaying)
    }

    private fun postBridgeNotification(title: String, artist: String, albumArt: Bitmap?, isPlaying: Boolean) {
        val openAppIntent = packageManager.getLaunchIntentForPackage(activeTargetController?.packageName ?: packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(albumArt)
            .setContentIntent(contentPendingIntent)
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
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun teardownBridgePlayback() {
        _statusFlow.value = _statusFlow.value.copy(
            activePlayerPackage = null,
            trackTitle = null,
            trackArtist = null,
            isPlaying = false
        )
        bridgeSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runTestCapsule() {
        isTesting = true
        Log.i(TAG, "Starting 8-second test Live Alert capsule")

        val title = "OxygenOS Live Alert Test"
        val artist = "Live Alerts Bridge Demo"
        val art = createFallbackAlbumArt("TEST")

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Demo")
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
            activePlayerPackage = "Demo (Self Test)",
            trackTitle = title,
            trackArtist = artist,
            isPlaying = true
        )

        postBridgeNotification(title, artist, art, true)

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
            color = Color.parseColor("#00668B")
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
        super.onDestroy()
        bridgeSession?.release()
        bridgeSession = null
        Log.i(TAG, "MediaBridgeService destroyed")
    }
}
