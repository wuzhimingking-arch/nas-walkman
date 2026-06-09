package tech.peakedge.naswalkman.playback

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import tech.peakedge.naswalkman.NasMusicApplication

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var isPlayerReleased = false
    private var retryMediaId: String? = null
    private var retryCountForCurrentMedia = 0

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        isPlayerReleased = false
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        releasePlayerSafely()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        val okHttpClient = (application as NasMusicApplication).container.okHttpClient
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .forceDisableMediaCodecAsynchronousQueueing()

        return ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                addListener(playerListener)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId
            if (mediaId != retryMediaId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                retryMediaId = mediaId
                retryCountForCurrentMedia = 0
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            Log.d(TAG, "audio format changed: ${audioFormatSummary(tracks)}")
        }

        override fun onPlayerError(error: PlaybackException) {
            logPlayerError(error)
            val currentUri = player?.currentMediaItem?.localConfiguration?.uri
            if (currentUri?.isRetryableNetworkUri() == true && isRecoverablePlaybackError(error) && retryCountForCurrentMedia == 0) {
                retryCountForCurrentMedia += 1
                rebuildPlayerAndRetryCurrentItem()
            } else {
                notifyPlaybackRecoveryFailed()
            }
        }
    }

    private fun releasePlayerSafely() {
        val currentPlayer = player ?: return
        if (isPlayerReleased) return

        isPlayerReleased = true
        player = null
        releasePlayerInstanceSafely(currentPlayer)
    }

    private fun releasePlayerInstanceSafely(currentPlayer: ExoPlayer) {
        currentPlayer.removeListener(playerListener)

        runCatching {
            currentPlayer.stop()
        }.onFailure {
            Log.w(TAG, "player stop failed", it)
        }

        runCatching {
            currentPlayer.clearMediaItems()
        }.onFailure {
            Log.w(TAG, "player clearMediaItems failed", it)
        }

        runCatching {
            currentPlayer.release()
        }.onFailure {
            Log.w(TAG, "player release failed", it)
        }
    }

    private fun rebuildPlayerAndRetryCurrentItem() {
        val oldPlayer = player ?: run {
            notifyPlaybackRecoveryFailed()
            return
        }
        val session = mediaSession ?: run {
            notifyPlaybackRecoveryFailed()
            return
        }
        val mediaItems = (0 until oldPlayer.mediaItemCount).map { index ->
            oldPlayer.getMediaItemAt(index)
        }
        if (mediaItems.isEmpty()) {
            notifyPlaybackRecoveryFailed()
            return
        }

        val startIndex = oldPlayer.currentMediaItemIndex.coerceIn(mediaItems.indices)
        val startPositionMs = oldPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlay = oldPlayer.playWhenReady
        val currentUrl = oldPlayer.currentMediaItem?.localConfiguration?.uri?.toString()

        val newPlayer = buildPlayer()
        runCatching {
            newPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
            player = newPlayer
            isPlayerReleased = false
            session.setPlayer(newPlayer)
        }.onFailure {
            Log.e(TAG, "failed to install rebuilt player for url=$currentUrl", it)
            releasePlayerInstanceSafely(newPlayer)
            notifyPlaybackRecoveryFailed()
            return
        }

        releasePlayerInstanceSafely(oldPlayer)

        runCatching {
            newPlayer.prepare()
            if (shouldPlay) newPlayer.play()
            Log.w(TAG, "rebuilt player and retried current item once: url=$currentUrl")
        }.onFailure {
            Log.e(TAG, "retry after player rebuild failed for url=$currentUrl", it)
            notifyPlaybackRecoveryFailed()
        }
    }

    private fun notifyPlaybackRecoveryFailed() {
        mediaSession?.broadcastCustomCommand(
            SessionCommand(ACTION_PLAYBACK_RECOVERY_FAILED, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun logPlayerError(error: PlaybackException) {
        val currentPlayer = player
        val currentUrl = currentPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val audioFormat = currentPlayer?.currentTracks?.let(::audioFormatSummary).orEmpty()
        Log.e(
            TAG,
            "player error " +
                "code=${error.errorCodeName}, " +
                "cause=${error.cause}, " +
                "url=$currentUrl, " +
                "audioFormat=$audioFormat, " +
                "device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                "sdk=${Build.VERSION.SDK_INT}, " +
                "media3=${MediaLibraryInfo.VERSION}",
            error,
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun audioFormatSummary(tracks: Tracks): String {
        val audioFormats = mutableListOf<String>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                audioFormats += "selected=${group.isSelected()}, " +
                    "sampleMimeType=${format.sampleMimeType}, " +
                    "codecs=${format.codecs}, " +
                    "bitrate=${format.bitrate}, " +
                    "containerMimeType=${format.containerMimeType}"
            }
        }
        return audioFormats.joinToString(separator = " | ").ifBlank { "none" }
    }

    private fun isRecoverablePlaybackError(error: PlaybackException): Boolean =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            -> true
            else -> false
        }

    private fun android.net.Uri.isRetryableNetworkUri(): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    companion object {
        const val ACTION_PLAYBACK_RECOVERY_FAILED =
            "tech.peakedge.naswalkman.playback.PLAYBACK_RECOVERY_FAILED"
        private const val TAG = "MusicPlaybackService"
    }
}
