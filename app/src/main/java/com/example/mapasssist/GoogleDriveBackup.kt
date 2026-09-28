package com.example.mapasssist

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mapasssist.data.AppDatabase
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID

object GoogleDriveBackup {
    private const val PREFS = "map_assist"
    private const val FOLDER_NAME = "Map Assist"
    private const val BACKUP_NAME = "map_assist_backup.csv"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val WEEKLY_WORK = "weekly_google_drive_backup"
    private const val LAUNCH_CHECK_WORK = "google_drive_launch_check"
    private val driveScope = Scope(DRIVE_SCOPE)

    data class RemoteBackup(val id: String, val modifiedTime: Long)

    fun signInIntent(context: Context): Intent {
        return client(context).signInIntent
    }

    fun disconnect(context: Context) = client(context).signOut()

    private fun client(context: Context) = GoogleSignIn.getClient(context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
    )

    fun hasConnectedAccount(context: Context): Boolean = GoogleSignIn.getLastSignedInAccount(context)
        ?.let { GoogleSignIn.hasPermissions(it, driveScope) } == true

    fun scheduleWeekly(context: Context) {
        if (!hasConnectedAccount(context) || !isSyncConfigured(context)) return
        val request = PeriodicWorkRequestBuilder<GoogleDriveBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WEEKLY_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueLaunchCheck(context: Context): UUID? {
        if (!hasConnectedAccount(context) || !isSyncConfigured(context)) return null
        val request = OneTimeWorkRequestBuilder<GoogleDriveCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LAUNCH_CHECK_WORK, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun clearConnection(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(LAUNCH_CHECK_WORK)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("drive_folder_id")
            .remove("drive_backup_file_id")
            .remove("drive_last_backup")
            .remove("drive_backup_newer")
            .remove("drive_sync_configured")
            .apply()
    }

    fun isSyncConfigured(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Keep existing connected installations working after this update.
        return preferences.getBoolean("drive_sync_configured", false) ||
            preferences.getLong("drive_last_backup", 0L) > 0L
    }

    private fun setSyncConfigured(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("drive_sync_configured", true)
            .apply()
    }

    /** Finds a backup without creating or changing anything in Google Drive. */
    suspend fun findExistingBackup(context: Context): RemoteBackup? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Connect a Google account first")
        check(GoogleSignIn.hasPermissions(account, driveScope)) { "Google Drive access has not been granted" }
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        val drive = DriveRest(token)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val folderId = preferences.getString("drive_folder_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFolder(FOLDER_NAME)
            ?: return@withContext null
        val fileId = preferences.getString("drive_backup_file_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFile(BACKUP_NAME, folderId)
            ?: return@withContext null
        val modifiedTime = drive.modifiedTime(fileId) ?: return@withContext null
        preferences.edit().putString("drive_folder_id", folderId).putString("drive_backup_file_id", fileId).apply()
        RemoteBackup(fileId, modifiedTime)
    }

    suspend fun restore(context: Context, backup: RemoteBackup? = null): String = withContext(Dispatchers.IO) {
        val remote = backup ?: findExistingBackup(context) ?: error("No Google Drive backup was found")
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Connect a Google account first")
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        val restored = BackupCsv.parse(DriveRest(token).downloadCsv(remote.id))
        check(restored.isNotEmpty()) { "Google Drive backup contains no DNC cards" }
        AppDatabase.getDatabase(context).textFileDao().replaceAll(restored)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("drive_backup_file_id", remote.id)
            .putLong("drive_last_backup", System.currentTimeMillis())
            .putBoolean("drive_backup_newer", false)
            .apply()
        setSyncConfigured(context)
        "Restored ${restored.size} DNC cards from Google Drive"
    }

    suspend fun upload(context: Context): String = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Connect a Google account first")
        check(GoogleSignIn.hasPermissions(account, driveScope)) { "Google Drive access has not been granted" }
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        val drive = DriveRest(token)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val folderId = preferences.getString("drive_folder_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFolder(FOLDER_NAME)
            ?: drive.createFolder(FOLDER_NAME)
        preferences.edit().putString("drive_folder_id", folderId).apply()
        val fileId = preferences.getString("drive_backup_file_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFile(BACKUP_NAME, folderId)
        val files = AppDatabase.getDatabase(context).textFileDao().getAllForBackup()
        val updated = drive.uploadCsv(fileId, folderId, BackupCsv.create(files))
        preferences.edit()
            .putString("drive_backup_file_id", updated)
            .putLong("drive_last_backup", System.currentTimeMillis())
            .putBoolean("drive_backup_newer", false)
            .apply()
        setSyncConfigured(context)
        "Backup saved to Google Drive"
    }

    /**
     * Keeps an empty install from replacing an existing Drive backup.  This is
     * deliberately also used by the periodic worker: background work must
     * never be able to erase a non-empty backup just because this device has
     * not been restored yet.
     */
    suspend fun sync(context: Context): String = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Connect a Google account first")
        check(GoogleSignIn.hasPermissions(account, driveScope)) { "Google Drive access has not been granted" }
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        val drive = DriveRest(token)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val folderId = preferences.getString("drive_folder_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFolder(FOLDER_NAME)
            ?: drive.createFolder(FOLDER_NAME)
        preferences.edit().putString("drive_folder_id", folderId).apply()

        val remoteId = preferences.getString("drive_backup_file_id", null)
            ?.takeIf { drive.fileExists(it) }
            ?: drive.findFile(BACKUP_NAME, folderId)
        val database = AppDatabase.getDatabase(context)
        val localFiles = database.textFileDao().getAllForBackup()

        if (localFiles.isEmpty() && remoteId != null) return@withContext restore(context, RemoteBackup(remoteId, drive.modifiedTime(remoteId) ?: 0L))

        upload(context)
    }

    suspend fun checkForNewerBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext false
        if (!GoogleSignIn.hasPermissions(account, driveScope)) return@withContext false
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        val drive = DriveRest(token)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remoteId = preferences.getString("drive_backup_file_id", null)
            ?: preferences.getString("drive_folder_id", null)?.let { drive.findFile(BACKUP_NAME, it) }
            ?: return@withContext false
        val remoteModified = drive.modifiedTime(remoteId) ?: return@withContext false
        val localModified = AppDatabase.getDatabase(context).textFileDao().latestModified() ?: 0L
        // A successful upload naturally makes the Drive file newer than the most
        // recent card edit. Only warn when it changed *after this device synced.
        val lastSynced = preferences.getLong("drive_last_backup", 0L)
        val newer = remoteModified > maxOf(localModified, lastSynced)
        preferences.edit().putBoolean("drive_backup_newer", newer).apply()
        newer
    }

    private class DriveRest(private val token: String) {
        fun fileExists(id: String): Boolean = request("GET", "https://www.googleapis.com/drive/v3/files/$id?fields=id", null).code == 200

        fun findFolder(name: String): String? = find(
            "name='${escapeQuery(name)}' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        )

        fun findFile(name: String, parent: String): String? = find(
            "name='${escapeQuery(name)}' and '$parent' in parents and trashed=false"
        )

        fun createFolder(name: String): String {
            val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()
            return JSONObject(request("POST", "https://www.googleapis.com/drive/v3/files?fields=id", body, "application/json").body).getString("id")
        }

        fun uploadCsv(existingId: String?, folderId: String, csv: String): String {
            val boundary = "MapAssistBoundary"
            val metadata = JSONObject().put("name", BACKUP_NAME).put("mimeType", "text/csv")
            if (existingId == null) metadata.put("parents", org.json.JSONArray().put(folderId))
            val body = buildString {
                append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadata).append("\r\n--$boundary\r\nContent-Type: text/csv\r\n\r\n")
                append(csv).append("\r\n--$boundary--")
            }.toByteArray(StandardCharsets.UTF_8)
            val base = "https://www.googleapis.com/upload/drive/v3/files"
            val url = if (existingId == null) "$base?uploadType=multipart&fields=id" else "$base/$existingId?uploadType=multipart&fields=id"
            val method = if (existingId == null) "POST" else "PATCH"
            return JSONObject(request(method, url, body, "multipart/related; boundary=$boundary").body).getString("id")
        }

        fun downloadCsv(id: String): String {
            val response = request("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", null)
            check(response.code == 200) { "Could not read the Google Drive backup" }
            return response.body
        }

        fun modifiedTime(id: String): Long? {
            val response = request("GET", "https://www.googleapis.com/drive/v3/files/$id?fields=modifiedTime", null)
            if (response.code != 200) return null
            return runCatching { Instant.parse(JSONObject(response.body).getString("modifiedTime")).toEpochMilli() }.getOrNull()
        }

        private fun find(query: String): String? {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = request("GET", "https://www.googleapis.com/drive/v3/files?q=$encoded&fields=files(id)&pageSize=1", null)
            if (response.code != 200) return null
            val files = JSONObject(response.body).getJSONArray("files")
            return if (files.length() == 0) null else files.getJSONObject(0).getString("id")
        }

        private fun request(method: String, endpoint: String, body: ByteArray?, contentType: String? = null): HttpResult {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000
                readTimeout = 20_000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                    outputStream.use { it.write(body) }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299 && code != 404) error("Google Drive error ($code): $response")
            return HttpResult(code, response)
        }

        private fun escapeQuery(value: String) = value.replace("'", "\\'")
    }

    private data class HttpResult(val code: Int, val body: String)
}

class GoogleDriveBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching { GoogleDriveBackup.sync(applicationContext) }
        .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

class GoogleDriveCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching { GoogleDriveBackup.checkForNewerBackup(applicationContext) }
        .fold(onSuccess = { newer -> Result.success(androidx.work.workDataOf("newer" to newer)) }, onFailure = { Result.retry() })
}
