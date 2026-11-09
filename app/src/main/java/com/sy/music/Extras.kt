package com.sy.music

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.math.abs

private const val HTTP_NOT_FOUND = 404
private const val MAX_DURATION_DIFF_S = 5
private const val NO_TIME = -1L
private val LRC_TAG = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
private val TITLE_NOISE = Regex("""\s*[(\[].*?[)\]]""")

data class LyricLine(val ms: Long, val text: String) {
    val synced get() = ms != NO_TIME
}

fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().mapNotNull { line ->
    val tag = LRC_TAG.find(line) ?: return@mapNotNull null
    val text = line.replace(LRC_TAG, "").trim()
    if (text.isEmpty()) return@mapNotNull null
    val (minutes, seconds) = tag.destructured
    LyricLine(minutes.toLong() * 60_000 + (seconds.toDouble() * 1000).toLong(), text)
}

/** Servicios ajenos a YouTube: si alguno falla, la música sigue igual. */
object Extras {
    private val http = OkHttpClient()

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "SY music player").build()
        http.newCall(request).execute().use {
            if (it.code == HTTP_NOT_FOUND) return null
            check(it.isSuccessful) { "Respuesta ${it.code}" }
            return it.body!!.string()
        }
    }

    fun lyrics(song: Song): List<LyricLine>? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", song.title.replace(TITLE_NOISE, ""))
            .addQueryParameter("artist_name", song.artist)
            .build()
        val body = get(url.toString()) ?: return null
        val results = JSONArray(body).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val best = results
            .filter { song.seconds == 0L || abs(it.optDouble("duration", 0.0) - song.seconds) <= MAX_DURATION_DIFF_S }
            .ifEmpty { results }
        val synced = best.firstOrNull { it.optString("syncedLyrics").isNotBlank() && !it.isNull("syncedLyrics") }
        if (synced != null) return parseLrc(synced.getString("syncedLyrics"))
        val plain = best.firstOrNull { it.optString("plainLyrics").isNotBlank() && !it.isNull("plainLyrics") }
            ?: return null
        return plain.getString("plainLyrics").lines().filter { it.isNotBlank() }.map { LyricLine(NO_TIME, it) }
    }

    /** Partes de un video musical sin música: intros, diálogos y finales. */
    fun offTopicSegments(songUrl: String): List<Pair<Long, Long>> {
        val id = songUrl.substringAfter("v=").substringBefore('&')
        val body = get("https://sponsor.ajay.app/api/skipSegments?videoID=$id&category=music_offtopic")
            ?: return emptyList()
        val array = JSONArray(body)
        return (0 until array.length()).map {
            val segment = array.getJSONObject(it).getJSONArray("segment")
            (segment.getDouble(0) * 1000).toLong() to (segment.getDouble(1) * 1000).toLong()
        }
    }
}
