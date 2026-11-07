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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.UUID

object GoogleDriveBackup {
    private const val PREFS = "map_assist"
    private const val FOLDER_NAME = "Map Assist"
    private const val BACKUP_NAME = "map_assist_backup.csv"
    // The name makes it clear that this is the live shared library, rather than
    // one of the safe-to-copy local exports in the same Drive folder.
    private const val SHARED_LIBRARY_NAME = "DO NOT DELETE MAP ASSIST SHARE.csv"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val WEEKLY_WORK = "weekly_google_drive_backup"
    private const val LAUNCH_CHECK_WORK = "google_drive_launch_check"
    private const val SHARED_SYNC_WORK = "shared_google_drive_sync"
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

    /** Stops legacy periodic personal-backup jobs left behind by older app versions. */
    fun disableLegacyWeeklyBackups(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_WORK)
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

    fun isSharedLibraryConfigured(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("shared_library_file_id", null) != null

    fun isSharedAutoSyncEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("shared_library_auto_sync", false)

    /** Lightweight state used by the app bar while shared-library work runs. */
    fun sharedSyncState(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("shared_library_state", "").orEmpty()

    private fun setSharedSyncState(context: Context, state: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("shared_library_state", state)
            .apply()
    }

    fun markSharedSyncFailed(context: Context) = setSharedSyncState(context, "failed")

    fun setSharedAutoSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("shared_library_auto_sync", enabled).apply()
    }

    fun disconnectSharedLibrary(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SHARED_SYNC_WORK)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("shared_library_file_id")
            .remove("shared_library_last_sync")
            .remove("shared_library_state")
            .remove("shared_library_auto_sync")
            .apply()
    }

    /** Queues an upload/download after a local save. Local storage always remains the first save target. */
    fun queueAutomaticSharedSync(context: Context) {
        if (!hasConnectedAccount(context) || !isSharedLibraryConfigured(context) || !isSharedAutoSyncEnabled(context)) return
        setSharedSyncState(context, "pending")
        val request = OneTimeWorkRequestBuilder<SharedLibrarySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SHARED_SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun createSharedLibrary(context: Context): String = withContext(Dispatchers.IO) {
        setSharedSyncState(context, "syncing")
        try {
            val drive = authenticatedDrive(context)
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val folderId = preferences.getString("drive_folder_id", null)
                ?.takeIf { drive.fileExists(it) }
                ?: drive.findFolder(FOLDER_NAME)
                ?: drive.createFolder(FOLDER_NAME)
            val fileId = drive.uploadCsv(
                null, folderId,
                BackupCsv.create(AppDatabase.getDatabase(context).textFileDao().getAllForBackup(), LedgerStore.read(context)),
                SHARED_LIBRARY_NAME
            )
            preferences.edit()
                .putString("shared_library_file_id", fileId)
                .putBoolean("shared_library_auto_sync", true)
                .apply()
            markSharedSynced(context, drive.modifiedTime(fileId) ?: System.currentTimeMillis())
            WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_WORK)
            "https://drive.google.com/open?id=$fileId"
        } catch (error: Throwable) {
            markSharedSyncFailed(context)
            throw error
        }
    }

    suspend fun connectSharedLibrary(context: Context, linkOrId: String): RemoteBackup = withContext(Dispatchers.IO) {
        val fileId = extractDriveId(linkOrId)
        require(fileId.isNotBlank()) { "Enter a valid Google Drive sharing link" }
        val drive = authenticatedDrive(context)
        check(drive.fileExists(fileId)) { "The shared library could not be opened. Check that it has been shared with this Google account." }
        val modified = drive.modifiedTime(fileId) ?: error("Could not read the shared library")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("shared_library_file_id", fileId)
            .putBoolean("shared_library_auto_sync", false)
            .putString("shared_library_state", "initial")
            .apply()
        RemoteBackup(fileId, modified)
    }

    suspend fun restoreSharedLibrary(context: Context, remote: RemoteBackup? = null): String = withContext(Dispatchers.IO) {
        setSharedSyncState(context, "syncing")
        try {
            val drive = authenticatedDrive(context)
            val fileId = remote?.id ?: requireNotNull(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("shared_library_file_id", null)) { "No shared library connected" }
            if (!drive.fileExists(fileId)) return@withContext markSharedMissing(context)
            val restored = BackupCsv.parseBundle(drive.downloadCsv(fileId))
            check(restored.files.isNotEmpty() || restored.assignments.isNotEmpty()) { "Shared library contains no Map Assist data" }
            AppDatabase.getDatabase(context).textFileDao().replaceAll(restored.files)
            LedgerStore.replace(context, restored.assignments, notifySharedSync = false)
            markSharedSynced(context, drive.modifiedTime(fileId) ?: System.currentTimeMillis())
            "Shared library downloaded"
        } catch (error: Throwable) {
            markSharedSyncFailed(context)
            throw error
        }
    }

    suspend fun uploadSharedLibrary(context: Context): String = withContext(Dispatchers.IO) {
        setSharedSyncState(context, "syncing")
        try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fileId = requireNotNull(preferences.getString("shared_library_file_id", null)) { "No shared library connected" }
            val drive = authenticatedDrive(context)
            if (!drive.fileExists(fileId)) return@withContext markSharedMissing(context)
            val files = AppDatabase.getDatabase(context).textFileDao().getAllForBackup()
            drive.uploadCsv(fileId, "", BackupCsv.create(files, LedgerStore.read(context)), SHARED_LIBRARY_NAME)
            markSharedSynced(context, drive.modifiedTime(fileId) ?: System.currentTimeMillis())
            "Shared library updated"
        } catch (error: Throwable) {
            markSharedSyncFailed(context)
            throw error
        }
    }

    /** Resolves a queued change without overwriting changes made remotely since the last successful sync. */
    suspend fun syncSharedLibrary(context: Context): String = withContext(Dispatchers.IO) {
        setSharedSyncState(context, "syncing")
        try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fileId = requireNotNull(preferences.getString("shared_library_file_id", null)) { "No shared library connected" }
            val lastSync = preferences.getLong("shared_library_last_sync", 0L)
            if (lastSync == 0L) {
                setSharedSyncState(context, "initial")
                return@withContext "Shared library needs an initial download or upload"
            }
            val drive = authenticatedDrive(context)
            if (!drive.fileExists(fileId)) return@withContext markSharedMissing(context)
            val remoteModified = drive.modifiedTime(fileId) ?: error("Could not read the shared library")
            val localModified = maxOf(AppDatabase.getDatabase(context).textFileDao().latestModified() ?: 0L, LedgerStore.lastModified(context))
            val remoteChanged = remoteModified > lastSync
            val localChanged = localModified > lastSync
            when {
                remoteChanged && localChanged -> {
                    setSharedSyncState(context, "conflict")
                    "Shared library needs attention"
                }
                remoteChanged -> restoreSharedLibrary(context, RemoteBackup(fileId, remoteModified))
                localChanged -> uploadSharedLibrary(context)
                else -> {
                    markSharedSynced(context, lastSync)
                    "Shared library is up to date"
                }
            }
        } catch (error: Throwable) {
            markSharedSyncFailed(context)
            throw error
        }
    }

    private fun markSharedSynced(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("shared_library_last_sync", timestamp)
            .putString("shared_library_state", "synced")
            .apply()
    }

    /** Never recreate a shared file automatically: only its owner may explicitly create a replacement. */
    private fun markSharedMissing(context: Context): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("shared_library_state", "missing")
            .apply()
        return "Shared library file is missing from Google Drive"
    }

    private fun authenticatedDrive(context: Context): DriveRest {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Connect a Google account first")
        check(GoogleSignIn.hasPermissions(account, driveScope)) { "Google Drive access has not been granted" }
        val token = GoogleAuthUtil.getToken(context, requireNotNull(account.account), "oauth2:$DRIVE_SCOPE")
        return DriveRest(token)
    }

    private fun extractDriveId(value: String): String {
        val text = value.trim()
        Regex("/d/([A-Za-z0-9_-]+)").find(text)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("[?&]id=([A-Za-z0-9_-]+)").find(text)?.groupValues?.getOrNull(1)?.let { return it }
        return text.takeIf { Regex("^[A-Za-z0-9_-]{10,}$").matches(it) }.orEmpty()
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
        val restored = BackupCsv.parseBundle(DriveRest(token).downloadCsv(remote.id))
        check(restored.files.isNotEmpty()) { "Google Drive backup contains no DNC cards" }
        AppDatabase.getDatabase(context).textFileDao().replaceAll(restored.files)
        LedgerStore.replace(context, restored.assignments, notifySharedSync = false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("drive_backup_file_id", remote.id)
            .putLong("drive_last_backup", System.currentTimeMillis())
            .putBoolean("drive_backup_newer", false)
            .apply()
        setSyncConfigured(context)
        "Restored ${restored.files.size} DNC cards from Google Drive"
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
        val updated = drive.uploadCsv(fileId, folderId, BackupCsv.create(files, LedgerStore.read(context)))
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
        val localModified = maxOf(AppDatabase.getDatabase(context).textFileDao().latestModified() ?: 0L, LedgerStore.lastModified(context))
        // A successful upload naturally makes the Drive file newer than the most
        // recent card edit. Only warn when it changed *after this device synced.
        val lastSynced = preferences.getLong("drive_last_backup", 0L)
        val newer = remoteModified > maxOf(localModified, lastSynced)
        preferences.edit().putBoolean("drive_backup_newer", newer).apply()
        newer
    }

    private class DriveRest(private val token: String) {
        fun fileExists(id: String): Boolean {
            val response = request("GET", "https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed", null)
            if (response.code != 200) return false
            // Google Drive keeps deleted files in the bin. A trashed shared CSV
            // must be treated as unavailable rather than patched in place.
            return !JSONObject(response.body).optBoolean("trashed", false)
        }

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

        fun uploadCsv(existingId: String?, folderId: String, csv: String, fileName: String = BACKUP_NAME): String {
            val boundary = "MapAssistBoundary"
            val metadata = JSONObject().put("name", fileName).put("mimeType", "text/csv")
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
            return runCatching { parseDriveTime(JSONObject(response.body).getString("modifiedTime")) }.getOrNull()
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

        private fun parseDriveTime(value: String): Long {
            val withoutFraction = value.replace(Regex("\\.\\d+(?=Z$)"), "")
            return requireNotNull(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(withoutFraction)).time
        }
    }

    private data class HttpResult(val code: Int, val body: String)
}

class GoogleDriveCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching { GoogleDriveBackup.checkForNewerBackup(applicationContext) }
        .fold(onSuccess = { newer -> Result.success(androidx.work.workDataOf("newer" to newer)) }, onFailure = { Result.retry() })
}

class SharedLibrarySyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching { GoogleDriveBackup.syncSharedLibrary(applicationContext) }
        .fold(onSuccess = { Result.success() }, onFailure = {
            GoogleDriveBackup.markSharedSyncFailed(applicationContext)
            Result.retry()
        })
}
