package com.sy.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage

private val Faded = Color.White.copy(alpha = 0.7f)
private val LyricWaiting = Color(0xFF6E6E6E)

@Composable
internal fun FullPlayer(song: MediaItem, model: PlayerModel, onClose: () -> Unit) {
    val info = song.mediaMetadata
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    val library by Library.data.collectAsState()
    val favorite = library.favorites.any { it.url == song.mediaId }

    LaunchedEffect(song.mediaId, showLyrics) {
        if (showLyrics) model.loadLyrics(song)
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        LazyColumn(Modifier.systemBarsPadding().padding(horizontal = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_expand_down), "Cerrar", tint = Color.White)
                    }
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            painterResource(R.drawable.ic_lyrics),
                            if (showLyrics) "Ver carátula" else "Ver letra",
                            tint = if (showLyrics) Accent else Color.White,
                        )
                    }
                }
                if (showLyrics) {
                    Lyrics(model)
                } else {
                    AsyncImage(
                        info.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
                Text(
                    info.title.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(
                        info.artist.toString(),
                        color = Faded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { model.openArtistByName(info.artist.toString()) },
                    )
                    IconButton(onClick = { model.toggleFavorite(song.toSong()) }) {
                        Icon(
                            painterResource(if (favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
                            if (favorite) "Quitar de favoritos" else "Agregar a favoritos",
                            tint = if (favorite) Accent else Color.White,
                        )
                    }
                    DownloadButton(song.toSong(), model)
                    SongMenu(song.toSong(), model)
                }

                val duration = model.durationMs.coerceAtLeast(1).toFloat()
                Slider(
                    value = (dragging ?: model.positionMs.toFloat()).coerceIn(0f, duration),
                    valueRange = 0f..duration,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { model.seekTo(it.toLong()) }
                        dragging = null
                    },
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(time(model.positionMs), color = Faded)
                    Text(time(model.durationMs), color = Faded)
                }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    IconButton(onClick = model::toggleShuffle, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(
                            painterResource(R.drawable.ic_shuffle),
                            if (model.shuffle) "Desactivar aleatorio" else "Activar aleatorio",
                            tint = if (model.shuffle) Accent else Color.White,
                        )
                    }
                    Row(
                        Modifier.align(Alignment.Center),
                        Arrangement.spacedBy(24.dp),
                        Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = model::previous) {
                            Icon(painterResource(R.drawable.ic_previous), "Anterior", tint = Color.White)
                        }
                        FilledIconButton(onClick = model::togglePlay, modifier = Modifier.size(64.dp)) {
                            if (model.buffering) CircularProgressIndicator(Modifier.size(28.dp))
                            else PlayPauseIcon(model.isPlaying)
                        }
                        IconButton(onClick = model::next) {
                            Icon(painterResource(R.drawable.ic_next), "Siguiente", tint = Color.White)
                        }
                    }
                }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "A continuación",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            items(model.upcoming) { (index, item) ->
                SongRow(
                    item.mediaMetadata.title.toString(),
                    item.mediaMetadata.artist.toString(),
                    item.mediaMetadata.artworkUri,
                    url = item.mediaId,
                ) { model.jumpTo(index) }
            }
        }
    }
}

@Composable
private fun Lyrics(model: PlayerModel) {
    val lines = model.lyrics
    val list = rememberLazyListState()
    val active = lines?.indexOfLast { it.synced && it.ms <= model.positionMs } ?: -1

    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem((active - 2).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) {
        if (lines.isNullOrEmpty()) {
            Text(model.lyricsMessage.orEmpty(), color = Faded)
        } else {
            LazyColumn(Modifier.fillMaxSize(), list) {
                itemsIndexed(lines) { i, line ->
                    // Solo las letras con tiempos avanzan con la canción y se pueden tocar para saltar.
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (i == active || !line.synced) Color.White else LyricWaiting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (line.synced) Modifier.clickable { model.seekTo(line.ms) } else Modifier)
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
