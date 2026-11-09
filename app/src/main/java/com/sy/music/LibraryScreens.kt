package com.sy.music

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.delay

private const val DOWNLOAD_REFRESH_MS = 500L
private const val ROOT = "root"
private const val FAVORITES = "favorites"
private const val DOWNLOADS = "downloads"
private const val BACKUP = "backup"
private const val PLAYLIST_PREFIX = "playlist:"
private val CACHE_CHOICES = listOf(1, 2, 5, 10)

@Composable
internal fun LibraryTab(model: PlayerModel) {
    var page by rememberSaveable { mutableStateOf(ROOT) }
    val library by Library.data.collectAsState()

    if (page != ROOT) BackHandler { page = ROOT }
    Column {
        if (page != ROOT) {
            IconButton(onClick = { page = ROOT }) {
                Icon(painterResource(R.drawable.ic_back), "Volver")
            }
        }
        when {
            page == FAVORITES -> SongListPage("Favoritos", library.favorites, model) { song ->
                IconButton(onClick = { model.toggleFavorite(song) }) {
                    Icon(painterResource(R.drawable.ic_heart), "Quitar de favoritos", tint = Accent)
                }
            }
            page == DOWNLOADS -> DownloadsPage(model)
            page == BACKUP -> BackupPage()
            page.startsWith(PLAYLIST_PREFIX) -> {
                val playlist = library.playlists.firstOrNull { it.id == page.removePrefix(PLAYLIST_PREFIX) }
                if (playlist == null) {
                    LaunchedEffect(Unit) { page = ROOT }
                } else {
                    Row {
                        Button(onClick = { model.downloadAll(playlist.songs) }) { Text("Descargar todas") }
                        TextButton(onClick = { Library.deletePlaylist(playlist.id) }) {
                            Text("Eliminar playlist", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    SongListPage(playlist.name, playlist.songs, model) { song ->
                        IconButton(onClick = { Library.removeFromPlaylist(playlist.id, song) }) {
                            Icon(painterResource(R.drawable.ic_delete), "Quitar de la playlist")
                        }
                    }
                }
            }
            else -> LibraryRoot(library, model) { page = it }
        }
    }
}

@Composable
private fun LibraryRoot(library: LibraryData, model: PlayerModel, open: (String) -> Unit) {
    val saved by Storage.saved.collectAsState()
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var cacheGb by remember { mutableStateOf(Storage.cacheLimitGb(context)) }

    LazyColumn {
        item { SectionTitle("Biblioteca") }
        item { LibraryRow("Favoritos", "${library.favorites.size} canciones") { open(FAVORITES) } }
        item { LibraryRow("Descargas", "${saved.size} canciones") { open(DOWNLOADS) } }
        item { SectionTitle("Playlists") }
        items(library.playlists) {
            LibraryRow(it.name, "${it.songs.size} canciones") { open(PLAYLIST_PREFIX + it.id) }
        }
        if (library.playlists.isEmpty()) {
            item { Text("Crea una desde el menú ⋮ de cualquier canción.") }
        }
        item { SectionTitle("Importar desde YouTube o Spotify") }
        item {
            OutlinedTextField(
                link,
                { link = it },
                Modifier.fillMaxWidth(),
                label = { Text("Pega el enlace de una canción o playlist") },
                singleLine = true,
            )
            Button(
                enabled = link.isNotBlank(),
                onClick = {
                    model.handleLink(link)
                    link = ""
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Abrir") }
        }
        item { SectionTitle("Ajustes") }
        item { LibraryRow("Copia de seguridad", "Exportar o importar todos tus datos") { open(BACKUP) } }
        item {
            Text("Espacio para el caché de lo escuchado", modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CACHE_CHOICES.forEach { gb ->
                    FilterChip(
                        selected = cacheGb == gb,
                        onClick = {
                            cacheGb = gb
                            Storage.setCacheLimitGb(context, gb)
                        },
                        label = { Text("$gb GB") },
                    )
                }
            }
            Text(
                "El cambio se aplica al volver a abrir la app.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SongListPage(
    title: String,
    songs: List<Song>,
    model: PlayerModel,
    action: @Composable (Song) -> Unit,
) {
    LazyColumn {
        item { SectionTitle(title) }
        if (songs.isEmpty()) item { Text("Todavía no hay canciones.") }
        items(songs) { song ->
            SongItem(
                song,
                model,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DownloadButton(song, model)
                        action(song)
                    }
                },
                onClick = { model.playAll(songs, songs.indexOf(song)) },
            )
        }
    }
}

@Composable
private fun DownloadsPage(model: PlayerModel) {
    val saved by Storage.saved.collectAsState()
    val ready = saved.filter { it.status == DownloadStatus.READY }.map { it.song }
    // Texto y no objetos Download: son siempre los mismos y Compose no vería cambios.
    var active by remember { mutableStateOf(emptyMap<String, String>()) }

    LaunchedEffect(saved) {
        while (saved.any { it.status == DownloadStatus.WORKING }) {
            active = Storage.active().mapValues { phase(it.value) }
            delay(DOWNLOAD_REFRESH_MS)
        }
    }

    LazyColumn {
        item { SectionTitle("Descargas") }
        if (saved.isEmpty()) {
            item { Text("Aún no tienes descargas. Toca la flecha junto a una canción para guardarla.") }
        }
        items(saved) { item ->
            val subtitle = when (item.status) {
                DownloadStatus.READY -> item.song.artist
                DownloadStatus.WORKING -> active[item.song.url] ?: "En cola"
                DownloadStatus.FAILED -> "Falló la descarga"
            }
            SongItem(
                item.song,
                model,
                subtitle,
                trailing = {
                    Row {
                        if (item.status == DownloadStatus.FAILED) DownloadButton(item.song, model)
                        SongMenu(item.song, model)
                        IconButton(onClick = { model.removeDownload(item.song) }) {
                            Icon(painterResource(R.drawable.ic_delete), "Eliminar descarga")
                        }
                    }
                },
                onClick = {
                    if (item.status == DownloadStatus.READY) model.playAll(ready, ready.indexOf(item.song))
                },
            )
        }
    }
}

private fun phase(download: Download): String = when {
    download.state == Download.STATE_QUEUED -> "En cola"
    download.percentDownloaded <= 0f -> "Buscando el audio…"
    else -> "Descargando… ${download.percentDownloaded.toInt()}%"
}

/** Igual que en FitLog: un código de texto para copiar, y combinar o reemplazar al importar. */
@Composable
private fun BackupPage() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var toFetch by remember { mutableStateOf(emptyList<Song>()) }

    fun import(replace: Boolean) {
        val songs = Library.importCode(code, replace)
        if (songs == null) {
            message = "Código no válido"
        } else {
            message = "✅ Datos importados"
            toFetch = songs
            code = ""
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Exportar todos los datos")
        Text("Incluye favoritos, playlists, gustos y la lista de descargas. Copia el código y guárdalo, o pégalo en otro dispositivo. El audio no va en el código.")
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(Library.exportCode()))
                message = "✅ Copiado"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copiar código") }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Library.exportCode())
                context.startActivity(Intent.createChooser(send, "Compartir copia de seguridad"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Compartir…") }

        SectionTitle("Importar todos los datos")
        Text("Pega el código exportado.")
        OutlinedTextField(
            code,
            { code = it },
            Modifier.fillMaxWidth(),
            placeholder = { Text("SY1:...") },
            maxLines = 5,
        )
        message?.let { Text(it) }
        Button(onClick = { import(replace = false) }, modifier = Modifier.fillMaxWidth()) {
            Text("Combinar con mis datos")
        }
        Button(
            onClick = { import(replace = true) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reemplazar todo") }
        if (toFetch.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    toFetch.forEach { Storage.download(context, it) }
                    toFetch = emptyList()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Volver a descargar ${toFetch.size} canciones") }
        }
    }
}
