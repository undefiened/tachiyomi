package eu.kanade.tachiyomi.data.sync
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.models.SData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class GoogleDriveSync(private val context: Context) {
    private val syncPreferences = Injekt.get<SyncPreferences>()
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var googleDriveService: Drive? = null

    init {
        initGoogleDriveService()
    }

    /**
     * Initializes the Google Drive service by obtaining the access token and refresh token from the SyncPreferences
     * and setting up the service using the obtained tokens.
     */
    private fun initGoogleDriveService() {
        val accessToken = syncPreferences.getGoogleDriveAccessToken()
        val refreshToken = syncPreferences.getGoogleDriveRefreshToken()

        if (accessToken == "" || refreshToken == "") {
            googleDriveService = null
            return
        }

        setupGoogleDriveService(accessToken, refreshToken)
    }

    /**
     * Generates the authorization URL required for the user to grant the application permission to access their Google Drive account.
     * Sets the approval prompt to "force" to ensure that the user is always prompted to grant access, even if they have previously granted access.
     * @return The authorization URL.
     */
    private fun generateAuthorizationUrl(): String {
        val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
        val secrets = GoogleClientSecrets.load(
            jsonFactory,
            InputStreamReader(context.assets.open("client_secrets.json")),
        )

        val flow = GoogleAuthorizationCodeFlow.Builder(
            NetHttpTransport(),
            jsonFactory,
            secrets,
            listOf(DriveScopes.DRIVE_FILE),
        ).setAccessType("offline").build()

        return flow.newAuthorizationUrl()
            .setRedirectUri("http://127.0.0.1:53682/auth")
            .setApprovalPrompt("force")
            .build()
    }

    /**
     * Launches a Custom Tab with the authorization URL to allow the user to sign in and grant the application permission to access their Google Drive account.
     * Also returns an OAuthCallbackServer to listen for the authorization code.
     * @param onCallback A callback function to listen for the authorization code.
     * @return An OAuthCallbackServer.
     */
    fun getSignInIntent(onCallback: (String) -> Unit): OAuthCallbackServer {
        val oAuthCallbackServer = Injekt.get<OAuthCallbackServer>().apply {
            setOnCallbackListener(onCallback)
        }

        val authorizationUrl = generateAuthorizationUrl()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        customTabsIntent.launchUrl(context, Uri.parse(authorizationUrl))

        return oAuthCallbackServer
    }

    /**
     * Sets up the Google Drive service using the provided access token and refresh token.
     * @param accessToken The access token obtained from the SyncPreferences.
     * @param refreshToken The refresh token obtained from the SyncPreferences.
     */
    private fun setupGoogleDriveService(accessToken: String, refreshToken: String) {
        val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
        val secrets = GoogleClientSecrets.load(
            jsonFactory,
            InputStreamReader(context.assets.open("client_secrets.json")),
        )

        val credential = GoogleCredential.Builder()
            .setJsonFactory(jsonFactory)
            .setTransport(NetHttpTransport())
            .setClientSecrets(secrets)
            .build()

        credential.accessToken = accessToken
        credential.refreshToken = refreshToken

        googleDriveService = Drive.Builder(
            NetHttpTransport(),
            jsonFactory,
            credential,
        ).setApplicationName("Tachiyomi")
            .build()
    }

    /**
     * Handles the authorization code returned after the user has granted the application permission to access their Google Drive account.
     * It obtains the access token and refresh token using the authorization code, saves the tokens to the SyncPreferences,
     * sets up the Google Drive service using the obtained tokens, and initializes the service.
     * @param authorizationCode The authorization code obtained from the OAuthCallbackServer.
     * @param activity The current activity.
     * @param onSuccess A callback function to be called on successful authorization.
     * @param onFailure A callback function to be called on authorization failure.
     */
    fun handleAuthorizationCode(authorizationCode: String, activity: Activity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
        val secrets = GoogleClientSecrets.load(
            jsonFactory,
            InputStreamReader(context.assets.open("client_secrets.json")),
        )

        val tokenResponse: GoogleTokenResponse = GoogleAuthorizationCodeTokenRequest(
            NetHttpTransport(),
            jsonFactory,
            secrets.web.clientId,
            secrets.web.clientSecret,
            authorizationCode,
            "http://127.0.0.1:53682/auth",
        ).setGrantType("authorization_code").execute()

        try {
            // Save the access token and refresh token
            val accessToken = tokenResponse.accessToken
            val refreshToken = tokenResponse.refreshToken

            // Save the tokens to SyncPreferences
            syncPreferences.setGoogleDriveAccessToken(accessToken)
            syncPreferences.setGoogleDriveRefreshToken(refreshToken)

            setupGoogleDriveService(accessToken, refreshToken)
            initGoogleDriveService()

            activity.runOnUiThread {
                onSuccess()
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                onFailure(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    suspend fun refreshToken() = withContext(Dispatchers.IO) {
        val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
        val secrets = GoogleClientSecrets.load(
            jsonFactory,
            InputStreamReader(context.assets.open("client_secrets.json")),
        )

        val credential = GoogleCredential.Builder()
            .setJsonFactory(jsonFactory)
            .setTransport(NetHttpTransport())
            .setClientSecrets(secrets)
            .build()

        credential.refreshToken = syncPreferences.getGoogleDriveRefreshToken()

        logcat(LogPriority.DEBUG) { "Refreshing access token with: ${syncPreferences.getGoogleDriveRefreshToken()}" }

        try {
            credential.refreshToken()
            val newAccessToken = credential.accessToken
            val oldAccessToken = syncPreferences.getGoogleDriveAccessToken()
            // Save the new access token
            syncPreferences.setGoogleDriveAccessToken(newAccessToken)
            setupGoogleDriveService(newAccessToken, credential.refreshToken)
            logcat(LogPriority.DEBUG) { "Google Access token refreshed old: $oldAccessToken new: $newAccessToken" }
        } catch (e: TokenResponseException) {
            if (e.details.error == "invalid_grant") {
                // The refresh token is invalid, prompt the user to sign in again
                logcat(LogPriority.ERROR) { "Refresh token is invalid, prompt user to sign in again" }
                throw e.message?.let { Exception(it) } ?: Exception("Unknown error")
            } else {
                // Token refresh failed; handle this situation
                logcat(LogPriority.ERROR) { "Failed to refresh access token ${e.message}" }
                logcat(LogPriority.ERROR) { "Google Drive sync will be disabled" }
            }
        } catch (e: IOException) {
            // Token refresh failed; handle this situation
            logcat(LogPriority.ERROR) { "Failed to refresh access token ${e.message}" }
            logcat(LogPriority.ERROR) { "Google Drive sync will be disabled" }
        }
    }

    suspend fun deleteSyncDataFromGoogleDrive(): Boolean {
        val fileName = "tachiyomi_sync_data.gz"
        val drive = googleDriveService

        if (drive == null) {
            logcat(LogPriority.ERROR) { "Google Drive service not initialized" }
            return false
        }
        refreshToken()

        return withContext(Dispatchers.IO) {
            val query = "mimeType='application/gzip' and trashed = false and name = '$fileName'"
            val fileList = drive.files().list().setQ(query).execute().files

            if (fileList.isNullOrEmpty()) {
                logcat(LogPriority.DEBUG) { "No sync data file found in Google Drive" }
                false
            } else {
                val fileId = fileList[0].id
                drive.files().delete(fileId).execute()
                logcat(LogPriority.DEBUG) { "Deleted sync data file in Google Drive with file ID: $fileId" }
                true
            }
        }
    }

    /**
     * Downloads a file from Google Drive given its file ID.
     *
     * @param fileId The ID of the file to be downloaded from Google Drive.
     * @return The content of the downloaded file as a string.
     */
    private suspend fun downloadFromGoogleDrive(fileId: String): String {
        val drive = googleDriveService

        if (drive == null) {
            SyncNotifier(context).showSyncError("Failed to sync: error copied to clipboard")
            logcat(LogPriority.ERROR) { "Google Drive service not initialized" }
            return ""
        }

        val outputStream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        return withContext(Dispatchers.IO) {
            val gzipInputStream = GZIPInputStream(ByteArrayInputStream(outputStream.toByteArray()))
            gzipInputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /**
     * Uploads sync data to Google Drive. If a file with the same name already exists,
     * this function will merge the local and remote data before updating the existing file.
     *
     * @param jsonData The JSON string containing the sync data to upload.
     * @return The JSON string containing the combined local and remote sync data, or null if the Google Drive service is not initialized.
     */
    suspend fun uploadToGoogleDrive(jsonData: String): String? {
        val fileName = "tachiyomi_sync_data.gz"

        val drive = googleDriveService

        // Check if the Google Drive service is initialized
        if (drive == null) {
            logcat(LogPriority.ERROR) { "Google Drive service not initialized" }
            return null
        }

        // Search for the existing file by name
        val query = "mimeType='application/gzip' and trashed = false and name = '$fileName'"
        val fileList = drive.files().list().setQ(query).execute().files
        Log.d("GoogleDrive", "File list: $fileList")

        val combinedJsonData: String

        if (fileList.isEmpty()) {
            // If the file doesn't exist, create a new file with the data
            val fileMetadata = File()
            fileMetadata.name = fileName
            fileMetadata.mimeType = "application/gzip"

            val byteArrayOutputStream = ByteArrayOutputStream()

            withContext(Dispatchers.IO) {
                val gzipOutputStream = GZIPOutputStream(byteArrayOutputStream)
                gzipOutputStream.write(jsonData.toByteArray(Charsets.UTF_8))
                gzipOutputStream.close()
            }

            val byteArrayContent = ByteArrayContent("application/octet-stream", byteArrayOutputStream.toByteArray())
            val uploadedFile = drive.files().create(fileMetadata, byteArrayContent)
                .setFields("id")
                .execute()

            combinedJsonData = jsonData

            logcat(LogPriority.DEBUG) { "Created sync data file in Google Drive with file ID: ${uploadedFile.id}" }
        } else {
            val gdriveFileId = fileList[0].id

            // Download the existing data from Google Drive
            val existingData = downloadFromGoogleDrive(gdriveFileId)
            // Merge the local and remote data
            combinedJsonData = mergeLocalAndRemoteData(jsonData, existingData)

            // Compress the combined JSON data using GZIP
            val byteArrayOutputStream = ByteArrayOutputStream()

            withContext(Dispatchers.IO) {
                val gzipOutputStream = GZIPOutputStream(byteArrayOutputStream)
                gzipOutputStream.write(combinedJsonData.toByteArray(Charsets.UTF_8))
                gzipOutputStream.close()
            }

            // Update the file with the compressed data
            val byteArrayContent = ByteArrayContent("application/octet-stream", byteArrayOutputStream.toByteArray())
            drive.files().delete(gdriveFileId).execute()

            val fileMetadata = File()
            fileMetadata.name = fileName
            fileMetadata.mimeType = "application/gzip"

            val newFile = drive.files().create(fileMetadata, byteArrayContent)
                .setFields("id")
                .execute()

            logcat(LogPriority.DEBUG) { "Updated sync data file in Google Drive with file ID: ${newFile.id}" }
        }

        return combinedJsonData
    }

    /**
     * Merges the local and remote sync data into a single JSON string.
     *
     * @param localJsonData The JSON string containing the local sync data.
     * @param remoteJsonData The JSON string containing the remote sync data.
     * @return The JSON string containing the merged sync data.
     */
    private fun mergeLocalAndRemoteData(localJsonData: String, remoteJsonData: String): String {
        val localSyncData: SData = json.decodeFromString(localJsonData)
        val remoteSyncData: SData = json.decodeFromString(remoteJsonData)

        val mergedMangaList = mergeMangaLists(localSyncData.backup?.backupManga, remoteSyncData.backup?.backupManga)
        val mergedCategoriesList = mergeCategoriesLists(localSyncData.backup?.backupCategories, remoteSyncData.backup?.backupCategories)

        // Create the merged Backup object
        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedCategoriesList,
            backupBrokenSources = localSyncData.backup?.backupBrokenSources ?: emptyList(),
            backupSources = localSyncData.backup?.backupSources ?: emptyList(),
        )

        // Create the merged SData object
        val mergedSyncData = SData(
            sync = localSyncData.sync, // always use the local sync info
            backup = mergedBackup,
            device = localSyncData.device, // always use the local device info
        )

        return json.encodeToString(mergedSyncData)
    }

    /**
     * Merges two lists of SyncManga objects, prioritizing the manga with the most recent lastModifiedAt value.
     * If lastModifiedAt is null, the function defaults to Instant.MIN for comparison purposes.
     *
     * @param localMangaList The list of local SyncManga objects.
     * @param remoteMangaList The list of remote SyncManga objects.
     * @return The merged list of SyncManga objects.
     */
    private fun mergeMangaLists(localMangaList: List<BackupManga>?, remoteMangaList: List<BackupManga>?): List<BackupManga> {
        if (localMangaList == null) return remoteMangaList ?: emptyList()
        if (remoteMangaList == null) return localMangaList

        val localMangaMap = localMangaList.associateBy { Pair(it.source, it.url) }
        val remoteMangaMap = remoteMangaList.associateBy { Pair(it.source, it.url) }

        val mergedMangaMap = mutableMapOf<Pair<Long, String>, BackupManga>()

        localMangaMap.forEach { (key, localManga) ->
            val remoteManga = remoteMangaMap[key]
            if (remoteManga != null) {
                val localInstant = localManga.lastModifiedAt?.let { Instant.ofEpochMilli(it) }
                val remoteInstant = remoteManga.lastModifiedAt?.let { Instant.ofEpochMilli(it) }

                val mergedManga = if ((localInstant ?: Instant.MIN) >= (remoteInstant ?: Instant.MIN)) {
                    localManga
                } else {
                    remoteManga
                }

                val localChapters = localManga.chapters
                val remoteChapters = remoteManga.chapters
                val mergedChapters = mergeChapters(localChapters, remoteChapters)

                val isFavorite = if ((localInstant ?: Instant.MIN) >= (remoteInstant ?: Instant.MIN)) {
                    localManga.favorite
                } else {
                    remoteManga.favorite
                }

                mergedMangaMap[key] = mergedManga.copy(chapters = mergedChapters, favorite = isFavorite)
            } else {
                mergedMangaMap[key] = localManga
            }
        }

        remoteMangaMap.forEach { (key, remoteManga) ->
            if (!mergedMangaMap.containsKey(key)) {
                mergedMangaMap[key] = remoteManga
            }
        }

        return mergedMangaMap.values.toList()
    }

    /**
     * Merges two lists of SyncChapter objects, prioritizing the chapter with the most recent lastModifiedAt value.
     * If lastModifiedAt is null, the function defaults to Instant.MIN for comparison purposes.
     *
     * @param localChapters The list of local SyncChapter objects.
     * @param remoteChapters The list of remote SyncChapter objects.
     * @return The merged list of SyncChapter objects.
     */
    private fun mergeChapters(localChapters: List<BackupChapter>, remoteChapters: List<BackupChapter>): List<BackupChapter> {
        val localChapterMap = localChapters.associateBy { it.url }
        val remoteChapterMap = remoteChapters.associateBy { it.url }
        val mergedChapterMap = mutableMapOf<String, BackupChapter>()

        localChapterMap.forEach { (url, localChapter) ->
            val remoteChapter = remoteChapterMap[url]
            if (remoteChapter != null) {
                val localInstant = localChapter.lastModifiedAt?.let { Instant.ofEpochMilli(it) }
                val remoteInstant = remoteChapter.lastModifiedAt?.let { Instant.ofEpochMilli(it) }

                val mergedChapter =
                    if ((localInstant ?: Instant.MIN) >= (remoteInstant ?: Instant.MIN)) {
                        localChapter
                    } else {
                        remoteChapter
                    }
                mergedChapterMap[url] = mergedChapter
            } else {
                mergedChapterMap[url] = localChapter
            }
        }

        remoteChapterMap.forEach { (url, remoteChapter) ->
            if (!mergedChapterMap.containsKey(url)) {
                mergedChapterMap[url] = remoteChapter
            }
        }

        return mergedChapterMap.values.toList()
    }

    /**
     * Merges two lists of SyncCategory objects, prioritizing the category with the most recent order value.
     *
     * @param localCategoriesList The list of local SyncCategory objects.
     * @param remoteCategoriesList The list of remote SyncCategory objects.
     * @return The merged list of SyncCategory objects.
     */
    private fun mergeCategoriesLists(localCategoriesList: List<BackupCategory>?, remoteCategoriesList: List<BackupCategory>?): List<BackupCategory> {
        if (localCategoriesList == null) return remoteCategoriesList ?: emptyList()
        if (remoteCategoriesList == null) return localCategoriesList
        val localCategoriesMap = localCategoriesList.associateBy { it.name }
        val remoteCategoriesMap = remoteCategoriesList.associateBy { it.name }

        val mergedCategoriesMap = mutableMapOf<String, BackupCategory>()

        localCategoriesMap.forEach { (name, localCategory) ->
            val remoteCategory = remoteCategoriesMap[name]
            if (remoteCategory != null) {
                // Compare and merge local and remote categories
                val mergedCategory = if (localCategory.order >= remoteCategory.order) {
                    localCategory
                } else {
                    remoteCategory
                }
                mergedCategoriesMap[name] = mergedCategory
            } else {
                // If the category is only in the local list, add it to the merged list
                mergedCategoriesMap[name] = localCategory
            }
        }

        // Add any categories from the remote list that are not in the local list
        remoteCategoriesMap.forEach { (name, remoteCategory) ->
            if (!mergedCategoriesMap.containsKey(name)) {
                mergedCategoriesMap[name] = remoteCategory
            }
        }

        return mergedCategoriesMap.values.toList()
    }
}
