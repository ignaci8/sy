package com.sy.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private enum class SongList(val label: String, val source: String) {
    TOP("Más escuchadas", "Según las reproducciones (YouTube Music)"),
    RECENT("Más recientes", "Por año de lanzamiento (YouTube Music)"),
    GUEST("Como invitado", "Canciones de otros artistas donde participa (YouTube Music)"),
}

@Composable
internal fun DetailScreen(model: PlayerModel) {
    Column {
        IconButton(onClick = model::closeDetail) {
            Icon(painterResource(R.drawable.ic_back), "Volver")
        }
        if (model.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val album = model.album
        val artist = model.artist
        when {
            album != null -> AlbumView(album, model)
            artist != null -> ArtistView(artist, model)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, thumbnail: String?, onPlay: () -> Unit) {
    AsyncImage(
        thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
    )
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp),
    )
    subtitle?.let { Text(it) }
    Button(onClick = onPlay, modifier = Modifier.padding(vertical = 8.dp)) { Text("Reproducir") }
}

@Composable
private fun ArtistView(artist: ArtistPage, model: PlayerModel) {
    var shown by remember(artist.id) { mutableStateOf(SongList.TOP) }
    LazyColumn {
        item {
            Header(artist.name, null, artist.thumbnail) { model.playAll(artist.topSongs, 0) }
        }
        item {
            // "Como invitado" solo aparece si YouTube encontró alguna: no tiene un apartado para esto.
            val options = SongList.entries.filter { it != SongList.GUEST || model.guests.isNotEmpty() }
            Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = shown == option,
                        onClick = {
                            shown = option
                            if (option == SongList.RECENT) model.loadRecent()
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            Text(
                shown.source,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val songs = when (shown) {
            SongList.TOP -> artist.topSongs
            SongList.RECENT -> model.recent.takeUnless { model.recentLoading }
            SongList.GUEST -> model.guests
        }
        if (songs == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else {
            items(songs) { song ->
                SongItem(song, model, onClick = { model.playAll(songs, songs.indexOf(song)) })
            }
        }
        artist.discography.forEach { section ->
            item { SectionTitle(section.label) }
            item {
                LazyRow {
                    items(section.albums) { album ->
                        CoverCard(album.title, album.year, album.thumbnail) { model.openAlbum(album.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumView(album: AlbumPage, model: PlayerModel) {
    LazyColumn {
        item {
            Header(album.title, album.artist, album.thumbnail) { model.playAll(album.songs, 0) }
        }
        items(album.songs) { song ->
            SongItem(song, model, onClick = { model.playAll(album.songs, album.songs.indexOf(song)) })
        }
    }
}
