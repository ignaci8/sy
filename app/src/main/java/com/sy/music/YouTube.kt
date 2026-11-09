package com.sy.music

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Song(
    val url: String,
    val title: String,
    val artist: String,
    val seconds: Long,
    val thumbnail: String?,
)

fun Song.toJson(): JSONObject = JSONObject()
    .put("url", url)
    .put("title", title)
    .put("artist", artist)
    .put("seconds", seconds)
    .put("thumbnail", thumbnail.orEmpty())

fun songFromJson(json: JSONObject) = Song(
    url = json.getString("url"),
    title = json.getString("title"),
    artist = json.getString("artist"),
    seconds = json.getLong("seconds"),
    thumbnail = json.getString("thumbnail").ifEmpty { null },
)

fun Song.toBytes(): ByteArray = toJson().toString().toByteArray()

fun songFromBytes(bytes: ByteArray): Song = songFromJson(JSONObject(String(bytes)))

/** La única pieza de la app que habla con YouTube. */
object YouTube {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // shortcut: vida fija de 1 h, YouTube da unas 6 h; leer el parámetro expire si hiciera falta.
    private const val URL_LIFETIME_MS = 3_600_000L
    private const val MAX_PLAYLIST_SONGS = 500
    private val urls = ConcurrentHashMap<String, Pair<String, Long>>()

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    init {
        NewPipe.init(OkHttpDownloader, Localization("es", "CL"), ContentCountry("CL"))
    }

    /** YouTube rechaza el audio si no se pide con la identidad del cliente que lo obtuvo. */
    val streamUserAgent: String get() = YoutubeParsingHelper.getVisionOsUserAgent(null)

    fun search(query: String): List<Song> {
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toSong)
    }

    /** La radio que YouTube Music genera a partir de una canción. */
    fun radio(songUrl: String): List<Song> {
        val id = idOf(songUrl)
        val extractor = ServiceList.YouTube
            .getPlaylistExtractor("https://music.youtube.com/watch?v=$id&list=RDAMVM$id")
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toSong)
    }

    fun streamUrl(songUrl: String): String {
        val now = System.currentTimeMillis()
        urls[songUrl]?.takeIf { now - it.second < URL_LIFETIME_MS }?.let { return it.first }
        return bestAudio(songUrl).content.also { urls[songUrl] = it to now }
    }

    fun isPlaylistLink(link: String) =
        link.contains("/playlist") || (link.contains("list=") && !link.contains("list=RD"))

    fun songFromLink(link: String): Song {
        val info = StreamInfo.getInfo(ServiceList.YouTube, link)
        return Song(
            url = "https://www.youtube.com/watch?v=${info.id}",
            title = info.name,
            artist = info.uploaderName.orEmpty(),
            seconds = info.duration,
            thumbnail = info.thumbnails.maxByOrNull { it.width }?.url,
        )
    }

    fun playlistFromLink(link: String): Pair<String, List<Song>> {
        val extractor = ServiceList.YouTube.getPlaylistExtractor(link)
        extractor.fetchPage()
        var page = extractor.initialPage
        val items = page.items.toMutableList()
        while (page.hasNextPage() && items.size < MAX_PLAYLIST_SONGS) {
            page = extractor.getPage(page.nextPage)
            items += page.items
        }
        return extractor.name to items.filterIsInstance<StreamInfoItem>().map(::toSong)
    }

    fun forget(songUrl: String) {
        urls.remove(songUrl)
    }

    private fun idOf(url: String) = url.substringAfter("v=").substringBefore('&')

    private fun toSong(item: StreamInfoItem) = Song(
        url = "https://www.youtube.com/watch?v=${idOf(item.url)}", // la misma canción, una sola URL
        title = item.name,
        artist = item.uploaderName.orEmpty(),
        seconds = item.duration,
        // Las carátulas de YouTube Music aceptan pedirse a mayor tamaño.
        thumbnail = item.thumbnails.maxByOrNull { it.width }?.url
            ?.replace(Regex("=w\\d+-h\\d+"), "=w800-h800"),
    )

    fun bestAudio(songUrl: String): AudioStream =
        StreamInfo.getInfo(ServiceList.YouTube, songUrl).audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            // El idioma original gana a un doblaje aunque el doblaje tenga más bitrate.
            .maxWithOrNull(
                compareBy(
                    { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL },
                    { it.averageBitrate },
                ),
            ) ?: throw ExtractionException("YouTube no entregó audio para esta canción")

    private object OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            http.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException("YouTube pidió un captcha", request.url())
                }
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string(),
                    response.request.url.toString(),
                )
            }
        }
    }
}
