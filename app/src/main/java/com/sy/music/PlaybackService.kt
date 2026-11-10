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
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.json.JSONArray
import java.io.IOException
import kotlin.math.pow

private const val MAX_CONSECUTIVE_ERRORS = 3
private const val RADIO_REFILL_THRESHOLD = 3
private const val ENGINE_FAILURES_BEFORE_RESERVE = 2
private const val HTTP_FORBIDDEN = 403
private const val MIN_LISTEN_MS = 30_000L
private const val BUFFER_MS = 120_000
private const val START_BUFFER_MS = 1_500
private const val RESUME_BUFFER_MS = 3_000
private const val STALL_TO_LOW_MS = 6_000L
private const val STALL_TO_DOWNLOADS_MS = 12_000L
private const val OFFLINE_GRACE_MS = 1_500L
private const val PRELOADED_SONGS = 3
private const val PRELOAD_DELAY_MS = 1_000L
private const val SAFE_BUFFER_MS = 20_000L
private const val BUFFER_CHECK_MS = 1_000L
private const val SAVE_POSITION_MS = 10_000L
private const val MAX_SAVED_SONGS = 100
private const val LAST_SESSION = "ultima_sesion"
private const val SAVED_QUEUE = "fila"
private const val SAVED_POSITION = "segundo"
private const val SAVED_SHUFFLE = "aleatorio"
private val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
)

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
    private var soundedId: String? = null
    private var stall: Job? = null
    private var preload: Job? = null
    private val playedOffline = mutableSetOf<String>()

    /** Escuchar más de 30 s o llegar al final cuenta como gusto; cambiar antes, como salto. */
    private val tasteListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            if (playing) soundedId = session.player.currentMediaItem?.mediaId
        }

        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int,
        ) {
            val item = old.mediaItem ?: return
            if (item.mediaId == new.mediaItem?.mediaId) return
            val played = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || old.positionMs >= MIN_LISTEN_MS
            // Una canción que no alcanzó a sonar por falta de internet no dice nada de tus gustos.
            if (played || item.mediaId == soundedId) Library.record(item.toSong(), played)
        }
    }

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Storage.playbackSource))
            // Dos minutos guardados aguantan las caídas; parte apenas tiene un poco de audio.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MS, BUFFER_MS, START_BUFFER_MS, RESUME_BUFFER_MS)
                    .build(),
            )
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
                if (item != null && Connection.unusable && !isAvailable(item) && playDownloadInstead()) return
                refillRadio()
                if (item != null) applyExtras(item)
                preloadUpcoming()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) preloadUpcoming()
            }

            override fun onPlaybackStateChanged(state: Int) {
                watchStall()
                if (state == Player.STATE_BUFFERING) preloadUpcoming() // primero la canción actual
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = watchStall()

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
                    items.map { it.buildUpon().setUri(Storage.uriFor(it.mediaId)).build() }.toMutableList(),
                )
            })
            .build()

        restoreLastSession()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = saveQueue()

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onIsPlayingChanged(playing: Boolean) = savePosition()

            override fun onPositionDiscontinuity(
                old: Player.PositionInfo,
                new: Player.PositionInfo,
                reason: Int,
            ) = savePosition()
        })
        scope.launch {
            while (true) {
                delay(SAVE_POSITION_MS)
                if (session.player.isPlaying) savePosition()
            }
        }
        scope.launch {
            Connection.online.collect { online ->
                if (online && exo.playerError != null) exo.prepare()
                preloadUpcoming()
            }
        }
    }

    private val saved by lazy { getSharedPreferences(LAST_SESSION, MODE_PRIVATE) }

    /** Desde la canción actual en adelante: así lo guardado es poco y no pesa. */
    private fun saveQueue() {
        val player = session.player
        if (player.mediaItemCount == 0) return // al pasar al motor de reserva la fila se vacía un instante
        val songs = JSONArray()
        val from = player.currentMediaItemIndex
        for (i in from until minOf(player.mediaItemCount, from + MAX_SAVED_SONGS)) {
            songs.put(player.getMediaItemAt(i).toSong().toJson())
        }
        saved.edit().putString(SAVED_QUEUE, songs.toString()).putBoolean(SAVED_SHUFFLE, player.shuffleModeEnabled).apply()
        savePosition()
    }

    private fun savePosition() {
        saved.edit().putLong(SAVED_POSITION, session.player.currentPosition).apply()
    }

    /** Al abrir de nuevo, la última canción queda en pausa en el segundo donde quedó. */
    private fun restoreLastSession() {
        val songs = try {
            val list = JSONArray(saved.getString(SAVED_QUEUE, null) ?: return)
            (0 until list.length()).map { songFromJson(list.getJSONObject(it)) }
        } catch (e: org.json.JSONException) {
            Log.w("SY", "No se pudo leer la última canción", e)
            return
        }
        if (songs.isEmpty()) return
        exo.setMediaItems(
            songs.map { it.toMediaItem().buildUpon().setUri(Storage.uriFor(it.url)).build() },
            0,
            saved.getLong(SAVED_POSITION, 0),
        )
        exo.shuffleModeEnabled = saved.getBoolean(SAVED_SHUFFLE, false)
        exo.prepare()
    }

    private fun isAvailable(item: MediaItem) = Storage.isAvailable(item.localConfiguration?.uri.toString())

    /** Si la canción se traba: primero calidad baja en el mismo segundo; si no alcanza, una descarga. */
    private fun watchStall() {
        if (exo.playbackState != Player.STATE_BUFFERING || !exo.playWhenReady) {
            stall?.cancel()
            return
        }
        if (stall?.isActive == true) return
        stall = scope.launch {
            if (exo.currentMediaItem?.let(::isAvailable) != false) return@launch
            if (Connection.online.value) {
                delay(STALL_TO_LOW_MS)
                Connection.reportSlow()
                useLowQualityNow()
                delay(STALL_TO_DOWNLOADS_MS)
            } else {
                delay(OFFLINE_GRACE_MS)
            }
            Connection.reportUnusable()
            playDownloadInstead()
        }
    }

    private fun useLowQualityNow() {
        val item = exo.currentMediaItem ?: return
        val uri = Storage.uriFor(item.mediaId)
        if (item.localConfiguration?.uri.toString() == uri) return
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition
        exo.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        exo.seekTo(index, position)
    }

    /** Pone una descarga antes de la canción que no carga: tu fila sigue igual cuando vuelva internet. */
    private fun playDownloadInstead(): Boolean {
        val current = exo.currentMediaItem?.mediaId
        val ready = Storage.readySongs.filter { it.url != current }
        if (ready.isEmpty()) return false
        val song = ready.filter { it.url !in playedOffline }
            .ifEmpty {
                playedOffline.clear()
                ready
            }
            .random()
        playedOffline += song.url
        val index = exo.currentMediaItemIndex
        exo.addMediaItem(index, song.toMediaItem())
        exo.seekTo(index, 0)
        return true
    }

    private fun upcomingIndexes(): List<Int> {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return emptyList()
        return generateSequence(exo.currentMediaItemIndex) {
            timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
                .takeIf { next -> next != C.INDEX_UNSET }
        }.drop(1).take(PRELOADED_SONGS).toList()
    }

    /** Guarda las próximas canciones de a una, solo si la actual ya tiene suficiente audio cargado. */
    private fun preloadUpcoming() {
        preload?.cancel()
        Storage.cancelPreload()
        if (session.player !== exo) return
        preload = scope.launch {
            delay(PRELOAD_DELAY_MS) // varios cambios seguidos cuentan como uno
            upcomingIndexes().forEach { index ->
                val item = exo.getMediaItemAt(index)
                val uri = Storage.uriFor(item.mediaId)
                if (item.localConfiguration?.uri.toString() != uri) {
                    exo.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
                }
            }
            upcomingIndexes().map { exo.getMediaItemAt(it).localConfiguration?.uri.toString() }.forEach { uri ->
                while (exo.totalBufferedDuration < SAFE_BUFFER_MS && exo.bufferedPercentage < 100) {
                    if (exo.playbackState == Player.STATE_IDLE) return@launch
                    delay(BUFFER_CHECK_MS)
                }
                if (!Connection.online.value) return@launch
                withContext(Dispatchers.IO) {
                    try {
                        Storage.preload(uri)
                    } catch (e: IOException) {
                        Log.i("SY", "No se pudo precargar", e)
                    }
                }
            }
        }
    }

    /** Volumen parejo y saltar partes sin música; si fallan, la canción suena normal. */
    private fun applyExtras(item: MediaItem) {
        scope.launch {
            try {
                // Con internet malo no se gasta en bajar la página completa del video.
                val loudness = if (Connection.slow) null else withContext(Dispatchers.IO) { YouTubeWeb.loudnessDb(item.mediaId) }
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
        } else if (error.errorCode in NETWORK_ERRORS && playDownloadInstead()) {
            Connection.reportUnusable()
            exo.prepare()
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
        // En aleatorio la posición no dice cuánto falta: se rellena solo al quedarse sin siguiente.
        if (player.shuffleModeEnabled) {
            if (player.hasNextMediaItem()) return
        } else if (count - player.currentMediaItemIndex > RADIO_REFILL_THRESHOLD) {
            return
        }

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
        if (session.player.mediaItemCount > 0) savePosition()
        scope.cancel()
        Storage.cancelPreload()
        session.release()
        exo.release()
        reservePlayer?.release()
        super.onDestroy()
    }
}
