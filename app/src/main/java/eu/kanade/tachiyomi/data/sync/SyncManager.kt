package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_ALL
import eu.kanade.tachiyomi.data.backup.BackupHolder
import eu.kanade.tachiyomi.data.backup.BackupManager
import eu.kanade.tachiyomi.data.backup.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.models.SData
import eu.kanade.tachiyomi.data.sync.models.SyncDevice
import eu.kanade.tachiyomi.data.sync.models.SyncStatus
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.gzip
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.mangaMapper
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },

) {
    private val backupManager: BackupManager = BackupManager(context)
    private val notifier: SyncNotifier = SyncNotifier(context)

    enum class SyncService(val value: Int) {
        NONE(0),
        GOOGLE_DRIVE(1),
        SELF_HOSTED(2),
        ;

        companion object {
            fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with the remote server.
     *
     * This function retrieves local data (favorites, manga, extensions, and categories),
     * then sends a request to the remote server to synchronize the data.
     * The local data is prepared by calling functions like `mangaList()`,
     * `getExtensionInfo()`, and `getCategories()`.
     * The server URL and API key are read from sync preferences.
     * The data is sent to the server by calling `syncDataWithServer()`.
     *
     * This function should be called when you want to synchronize local library
     * data with a remote server. It is designed to be used with a coroutine
     * as it is marked as a suspend function.
     */
    suspend fun syncData() {
        val databaseManga = getAllMangaFromDB()

        logcat(LogPriority.DEBUG) { "Mangas to sync: $databaseManga" }

        val backup = Backup(
            backupManager.backupMangas(databaseManga, BACKUP_ALL),
            backupManager.backupCategories(BACKUP_ALL),
            emptyList(),
            backupManager.backupExtensionInfo(databaseManga),
        )

        when (val syncService = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.GOOGLE_DRIVE -> {
                syncDataWithServer(
                    SyncService.GOOGLE_DRIVE,
                    null,
                    null,
                    backup,
                )
            }

            SyncService.SELF_HOSTED -> {
                val host = syncPreferences.syncHost().get()
                val apiKey = syncPreferences.syncAPIKey().get()
                val url = "$host/api/sync/data"

                syncDataWithServer(
                    SyncService.SELF_HOSTED,
                    url,
                    apiKey,
                    backup,
                )
            }

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
            }
        }
    }

    /**
     * Sends a sync request to the server containing backup data.
     *
     * This function constructs the required objects (SyncStatus, SyncDevice, and SData)
     * using the given input parameters, converts the SData object to a JSON string,
     * and sends the JSON data to the server or uploads it to Google Drive depending on the sync service.
     *
     * @param syncService The sync service type (Google Drive or Self-hosted).
     * @param url The server URL to send the sync request to (only required for Self-hosted sync).
     * @param apiKey The API key for authentication (only required for Self-hosted sync).
     * @param backup A Backup object containing the manga, categories, and extensions data to be synced.
     */
    private suspend fun syncDataWithServer(
        syncService: SyncService,
        url: String?,
        apiKey: String?,
        backup: Backup,
    ) {
        // Create the SyncStatus object
        val syncStatus = SyncStatus(
            lastSynced = Instant.now().toString(),
            status = "completed",
        )

        // Create the Device object
        val device = SyncDevice(
            id = syncPreferences.deviceID().get(),
            name = syncPreferences.deviceName().get(),
        )

        // Create the SyncData object
        val syncData = SData(
            sync = syncStatus,
            backup = backup,
            device = device,
        )

        // Convert the SyncData object to a JSON string
        val jsonData = json.encodeToString(syncData)

        // Handle sync based on the selected service
        when (syncService) {
            SyncService.GOOGLE_DRIVE -> {
                val googleDriveSync = GoogleDriveSync(context)
                val syncWithGdrive = googleDriveSync.uploadToGoogleDrive(jsonData)
                if (syncWithGdrive != null) {
                    sendSyncData(
                        url = "",
                        apiKey = "",
                        jsonData = syncWithGdrive,
                        storageType = "GoogleDrive",
                    )
                }
            }

            SyncService.SELF_HOSTED -> {
                if (url != null && apiKey != null) {
                    sendSyncData(url, apiKey, jsonData)
                }
            }

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
            }
        }
    }

    /**
     * Sends the sync data to the server as a compressed Gzip or uploads it to Google Drive.
     *
     * This function prepares an HTTP POST request with the given URL, API key, and JSON data for the server
     * or uploads the JSON data to Google Drive depending on the storageType.
     * The JSON data is sent in the request body, and the API key is added as a header for server requests.
     * After sending the data, the function updates the local data if required.
     *
     * @param url The server URL to send the sync data to.
     * @param apiKey The API key for authentication.
     * @param jsonData The JSON string containing the sync data.
     * @param storageType The storage type to use for sync (e.g., "GoogleDrive"). Defaults to null for server sync.
     */
    private suspend fun sendSyncData(
        url: String,
        apiKey: String,
        jsonData: String,
        storageType: String? = null,
    ) {
        if (storageType == "GoogleDrive") {
            val googleDriveSync = GoogleDriveSync(context)
            val combinedJsonData = googleDriveSync.uploadToGoogleDrive(jsonData)
            if (combinedJsonData != null) {
                val backup = decodeSyncBackup(combinedJsonData)
                BackupHolder.backup = backup
                BackupRestoreJob.start(context, "".toUri(), true)
                syncPreferences.syncLastSync().set(Instant.now())
            }
        } else {
            val client = OkHttpClient()
            val mediaType = "application/gzip".toMediaTypeOrNull()
            val body = jsonData.toRequestBody(mediaType).gzip()

            val headers = Headers.Builder()
                .add("Content-Type", "application/gzip")
                .add("Content-Encoding", "gzip")
                .add("X-API-Token", apiKey)
                .build()

            val request = POST(
                url = url,
                headers = headers,
                body = body,
            )

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()

                if (response.isSuccessful) {
                    val syncDataResponse: SData = json.decodeFromString(responseBody)

                    // TODO: BackupRestoreJob here after getting the response from the server (also fix the server)
                    syncDataResponse.sync?.lastSyncedEpoch?.let {
                        Instant.ofEpochMilli(it)
                    }?.let { syncPreferences.syncLastSync().set(it) }
                    syncPreferences.syncLastSync().set(Instant.now())

                    // If the device ID is 0 and not equal to the server device ID (this happens when the DB is fresh and the app is not), update it
                    if (syncPreferences.deviceID().get() == 0 || syncPreferences.deviceID()
                        .get() != syncDataResponse.device?.id
                    ) {
                        syncDataResponse.device?.id?.let { syncPreferences.deviceID().set(it) }
                    }

                    logcat(
                        LogPriority.INFO,
                        null,
                    ) { "Local data is up to date! Not syncing!" }
                } else {
                    notifier.showSyncError("Failed to sync: error copied to clipboard")
                    responseBody.let { logcat(LogPriority.ERROR) { "SyncError:$it" } }
                    responseBody.let { context.copyToClipboard("sync_error", it) }
                }
            }
        }
    }

    /**
     * Decodes the given sync data string into a Backup object.
     *
     * @param data The sync data string to be decoded.
     * @return The decoded Backup object.
     */
    private fun decodeSyncBackup(data: String): Backup {
        val sData = json.decodeFromString(SData.serializer(), data)
        return sData.backup!!
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDB(): List<Manga> {
        return handler.awaitList { mangasQueries.getAllManga(mangaMapper) }
    }
}
