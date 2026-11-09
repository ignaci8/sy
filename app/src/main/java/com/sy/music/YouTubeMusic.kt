package com.sy.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val BROWSER =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
private const val ONLY_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
private const val MAX_TOP_SONGS = 30
private const val SPOTIFY_PARALLEL_SEARCHES = 4
private val DURATION = Regex("""^\d+(:\d+)+$""")
private val THUMB_SIZE = Regex("=w\\d+-h\\d+")
private val SPOTIFY_LINK = Regex("""open\.spotify\.com/(?:intl-\w+/)?(playlist|album)/(\w+)""")
private val SPOTIFY_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

data class ArtistRef(val id: String, val name: String, val thumbnail: String?)
data class Album(val id: String, val title: String, val year: String, val thumbnail: String?)
data class Discography(val label: String, val albums: List<Album>)
data class ArtistPage(
    val id: String,
    val name: String,
    val thumbnail: String?,
    val topSongs: List<Song>,
    val discography: List<Discography>,
)
data class AlbumPage(val title: String, val artist: String, val thumbnail: String?, val songs: List<Song>)

private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
    when (node) {
        is JSONObject -> node.keys().forEach { name ->
            val value = node.get(name)
            if (name == key && value is JSONObject) out += value else collect(value, key, out)
        }
        is JSONArray -> for (i in 0 until node.length()) collect(node.get(i), key, out)
    }
}

private fun all(node: Any?, key: String) = mutableListOf<JSONObject>().also { collect(node, key, it) }

private fun runs(field: JSONObject?): List<String> {
    val array = field?.optJSONArray("runs") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it).optString("text") }
}

/** La carátula más grande disponible que haya dentro de este bloque. */
private fun thumbnailOf(node: JSONObject?): String? {
    val found = mutableListOf<JSONArray>()
    fun walk(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { name ->
                val child = value.get(name)
                if (name == "thumbnails" && child is JSONArray) found += child else walk(child)
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.get(i))
        }
    }
    walk(node)
    val list = found.firstOrNull() ?: return null
    return list.optJSONObject(list.length() - 1)?.optString("url")?.replace(THUMB_SIZE, "=w800-h800")
}

private fun column(item: JSONObject, index: Int): List<String> = runs(
    item.optJSONArray("flexColumns")?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"),
)

private fun songFromRow(item: JSONObject, fallbackArtist: String, fallbackThumb: String?): Song? {
    val id = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
    if (id.isBlank()) return null
    val title = column(item, 0).firstOrNull() ?: return null
    val duration = (0 until (item.optJSONArray("flexColumns")?.length() ?: 0)).flatMap { column(item, it) } +
        all(item, "musicResponsiveListItemFixedColumnRenderer").flatMap { runs(it.optJSONObject("text")) }
    return Song(
        url = "https://www.youtube.com/watch?v=$id",
        title = title,
        artist = column(item, 1).firstOrNull()?.takeUnless { DURATION.matches(it) } ?: fallbackArtist,
        seconds = duration.firstOrNull { DURATION.matches(it) }?.split(':')
            ?.fold(0L) { total, part -> total * 60 + part.toLong() } ?: 0,
        thumbnail = thumbnailOf(item.optJSONObject("thumbnail")) ?: fallbackThumb,
    )
}

/** Artistas y discografía salen de la API web de YouTube Music, sin NewPipeExtractor. */
object YouTubeMusic {
    private const val CLIENT_VERSION = "1.20260101.01.00"
    private val http = OkHttpClient()

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "es")
            .put("gl", "CL")
        body.put("context", JSONObject().put("client", client))
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", BROWSER)
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("YouTube Music respondió ${it.code}")
            return JSONObject(it.body!!.string())
        }
    }

    private fun browse(id: String, params: String? = null): JSONObject {
        val body = JSONObject().put("browseId", id)
        if (!params.isNullOrBlank()) body.put("params", params)
        return call("browse", body)
    }

    fun searchArtists(query: String): List<ArtistRef> =
        all(call("search", JSONObject().put("query", query).put("params", ONLY_ARTISTS)), "musicResponsiveListItemRenderer")
            .mapNotNull { item ->
                val id = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                val name = column(item, 0).firstOrNull()
                if (id.startsWith("UC") && name != null) {
                    ArtistRef(id, name, thumbnailOf(item.optJSONObject("thumbnail")))
                } else {
                    null
                }
            }

    /** Las canciones traen el artista como texto; este busca su perfil. */
    fun artistFromName(rawName: String): ArtistPage? {
        val name = rawName.removeSuffix(" - Topic").substringBefore(',').substringBefore(" & ").trim()
        val match = searchArtists(name).firstOrNull() ?: return null
        return artist(match.id)
    }

    fun artist(id: String): ArtistPage {
        val root = browse(id)
        val header = all(root, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: all(root, "musicVisualHeaderRenderer").firstOrNull()
        val name = runs(header?.optJSONObject("title")).firstOrNull().orEmpty()
        val shelf = all(root, "musicShelfRenderer").firstOrNull()
        val shortList = all(shelf, "musicResponsiveListItemRenderer").mapNotNull { songFromRow(it, name, null) }
        val allSongsId = shelf?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        val topSongs = if (allSongsId.isNullOrBlank()) {
            shortList
        } else {
            runCatching {
                all(browse(allSongsId), "musicResponsiveListItemRenderer")
                    .mapNotNull { songFromRow(it, name, null) }
                    .take(MAX_TOP_SONGS)
            }.getOrDefault(shortList).ifEmpty { shortList }
        }
        val discography = all(root, "musicCarouselShelfRenderer").mapNotNull { carousel ->
            val basic = carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val visible = albumsIn(carousel)
            if (visible.isEmpty()) return@mapNotNull null
            val more = basic?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val complete = more?.let {
                runCatching { albumsIn(browse(it.getString("browseId"), it.optString("params"))) }.getOrNull()
            }
            Discography(
                runs(basic?.optJSONObject("title")).firstOrNull().orEmpty(),
                complete?.takeIf { it.isNotEmpty() } ?: visible,
            )
        }
        return ArtistPage(id, name, thumbnailOf(header?.optJSONObject("thumbnail")), topSongs, discography)
    }

    private fun albumsIn(node: Any?): List<Album> = all(node, "musicTwoRowItemRenderer").mapNotNull { item ->
        val id = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        if (!id.startsWith("MPRE")) return@mapNotNull null
        Album(
            id = id,
            title = runs(item.optJSONObject("title")).firstOrNull().orEmpty(),
            year = runs(item.optJSONObject("subtitle")).lastOrNull().orEmpty(),
            thumbnail = thumbnailOf(item.optJSONObject("thumbnailRenderer")),
        )
    }

    fun album(id: String): AlbumPage {
        val root = browse(id)
        val header = all(root, "musicResponsiveHeaderRenderer").firstOrNull()
        val artist = runs(header?.optJSONObject("straplineTextOne")).firstOrNull().orEmpty()
        val thumbnail = thumbnailOf(header?.optJSONObject("thumbnail"))
        return AlbumPage(
            title = runs(header?.optJSONObject("title")).firstOrNull().orEmpty(),
            artist = artist,
            thumbnail = thumbnail,
            songs = all(root, "musicResponsiveListItemRenderer").mapNotNull { songFromRow(it, artist, thumbnail) },
        )
    }
}

data class SpotifyTrack(val title: String, val artist: String)

/** Spotify no entrega su lista sin cuenta, salvo en la página pública para incrustar. */
object Spotify {
    private val http = OkHttpClient()

    fun isLink(text: String) = SPOTIFY_LINK.containsMatchIn(text)

    fun tracks(link: String): Pair<String, List<SpotifyTrack>> {
        val (type, id) = SPOTIFY_LINK.find(link)?.destructured ?: throw IOException("Enlace no válido")
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", BROWSER)
            .build()
        val html = http.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Spotify respondió ${it.code}")
            it.body!!.string()
        }
        val data = SPOTIFY_DATA.find(html)?.groupValues?.get(1) ?: throw IOException("Spotify cambió su página")
        return parseSpotifyEmbed(JSONObject(data))
    }

    /** Busca cada canción en YouTube; las que no aparecen se omiten. */
    suspend fun matchOnYouTube(tracks: List<SpotifyTrack>): List<Song> = coroutineScope {
        val limited = Dispatchers.IO.limitedParallelism(SPOTIFY_PARALLEL_SEARCHES)
        tracks.map { track ->
            async(limited) {
                runCatching { Catalog.search("${track.title} ${track.artist}").firstOrNull() }.getOrNull()
            }
        }.awaitAll().filterNotNull().distinctBy { it.url }
    }
}

fun parseSpotifyEmbed(root: JSONObject): Pair<String, List<SpotifyTrack>> {
    val entity = root.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
        .getJSONObject("data").getJSONObject("entity")
    val list = entity.getJSONArray("trackList")
    val name = entity.optString("name").ifBlank { entity.optString("title") }
    return name to (0 until list.length()).map {
        val track = list.getJSONObject(it)
        SpotifyTrack(track.getString("title"), track.optString("subtitle"))
    }
}
