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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

internal val Background = Color.Black
internal val Accent = Color(0xFF8AB4FF)
private val PlayingNow = Color(0xFFFF3B30)
private val Secondary = Color.White.copy(alpha = 0.7f)
private val Raised = Color(0xFF1C1C1E)
private val SMALL_ARTWORK = 80.dp

internal val LocalPlayingUrl = compositionLocalOf<String?> { null }
internal val LocalPlaylistUrls = compositionLocalOf<Set<String>> { emptySet() }

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
                    onBackground = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color.White,
                    secondaryContainer = Raised,
                    onSecondaryContainer = Color.White,
                    surfaceContainer = Raised,
                    surfaceContainerHigh = Raised,
                ),
            ) {
                // Sin esta superficie, los íconos sueltos heredan color negro y no se ven.
                Surface(color = Background) { App() }
            }
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
    val library by Library.data.collectAsState()
    val inPlaylists = remember(library.playlists) {
        library.playlists.flatMap { it.songs }.map { it.url }.toSet()
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.trackPosition() }
    }
    LaunchedEffect(link) {
        link?.let {
            model.handleLink(it)
            sharedLink.value = null
        }
    }
    LaunchedEffect(model.collapseRequests) {
        if (model.collapseRequests > 0) expanded = false
    }

    CompositionLocalProvider(
        LocalPlayingUrl provides song?.mediaId,
        LocalPlaylistUrls provides inPlaylists,
    ) {
        if (expanded && song != null) {
            BackHandler { expanded = false }
            FullPlayer(song, model) { expanded = false }
        } else {
            MainScreen(model) { expanded = true }
        }
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
                            onClick = {
                                tab = it
                                model.closeDetails()
                            },
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
            if (model.detailOpen) {
                BackHandler(onBack = model::closeDetail)
                DetailScreen(model)
            } else {
                when (tab) {
                    Tab.HOME -> HomeTab(model)
                    Tab.SEARCH -> SearchTab(model)
                    Tab.LIBRARY -> LibraryTab(model)
                }
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
internal fun CoverCard(title: String, subtitle: String?, thumbnail: String?, url: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(end = 12.dp)
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Artwork(thumbnail, 120.dp)
        Text(
            title,
            color = if (url != null && url == LocalPlayingUrl.current) PlayingNow else Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        subtitle?.let {
            Text(it, color = Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
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
                        CoverCard(song.title, null, song.thumbnail, song.url) { model.play(song) }
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
        items(model.forYou) { song -> SongItem(song, model) }
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
            if (model.artistResults.isNotEmpty()) {
                item { SectionTitle("Artistas") }
                item {
                    LazyRow {
                        items(model.artistResults) { artist ->
                            CoverCard(artist.name, null, artist.thumbnail) { model.openArtist(artist.id) }
                        }
                    }
                }
                item { SectionTitle("Canciones") }
            }
            items(model.results) { song ->
                SongItem(song, model, "${song.artist} · ${time(song.seconds * 1000)}")
            }
        }
    }
}

/** Fila de canción completa: marca la que suena y desliza a la izquierda para la fila. */
@Composable
internal fun SongItem(
    song: Song,
    model: PlayerModel,
    subtitle: String = song.artist,
    trailing: @Composable () -> Unit = { SongActions(song, model) },
    onClick: () -> Unit = { model.play(song) },
) {
    SongRow(
        song.title,
        subtitle,
        song.thumbnail,
        url = song.url,
        trailing = trailing,
        onSwipe = { model.addToQueue(song) },
        onClick = onClick,
    )
}

@Composable
internal fun SongRow(
    title: String,
    subtitle: String,
    artwork: Any?,
    url: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onSwipe: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val playing = url != null && url == LocalPlayingUrl.current
    val inPlaylist = url != null && url in LocalPlaylistUrls.current
    val textColor = if (playing) PlayingNow else Color.Unspecified
    val row = @Composable {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = Background, supportingColor = Secondary),
            leadingContent = { Artwork(artwork, 52.dp) },
            trailingContent = trailing,
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inPlaylist) Text(" ♪", color = Accent)
                }
            },
            supportingContent = {
                Text(subtitle, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
        )
    }
    if (onSwipe == null) row() else SwipeToQueue(onSwipe, row)
}

@Composable
private fun SwipeToQueue(onSwipe: () -> Unit, content: @Composable () -> Unit) {
    val action by rememberUpdatedState(onSwipe)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) action()
            false // la fila vuelve a su lugar: la canción no sale de la lista
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), Alignment.CenterEnd) {
                Text("Agregar a la fila", color = Accent)
            }
        },
    ) { content() }
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
            Icon(painterResource(R.drawable.ic_download), "Descargar", tint = Color.White)
        }
    }
}

@Composable
internal fun SongMenu(song: Song, model: PlayerModel) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), "Más opciones", tint = Color.White)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("Agregar a la fila") },
                onClick = {
                    model.addToQueue(song)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Ver artista") },
                onClick = {
                    model.openArtistByName(song.artist)
                    open = false
                },
            )
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
    Surface(color = Background, modifier = Modifier.clickable(onClick = onExpand)) {
        Column {
            LinearProgressIndicator(
                progress = { if (model.durationMs > 0) model.positionMs.toFloat() / model.durationMs else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Background, supportingColor = Secondary),
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
