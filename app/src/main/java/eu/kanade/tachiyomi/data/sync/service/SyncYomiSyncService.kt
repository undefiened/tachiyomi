package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.data.sync.models.SyncData
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.gzip
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.sync.SyncPreferences
import java.time.Instant

class SyncYomiSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
) : SyncService(context, json, syncPreferences) {
    override suspend fun doSync(syncData: SyncData): Backup? {
        logcat(
            LogPriority.DEBUG,
        ) { "SyncYomi sync started!" }

        val jsonData = json.encodeToString(syncData)

        val mediaType = "application/gzip".toMediaTypeOrNull()
        val body = jsonData.toRequestBody(mediaType).gzip()

        val remoteSyncData = downloadSyncData()

        val finalSyncData =
            if (remoteSyncData?.backup == null) {
                uploadSyncData(body)
                syncData
            } else {
                val mergedSyncData = mergeSyncData(syncData, remoteSyncData)
                val encodeMergedData = json.encodeToString(mergedSyncData)
                uploadSyncData(encodeMergedData.toRequestBody(mediaType).gzip())
                mergedSyncData
            }

        return finalSyncData.backup
    }

    suspend fun downloadSyncData(): SyncData? {
        val host = syncPreferences.syncHost().get()
        val apiKey = syncPreferences.syncAPIKey().get()
        val deviceId = syncPreferences.deviceID().get()
        val downloadUrl = "$host/api/sync/download?deviceId=$deviceId"

        val client = OkHttpClient()
        val headers = Headers.Builder().add("X-API-Token", apiKey).build()

        val downloadRequest = GET(
            url = downloadUrl,
            headers = headers,
        )

        client.newCall(downloadRequest).execute().use { response ->
            val responseBody = response.body.string()

            if (response.isSuccessful) {
                return json.decodeFromString<SyncData>(responseBody)
            } else {
                notifier.showSyncError("Failed to download sync data: $responseBody")
                responseBody.let { logcat(LogPriority.ERROR) { "SyncError:$it" } }
                return null
            }
        }
    }

    suspend fun uploadSyncData(body: RequestBody) {
        val host = syncPreferences.syncHost().get()
        val apiKey = syncPreferences.syncAPIKey().get()
        val uploadUrl = "$host/api/sync/upload"

        val client = OkHttpClient()

        val headers = Headers.Builder().add("Content-Type", "application/gzip").add("Content-Encoding", "gzip").add("X-API-Token", apiKey).build()

        val uploadRequest = POST(
            url = uploadUrl,
            headers = headers,
            body = body,
        )

        client.newCall(uploadRequest).execute().use {
            if (it.isSuccessful) {
                logcat(
                    LogPriority.DEBUG,
                ) { "SyncYomi sync completed!" }
            } else {
                val responseBody = it.body.string()
                notifier.showSyncError("Failed to upload sync data: $responseBody")
                responseBody.let { logcat(LogPriority.ERROR) { "SyncError:$it" } }
            }
        }
    }

    /**
     * Merges the local and remote sync data into a single JSON string.
     *
     * @param localSyncData The SyncData containing the local sync data.
     * @param remoteSyncData The SyncData containing the remote sync data.
     * @return The JSON string containing the merged sync data.
     */
    fun mergeSyncData(localSyncData: SyncData, remoteSyncData: SyncData): SyncData {
        val mergedMangaList = mergeMangaLists(localSyncData.backup?.backupManga, remoteSyncData.backup?.backupManga)
        val mergedCategoriesList = mergeCategoriesLists(localSyncData.backup?.backupCategories, remoteSyncData.backup?.backupCategories)

        // Create the merged Backup object
        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedCategoriesList,
            backupBrokenSources = localSyncData.backup?.backupBrokenSources ?: emptyList(),
            backupSources = localSyncData.backup?.backupSources ?: emptyList(),
        )

        // Create the merged SyncData object
        return SyncData(
            sync = localSyncData.sync, // always use the local sync info
            backup = mergedBackup,
            device = localSyncData.device, // always use the local device info
        )
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

                val mergedManga = if ((localInstant ?: Instant.MIN) >= (
                    remoteInstant
                        ?: Instant.MIN
                    )
                ) {
                    localManga
                } else {
                    remoteManga
                }

                val localChapters = localManga.chapters
                val remoteChapters = remoteManga.chapters
                val mergedChapters = mergeChapters(localChapters, remoteChapters)

                val isFavorite = if ((localInstant ?: Instant.MIN) >= (
                    remoteInstant
                        ?: Instant.MIN
                    )
                ) {
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
