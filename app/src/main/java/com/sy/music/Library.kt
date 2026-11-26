package com.sy.music

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val CODE_PREFIX = "SY1:"
private const val MAX_TASTES = 500
private const val SEEDS = 3
private const val FOR_YOU_SIZE = 40
private const val DISLIKE_SKIPS = 3
private const val DISLIKED_ARTIST_SKIPS = 6
private const val FAVORITE_BONUS = 3
private const val PLAYLIST_BONUS = 2
private const val DAY_MS = 86_400_000L
private const val RECENCY_DAYS = 14.0
private const val FAMILIAR_SIZE = 30
private const val MAX_PER_ARTIST = 2
private const val MAX_SONG_SECONDS = 600
private val NOT_MUSIC = Regex("""\b(en vivo|live|full album|[aá]lbum completo|concierto|concert)\b""", RegexOption.IGNORE_CASE)

data class Playlist(val id: String, val name: String, val songs: List<Song>)

data class Taste(val song: Song, val plays: Int, val skips: Int, val lastPlayed: Long) {
    val score get() = plays - 2 * skips
}

data class LibraryData(
    val favorites: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val tastes: List<Taste> = emptyList(),
    val downloads: List<Song> = emptyList(),
)

private fun songs(list: List<Song>) = JSONArray(list.map { it.toJson() })

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun LibraryData.toJson(): JSONObject = JSONObject()
    .put("favorites", songs(favorites))
    .put("playlists", JSONArray(playlists.map {
        JSONObject().put("id", it.id).put("name", it.name).put("songs", songs(it.songs))
    }))
    .put("tastes", JSONArray(tastes.map {
        it.song.toJson().put("plays", it.plays).put("skips", it.skips).put("last", it.lastPlayed)
    }))
    .put("downloads", songs(downloads))

fun libraryFromJson(json: JSONObject) = LibraryData(
    favorites = json.getJSONArray("favorites").objects().map(::songFromJson),
    playlists = json.getJSONArray("playlists").objects().map { playlist ->
        Playlist(
            id = playlist.getString("id"),
            name = playlist.getString("name"),
            songs = playlist.getJSONArray("songs").objects().map(::songFromJson),
        )
    },
    tastes = json.getJSONArray("tastes").objects().map {
        Taste(songFromJson(it), it.getInt("plays"), it.getInt("skips"), it.getLong("last"))
    },
    downloads = json.getJSONArray("downloads").objects().map(::songFromJson),
)

/** Código de texto para copiar y pegar, comprimido porque el historial crece rápido. */
fun LibraryData.toCode(): String {
    val zipped = ByteArrayOutputStream()
    GZIPOutputStream(zipped).use { it.write(toJson().toString().toByteArray()) }
    return CODE_PREFIX + Base64.getEncoder().encodeToString(zipped.toByteArray())
}

/** Lanza una excepción si el código no es válido, sin tocar ningún dato. */
fun libraryFromCode(code: String): LibraryData {
    val clean = code.trim()
    require(clean.startsWith(CODE_PREFIX)) { "Código no válido" }
    val zipped = Base64.getDecoder().decode(clean.removePrefix(CODE_PREFIX))
    val text = GZIPInputStream(ByteArrayInputStream(zipped)).use { String(it.readBytes()) }
    return libraryFromJson(JSONObject(text))
}

fun LibraryData.mergedWith(other: LibraryData): LibraryData {
    val knownPlaylists = playlists.map { it.id }.toSet()
    val mergedTastes = (tastes + other.tastes).groupBy { it.song.url }.values.map { same ->
        Taste(
            song = same.first().song,
            plays = same.maxOf { it.plays },
            skips = same.maxOf { it.skips },
            lastPlayed = same.maxOf { it.lastPlayed },
        )
    }
    return LibraryData(
        favorites = (favorites + other.favorites).distinctBy { it.url },
        playlists = playlists + other.playlists.filter { it.id !in knownPlaylists },
        tastes = mergedTastes.sortedByDescending { it.lastPlayed },
        downloads = (downloads + other.downloads).distinctBy { it.url },
    )
}

/** Saltar en los primeros segundos dice "no me gusta" con más fuerza: cuenta doble. */
fun LibraryData.recorded(song: Song, played: Boolean, now: Long, earlySkip: Boolean = false): LibraryData {
    val old = tastes.firstOrNull { it.song.url == song.url }
    val updated = Taste(
        song = song,
        plays = (old?.plays ?: 0) + if (played) 1 else 0,
        skips = (old?.skips ?: 0) + if (played) 0 else if (earlySkip) 2 else 1,
        lastPlayed = now,
    )
    return copy(tastes = (listOf(updated) + tastes.filter { it.song.url != song.url }).take(MAX_TASTES))
}

fun LibraryData.dislikedUrls(): Set<String> =
    tastes.filter { it.skips >= DISLIKE_SKIPS && it.skips > it.plays }.map { it.song.url }.toSet()

/** Artistas que casi siempre saltas: dejan de aparecer en las sugerencias. */
fun LibraryData.dislikedArtists(): Set<String> = tastes.groupBy { mainArtist(it.song.artist).lowercase() }
    .filter { (_, list) -> list.sumOf { it.skips } >= DISLIKED_ARTIST_SKIPS && list.sumOf { it.skips } > 2 * list.sumOf { it.plays } }
    .keys

/**
 * Cuánto te gusta una canción hoy: lo escuchado menos lo saltado, más un extra si es favorita
 * o está en una playlist, y pierde peso con el tiempo para seguir tu ánimo actual.
 */
fun LibraryData.affinities(now: Long): Map<Taste, Double> {
    val favorite = favorites.map { it.url }.toSet()
    val listed = playlists.flatMap { it.songs }.map { it.url }.toSet()
    return tastes.associateWith { taste ->
        val bonus = (if (taste.song.url in favorite) FAVORITE_BONUS else 0) + (if (taste.song.url in listed) PLAYLIST_BONUS else 0)
        val days = (now - taste.lastPlayed).coerceAtLeast(0) / DAY_MS.toDouble()
        (taste.score + bonus) / (1 + days / RECENCY_DAYS)
    }
}

fun LibraryData.forYouSeeds(now: Long = System.currentTimeMillis()): List<Song> =
    affinities(now).filter { (taste, value) -> taste.plays > 0 && value > 0 }
        .entries.sortedByDescending { it.value }.take(SEEDS).map { it.key.song }

/** Para meter en la radio de vez en cuando: canciones que ya sabes que te gustan. */
fun LibraryData.familiar(now: Long = System.currentTimeMillis()): List<Song> =
    (favorites + affinities(now).filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(FAMILIAR_SIZE).map { it.key.song })
        .distinctBy { it.url }

/** Lo que no debe entrar en sugerencias: lo que saltas, y como mucho 2 por artista. */
fun LibraryData.suggestable(songs: List<Song>, alreadyPerArtist: Map<String, Int> = emptyMap()): List<Song> {
    val disliked = dislikedUrls()
    val badArtists = dislikedArtists()
    val perArtist = alreadyPerArtist.toMutableMap()
    return songs.filter { song ->
        val artist = mainArtist(song.artist).lowercase()
        val ok = song.url !in disliked && artist !in badArtists && !isNotMusic(song) &&
            (perArtist[artist] ?: 0) < MAX_PER_ARTIST
        if (ok) perArtist[artist] = (perArtist[artist] ?: 0) + 1
        ok
    }
}

/** Videos largos, en vivo o álbumes completos no son canciones para una radio. */
fun isNotMusic(song: Song) = song.seconds > MAX_SONG_SECONDS || NOT_MUSIC.containsMatchIn(song.title)

/** Intercala las radios para que ningún gusto ocupe toda la lista. */
fun LibraryData.mixForYou(radios: List<List<Song>>): List<Song> = suggestable(
    (0 until (radios.maxOfOrNull { it.size } ?: 0))
        .flatMap { i -> radios.mapNotNull { it.getOrNull(i) } }
        .distinctBy { it.url },
).take(FOR_YOU_SIZE)

/** Todo lo tuyo queda en un archivo del celular; nada depende de YouTube. */
object Library {
    val data = MutableStateFlow(LibraryData())

    private lateinit var file: AtomicFile

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun init(context: Context) {
        val path = File(context.filesDir, "library.json")
        file = AtomicFile(path)
        if (!path.exists()) return
        try {
            data.value = libraryFromJson(JSONObject(String(file.readFully())))
        } catch (e: Exception) {
            Log.e("SY", "Biblioteca dañada; la guardo aparte para no sobrescribirla", e)
            path.renameTo(File(context.filesDir, "library.corrupt.json"))
        }
    }

    private fun change(block: (LibraryData) -> LibraryData) {
        data.update(block)
        saver.launch {
            val text = data.value.toJson().toString().toByteArray()
            val stream = file.startWrite()
            try {
                stream.write(text)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream) // el archivo anterior queda intacto
                Log.e("SY", "No se pudo guardar la biblioteca", e)
            }
        }
    }

    fun isFavorite(url: String) = data.value.favorites.any { it.url == url }

    /** Un favorito nuevo se descarga solo, así siempre está disponible. */
    fun toggleFavorite(context: Context, song: Song) {
        val adding = !isFavorite(song.url)
        change { d ->
            d.copy(favorites = if (adding) listOf(song) + d.favorites else d.favorites.filter { it.url != song.url })
        }
        if (adding && !Storage.isReady(song.url)) Storage.download(context, song)
    }

    fun record(song: Song, played: Boolean, earlySkip: Boolean = false) =
        change { it.recorded(song, played, System.currentTimeMillis(), earlySkip) }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        change { it.copy(playlists = it.playlists + Playlist(UUID.randomUUID().toString(), name, songs)) }
    }

    fun deletePlaylist(id: String) = change { d -> d.copy(playlists = d.playlists.filter { it.id != id }) }

    fun addToPlaylist(id: String, song: Song) = editPlaylist(id) { (it + song).distinctBy { s -> s.url } }

    fun removeFromPlaylist(id: String, song: Song) = editPlaylist(id) { list -> list.filter { it.url != song.url } }

    private fun editPlaylist(id: String, edit: (List<Song>) -> List<Song>) = change { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(songs = edit(it.songs)) else it })
    }

    fun exportCode() = data.value.copy(downloads = Storage.readySongs).toCode()

    /** Devuelve las canciones por volver a descargar, o null si el código no sirve. */
    fun importCode(code: String, replace: Boolean): List<Song>? {
        val imported = try {
            libraryFromCode(code)
        } catch (e: Exception) {
            return null
        }
        val songsToFetch = imported.downloads.filter { !Storage.isReady(it.url) }
        change { current ->
            (if (replace) imported else current.mergedWith(imported)).copy(downloads = emptyList())
        }
        return songsToFetch
    }
}
