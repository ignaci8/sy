package com.sy.music

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val STARTER_QUERY = "éxitos del momento"
private const val TOP_ARTISTS = 5
private const val ALBUMS_PER_ARTIST = 4
private const val PLAYLISTS_PER_ARTIST = 3
private const val LIFETIME_MS = 6 * 60 * 60 * 1000L

/** Un mix de YouTube: al tocarlo suena tu canción favorita del artista y la radio sigue sola. */
data class Mix(val artist: String, val thumbnail: String?, val seed: Song)

data class HomeFeed(
    val mixes: List<Mix> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Album> = emptyList(),
    val forYou: List<Song> = emptyList(),
    val key: String = "",
    val savedAt: Long = 0,
)

/** Los artistas que más escuchas, cada uno con la canción suya que más te gusta. */
fun LibraryData.topArtists(): List<Pair<String, Song>> = tastes.filter { it.plays > 0 }
    .groupBy { mainArtist(it.song.artist) }
    .filterKeys { it.isNotBlank() }
    .map { (artist, list) -> Triple(artist, list.sumOf { it.score }, list.maxBy { it.score }.song) }
    .filter { it.second > 0 }
    .sortedByDescending { it.second }
    .take(TOP_ARTISTS)
    .map { it.first to it.third }

/** Si cambian tus artistas o tus canciones favoritas, el Inicio se vuelve a pedir. */
fun LibraryData.homeKey() =
    (topArtists().map { it.first }.sorted() + forYouSeeds().map { it.url }.sorted()).joinToString("|")

/** Una de cada artista por turno, para que ninguno ocupe toda la fila. */
private fun <T> interleave(lists: List<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }

private fun Album.toJson() = JSONObject().put("id", id).put("title", title).put("year", year).put("thumbnail", thumbnail.orEmpty())

private fun albumFromJson(json: JSONObject) =
    Album(json.getString("id"), json.getString("title"), json.getString("year"), json.getString("thumbnail").ifEmpty { null })

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun HomeFeed.toJson(): JSONObject = JSONObject()
    .put("mixes", JSONArray(mixes.map { JSONObject().put("artist", it.artist).put("thumbnail", it.thumbnail.orEmpty()).put("seed", it.seed.toJson()) }))
    .put("albums", JSONArray(albums.map { it.toJson() }))
    .put("playlists", JSONArray(playlists.map { it.toJson() }))
    .put("forYou", JSONArray(forYou.map { it.toJson() }))
    .put("key", key)
    .put("savedAt", savedAt)

fun homeFromJson(json: JSONObject) = HomeFeed(
    mixes = json.getJSONArray("mixes").objects().map {
        Mix(it.getString("artist"), it.getString("thumbnail").ifEmpty { null }, songFromJson(it.getJSONObject("seed")))
    },
    albums = json.getJSONArray("albums").objects().map(::albumFromJson),
    playlists = json.getJSONArray("playlists").objects().map(::albumFromJson),
    forYou = json.getJSONArray("forYou").objects().map(::songFromJson),
    key = json.getString("key"),
    savedAt = json.getLong("savedAt"),
)

/** Las sugerencias se piden una vez y se guardan unas horas: el Inicio abre rápido y no gasta datos. */
object Home {
    private fun file(context: Context) = File(context.filesDir, "inicio.json")

    fun saved(context: Context): HomeFeed? = try {
        file(context).takeIf { it.exists() }?.let { homeFromJson(JSONObject(it.readText())) }
    } catch (e: Exception) {
        Log.w("SY", "Inicio guardado ilegible", e)
        null
    }

    fun isFresh(feed: HomeFeed, library: LibraryData) =
        feed.key == library.homeKey() && System.currentTimeMillis() - feed.savedAt < LIFETIME_MS

    fun save(context: Context, feed: HomeFeed) {
        file(context).writeText(feed.toJson().toString())
    }

    /** Bloquea mientras consulta YouTube; llamar fuera del hilo principal. */
    suspend fun build(library: LibraryData): HomeFeed = coroutineScope {
        val artists = library.topArtists()
        val seeds = library.forYouSeeds()
        val shelves = artists.map { (name, _) ->
            async { runCatching { YouTubeMusic.artistShelves(name) }.getOrNull() }
        }
        val forYou = if (seeds.isEmpty()) {
            Catalog.search(STARTER_QUERY)
        } else {
            library.mixForYou(seeds.map { async { Catalog.radio(it.url) } }.awaitAll())
        }
        val found = shelves.awaitAll()
        HomeFeed(
            mixes = artists.mapIndexed { i, (name, seed) ->
                val artist = found[i]?.artist
                Mix(artist?.name ?: name, artist?.thumbnail ?: seed.thumbnail, seed)
            },
            albums = interleave(found.map { shelf ->
                shelf?.albums.orEmpty().take(ALBUMS_PER_ARTIST).map { it.copy(year = "${shelf!!.artist.name} · ${it.year}") }
            }),
            playlists = interleave(found.map { it?.playlists.orEmpty().take(PLAYLISTS_PER_ARTIST) }).distinctBy { it.id },
            forYou = forYou,
            key = library.homeKey(),
            savedAt = System.currentTimeMillis(),
        )
    }
}
