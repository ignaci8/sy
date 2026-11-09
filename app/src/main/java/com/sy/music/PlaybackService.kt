package com.sy.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import kotlin.math.pow

private const val MAX_CONSECUTIVE_ERRORS = 3
private const val RADIO_REFILL_THRESHOLD = 3
private const val ENGINE_FAILURES_BEFORE_RESERVE = 2
private const val HTTP_FORBIDDEN = 403
private const val MIN_LISTEN_MS = 30_000L

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(url)
    .setUri(url)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(thumbnail?.toUri())
            .setDurationMs(seconds * 1000)
            .build(),
    )
    .build()

fun MediaItem.toSong() = Song(
    url = mediaId,
    title = mediaMetadata.title.toString(),
    artist = mediaMetadata.artist.toString(),
    seconds = (mediaMetadata.durationMs ?: 0) / 1000,
    thumbnail = mediaMetadata.artworkUri?.toString(),
)

/** Vive aparte de la pantalla: por eso la música sigue con el celular bloqueado. */
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private lateinit var session: MediaSession
    private lateinit var exo: ExoPlayer
    private var reservePlayer: ReservePlayer? = null
    private var refilling = false
    private var errors = 0
    private var engineFailures = 0

    /** Escuchar más de 30 s o llegar al final cuenta como gusto; cambiar antes, como salto. */
    private val tasteListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int,
        ) {
            val item = old.mediaItem ?: return
            if (item.mediaId == new.mediaItem?.mediaId) return
            val played = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || old.positionMs >= MIN_LISTEN_MS
            Library.record(item.toSong(), played)
        }
    }

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Storage.playbackSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addListener(tasteListener)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                refillRadio()
                if (item != null) applyExtras(item)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    errors = 0
                    engineFailures = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Android quita la URI al pasar la canción desde la pantalla.
                override fun onAddMediaItems(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    items: MutableList<MediaItem>,
                ) = Futures.immediateFuture(
                    items.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList(),
                )
            })
            .build()
    }

    /** Volumen parejo y saltar partes sin música; si fallan, la canción suena normal. */
    private fun applyExtras(item: MediaItem) {
        scope.launch {
            try {
                val loudness = withContext(Dispatchers.IO) { YouTubeWeb.loudnessDb(item.mediaId) }
                // Como YouTube: solo se baja lo que suena más fuerte que la referencia.
                if (exo.currentMediaItem == item) {
                    exo.volume = if (loudness != null && loudness > 0) 10.0.pow(-loudness / 20).toFloat() else 1f
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (exo.currentMediaItem == item) exo.volume = 1f
                Log.i("SY", "Sin dato de volumen", e)
            }
        }
        scope.launch {
            try {
                val segments = withContext(Dispatchers.IO) { Extras.offTopicSegments(item.mediaId) }
                if (exo.currentMediaItem != item) return@launch
                val index = exo.currentMediaItemIndex
                segments.forEach { (start, end) ->
                    exo.createMessage { _, _ ->
                        if (exo.currentMediaItem == item && exo.currentPosition < end) exo.seekTo(end)
                    }
                        .setLooper(Looper.getMainLooper())
                        .setPosition(index, start)
                        .setDeleteAfterDelivery(true)
                        .send()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("SY", "Sin datos de partes sin música", e)
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        Log.w("SY", "Fallo de reproducción", error)
        if (isEngineFailure(error)) {
            exo.currentMediaItem?.let { YouTube.forget(it.mediaId) }
            if (++engineFailures >= ENGINE_FAILURES_BEFORE_RESERVE) {
                engineFailures = 0
                switchTo(reserve())
            } else {
                exo.prepare()
            }
        } else if (++errors < MAX_CONSECUTIVE_ERRORS && exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.prepare()
        }
    }

    /** Solo cuenta si falló la pieza de YouTube, no si falta internet o una canción no existe. */
    private fun isEngineFailure(error: PlaybackException) =
        generateSequence<Throwable>(error) { it.cause }.any {
            (it is HttpDataSource.InvalidResponseCodeException && it.responseCode == HTTP_FORBIDDEN) ||
                (it is ExtractionException && it !is ContentNotAvailableException)
        }

    /** La pieza pesada (la ventana web) solo se crea si de verdad hace falta. */
    private fun reserve(): ReservePlayer = reservePlayer ?: ReservePlayer(this).also {
        reservePlayer = it
        it.addListener(tasteListener)
        it.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                refillRadio()
                if (item != null) tryReturnToMain(item)
            }
        })
    }

    private fun switchTo(target: Player) {
        val from = session.player
        if (from === target) return
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex
        val position = from.currentPosition
        val play = from.playWhenReady
        from.stop()
        from.clearMediaItems()
        session.player = target
        Engine.reserveActive.value = target !== exo
        if (items.isNotEmpty()) {
            target.setMediaItems(items, index, position)
            target.prepare()
            target.playWhenReady = play
        }
    }

    private fun tryReturnToMain(item: MediaItem) {
        if (Storage.isReady(item.mediaId)) { // la ventana web no puede leer las descargas
            switchTo(exo)
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { YouTube.streamUrl(item.mediaId) }
                if (session.player.currentMediaItem == item) switchTo(exo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("SY", "El motor principal sigue sin funcionar", e)
            }
        }
    }

    private fun refillRadio() {
        val player = session.player
        val count = player.mediaItemCount
        if (refilling || count == 0) return
        if (count - player.currentMediaItemIndex > RADIO_REFILL_THRESHOLD) return

        val seed = player.getMediaItemAt(count - 1).mediaId
        refilling = true
        scope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { Catalog.radio(seed) }
                val current = session.player
                val queued = queuedIds()
                val disliked = Library.data.value.dislikedUrls()
                refilling = false
                if (seed in queued) {
                    current.addMediaItems(
                        songs.filter { it.url !in queued && it.url !in disliked }.map { it.toMediaItem() },
                    )
                } else {
                    refillRadio() // el usuario eligió otra canción mientras cargaba
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refilling = false
                Log.w("SY", "No se pudo cargar la radio; sigo con las descargas", e)
                val queued = queuedIds()
                session.player.addMediaItems(
                    Storage.readySongs.filter { it.url !in queued }.shuffled().map { it.toMediaItem() },
                )
            }
        }
    }

    private fun queuedIds(): Set<String> {
        val player = session.player
        return (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        session.release()
        exo.release()
        reservePlayer?.release()
        super.onDestroy()
    }
}
