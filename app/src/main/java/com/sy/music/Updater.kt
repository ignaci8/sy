package com.sy.music

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val APK_TYPE = "application/vnd.android.package-archive"

data class AppUpdate(val versionCode: Int, val apkUrl: String)

/** Lee una publicación de GitHub; null si no es más nueva o no trae la app. */
fun parseUpdate(release: JSONObject, currentCode: Int): AppUpdate? {
    val code = release.getString("tag_name").removePrefix("v").toIntOrNull() ?: return null
    if (code <= currentCode) return null
    val assets = release.getJSONArray("assets")
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
    return AppUpdate(code, apk.getString("browser_download_url"))
}

object Updater {
    private val http = OkHttpClient()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    fun check(): AppUpdate? {
        if (BuildConfig.UPDATE_REPO.isBlank()) return null // compilada en el PC, no desde GitHub
        get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").use {
            if (!it.isSuccessful) return null
            return parseUpdate(JSONObject(it.body!!.string()), BuildConfig.VERSION_CODE)
        }
    }

    fun download(context: Context, update: AppUpdate): File {
        val file = File(context.cacheDir, "updates/sy.apk").apply { parentFile!!.mkdirs() }
        get(update.apkUrl).use { response ->
            if (!response.isSuccessful) throw IOException("GitHub respondió ${response.code}")
            file.outputStream().use { response.body!!.byteStream().copyTo(it) }
        }
        return file
    }

    /** Android muestra su propia confirmación y rechaza un archivo con otra firma. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
