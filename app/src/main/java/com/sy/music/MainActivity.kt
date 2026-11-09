package com.sy.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow

internal val Background = Color(0xFF0E0E12)
internal val Accent = Color(0xFF8AB4FF)
private val SMALL_ARTWORK = 80.dp

private val sharedLink = MutableStateFlow<String?>(null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveShare(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Background,
                    surface = Background,
                ),
            ) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) sharedLink.value = intent.getStringExtra(Intent.EXTRA_TEXT)
    }
}

@Composable
private fun App(model: PlayerModel = viewModel()) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val song = model.current
    val lifecycle = LocalLifecycleOwner.current
    val link by sharedLink.collectAsState()

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.trackPosition() }
    }
    LaunchedEffect(link) {
        link?.let {
            model.handleLink(it)
            sharedLink.value = null
        }
    }

    if (expanded && song != null) {
        BackHandler { expanded = false }
        FullPlayer(song, model) { expanded = false }
    } else {
        MainScreen(model) { expanded = true }
    }
}

private enum class Tab(val label: String, val icon: Int) {
    HOME("Inicio", R.drawable.ic_home),
    SEARCH("Buscar", R.drawable.ic_search),
    LIBRARY("Biblioteca", R.drawable.ic_library),
}

@Composable
private fun MainScreen(model: PlayerModel, onExpand: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val reserve by Engine.reserveActive.collectAsState()

    Scaffold(
        containerColor = Background,
        bottomBar = {
            Column {
                model.current?.let { MiniPlayer(it, model, onExpand) }
                NavigationBar(containerColor = Background) {
                    Tab.entries.forEach {
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(painterResource(it.icon), null) },
                            label = { Text(it.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (reserve) Text("Modo reserva activo", color = Accent, modifier = Modifier.padding(vertical = 4.dp))
            when (tab) {
                Tab.HOME -> HomeTab(model)
                Tab.SEARCH -> SearchTab(model)
                Tab.LIBRARY -> LibraryTab(model)
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun HomeTab(model: PlayerModel) {
    val library by Library.data.collectAsState()
    val recent = remember(library.tastes) { library.tastes.take(12).map { it.song } }
    val personal = library.forYouSeeds().isNotEmpty()

    LazyColumn {
        if (model.update != null) {
            item {
                TextButton(enabled = !model.updating, onClick = model::installUpdate) {
                    Text(if (model.updating) "Descargando actualización…" else "Hay una versión nueva · Actualizar")
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Escuchado recientemente") }
            item {
                LazyRow {
                    items(recent) { song ->
                        Column(
                            Modifier
                                .padding(end = 12.dp)
                                .width(120.dp)
                                .clickable { model.play(song) },
                        ) {
                            Artwork(song.thumbnail, 120.dp)
                            Text(
                                song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        item { SectionTitle(if (personal) "Para ti" else "Para empezar") }
        if (model.homeLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!model.homeLoading && model.forYou.isEmpty()) {
            item {
                TextButton(onClick = model::loadHome) { Text("Sin conexión. Reintentar") }
            }
        }
        items(model.forYou) { song ->
            SongRow(song.title, song.artist, song.thumbnail, trailing = { SongActions(song, model) }) {
                model.play(song)
            }
        }
    }
}

@Composable
private fun SearchTab(model: PlayerModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Buscar canciones o artistas") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                model.search(query)
            }),
        )
        if (model.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }
        LazyColumn {
            items(model.results) { song ->
                SongRow(
                    song.title,
                    "${song.artist} · ${time(song.seconds * 1000)}",
                    song.thumbnail,
                    trailing = { SongActions(song, model) },
                ) { model.play(song) }
            }
        }
    }
}

@Composable
internal fun SongRow(
    title: String,
    subtitle: String,
    artwork: Any?,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Artwork(artwork, 52.dp) },
        trailingContent = trailing,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
internal fun Artwork(model: Any?, size: Dp) {
    val url = model?.toString()
    AsyncImage(
        // Las filas son pequeñas: no hace falta bajar la carátula de 800 px.
        if (size < SMALL_ARTWORK) url?.replace("=w800-h800", "=w160-h160") else url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
internal fun SongActions(song: Song, model: PlayerModel) {
    Row {
        DownloadButton(song, model)
        SongMenu(song, model)
    }
}

@Composable
internal fun DownloadButton(song: Song, model: PlayerModel) {
    val saved by Storage.saved.collectAsState()
    when (saved.firstOrNull { it.song.url == song.url }?.status) {
        DownloadStatus.READY -> Icon(
            painterResource(R.drawable.ic_check),
            "Descargada",
            tint = Accent,
            modifier = Modifier.padding(12.dp),
        )
        DownloadStatus.WORKING -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        else -> IconButton(onClick = { model.download(song) }) {
            Icon(painterResource(R.drawable.ic_download), "Descargar")
        }
    }
}

@Composable
internal fun SongMenu(song: Song, model: PlayerModel) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), "Más opciones")
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(if (Library.isFavorite(song.url)) "Quitar de favoritos" else "Agregar a favoritos") },
                onClick = {
                    model.toggleFavorite(song)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Agregar a playlist") },
                onClick = {
                    open = false
                    choosing = true
                },
            )
        }
    }
    if (choosing) PlaylistPicker(song) { choosing = false }
}

@Composable
private fun PlaylistPicker(song: Song, onClose: () -> Unit) {
    val library by Library.data.collectAsState()
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Agregar a playlist") },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(library.playlists) { playlist ->
                        Text(
                            playlist.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Library.addToPlaylist(playlist.id, song)
                                    onClose()
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nueva playlist") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    Library.createPlaylist(name.trim(), listOf(song))
                    onClose()
                },
            ) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

@Composable
private fun MiniPlayer(song: MediaItem, model: PlayerModel, onExpand: () -> Unit) {
    val info = song.mediaMetadata
    Surface(tonalElevation = 6.dp, modifier = Modifier.clickable(onClick = onExpand)) {
        Column {
            LinearProgressIndicator(
                progress = { if (model.durationMs > 0) model.positionMs.toFloat() / model.durationMs else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Artwork(info.artworkUri, 48.dp) },
                headlineContent = {
                    Text(info.title.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(info.artist.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = model::togglePlay) { PlayPauseIcon(model.isPlaying) }
                        IconButton(onClick = model::next) {
                            Icon(painterResource(R.drawable.ic_next), "Siguiente")
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun PlayPauseIcon(playing: Boolean) {
    Icon(
        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
        if (playing) "Pausar" else "Reproducir",
    )
}

internal fun time(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
