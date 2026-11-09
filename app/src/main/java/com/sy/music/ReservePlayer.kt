package com.sy.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayInputStream

private const val LOAD_TIMEOUT_MS = 20_000L
private const val SEEK_TOLERANCE_MS = 2_000L
private const val MOBILE_CHROME =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/141.0.0.0 Mobile Safari/537.36"
private val AD_HOSTS = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com")
private val AD_PATHS = listOf("/pagead/", "/ptracking", "/api/stats/ads")

// Hace creer a YouTube que la página está visible, salta anuncios y reporta el estado a la app.
private const val PAGE_SCRIPT = """
(function () {
  if (window.__sy) return;
  window.__sy = true;
  Object.defineProperty(document, 'hidden', { get: function () { return false; } });
  Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; } });
  document.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
  setInterval(function () {
    var v = document.querySelector('video');
    if (!v) return;
    var ad = !!document.querySelector('.ad-showing');
    if (ad) {
      v.muted = true;
      if (isFinite(v.duration)) v.currentTime = v.duration;
      var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
      if (skip) skip.click();
    } else {
      v.muted = false;
    }
    SY.state(location.href, v.currentTime || 0, v.duration || 0, v.paused, v.ended, ad);
  }, 500);
})();
"""

/** Reproduce con el propio reproductor web de YouTube, dentro de una ventana oculta. */
class ReservePlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private var items = listOf<MediaItem>()
    private var index = 0
    private var wantPlay = false
    private var phase = Player.STATE_IDLE
    private var positionMs = 0L
    private var durationMs = 0L
    private var pendingSeekMs = 0L
    private var reportedByPage = false

    private val timeout = Runnable {
        if (!reportedByPage) advance() // el video nunca cargó: no quedarse en silencio
    }

    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.blockNetworkImage = true
        settings.userAgentString = MOBILE_CHROME
        addJavascriptInterface(this@ReservePlayer, "SY")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                val isAd = AD_HOSTS.any { url.host.orEmpty().endsWith(it) } ||
                    AD_PATHS.any { url.path.orEmpty().startsWith(it) }
                return if (isAd) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(PAGE_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(PAGE_SCRIPT, null)
            }
        }
    }

    private val currentId get() = items.getOrNull(index)?.mediaId?.substringAfter("v=")

    @JavascriptInterface
    fun state(url: String, time: Double, duration: Double, paused: Boolean, ended: Boolean, ad: Boolean) {
        main.post { onPageState(url, time, duration, paused, ended, ad) }
    }

    private fun onPageState(
        url: String,
        time: Double,
        duration: Double,
        paused: Boolean,
        ended: Boolean,
        ad: Boolean,
    ) {
        val id = currentId ?: return
        if (phase == Player.STATE_IDLE || !url.contains(id)) return // reporte de una página anterior
        reportedByPage = true
        if (ad) {
            setPhase(Player.STATE_BUFFERING)
            return
        }
        positionMs = (time * 1000).toLong()
        val newDuration = (duration * 1000).toLong()
        if (newDuration != durationMs) {
            durationMs = newDuration
            invalidateState()
        }
        if (pendingSeekMs > 0 && durationMs > 0) {
            if (positionMs + SEEK_TOLERANCE_MS < pendingSeekMs) video("currentTime = ${pendingSeekMs / 1000.0}")
            pendingSeekMs = 0
        }
        when {
            ended -> advance()
            else -> {
                setPhase(Player.STATE_READY)
                if (paused && wantPlay) video("play()")
                if (!paused && !wantPlay) video("pause()")
            }
        }
    }

    private fun setPhase(newPhase: Int) {
        if (newPhase != phase) {
            phase = newPhase
            invalidateState()
        }
    }

    private fun video(command: String) {
        web.evaluateJavascript("var v = document.querySelector('video'); if (v) v.$command;", null)
    }

    private fun load() {
        val id = currentId ?: return
        main.removeCallbacks(timeout)
        reportedByPage = false
        positionMs = pendingSeekMs
        durationMs = 0
        phase = Player.STATE_BUFFERING
        web.onResume()
        web.resumeTimers()
        web.loadUrl("https://m.youtube.com/watch?v=$id")
        main.postDelayed(timeout, LOAD_TIMEOUT_MS)
    }

    private fun advance() {
        if (index + 1 < items.size) {
            index++
            pendingSeekMs = 0
            load()
        } else {
            main.removeCallbacks(timeout)
            phase = Player.STATE_ENDED
        }
        invalidateState()
    }

    override fun getState(): State {
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .setDurationUs(if (i == index && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .build()
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else phase)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(PositionSupplier { positionMs })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        video(if (playWhenReady) "play()" else "pause()")
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        load()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        main.removeCallbacks(timeout)
        phase = Player.STATE_IDLE
        web.loadUrl("about:blank")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        main.removeCallbacks(timeout)
        web.destroy()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        pendingSeekMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        if (phase != Player.STATE_IDLE) load()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        items = items.take(index) + mediaItems + items.drop(index)
        if (index <= this.index && items.size > mediaItems.size) this.index += mediaItems.size
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        items = items.take(fromIndex) + items.drop(toIndex)
        index = when {
            index >= toIndex -> index - (toIndex - fromIndex)
            index >= fromIndex -> fromIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
            else -> index
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0)
        if (mediaItemIndex != index) {
            index = mediaItemIndex
            pendingSeekMs = target
            load()
        } else {
            this.positionMs = target
            video("currentTime = ${target / 1000.0}")
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_RELEASE,
            )
            .build()
    }
}
