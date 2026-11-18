package com.example.recordwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Plays the saved tracks in list order, looping at the end (REPEAT_MODE_ALL).
 *
 * Started by the widget through [PendingIntent.getForegroundService] with [ACTION_TOGGLE] /
 * [ACTION_NEXT], so those two actions always reach `startForeground` synchronously (placeholder
 * notification, later replaced by Media3's media notification which uses the same id/channel).
 * [ACTION_RELOAD] is sent by the app (plain `startService`, see [reload]) after the track list
 * changes. Anything else (media buttons, Media3's own start-self intent) goes to `super`.
 */
@OptIn(UnstableApi::class) // setMediaNotificationProvider, DefaultMediaNotificationProvider, isPlaybackOngoing
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    /** Errors in a row without reaching STATE_READY; bounds skip-on-error to one lap. */
    private var consecutiveErrors = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveResume()
            updateWidget()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveResume()
            updateWidget()
        }

        // A rename of the current track (RELOAD -> replaceMediaItem) changes only the metadata.
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateWidget()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            updateWidget()
        }

        // The record keeps turning while a web track buffers: it follows "wants to play".
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateWidget()

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error", error)
            consecutiveErrors++
            if (consecutiveErrors < player.mediaItemCount) {
                // Unreadable file: skip it. playWhenReady is still true, so playback resumes.
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                // Every track failed: stop trying. Player stays IDLE until the next TOGGLE.
                consecutiveErrors = 0
                player.pause()
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            updateWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Same id/channel as the placeholder notification below, so Media3 replaces it in place.
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.channel_name)
            .build()
        provider.setSmallIcon(R.drawable.ic_note)
        setMediaNotificationProvider(provider)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(listener)

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    RC_SESSION_ACTIVITY,
                    RecordWidget.mainActivityIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        // The widget starts this service without binding, so no controller would ever call
        // onGetSession(); without addSession() Media3 would never post its media notification
        // (nor take over / drop the foreground state).
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Sticky restart after the process was killed (Media3's start-self intent returns
            // START_STICKY): the queue is gone, so fix a stale "playing" widget and go away.
            updateWidget()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = intent.action
        // Media3's own intents: start-self (no action, Media3 calls startForeground itself right
        // after), media buttons and notification custom actions.
        if (action == null ||
            action == Intent.ACTION_MEDIA_BUTTON ||
            action.startsWith("androidx.media3.")
        ) {
            return super.onStartCommand(intent, flags, startId)
        }
        if (action == ACTION_RELOAD) {
            // Only sent via plain startService (companion reload()), never foreground-required:
            // no placeholder (no flicker) and no stop/detach, which could resurrect a paused
            // notification the user swiped away.
            reload(startId)
            return START_NOT_STICKY
        }
        // TOGGLE/NEXT come via startForegroundService from the widget; unknown actions might too.
        // Satisfy the 5 s startForeground rule first, then leave the foreground again unless we
        // are (about to be) playing.
        val placeholder = enterForegroundIfNeeded()
        when (action) {
            ACTION_TOGGLE -> toggle(startId)
            ACTION_NEXT -> next(startId)
            else -> {
                Log.w(TAG, "ignoring unknown action $action")
                if (player.mediaItemCount == 0) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        leaveForegroundIfPaused(placeholder)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        if (player.mediaItemCount > 0) saveResume()
        player.removeListener(listener)
        player.release()
        session.release()
        // null title -> the widget shows the saved resume track's title.
        RecordWidget.update(this, false, null)
        super.onDestroy()
    }

    // ---- actions ---------------------------------------------------------------------------

    private fun toggle(startId: Int) {
        if (!applyPendingReload() || !ensureQueue()) {
            finishEmpty(startId)
            return
        }
        if (isActive()) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    private fun next(startId: Int) {
        if (!applyPendingReload() || !ensureQueue()) {
            finishEmpty(startId)
            return
        }
        player.seekToNextMediaItem() // wraps to the first item because of REPEAT_MODE_ALL
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /**
     * Rebuilds the queue from the store (Review Focus 3). If the current track still exists it
     * keeps playing without interruption: only the items around it are removed/re-added, and the
     * item itself is swapped via replaceMediaItem, which ExoPlayer 1.4 applies in place when the
     * URI is unchanged (ProgressiveMediaSource.canUpdateMediaItem), so a rename updates the title.
     * If it was deleted, playback continues at the same index (= the following track; wraps to
     * the first if it was the last), position 0.
     * play/pause state (playWhenReady) is untouched in both cases.
     */
    private fun reload(startId: Int) {
        pendingReload = false
        if (player.mediaItemCount == 0) {
            // Nothing loaded: only the widget (empty hint / first title) may need a refresh.
            updateWidget()
            stopSelf(startId)
            return
        }
        if (!rebuildQueue()) finishEmpty(startId)
    }

    /**
     * A RELOAD that could not be delivered (see companion [reload]) is applied before the next
     * TOGGLE/NEXT. False if the list became empty (caller then finishes like an empty store).
     */
    private fun applyPendingReload(): Boolean {
        if (!pendingReload) return true
        pendingReload = false
        return player.mediaItemCount == 0 || rebuildQueue()
    }

    /** The RELOAD queue rebuild for a non-empty queue. False (queue cleared) if nothing is playable. */
    private fun rebuildQueue(): Boolean {
        val items = loadQueue()
        if (items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return false
        }
        val oldIndex = player.currentMediaItemIndex
        val newIndex = items.indexOfFirst { it.mediaId == player.currentMediaItem?.mediaId }
        if (newIndex >= 0) {
            player.replaceMediaItem(oldIndex, items[newIndex])
            player.removeMediaItems(oldIndex + 1, player.mediaItemCount)
            player.removeMediaItems(0, oldIndex) // current item is now index 0
            player.addMediaItems(0, items.subList(0, newIndex))
            player.addMediaItems(items.subList(newIndex + 1, items.size))
        } else {
            // Same index = the following track; past the end (last one deleted) wraps to 0.
            player.setMediaItems(items, if (oldIndex >= items.size) 0 else oldIndex, 0L)
            if (player.playWhenReady && player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        updateWidget()
        return true
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Loads the queue if it is empty, resuming the saved track/position (so a TOGGLE after the
     * paused service was reaped continues where it stopped). False if there is nothing playable.
     */
    private fun ensureQueue(): Boolean {
        if (player.mediaItemCount > 0) return true
        val items = loadQueue()
        if (items.isEmpty()) return false
        val savedId = PlaybackState.resumeId(this)
        val index = items.indexOfFirst { it.mediaId == savedId }
        if (index >= 0) {
            player.setMediaItems(items, index, PlaybackState.resumePositionMs(this))
        } else {
            player.setMediaItems(items, 0, 0L)
        }
        return true
    }

    private fun saveResume() {
        val item = player.currentMediaItem ?: return
        PlaybackState.saveResume(this, item.mediaId, player.currentPosition)
    }

    private fun loadQueue(): List<MediaItem> =
        PlaybackState.playableTracks(this).map { t ->
            MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(t.url?.let(Uri::parse) ?: Uri.fromFile(TrackStore(filesDir).fileFor(t)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).build())
                .build()
        }

    /** Playing or about to play (buffering). Used for the toggle decision. */
    private fun isActive(): Boolean =
        player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    private fun finishEmpty(startId: Int) {
        RecordWidget.update(this, false, null) // widget switches to "tap to add" -> MainActivity
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun currentTitle(): String? = player.currentMediaItem?.mediaMetadata?.title?.toString()

    private fun updateWidget() =
        RecordWidget.update(this, isActive(), currentTitle(), player.currentMediaItem?.mediaId)

    /**
     * A getForegroundService() start must reach startForeground() within 5 s. If Media3 already
     * runs the service in the foreground with its media notification (playing), the system does
     * not require another call, and re-posting the placeholder under the same id would overwrite
     * that notification (e.g. NEXT on a 1-track list changes nothing Media3 listens to).
     */
    private fun enterForegroundIfNeeded(): Boolean {
        if (player.playWhenReady && isPlaybackOngoing()) return false
        createChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_preparing))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(session.sessionActivity)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        } catch (e: IllegalStateException) {
            // API 31+ ForegroundServiceStartNotAllowedException: only possible when the action
            // came via plain startService() from the background (not from our widget).
            Log.w(TAG, "startForeground not allowed", e)
            return false
        }
        return true
    }

    /**
     * After the action, a paused service must not stay foreground. When paused with a loaded
     * queue Media3 shows a (paused) notification under the same id via notify() and calls
     * stopForeground(DETACH) itself; we detach right away, and if we posted the placeholder we
     * ask Media3 to re-post now ([onUpdateNotification]) — an action such as RELOAD of an
     * unchanged list may fire no event Media3 listens to, which would leave "準備中…" behind.
     * Without a usable queue Media3 shows nothing, so remove the notification.
     */
    private fun leaveForegroundIfPaused(placeholderPosted: Boolean) {
        if (player.playWhenReady) return // Media3 takes over the foreground notification
        val media3WillNotify = player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE
        if (media3WillNotify) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            if (placeholderPosted) onUpdateNotification(session, /* startInForegroundRequired= */ false)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        const val ACTION_TOGGLE = "com.example.recordwidget.TOGGLE"
        const val ACTION_NEXT = "com.example.recordwidget.NEXT"
        const val ACTION_RELOAD = "com.example.recordwidget.RELOAD"

        /** = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID / DEFAULT_CHANNEL_ID. */
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "default_channel_id"

        private const val RC_SESSION_ACTIVITY = 4

        /** True between onCreate and onDestroy (same process). */
        @Volatile
        var isRunning = false
            private set

        /** Set when a RELOAD could not be delivered; applied on the next TOGGLE/NEXT. */
        @Volatile
        private var pendingReload = false

        /**
         * For MainActivity after it changes the track list. If the service is not running there
         * is no queue to fix, so only the widget is redrawn. Otherwise plain startService (the
         * activity is in the foreground, or the service is, which counts as foreground).
         */
        fun reload(ctx: Context) {
            if (!isRunning) {
                RecordWidget.update(ctx, false, null)
                return
            }
            try {
                ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_RELOAD))
            } catch (e: IllegalStateException) {
                // Background start refused: the app is in the background and the service is
                // alive but not foreground (paused), or it just stopped. Rebuild the queue on the
                // next TOGGLE/NEXT instead (if it has stopped, the next start loads fresh anyway).
                pendingReload = true
                RecordWidget.update(ctx, false, null)
            }
        }

        private fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
