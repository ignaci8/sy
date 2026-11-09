package com.sy.music

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STARTER_QUERY = "éxitos del momento"
private const val POSITION_REFRESH_MS = 500L
private val LINK = Regex("""https?://\S+""")

class PlayerModel(app: Application) : AndroidViewModel(app) {
    var results by mutableStateOf(emptyList<Song>())
        private set
    var forYou by mutableStateOf(emptyList<Song>())
        private set
    var homeLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    var current by mutableStateOf<MediaItem?>(null)
        private set
    var queue by mutableStateOf(emptyList<MediaItem>())
        private set
    var index by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    var lyrics by mutableStateOf<List<LyricLine>?>(null)
        private set
    var lyricsMessage by mutableStateOf<String?>(null)
        private set

    var update by mutableStateOf<AppUpdate?>(null)
        private set
    var updating by mutableStateOf(false)
        private set

    private var lyricsFor: String? = null
    private var lastQuery = ""
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                controller = controllerFuture.get().apply {
                    addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = sync(player)

                        override fun onPlayerError(e: PlaybackException) {
                            error = "No se pudo reproducir (${e.errorCodeName})"
                        }
                    })
                    sync(this)
                }
            },
            ContextCompat.getMainExecutor(app),
        )
        loadHome()
        viewModelScope.launch {
            update = try {
                withContext(Dispatchers.IO) { Updater.check() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // sin internet o sin GitHub: se revisa al abrir de nuevo
            }
        }
    }

    fun installUpdate() {
        val available = update ?: return
        updating = true
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { Updater.download(getApplication(), available) }
                Updater.install(getApplication(), apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("No se pudo descargar la actualización")
            } finally {
                updating = false
            }
        }
    }

    /** Solo corre mientras la pantalla de la app está visible. */
    suspend fun trackPosition() {
        while (true) {
            controller?.let {
                positionMs = it.currentPosition
                durationMs = it.duration.coerceAtLeast(0)
            }
            delay(POSITION_REFRESH_MS)
        }
    }

    private fun sync(player: Player) {
        current = player.currentMediaItem
        queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        index = player.currentMediaItemIndex
        isPlaying = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
    }

    fun loadHome() {
        homeLoading = true
        viewModelScope.launch {
            val library = Library.data.value
            val seeds = library.forYouSeeds()
            forYou = try {
                withContext(Dispatchers.IO) {
                    if (seeds.isEmpty()) {
                        Catalog.search(STARTER_QUERY)
                    } else {
                        coroutineScope {
                            library.mixForYou(seeds.map { async { Catalog.radio(it.url) } }.awaitAll())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Storage.readySongs // sin conexión: lo que ya tienes guardado
            }
            homeLoading = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        lastQuery = query
        searching = true
        error = null
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Catalog.search(query) }
                if (query == lastQuery) results = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Error de YouTube o sin conexión: ${e.message}"
            } finally {
                if (query == lastQuery) searching = false
            }
        }
    }

    fun play(song: Song) {
        error = null
        controller?.run {
            setMediaItem(song.toMediaItem())
            prepare()
            play()
        }
    }

    fun playAll(songs: List<Song>, startIndex: Int) {
        error = null
        controller?.run {
            setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
            prepare()
            play()
        }
    }

    /** Un enlace de canción se reproduce; uno de playlist se guarda en la biblioteca. */
    fun handleLink(text: String) {
        val link = LINK.find(text)?.value
        if (link == null) {
            toast("No encontré un enlace de YouTube")
            return
        }
        viewModelScope.launch {
            try {
                if (YouTube.isPlaylistLink(link)) {
                    val (name, songs) = withContext(Dispatchers.IO) { YouTube.playlistFromLink(link) }
                    Library.createPlaylist(name, songs)
                    toast("Playlist importada: $name (${songs.size})")
                } else {
                    play(withContext(Dispatchers.IO) { YouTube.songFromLink(link) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("No se pudo abrir el enlace")
            }
        }
    }

    fun loadLyrics(item: MediaItem) {
        if (lyricsFor == item.mediaId) return
        lyricsFor = item.mediaId
        lyrics = null
        lyricsMessage = "Buscando letra…"
        viewModelScope.launch {
            val (found, message) = try {
                val result = withContext(Dispatchers.IO) { Extras.lyrics(item.toSong()) }
                result to if (result.isNullOrEmpty()) "Sin letra disponible" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to "No se pudo cargar la letra"
            }
            if (lyricsFor == item.mediaId) {
                lyrics = found
                lyricsMessage = message
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()

    fun download(song: Song) = Storage.download(getApplication(), song)

    fun removeDownload(song: Song) = Storage.remove(getApplication(), song)

    fun toggleFavorite(song: Song) = Library.toggleFavorite(getApplication(), song)

    fun togglePlay() {
        controller?.run { if (isPlaying) pause() else play() }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun jumpTo(queueIndex: Int) {
        controller?.seekTo(queueIndex, 0)
    }

    override fun onCleared() = MediaController.releaseFuture(controllerFuture)
}
