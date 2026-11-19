package com.example.mapasssist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks published GitHub releases and hands verified APK updates to Android's installer. */
object GitHubUpdateManager {
    private const val OWNER = "dubeymatt"
    private const val REPOSITORY = "MapAsssist"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"

    data class Release(
        val version: String,
        val notes: String,
        val assetName: String,
        val downloadUrl: String
    )

    suspend fun checkForUpdate(): Release? = withContext(Dispatchers.IO) {
        val release = readLatestRelease()
        if (isNewer(release.version, BuildConfig.VERSION_NAME)) release else null
    }

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val updatesDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(updatesDirectory, "map-assist-${release.version}.apk")
        val partial = File(updatesDirectory, "${destination.name}.download")
        partial.delete()
        try {
            val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Map-Assist-Android")
            }
            connection.inputStream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
            check(partial.length() > 0L) { "The update download was empty" }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "Could not save the update" }
            val archive = @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(destination.path, 0)
            check(archive?.packageName == context.packageName) { "This release is not a Map Assist update" }
            destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun readLatestRelease(): Release {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Map-Assist-Android")
        }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val assets = json.optJSONArray("assets")
        val asset = (0 until (assets?.length() ?: 0))
            .asSequence()
            .mapNotNull { index -> assets?.optJSONObject(index) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: error("The latest GitHub release does not include an APK")
        return Release(
            version = json.optString("tag_name").removePrefix("v").trim(),
            notes = json.optString("body").trim(),
            assetName = asset.optString("name"),
            downloadUrl = asset.getString("browser_download_url")
        )
    }

    private fun isNewer(candidate: String, installed: String): Boolean {
        fun components(value: String): List<Int> = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val remote = components(candidate)
        val local = components(installed)
        val length = maxOf(remote.size, local.size)
        return (0 until length).firstOrNull { index ->
            (remote.getOrElse(index) { 0 }) != (local.getOrElse(index) { 0 })
        }?.let { index -> remote.getOrElse(index) { 0 } > local.getOrElse(index) { 0 } } ?: false
    }
}
