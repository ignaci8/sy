package com.sy.music

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

/** Qué motor está en uso. Pantalla y servicio viven en el mismo proceso. */
object Engine {
    val reserveActive = MutableStateFlow(false)
}

/** Búsqueda y radio: NewPipeExtractor primero; un fallo de internet no activa la reserva. */
object Catalog {
    fun search(query: String) = withReserve({ YouTube.search(query) }, { YouTubeWeb.search(query) })

    fun radio(songUrl: String) = withReserve({ YouTube.radio(songUrl) }, { YouTubeWeb.radio(songUrl) })

    private inline fun <T> withReserve(main: () -> T, reserve: () -> T): T = try {
        main()
    } catch (e: ExtractionException) {
        reserve()
    }
}

/** Habla directo con la API web de YouTube, sin depender de NewPipeExtractor. */
object YouTubeWeb {
    private const val CLIENT_VERSION = "2.20260101.00.00"
    private const val ONLY_VIDEOS = "EgIQAQ=="
    private const val DESKTOP_BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private val LOUDNESS = Regex(""""loudnessDb":(-?[0-9.]+)""")
    private val http = OkHttpClient()

    fun search(query: String): List<Song> {
        val body = JSONObject().put("query", query).put("params", ONLY_VIDEOS)
        return songs(call("search", body), "videoRenderer", "ownerText")
    }

    fun radio(songUrl: String): List<Song> {
        val id = idOf(songUrl)
        val body = JSONObject().put("videoId", id).put("playlistId", "RDAMVM$id")
        return songs(call("next", body), "playlistPanelVideoRenderer", "shortBylineText")
    }

    /** Cuánto más fuerte que el nivel de referencia suena la canción, en dB. */
    fun loudnessDb(songUrl: String): Double? {
        // La API se lo niega a clientes sin token; la página pública del video sí lo trae.
        val request = Request.Builder()
            .url("https://www.youtube.com/watch?v=${idOf(songUrl)}")
            .header("User-Agent", DESKTOP_BROWSER)
            .build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("YouTube respondió ${it.code}")
            return LOUDNESS.find(it.body!!.string())?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }

    private fun idOf(url: String) = url.substringAfter("v=").substringBefore('&')

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "es")
            .put("gl", "CL")
        body.put("context", JSONObject().put("client", client))
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("YouTube respondió ${it.code}")
            return JSONObject(it.body!!.string())
        }
    }

    private fun songs(root: JSONObject, rendererKey: String, artistKey: String): List<Song> {
        val found = mutableListOf<JSONObject>()
        collect(root, rendererKey, found) // buscar por nombre resiste cambios en el orden de la respuesta
        return found.filter { it.optString("videoId").isNotBlank() }.map {
            val thumbnails = it.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            Song(
                url = "https://www.youtube.com/watch?v=${it.getString("videoId")}",
                title = text(it, "title"),
                artist = text(it, artistKey),
                seconds = text(it, "lengthText").split(':')
                    .fold(0L) { total, part -> total * 60 + (part.toLongOrNull() ?: 0) },
                thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url"),
            )
        }
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> node.keys().forEach { name ->
                val value = node.get(name)
                if (name == key && value is JSONObject) out += value else collect(value, key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.get(i), key, out)
        }
    }

    private fun text(item: JSONObject, key: String): String {
        val field = item.optJSONObject(key) ?: return ""
        return field.optString("simpleText").ifBlank {
            field.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        }
    }
}
