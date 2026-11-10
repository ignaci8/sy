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
import androidx.media3.common.C
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

private const val POSITION_REFRESH_MS = 500L
private const val MAX_ARTIST_RESULTS = 6
private val LINK = Regex("""https?://\S+""")

class PlayerModel(app: Application) : AndroidViewModel(app) {
    var results by mutableStateOf(emptyList<Song>())
        private set
    var home by mutableStateOf(HomeFeed())
        private set
    var homeLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    var current by mutableStateOf<MediaItem?>(null)
        private set
    /** Lo que sonará después, en el orden real (también con aleatorio), con su posición en la cola. */
    var upcoming by mutableStateOf(emptyList<Pair<Int, MediaItem>>())
        private set
    var shuffle by mutableStateOf(false)
        private set
    var artistResults by mutableStateOf(emptyList<ArtistRef>())
        private set
    var artist by mutableStateOf<ArtistPage?>(null)
        private set
    var album by mutableStateOf<AlbumPage?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var collapseRequests by mutableIntStateOf(0)
        private set
    val detailOpen get() = artist != null || album != null || detailLoading
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
    private val queuedByHand = mutableSetOf<String>()
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
        shuffle = player.shuffleModeEnabled
        upcoming = buildList {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return@buildList
            var i = timeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffle)
            while (i != C.INDEX_UNSET) {
                add(i to player.getMediaItemAt(i))
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
        isPlaying = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
    }

    /** Muestra al tiro lo guardado y solo vuelve a preguntar a YouTube si ya está viejo. */
    fun loadHome() {
        homeLoading = true
        viewModelScope.launch {
            val library = Library.data.value
            val saved = withContext(Dispatchers.IO) { Home.saved(getApplication()) }
            if (saved != null) home = saved
            if (saved == null || !Home.isFresh(saved, library)) {
                try {
                    home = withContext(Dispatchers.IO) {
                        Home.build(library).also { Home.save(getApplication(), it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sin conexión: lo guardado de antes, o al menos tus descargas.
                    if (saved == null) home = HomeFeed(forYou = Storage.readySongs)
                }
            }
            homeLoading = false
        }
    }

    fun openPlaylist(playlist: Playlist) = loadDetail {
        album = AlbumPage(
            playlist.name,
            "${playlist.songs.size} canciones",
            playlist.songs.firstNotNullOfOrNull { it.thumbnail },
            playlist.songs,
        )
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
        viewModelScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { YouTubeMusic.searchArtists(query).take(MAX_ARTIST_RESULTS) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (query == lastQuery) artistResults = found
        }
    }

    private fun loadDetail(block: suspend () -> Unit) {
        collapseRequests++
        detailLoading = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("No se pudo abrir")
            } finally {
                detailLoading = false
            }
        }
    }

    fun openArtist(id: String) = loadDetail {
        album = null
        artist = withContext(Dispatchers.IO) { YouTubeMusic.artist(id) }
    }

    fun openArtistByName(name: String) = loadDetail {
        album = null
        artist = withContext(Dispatchers.IO) { YouTubeMusic.artistFromName(name) }
        if (artist == null) toast("No encontré ese artista")
    }

    fun openAlbum(id: String) = loadDetail {
        album = withContext(Dispatchers.IO) { YouTubeMusic.album(id) }
    }

    /** Del álbum vuelve al artista, y del artista a la pestaña. */
    fun closeDetail() {
        if (album != null) album = null else artist = null
    }

    fun closeDetails() {
        album = null
        artist = null
    }

    fun toggleShuffle() {
        controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    }

    /** Entra justo después de lo ya puesto en la fila a mano, antes de la radio. */
    fun addToQueue(song: Song) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return play(song)
        if (player.currentMediaItem?.mediaId == song.url) return toast("Ya está sonando")
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == song.url }
        // El gesto puede avisar dos veces; una canción nunca va repetida en la cola.
        if (existing != null && existing > player.currentMediaItemIndex && song.url in queuedByHand) return
        existing?.let { player.removeMediaItem(it) }
        var position = player.currentMediaItemIndex + 1
        while (position < player.mediaItemCount && player.getMediaItemAt(position).mediaId in queuedByHand) position++
        player.addMediaItem(position, song.toMediaItem())
        queuedByHand += song.url
        toast("Agregada a la fila")
    }

    fun downloadAll(songs: List<Song>) {
        val known = Storage.saved.value
            .filter { it.status != DownloadStatus.FAILED }
            .map { it.song.url }
            .toSet()
        val missing = songs.filter { it.url !in known }
        missing.forEach { download(it) }
        toast(if (missing.isEmpty()) "Ya están todas descargadas" else "Descargando ${missing.size} canciones")
    }

    fun play(song: Song) {
        error = null
        queuedByHand.clear()
        controller?.run {
            setMediaItem(song.toMediaItem())
            prepare()
            play()
        }
    }

    fun playAll(songs: List<Song>, startIndex: Int) {
        error = null
        queuedByHand.clear()
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
            toast("No encontré un enlace")
            return
        }
        viewModelScope.launch {
            try {
                if (Spotify.isLink(link)) {
                    toast("Importando desde Spotify…")
                    val (name, tracks) = withContext(Dispatchers.IO) { Spotify.tracks(link) }
                    val songs = Spotify.matchOnYouTube(tracks)
                    if (songs.isEmpty()) throw IllegalStateException("Sin coincidencias")
                    Library.createPlaylist(name, songs)
                    toast("Playlist importada: $name (${songs.size} de ${tracks.size})")
                } else if (YouTube.isPlaylistLink(link)) {
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
