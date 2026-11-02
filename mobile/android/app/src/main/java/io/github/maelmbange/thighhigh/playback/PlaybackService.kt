package io.github.maelmbange.thighhigh.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.maelmbange.thighhigh.MainActivity
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.scanLibrary
import io.github.maelmbange.thighhigh.library.toMediaItem
import java.util.concurrent.Executors

/**
 * Owns the player. Running as a media session service keeps the music going
 * when the app is in the background, with the system's media notification
 * (cover, loop and shuffle buttons), lock-screen controls and the buttons of
 * wired or Bluetooth headsets. The queue is saved, so a headset's play button
 * starts the music again even after Android closed the app.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var queueStore: QueueStore

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, TappedRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        queueStore = QueueStore(this)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AudioTap.playing = isPlaying
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    session?.setMediaButtonPreferences(buttons(player))
                }
                // Saved on every change of track, queue or play/pause (which
                // also records where the track was paused).
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )
                ) {
                    queueStore.save(player)
                }
            }
        })
        // Tapping the notification or the lock-screen controls opens the player.
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(openPlayer)
            .setBitmapLoader(CoverBitmapLoader(this))
            .setMediaButtonPreferences(buttons(player))
            .build()
    }

    /** The loop and shuffle buttons beside play/next/previous, showing the current mode. */
    private fun buttons(player: Player): List<CommandButton> {
        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.string.repeat_all
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.string.repeat_one
            else -> CommandButton.ICON_REPEAT_OFF to R.string.repeat_off
        }
        val shuffle = if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON to R.string.shuffle_on
        else CommandButton.ICON_SHUFFLE_OFF to R.string.shuffle_off
        return listOf(
            CommandButton.Builder(shuffle.first)
                .setDisplayName(getString(shuffle.second))
                .setSessionCommand(SHUFFLE)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(repeat.first)
                .setDisplayName(getString(repeat.second))
                .setSessionCommand(REPEAT)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let(queueStore::save)
        // Swiping the app away stops the service only if nothing is playing.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioTap.playing = false
        session?.run {
            queueStore.save(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Copies what is played into [AudioTap] for the oscilloscope. The audio
     * track buffer is fixed so the tap knows how far ahead of the speaker it is.
     */
    @OptIn(UnstableApi::class)
    private class TappedRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val bufferUs = AudioTap.AUDIO_BUFFER_MS * 1000
            return DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioTap)))
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(bufferUs)
                        .setMaxPcmBufferDurationUs(bufferUs)
                        .build()
                )
                .build()
        }
    }

    private inner class Callback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(REPEAT)
                .add(SHUFFLE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Controllers send items by media id (a MediaStore id); the URI may be
         * stripped on the way, so it is rebuilt here.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val id = item.mediaId.toLongOrNull()
                if (item.localConfiguration != null || id == null) item
                else item.buildUpon().setUri(Track.uriOf(id)).build()
            }.toMutableList()
        )

        /**
         * Play pressed on a headset (or the system's resume card) while nothing
         * is loaded: the saved queue comes back, minus tracks deleted since.
         */
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = queueStore.load()
            saved?.let {
                mediaSession.player.repeatMode = it.repeatMode
                mediaSession.player.shuffleModeEnabled = it.shuffle
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            io.execute {
                try {
                    val tracks = scanLibrary(this@PlaybackService)
                    val byId = tracks.associateBy { it.id }
                    // Nothing saved yet: the whole library, newest first.
                    val kept = saved?.ids?.mapNotNull(byId::get) ?: tracks
                    val index = saved?.let { kept.indexOfFirst { t -> t.id == it.ids.getOrNull(it.index) } } ?: -1
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            kept.map { it.toMediaItem() },
                            index.coerceAtLeast(0),
                            if (index >= 0) saved!!.positionMs else 0L,
                        )
                    )
                } catch (e: Throwable) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    private companion object {
        val REPEAT = SessionCommand("io.github.maelmbange.thighhigh.REPEAT", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("io.github.maelmbange.thighhigh.SHUFFLE", Bundle.EMPTY)
    }
}
