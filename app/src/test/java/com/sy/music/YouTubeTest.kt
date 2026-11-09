package com.sy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Prueba real contra YouTube: falla si YouTube cambió algo que rompe la app. */
class YouTubeTest {
    private val a = Song("https://www.youtube.com/watch?v=a", "A ñ", "X", 200, null)
    private val b = Song("https://www.youtube.com/watch?v=b", "B", "Y", 100, "http://t")

    @Test
    fun backupCodeRestoresEverything() {
        val data = LibraryData(
            favorites = listOf(a),
            playlists = listOf(Playlist("1", "Mix \"é\"", listOf(a, b))),
            tastes = listOf(Taste(b, 3, 1, 99)),
            downloads = listOf(b),
        )
        assertEquals(data, libraryFromCode("  " + data.toCode() + "\n"))
    }

    @Test
    fun invalidBackupCodesAreRejected() {
        listOf("", "hola", "SY1:", "SY1:@@@", "FL1:abc").forEach { code ->
            assertTrue(runCatching { libraryFromCode(code) }.isFailure)
        }
    }

    @Test
    fun mergeKeepsBothSidesWithoutDuplicates() {
        val mine = LibraryData(favorites = listOf(a), tastes = listOf(Taste(a, 5, 0, 10)))
        val other = LibraryData(
            favorites = listOf(a, b),
            playlists = listOf(Playlist("1", "P", listOf(b))),
            tastes = listOf(Taste(a, 2, 4, 20)),
        )
        val merged = mine.mergedWith(other)
        assertEquals(listOf(a, b), merged.favorites)
        assertEquals(1, merged.playlists.size)
        assertEquals(listOf(Taste(a, 5, 4, 20)), merged.tastes)
    }

    @Test
    fun tastesDriveForYou() {
        var data = LibraryData()
        repeat(3) { data = data.recorded(a, played = true, now = 1) }
        repeat(3) { data = data.recorded(b, played = false, now = 2) }
        assertEquals(listOf(a), data.forYouSeeds())
        assertEquals(setOf(b.url), data.dislikedUrls())
        assertEquals(listOf(a), data.mixForYou(listOf(listOf(b, a), listOf(a))))
    }

    @Test
    fun parsesSyncedLyrics() {
        val lines = parseLrc("[00:12.50] Hola\n[01:02.00]Chao\n[01:03.00]\nsin marca")
        assertEquals(listOf(LyricLine(12_500, "Hola"), LyricLine(62_000, "Chao")), lines)
    }

    @Test
    fun extrasAnswerForARealSong() {
        val song = YouTube.search("Daft Punk Get Lucky").first()
        val lyrics = Extras.lyrics(song)
        println("Letra: ${lyrics?.size} líneas, ej: ${lyrics?.firstOrNull()}")
        assertTrue(!lyrics.isNullOrEmpty())
        val loudness = YouTubeWeb.loudnessDb(song.url)
        println("Volumen: $loudness dB")
        assertTrue(loudness != null)
        println("Partes sin música: ${Extras.offTopicSegments(song.url)}")
    }

    @Test
    fun linksOpenSongsAndPlaylists() {
        assertTrue(YouTube.isPlaylistLink("https://www.youtube.com/playlist?list=PLabc"))
        assertTrue(!YouTube.isPlaylistLink("https://youtu.be/5NV6Rdv1a3I?si=x"))
        assertTrue(!YouTube.isPlaylistLink("https://www.youtube.com/watch?v=a&list=RDa"))
        val song = YouTube.songFromLink("https://youtu.be/5NV6Rdv1a3I?si=x")
        println("Enlace: ${song.title} | ${song.url}")
        assertEquals("https://www.youtube.com/watch?v=5NV6Rdv1a3I", song.url)
        val (name, songs) = YouTube.playlistFromLink(
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
        )
        println("Playlist: $name, ${songs.size} canciones")
        assertTrue(songs.isNotEmpty())
    }

    @Test
    fun updatesOnlyWhenNewer() {
        val release = org.json.JSONObject(
            """{"tag_name":"v120","assets":[{"name":"x.txt","browser_download_url":"t"},
            {"name":"app-release.apk","browser_download_url":"https://a/app.apk"}]}""",
        )
        assertEquals(AppUpdate(120, "https://a/app.apk"), parseUpdate(release, 119))
        assertEquals(null, parseUpdate(release, 120))
    }

    @Test
    fun songSurvivesSaving() {
        val song = Song("https://www.youtube.com/watch?v=a", "Título ñ \"x\"", "Artista", 200, null)
        assertEquals(song, songFromBytes(song.toBytes()))
    }

    @Test
    fun reserveSearchesAndBuildsRadioWithoutNewPipe() {
        val song = YouTubeWeb.search("Daft Punk Get Lucky").first()
        println("Reserva: ${song.title} - ${song.artist} | ${song.seconds}s | ${song.url}")
        assertTrue(song.title.isNotBlank() && song.artist.isNotBlank() && song.seconds > 0)

        val radio = YouTubeWeb.radio(song.url)
        println("Radio reserva: ${radio.size}, ej: ${radio.drop(1).take(3).map { it.title }}")
        assertTrue(radio.size >= 5)
        assertTrue(radio.all { it.title.isNotBlank() })
    }

    @Test
    fun searchesAndStreamsBestAudio() {
        val song = YouTube.search("Daft Punk Get Lucky").first()
        val audio = YouTube.bestAudio(song.url)
        println("Canción: ${song.title} - ${song.artist} | ${audio.format} ${audio.averageBitrate} kbps")
        assertTrue(audio.averageBitrate >= 120)

        // Igual que lo pide el reproductor: misma identidad y con rango.
        val connection = URL(audio.content).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", YouTube.streamUserAgent)
        connection.setRequestProperty("Range", "bytes=0-")
        assertEquals(206, connection.responseCode)
        val bytes = connection.inputStream.use { it.readNBytes(256 * 1024) }
        assertEquals(256 * 1024, bytes.size)

        val radio = YouTube.radio(song.url)
        println("Radio: ${radio.size} canciones, ej: ${radio.drop(1).take(3).map { it.title }}")
        assertTrue(radio.size >= 5)
    }
}
