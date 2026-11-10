package com.sy.music

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private const val BYTES_PER_GB = 1024L * 1024 * 1024
private const val DEFAULT_CACHE_GB = 1
private const val PREFS = "sy"
private const val PREF_CACHE_GB = "cache_gb"
private const val MAX_PARALLEL_DOWNLOADS = 2
private const val CHANNEL_ID = "descargas"
private const val NOTIFICATION_ID = 7
private const val NOTIFICATION_INTERVAL_MS = 1000L
private const val LOW_QUALITY = "#low"
private const val STUCK_CHECK_MS = 30_000L
private const val STUCK_AFTER_MS = 60_000L
private const val RESTART_STOP_REASON = 1

enum class DownloadStatus { READY, WORKING, FAILED }

data class SavedSong(val song: Song, val status: DownloadStatus)

class SyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Connection.init(this)
        Storage.init(this)
    }
}

/** Dos almacenes: las descargas nunca se borran solas; lo escuchado se recicla al llenarse. */
object Storage {
    val saved = MutableStateFlow(emptyList<SavedSong>())

    lateinit var manager: DownloadManager
        private set
    lateinit var playbackSource: DataSource.Factory
        private set

    private lateinit var listened: SimpleCache
    private lateinit var listenedSource: CacheDataSource.Factory

    @Volatile private var preloading: CacheWriter? = null

    private val handler = Handler(Looper.getMainLooper())
    private val lastProgress = mutableMapOf<String, Pair<Long, Long>>() // bytes y desde cuándo
    private val watchStuck = object : Runnable {
        override fun run() {
            restartStuck()
            handler.postDelayed(this, STUCK_CHECK_MS)
        }
    }

    val readySongs get() = saved.value.filter { it.status == DownloadStatus.READY }.map { it.song }

    fun init(context: Context) {
        val database = StandaloneDatabaseProvider(context)
        val downloads = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database)
        listened = SimpleCache(
            File(context.cacheDir, "listened"),
            LeastRecentlyUsedCacheEvictor(cacheLimitGb(context) * BYTES_PER_GB),
            database,
        )
        val http = DefaultHttpDataSource.Factory().setUserAgent(YouTube.streamUserAgent)
        // La URL real del audio caduca: se pide solo si hay que traer datos de YouTube.
        val youtube = ResolvingDataSource.Factory(http) { spec ->
            try {
                val uri = spec.uri.toString()
                spec.withUri(YouTube.streamUrl(uri.removeSuffix(LOW_QUALITY), uri.endsWith(LOW_QUALITY)).toUri())
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
        }
        listenedSource = CacheDataSource.Factory()
            .setCache(listened)
            .setUpstreamDataSourceFactory(youtube)
        playbackSource = CacheDataSource.Factory()
            .setCache(downloads)
            .setUpstreamDataSourceFactory(listenedSource)
            .setCacheWriteDataSinkFactory(null) // reproducir no debe escribir en las descargas

        manager = DownloadManager(
            context,
            database,
            downloads,
            youtube,
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(manager: DownloadManager) = refresh()

                override fun onDownloadChanged(
                    manager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) = refresh()

                override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
            })
        }
        handler.postDelayed(watchStuck, STUCK_CHECK_MS)
    }

    fun cacheLimitGb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREF_CACHE_GB, DEFAULT_CACHE_GB)

    /** Se aplica la próxima vez que se abra la app. */
    fun setCacheLimitGb(context: Context, gb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_CACHE_GB, gb).apply()
    }

    fun download(context: Context, song: Song) {
        val request = DownloadRequest.Builder(song.url, song.url.toUri())
            .setData(song.toBytes())
            .build()
        DownloadService.sendAddDownload(context, SongDownloadService::class.java, request, false)
    }

    fun resumeDownloads(context: Context) {
        try {
            DownloadService.start(context, SongDownloadService::class.java)
        } catch (e: IllegalStateException) {
            Log.w("SY", "Android no dejó encender las descargas", e) // la app ya no estaba a la vista
        }
    }

    /** Una descarga que no avanza en un minuto se reinicia con un enlace nuevo, desde donde iba. */
    private fun restartStuck() {
        val now = SystemClock.elapsedRealtime()
        val downloading = manager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
        lastProgress.keys.retainAll(downloading.map { it.request.id }.toSet())
        downloading.forEach { download ->
            val id = download.request.id
            val (bytes, since) = lastProgress[id] ?: (-1L to now)
            if (download.bytesDownloaded != bytes) {
                lastProgress[id] = download.bytesDownloaded to now
            } else if (now - since >= STUCK_AFTER_MS) {
                Log.w("SY", "Descarga atascada, se reinicia: $id")
                lastProgress.remove(id)
                YouTube.forget(id)
                manager.setStopReason(id, RESTART_STOP_REASON)
                manager.setStopReason(id, Download.STOP_REASON_NONE)
            }
        }
    }

    fun remove(context: Context, song: Song) {
        DownloadService.sendRemoveDownload(context, SongDownloadService::class.java, song.url, false)
    }

    /** Descargas en curso, por URL de la canción. */
    fun active() = manager.currentDownloads.associateBy { it.request.id }

    fun isReady(url: String) = saved.value.any { it.song.url == url && it.status == DownloadStatus.READY }

    /** La dirección marca la calidad, así los dos archivos de una canción no se mezclan en el caché. */
    fun uriFor(url: String) = if (!isAvailable(url) && Connection.slow) url + LOW_QUALITY else url

    /** Si la canción completa se puede escuchar sin internet. */
    fun isAvailable(uri: String): Boolean {
        if (isReady(uri)) return true
        val length = ContentMetadata.getContentLength(listened.getContentMetadata(uri))
        return length > 0 && listened.isCached(uri, 0, length)
    }

    /** Guarda la canción en lo escuchado. Bloquea hasta terminar, fallar o cancelarse. */
    fun preload(uri: String) {
        if (isAvailable(uri)) return
        val writer = CacheWriter(listenedSource.createDataSource(), DataSpec(uri.toUri()), null, null)
        preloading = writer
        writer.cache()
    }

    fun cancelPreload() {
        preloading?.cancel()
    }

    private fun refresh() {
        val found = mutableListOf<Pair<Long, SavedSong>>()
        try {
            manager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    val status = when (download.state) {
                        Download.STATE_COMPLETED -> DownloadStatus.READY
                        Download.STATE_FAILED -> DownloadStatus.FAILED
                        Download.STATE_REMOVING -> continue
                        else -> DownloadStatus.WORKING
                    }
                    found += download.startTimeMs to SavedSong(songFromBytes(download.request.data), status)
                }
            }
        } catch (e: IOException) {
            Log.w("SY", "No se pudo leer la lista de descargas", e)
            return
        }
        saved.value = found.sortedByDescending { it.first }.map { it.second }
    }
}

class SongDownloadService :
    DownloadService(NOTIFICATION_ID, NOTIFICATION_INTERVAL_MS, CHANNEL_ID, R.string.downloads, 0) {
    override fun getDownloadManager() = Storage.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification = DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
        this,
        R.drawable.ic_download,
        null,
        null,
        downloads,
        notMetRequirements,
    )
}
