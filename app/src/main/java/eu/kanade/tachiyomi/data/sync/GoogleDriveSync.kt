package eu.kanade.tachiyomi.data.sync
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.gson.Gson
import eu.kanade.tachiyomi.data.sync.models.Data
import eu.kanade.tachiyomi.data.sync.models.SData
import eu.kanade.tachiyomi.data.sync.models.SyncCategory
import eu.kanade.tachiyomi.data.sync.models.SyncManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

class GoogleDriveSync(private val context: Context) {
    private val syncPreferences = Injekt.get<SyncPreferences>()
    private val gson = Gson()

    /**
     * Initializes the GoogleDriveSync class by checking for a saved account and setting up the Google Drive service if one exists.
     */
    init {
        val savedAccount = getSavedAccount()
        if (savedAccount != null) {
            setupGoogleDriveService(savedAccount)
        }
    }

    private val googleSignInOptions: GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

    private val googleSignInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, googleSignInOptions)

    private var googleDriveService: Drive? = null

    /**
     * Returns a GoogleSignIn intent to start the sign-in process.
     *
     * @return An Intent to start the Google sign-in process.
     */
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Handles the result of the Google sign-in process by setting up the Google Drive service and saving the account information upon successful sign-in.
     *
     * @param requestCode The request code used to start the sign-in process.
     * @param resultCode The result code returned by the sign-in process.
     * @param data The intent containing the result data.
     * @param onSuccess A callback function to be called upon successful sign-in.
     * @param onFailure A callback function to be called upon sign-in failure, passing an error message as a parameter.
     */
    fun handleSignInResult(requestCode: Int, resultCode: Int, data: Intent?, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                saveAccountInfo(account)
                setupGoogleDriveService(account)
                onSuccess()
            } catch (e: ApiException) {
                onFailure(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    /**
     * Sets up the Google Drive service using the provided GoogleSignInAccount.
     *
     * @param account The GoogleSignInAccount used to authenticate the Google Drive service.
     */
    private fun setupGoogleDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE),
        ).setSelectedAccount(account.account)
        googleDriveService = Drive.Builder(
            NetHttpTransport(),
            JacksonFactory(),
            credential,
        ).setApplicationName("Tachiyomi")
            .build()
    }

    companion object {
        const val RC_SIGN_IN = 1001
    }

    /**
     * Saves the account information as a JSON string in the sync preferences.
     *
     * @param account The GoogleSignInAccount to be saved.
     */
    private fun saveAccountInfo(account: GoogleSignInAccount) {
        val accountJson = gson.toJson(account)
        syncPreferences.setGoogleAccountJson(accountJson)
    }

    /**
     * Retrieves the saved GoogleSignInAccount from sync preferences if it exists.
     *
     * @return The saved GoogleSignInAccount, or null if no account is saved.
     */
    private fun getSavedAccount(): GoogleSignInAccount? {
        val accountJson = syncPreferences.googleAccountJson().get()

        return if (accountJson.isNotEmpty()) {
            gson.fromJson(accountJson, GoogleSignInAccount::class.java)
        } else {
            null
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
                // Compare and merge local and remote manga
                val mergedManga = if (localManga.lastModifiedAt!! >= remoteManga.lastModifiedAt!!) {
                    localManga
                } else {
                    remoteManga
                }
                mergedMangaMap[key] = mergedManga
            } else {
                // If the manga is only in the local list, add it to the merged list
                mergedMangaMap[key] = localManga
            }
        }

        // Add any manga from the remote list that are not in the local list
        remoteMangaMap.forEach { (key, remoteManga) ->
            if (!mergedMangaMap.containsKey(key)) {
                mergedMangaMap[key] = remoteManga
            }
        }

        return mergedMangaMap.values.toList()
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
