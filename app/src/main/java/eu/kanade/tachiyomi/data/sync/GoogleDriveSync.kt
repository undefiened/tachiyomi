package eu.kanade.tachiyomi.data.sync
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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
import com.google.gson.Gson
import eu.kanade.tachiyomi.data.sync.models.Data
import eu.kanade.tachiyomi.data.sync.models.SData
import eu.kanade.tachiyomi.data.sync.models.SyncCategory
import eu.kanade.tachiyomi.data.sync.models.SyncChapter
import eu.kanade.tachiyomi.data.sync.models.SyncManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.time.Instant

class GoogleDriveSync(private val context: Context) {
    private val syncPreferences = Injekt.get<SyncPreferences>()
    private val gson = Gson()

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
            outputStream.toString(Charsets.UTF_8.name())
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
        val fileName = "tachiyomi_sync_data.json"

        val drive = googleDriveService

        // Check if the Google Drive service is initialized
        if (drive == null) {
            logcat(LogPriority.ERROR) { "Google Drive service not initialized" }
            return null
        }

        // Search for the existing file by name
        val query = "mimeType='text/plain' and trashed = false and name = '$fileName'"
        val fileList = drive.files().list().setQ(query).execute().files

        val combinedJsonData: String

        if (fileList.isNullOrEmpty()) {
            // If the file doesn't exist, create a new one
            val fileMetadata = File()
            fileMetadata.name = fileName
            fileMetadata.mimeType = "text/plain"

            val byteArrayContent = ByteArrayContent.fromString("text/plain", jsonData)
            val uploadedFile = drive.files().create(fileMetadata, byteArrayContent)
                .setFields("id")
                .execute()

            logcat(LogPriority.DEBUG) { "Created sync data file in Google Drive with file ID: ${uploadedFile.id}" }

            // Return the original jsonData since there is no existing data to merge
            combinedJsonData = jsonData
        } else {
            // Download the existing data from Google Drive
            val existingData = downloadFromGoogleDrive(fileList[0].id)
            // Merge the local and remote data
            combinedJsonData = mergeLocalAndRemoteData(jsonData, existingData)

            // Update the existing file with the merged data
            val fileId = fileList[0].id
            val byteArrayContent = ByteArrayContent.fromString("text/plain", combinedJsonData)
            val updatedFile = drive.files().update(fileId, null, byteArrayContent)
                .setFields("id")
                .execute()

            logcat(LogPriority.DEBUG) { "Updated sync data file in Google Drive with file ID: ${updatedFile.id}" }
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
        val localSyncData: SData = gson.fromJson(localJsonData, SData::class.java)
        val remoteSyncData: SData = gson.fromJson(remoteJsonData, SData::class.java)

        val mergedMangaList = mergeMangaLists(localSyncData.data?.manga, remoteSyncData.data?.manga)
        val mergedCategoriesList = mergeCategoriesLists(localSyncData.data?.categories, remoteSyncData.data?.categories)

        // Create the merged Data object
        val mergedData = Data(
            manga = mergedMangaList,
            extensions = localSyncData.data?.extensions, // extensions are not synced
            categories = mergedCategoriesList,
        )

        // Create the merged SyncData object
        val mergedSyncData = SData(
            sync = localSyncData.sync, // always use the local sync info
            data = mergedData,
            device = localSyncData.device, // always use the local device info
        )

        return gson.toJson(mergedSyncData)
    }

    /**
     * Merges two lists of SyncManga objects, prioritizing the manga with the most recent lastModifiedAt value.
     * If lastModifiedAt is null, the function defaults to Instant.MIN for comparison purposes.
     *
     * @param localMangaList The list of local SyncManga objects.
     * @param remoteMangaList The list of remote SyncManga objects.
     * @return The merged list of SyncManga objects.
     */
    private fun mergeMangaLists(localMangaList: List<SyncManga>?, remoteMangaList: List<SyncManga>?): List<SyncManga> {
        if (localMangaList == null) return remoteMangaList ?: emptyList()
        if (remoteMangaList == null) return localMangaList

        val localMangaMap = localMangaList.associateBy { Pair(it.source, it.url) }
        val remoteMangaMap = remoteMangaList.associateBy { Pair(it.source, it.url) }

        val mergedMangaMap = mutableMapOf<Pair<Long?, String?>, SyncManga>()

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

                val localChapters = localManga.chapters ?: emptyList()
                val remoteChapters = remoteManga.chapters ?: emptyList()
                val mergedChapters = mergeChapters(localChapters, remoteChapters)

                val isFavorite = localManga.favorite == true || remoteManga.favorite == true
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
    private fun mergeChapters(localChapters: List<SyncChapter>, remoteChapters: List<SyncChapter>): List<SyncChapter> {
        val localChapterMap = localChapters.associateBy { it.url }
        val remoteChapterMap = remoteChapters.associateBy { it.url }

        val mergedChapterMap = mutableMapOf<String?, SyncChapter>()

        localChapterMap.forEach { (url, localChapter) ->
            val remoteChapter = remoteChapterMap[url]
            if (remoteChapter != null) {
                val localInstant = localChapter.lastModifiedAt?.let { Instant.ofEpochMilli(it) }
                val remoteInstant = remoteChapter.lastModifiedAt?.let { Instant.ofEpochMilli(it) }

                val mergedChapter = if ((localInstant ?: Instant.MIN) >= (
                    remoteInstant
                        ?: Instant.MIN
                    )
                ) {
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
    private fun mergeCategoriesLists(localCategoriesList: List<SyncCategory>?, remoteCategoriesList: List<SyncCategory>?): List<SyncCategory> {
        if (localCategoriesList == null) return remoteCategoriesList ?: emptyList()
        if (remoteCategoriesList == null) return localCategoriesList

        val localCategoriesMap = localCategoriesList.associateBy { it.name }
        val remoteCategoriesMap = remoteCategoriesList.associateBy { it.name }

        val mergedCategoriesMap = mutableMapOf<String?, SyncCategory>()

        localCategoriesMap.forEach { (name, localCategory) ->
            val remoteCategory = remoteCategoriesMap[name]
            if (remoteCategory != null) {
                // Compare and merge local and remote categories
                val mergedCategory = if (localCategory.order!! >= remoteCategory.order!!) {
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
