package eu.kanade.tachiyomi.data.sync

import android.content.Context
import com.google.gson.Gson
import eu.kanade.domain.chapter.model.copyFrom
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.models.Data
import eu.kanade.tachiyomi.data.sync.models.SData
import eu.kanade.tachiyomi.data.sync.models.SyncCategory
import eu.kanade.tachiyomi.data.sync.models.SyncCategory.Companion.toCategory
import eu.kanade.tachiyomi.data.sync.models.SyncChapter
import eu.kanade.tachiyomi.data.sync.models.SyncChapter.Companion.syncChapterMapper
import eu.kanade.tachiyomi.data.sync.models.SyncDevice
import eu.kanade.tachiyomi.data.sync.models.SyncExtension
import eu.kanade.tachiyomi.data.sync.models.SyncHistory
import eu.kanade.tachiyomi.data.sync.models.SyncManga
import eu.kanade.tachiyomi.data.sync.models.SyncManga.Companion.toManga
import eu.kanade.tachiyomi.data.sync.models.SyncStatus
import eu.kanade.tachiyomi.data.sync.models.SyncTracking
import eu.kanade.tachiyomi.data.sync.models.SyncTracking.Companion.syncTrackMapper
import eu.kanade.tachiyomi.data.sync.models.syncCategoryMapper
import eu.kanade.tachiyomi.data.sync.models.syncChapterToChapter
import eu.kanade.tachiyomi.data.sync.models.syncTrackingToTrack
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.copyFrom
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
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
import tachiyomi.core.util.lang.toLong
import tachiyomi.core.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Manga_sync
import tachiyomi.data.Mangas
import tachiyomi.data.updateStrategyAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.sync.SyncPreferences
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.StrictMath.max
import java.time.Instant
import java.util.Date

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private var json: Json = Json {
        ignoreUnknownKeys = true
    },
    private val gson: Gson = Gson(),

) {

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
        val favorites = getFavorites.await()

        logcat(LogPriority.DEBUG) { "Mangas to sync: $favorites" }

        val mangaList = mangaList(favorites)
        val extensionsList = getExtensionInfo(favorites)
        val categoriesList = getCategories()

        when (val syncService = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.GOOGLE_DRIVE -> {
                syncDataWithServer(SyncService.GOOGLE_DRIVE, null, null, mangaList, categoriesList, extensionsList)
            }
            SyncService.SELF_HOSTED -> {
                val host = syncPreferences.syncHost().get()
                val apiKey = syncPreferences.syncAPIKey().get()
                val url = "$host/api/sync/data"

                syncDataWithServer(SyncService.SELF_HOSTED, url, apiKey, mangaList, categoriesList, extensionsList)
            }
            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
            }
        }
    }

    /**
     * Creates a list of SyncManga objects from the provided list of Manga objects.
     *
     * This function iterates through the given mangaList, retrieves additional data
     * such as chapters, categories, tracking, and history for each Manga, and creates
     * SyncManga objects with the retrieved data. The SyncManga objects are then added
     * to a mutable list called syncMangaList, which is returned at the end.
     *
     * @param mangaList A list of Manga objects to be converted into SyncManga objects.
     * @return A list of SyncManga objects created from the given mangaList.
     */
    private suspend fun mangaList(mangaList: List<Manga>): List<SyncManga> {
        val syncMangaList = mutableListOf<SyncManga>()

        for (m in mangaList) {
            val chapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(m.id, syncChapterMapper) }

            val categoriesForManga = getCategories.await(m.id)

            val tracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(m.id, syncTrackMapper) }

            val historyByMangaId = handler.awaitList(true) { historyQueries.getHistoryByMangaId(m.id) }
            val history = historyByMangaId.map { history ->
                val chapter = handler.awaitOne { chaptersQueries.getChapterById(history.chapter_id) }
                SyncHistory(chapter.url, history.last_read?.time ?: 0L, history.time_read)
            }

            // Create SyncManga directly from Manga
            val syncManga = SyncManga(
                source = m.source,
                url = m.url,
                favorite = m.favorite,
                title = m.title,
                artist = m.artist ?: "",
                author = m.author ?: "",
                description = m.description ?: "",
                genre = m.genre ?: emptyList(),
                status = m.status.toInt(),
                thumbnailUrl = m.thumbnailUrl ?: "",
                dateAdded = m.dateAdded,
                viewer = m.viewerFlags.toInt() and ReadingModeType.MASK,
                chapters = chapters.map { chapter ->
                    SyncChapter(
                        id = chapter.id,
                        mangaId = chapter.mangaId,
                        url = chapter.url,
                        name = chapter.name,
                        scanlator = chapter.scanlator ?: "",
                        read = chapter.read,
                        bookmark = chapter.bookmark,
                        lastPageRead = chapter.lastPageRead,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                        chapterNumber = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        lastModifiedAt = chapter.lastModifiedAt,
                        mangaUrl = m.url,
                        mangaSource = m.source,
                    )
                },
                tracking = mutableListOf(),
                categories = categoriesForManga.map { it.order },
                viewer_flags = m.viewerFlags.toInt(),
                history = history,
                updateStrategy = m.updateStrategy,
                lastModifiedAt = m.lastModifiedAt,
            )

            if (tracks.isNotEmpty()) {
                syncManga.tracking?.addAll(tracks)
            }

            syncMangaList.add(syncManga)
        }

        return syncMangaList
    }

    /**
     * Retrieves the extension information for the list of Manga objects provided.
     *
     * This function takes a list of Manga objects, extracts the unique source IDs,
     * and retrieves the corresponding extension data using the sourceManager.
     * It then maps the extension data to SyncExtension objects and returns them in a list.
     *
     * @param mangas A list of Manga objects for which to retrieve the extension information.
     * @return A list of SyncExtension objects containing the extension information.
     */
    private fun getExtensionInfo(mangas: List<Manga>): List<SyncExtension> {
        return mangas
            .asSequence()
            .map(Manga::source)
            .distinct()
            .map(sourceManager::getOrStub)
            .map(SyncExtension::copyFrom)
            .toList()
    }

    /**
     * Retrieves a list of SyncCategory objects representing user-created categories.
     *
     * This function fetches all the categories and filters out any system-generated categories.
     * It then maps the remaining user-created categories to SyncCategory objects and returns them in a list.
     *
     * @return A list of SyncCategory objects representing the user-created categories.
     */
    private suspend fun getCategories(): List<SyncCategory> {
        val categories = getCategories.await()
        return categories
            .filterNot(Category::isSystemCategory)
            .map(syncCategoryMapper)
    }

    /**
     * Sends a sync request to the server containing manga, categories, and extensions data.
     *
     * This function constructs the required objects (SyncStatus, Data, SyncDevice, and SData)
     * using the given input parameters, converts the SData object to a JSON string,
     * and sends the JSON data to the server using the provided URL and API key.
     *
     * @param syncService The sync service type (Google Drive or Self-hosted).
     * @param url The server URL to send the sync request to (only required for Self-hosted sync).
     * @param apiKey The API key for authentication (only required for Self-hosted sync).
     * @param mangaList A list of SyncManga objects representing the manga data to be synced.
     * @param categories A list of SyncCategory objects representing the categories data to be synced.
     * @param extensions A list of SyncExtension objects representing the extensions data to be synced.
     */
    private suspend fun syncDataWithServer(syncService: SyncService, url: String?, apiKey: String?, mangaList: List<SyncManga>, categories: List<SyncCategory>, extensions: List<SyncExtension>) {
        // Create the SyncStatus object
        val syncStatus = SyncStatus(
            lastSynced = Instant.now().toString(),
            lastSyncedEpoch = syncPreferences.syncLastLocalUpdate().get().toEpochMilli(),
            status = "completed",
        )

        // Create the Data object
        val data = Data(
            manga = mangaList,
            extensions = extensions,
            categories = categories,
        )

        // Create the Device object
        val device = SyncDevice(
            id = syncPreferences.deviceID().get(),
            name = syncPreferences.deviceName().get(),
        )

        // Create the SyncData object
        val syncData = SData(
            sync = syncStatus,
            data = data,
            device = device,
        )

        // Convert the SyncData object to a JSON string
        val jsonData = Json.encodeToString(syncData)

        // Handle sync based on the selected service
        when (syncService) {
            SyncService.GOOGLE_DRIVE -> {
                val googleDriveSync = GoogleDriveSync(context)
                val syncWithGdrive = googleDriveSync.uploadToGoogleDrive(jsonData)
                if (syncWithGdrive != null) {
                    sendSyncData(url = "", apiKey = "", jsonData = syncWithGdrive, storageType = "GoogleDrive")
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
    private suspend fun sendSyncData(url: String, apiKey: String, jsonData: String, storageType: String? = null) {
        if (storageType == "GoogleDrive") {
            val googleDriveSync = GoogleDriveSync(context)
            val combinedJsonData = googleDriveSync.uploadToGoogleDrive(jsonData)
            if (combinedJsonData != null) {
                val updatedSyncData: SData = gson.fromJson(combinedJsonData, SData::class.java)
                updateLocalData(updatedSyncData)
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
                    logcat(
                        LogPriority.DEBUG,
                        null,
                    ) { "Sync response: ${syncDataResponse.update_required}" }

                    // If a local update is required
                    if (syncDataResponse.update_required == true) {
                        updateLocalData(syncDataResponse)
                    } else {
                        syncDataResponse.sync?.lastSyncedEpoch?.let {
                            Instant.ofEpochMilli(it)
                        }?.let { syncPreferences.syncLastSync().set(it) }
                        syncPreferences.syncLastSync().set(Instant.now())

                        // If the device ID is 0 and not equal to the server device ID (this happens when the DB is fresh and the app is not), update it
                        if (syncPreferences.deviceID().get() == 0 || syncPreferences.deviceID().get() != syncDataResponse.device?.id) {
                            syncDataResponse.device?.id?.let { syncPreferences.deviceID().set(it) }
                        }

                        SyncNotifier(context).showSyncComplete()
                        logcat(
                            LogPriority.INFO,
                            null,
                        ) { "Local data is up to date! Not syncing!" }
                    }
                } else {
                    SyncNotifier(context).showSyncError("Failed to sync: error copied to clipboard")
                    responseBody.let { logcat(LogPriority.ERROR) { "SyncError:$it" } }
                    responseBody.let { context.copyToClipboard("sync_error", it) }
                }
            }
        }
    }

    /**
     * Updates the local data with the provided sync data response.
     *
     * This function restores manga and related data (e.g., tracking, history, categories, and chapters)
     * based on the provided sync data response. It restores system categories first, and then it restores
     * manga and related data. It also triggers a global update to fix the UI prev/next chapter buttons,
     * updates the sync last sync time preference, and updates the device ID if necessary.
     *
     * @param syncDataResponse The SData object containing the sync data response.
     */
    private suspend fun updateLocalData(syncDataResponse: SData) {
        val mangaList = syncDataResponse.data?.manga ?: emptyList()
        val tracking = mangaList.flatMap { syncManga: SyncManga -> syncManga.tracking ?: emptyList() }
        val history = mangaList.flatMap { it.history ?: emptyList() }.map { SyncHistory(it.url, it.lastRead, it.readDuration) }
        val syncCategories = syncDataResponse.data?.categories ?: emptyList()
        val chapters = mangaList.flatMap { it.chapters ?: emptyList() }

        // Restore system categories first.
        syncDataResponse.data?.categories?.let { restoreSyncCategories(it) }

        // Restore manga and related data.
        restoreManga(mangaList, history, tracking, syncCategories, chapters)

        // Trigger a global update after restoring everything: this should fix the UI prev/next chapter buttons.
        LibraryUpdateJob.startNow(context)

        syncPreferences.syncLastSync().set(Instant.now())
        // If the device ID is 0 and not equal to the server device ID (this happens when the DB is fresh and the app is not), update it
        if (syncPreferences.deviceID().get() == 0 || syncPreferences.deviceID().get() != syncDataResponse.device?.id) {
            syncDataResponse.device?.id?.let { syncPreferences.deviceID().set(it) }
        }
    }

    /**
     * Restores manga data, including chapters, history, tracking, and categories,
     * from a list of SyncManga objects. This function handles both existing manga
     * and new manga that are not present in the database. It updates or inserts
     * the corresponding data in the database as needed.
     *
     * @param syncMangas A list of SyncManga objects containing the manga data to sync.
     * @param history A list of SyncHistory objects containing the history data to sync.
     * @param tracks A list of SyncTracking objects containing the tracking data to sync.
     * @param syncCategories A list of SyncCategory objects containing the category data to sync.
     * @param chapters A list of SyncChapter objects containing the chapter data to sync.
     */
    private suspend fun restoreManga(
        syncMangas: List<SyncManga>,
        history: List<SyncHistory>,
        tracks: List<SyncTracking>,
        syncCategories: List<SyncCategory>,
        chapters: List<SyncChapter>,
    ) {
        syncMangas.forEach { syncManga ->
            val dbManga = syncManga.source?.let { source -> syncManga.url?.let { url -> getMangaFromDatabase(url, source) } }
            val mangaChapters = chapters.filter { it.mangaUrl == syncManga.url && it.mangaSource == syncManga.source }
            if (dbManga != null) {
                val restoredManga = restoreExistingManga(syncManga, dbManga)
                restoreChapters(restoredManga, mangaChapters)
            } else {
                val restoredNewManga = restoreNewManga(syncManga)
                restoreChapters(restoredNewManga, mangaChapters)
            }

            restoreExtras(syncManga, history, tracks, syncCategories)
        }
        // update the favorite status for all non-sync manga
        updateFavoriteStatusForNonSyncManga(syncMangas)
    }

    /**
     * Restores existing manga data by updating the local database with the latest information.
     * This function processes the given SyncManga object and updates the corresponding
     * data for the existing manga.
     *
     * @param syncManga A SyncManga object containing the manga data to sync.
     * @param dbManga A Mangas object containing the existing manga data in the local database.
     * @return Manga The updated manga object after syncing the latest data.
     */
    private suspend fun restoreExistingManga(syncManga: SyncManga, dbManga: Mangas): Manga {
        var manga = syncManga.toManga().copy(id = dbManga._id)
        manga = manga.copyFrom(dbManga)
        updateManga(manga)
        return manga
    }

    /**
     * Fetches manga information
     *
     * @param syncManga manga that needs updating
     * @return Updated manga info.
     */
    private suspend fun restoreNewManga(syncManga: SyncManga): Manga {
        val manga = syncManga.toManga()
        return manga.copy(
            initialized = manga.description != null,
            id = insertManga(manga),
        )
    }

    /**
     * Updates the favorite status for all local manga that are not included in the given list of SyncManga.
     * If a local manga is marked as favorite but not found in the SyncManga list, its favorite status will be updated to false.
     *
     * @param syncMangaList a list of SyncManga to compare against the local manga list
     */
    private suspend fun updateFavoriteStatusForNonSyncManga(syncMangaList: List<SyncManga>) {
        val localMangaList = getAllMangaFromDatabase()

        localMangaList.forEach { dbManga ->
            val syncManga = syncMangaList.find { it.url == dbManga.url && it.source == dbManga.source }

            if (syncManga == null && dbManga.favorite) {
                val updatedManga = convertMangasToManga(dbManga).copy(favorite = false)
                updateManga(updatedManga)
            }
        }
    }

    /**
     * Restores the additional sync data related to a given SyncManga object, including the
     * reading history, tracking, and categories.
     *
     * @param syncManga A SyncManga object containing the manga data to sync.
     * @param history A list of SyncHistory objects containing the reading history data to sync.
     * @param tracks A list of SyncTracking objects containing the tracking data to sync.
     * @param syncCategories A list of SyncCategory objects containing the category data to sync.
     */
    private suspend fun restoreExtras(syncManga: SyncManga, history: List<SyncHistory>, tracks: List<SyncTracking>, syncCategories: List<SyncCategory>) {
        // Convert SyncManga to Manga
        val manga = syncManga.toManga()

        restoreSyncCategoriesForManga(manga, syncManga.categories ?: emptyList(), syncCategories)
        restoreHistory(history)
        restoreTracking(manga, tracks)
    }

    /**
     * Restores the categories from a list of SyncCategory objects. If a category with the same
     * name already exists in the database, the existing category's ID is assigned to the
     * SyncCategory's corresponding category. Otherwise, a new category is created and added to
     * the database.
     *
     * @param syncCategories A list of SyncCategory objects containing the category data to sync.
     */
    private suspend fun restoreSyncCategories(syncCategories: List<SyncCategory>) {
        // Get categories from db
        val dbCategories = getCategories.await()

        val categories = syncCategories.map { syncCategory ->
            var category = syncCategory.toCategory()
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category = category.copy(id = dbCategory.id)
                    found = true
                    break
                }
            }
            if (!found) {
                // Let the db assign the id
                val id = handler.awaitOne {
                    categoriesQueries.insert(category.name, category.order, category.flags)
                    categoriesQueries.selectLastInsertedRowId()
                }
                category = category.copy(id = id)
            }

            category
        }

        libraryPreferences.categorizedDisplaySettings().set(
            (dbCategories + categories)
                .distinctBy { it.flags }
                .size > 1,
        )
    }

    /**
     * Restores the categories for a specific manga by associating the Manga with
     * the categories from a list of category IDs and SyncCategory objects.
     * The function first retrieves the corresponding Manga from the database.
     * Then, it iterates through the categories, finds the matching SyncCategory,
     * and associates it with the Manga in the database.
     *
     * @param manga The Manga for which the categories need to be restored.
     * @param categories A list of category IDs to be associated with the Manga.
     * @param syncCategories A list of SyncCategory objects containing the category data to sync.
     */
    private suspend fun restoreSyncCategoriesForManga(manga: Manga, categories: List<Long>, syncCategories: List<SyncCategory>) {
        val m = getMangaFromDatabase(manga.url, manga.source)
        val dbCategories = getCategories.await()
        val mangaCategoriesToUpdate = mutableListOf<Pair<Long, Long>>()

        val mangaId = m?._id ?: return

        categories.forEach { syncCategoryOrder ->
            syncCategories.firstOrNull {
                it.order == syncCategoryOrder
            }?.let { syncCategory ->
                dbCategories.firstOrNull { dbCategory ->
                    dbCategory.name == syncCategory.name
                }?.let { dbCategory ->
                    mangaCategoriesToUpdate.add(Pair(mangaId, dbCategory.id))
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
        } else {
            // remove the categories if the manga is not in any category (default category)
            handler.await(true) {
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            }
        }
    }

    /**
     * Restores the reading history from a list of SyncHistory objects.
     * The function iterates through the SyncHistory objects, and for each
     * one, it checks if the corresponding history record is already in the
     * database. If the record is found, it updates the last_read and
     * time_read fields with the maximum values between the existing and
     * the new SyncHistory data. If the record is not found, it creates a
     * new history record using the chapter and reading progress data.
     *
     * @param history A list of SyncHistory objects containing the reading history data to sync.
     */
    private suspend fun restoreHistory(history: List<SyncHistory>) {
        // List containing history to be updated
        val toUpdate = mutableListOf<HistoryUpdate>()
        for (syncHistory in history) {
            val url = syncHistory.url ?: continue
            val lastRead = syncHistory.lastRead ?: 0L
            val readDuration = syncHistory.readDuration ?: 0L

            var dbHistory = handler.awaitOneOrNull { historyQueries.getHistoryByChapterUrl(url) }
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory = dbHistory.copy(
                    last_read = Date(max(lastRead, dbHistory.last_read?.time ?: 0L)),
                    time_read = max(readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
                toUpdate.add(
                    HistoryUpdate(
                        chapterId = dbHistory.chapter_id,
                        readAt = dbHistory.last_read!!,
                        sessionReadDuration = dbHistory.time_read,
                    ),
                )
            } else {
                // If not in database create
                handler
                    .awaitOneOrNull { chaptersQueries.getChapterByUrl(url) }
                    ?.let {
                        toUpdate.add(
                            HistoryUpdate(
                                chapterId = it._id,
                                readAt = Date(lastRead),
                                sessionReadDuration = readDuration,
                            ),
                        )
                    }
            }
        }
        handler.await(true) {
            toUpdate.forEach { payload ->
                historyQueries.upsert(
                    payload.chapterId,
                    payload.readAt,
                    payload.sessionReadDuration,
                )
            }
        }
    }

    /**
     * Restores tracking data for a given manga from a list of SyncTracking objects.
     * The function iterates through the SyncTracking objects and checks if the
     * corresponding tracking record is already in the database. If the record is
     * found, it updates the fields with the new data. If the record is not found,
     * it inserts a new tracking record using the given manga and tracking data.
     *
     * @param manga The Manga object to which the tracking data belongs.
     * @param tracks A list of SyncTracking objects containing the tracking data to sync.
     */
    private suspend fun restoreTracking(manga: Manga, tracks: List<SyncTracking>) {
        // Fix foreign keys with the current manga id
        val tracks = tracks.map { syncTrack ->
            syncTrackingToTrack(syncTrack, manga.id)
        }

        // Get tracks from database
        val dbTracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id) }
        val toUpdate = mutableListOf<Manga_sync>()
        val toInsert = mutableListOf<Track>()

        tracks.forEach { track ->
            var isInDatabase = false
            for (dbTrack in dbTracks) {
                if (track.syncId == dbTrack.sync_id) {
                    // The sync is already in the db, only update its fields
                    var temp = dbTrack
                    if (track.remoteId != dbTrack.remote_id) {
                        temp = temp.copy(remote_id = track.remoteId)
                    }
                    if (track.libraryId != dbTrack.library_id) {
                        temp = temp.copy(library_id = track.libraryId)
                    }
                    temp = temp.copy(last_chapter_read = max(dbTrack.last_chapter_read, track.lastChapterRead))
                    isInDatabase = true
                    toUpdate.add(temp)
                    break
                }
            }
            if (!isInDatabase) {
                // Insert new sync. Let the db assign the id
                toInsert.add(track.copy(id = 0))
            }
        }
        // Update database
        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach { track ->
                    manga_syncQueries.update(
                        track.manga_id,
                        track.sync_id,
                        track.remote_id,
                        track.library_id,
                        track.title,
                        track.last_chapter_read,
                        track.total_chapters,
                        track.status,
                        track.score.toDouble(),
                        track.remote_url,
                        track.start_date,
                        track.finish_date,
                        track._id,
                    )
                }
            }
        }
        if (toInsert.isNotEmpty()) {
            handler.await(true) {
                toInsert.forEach { track ->
                    manga_syncQueries.insert(
                        track.mangaId,
                        track.syncId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastChapterRead,
                        track.totalChapters,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                    )
                }
            }
        }
    }

    /**
     * Restores chapter data for a given manga from a list of SyncChapter objects.
     * The function processes each SyncChapter object and maps it to a corresponding
     * Chapter object. If the chapter is already in the database, it updates the
     * fields with the new data. If the chapter is not found in the database, it
     * inserts a new chapter record using the given manga and chapter data.
     *
     * @param manga The Manga object to which the chapter data belongs.
     * @param chapters A list of SyncChapter objects containing the chapter data to sync.
     */
    private suspend fun restoreChapters(manga: Manga, chapters: List<SyncChapter>) {
        val dbChapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(manga.id) }

        val processed = chapters.map { syncChapter ->
            val chapter = syncChapterToChapter(syncChapter, manga.id)
            val dbChapter = dbChapters.find { it.url == chapter.url }
            var updatedChapter = chapter
            if (dbChapter != null) {
                updatedChapter = updatedChapter.copy(id = dbChapter._id)
                updatedChapter = updatedChapter.copyFrom(dbChapter)
                if (dbChapter.read != chapter.read) {
                    updatedChapter = updatedChapter.copy(read = chapter.read, lastPageRead = chapter.lastPageRead)
                } else if (updatedChapter.lastPageRead == 0L && dbChapter.last_page_read != 0L) {
                    updatedChapter = updatedChapter.copy(lastPageRead = dbChapter.last_page_read)
                }
                if (!updatedChapter.bookmark && dbChapter.bookmark) {
                    updatedChapter = updatedChapter.copy(bookmark = dbChapter.bookmark)
                }
            }

            updatedChapter.copy(mangaId = manga.id)
        }

        val newChapters = processed.groupBy { it.id > 0 }
        newChapters[true]?.let {
            updateKnownChapters(it)
        }
        newChapters[false]?.let {
            insertChapters(it)
        }
    }

    /**
     * Returns manga
     *
     * @return [Manga], null if not found
     */
    private suspend fun getMangaFromDatabase(url: String, source: Long): Mangas? {
        return handler.awaitOneOrNull { mangasQueries.getMangaByUrlAndSource(url, source) }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOne(true) {
            mangasQueries.insert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = 0L,
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                updateStrategy = manga.updateStrategy,
            )
            mangasQueries.selectLastInsertedRowId()
        }
    }

    private suspend fun updateManga(manga: Manga): Long {
        handler.await(true) {
            mangasQueries.update(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre?.joinToString(separator = ", "),
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite.toLong(),
                lastUpdate = manga.lastUpdate,
                initialized = manga.initialized.toLong(),
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                mangaId = manga.id,
                updateStrategy = manga.updateStrategy.let(updateStrategyAdapter::encode),
            )
        }
        return manga.id
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDatabase(): List<Mangas> {
        return handler.awaitList { mangasQueries.getAllManga() }
    }

    /**
     * Inserts list of chapters
     */
    private suspend fun insertChapters(chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                )
            }
        }
    }

    /**
     * Updates a list of chapters
     */
    private suspend fun updateChapters(chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read.toLong(),
                    chapter.bookmark.toLong(),
                    chapter.lastPageRead,
                    chapter.chapterNumber.toDouble(),
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.id,
                )
            }
        }
    }

    /**
     * Updates a list of chapters with known database ids
     */
    private suspend fun updateKnownChapters(chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read.toLong(),
                    bookmark = chapter.bookmark.toLong(),
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                )
            }
        }
    }
}

/**
 * Converts a Mangas object to a Manga object.
 *
 * @param mangas the Mangas object to convert
 * @return the resulting Manga object
 */
fun convertMangasToManga(mangas: Mangas): Manga {
    return Manga(
        id = mangas._id,
        source = mangas.source,
        favorite = mangas.favorite,
        lastUpdate = mangas.last_update ?: 0L,
        dateAdded = mangas.date_added,
        viewerFlags = mangas.viewer,
        chapterFlags = mangas.chapter_flags,
        coverLastModified = mangas.cover_last_modified,
        url = mangas.url,
        title = mangas.title,
        artist = mangas.artist,
        author = mangas.author,
        description = mangas.description,
        genre = mangas.genre,
        status = mangas.status,
        thumbnailUrl = mangas.thumbnail_url,
        updateStrategy = mangas.update_strategy,
        initialized = mangas.initialized,
        lastModifiedAt = mangas.last_modified_at,
    )
}
