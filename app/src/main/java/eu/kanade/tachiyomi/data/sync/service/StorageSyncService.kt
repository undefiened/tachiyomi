package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.models.SData
import kotlinx.serialization.json.Json
import tachiyomi.domain.sync.SyncPreferences
import java.time.Instant

abstract class StorageSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
) : SyncService(context, json, syncPreferences) {
    override suspend fun doSync(syncData: SData): Backup? {
        beforeSync()

        val remoteSData = downloadSyncData()

        val finalSyncData =
            if (remoteSData == null) {
                uploadSyncData(syncData)
                syncData
            } else {
                val mergedSyncData = mergeSyncData(syncData, remoteSData)
                uploadSyncData(mergedSyncData)
                mergedSyncData
            }

        return finalSyncData.backup
    }

    /**
     * For refreshing tokens and other possible operations before connecting to the remote storage
     */
    open suspend fun beforeSync() {}

    /**
     * Download sync data from the remote storage
     */
    abstract suspend fun downloadSyncData(): SData?

    /**
     * Upload sync data to the remote storage
     */
    abstract suspend fun uploadSyncData(syncData: SData)

    /**
     * Merges the local and remote sync data into a single JSON string.
     *
     * @param localSyncData The SData containing the local sync data.
     * @param remoteSyncData The SData containing the remote sync data.
     * @return The JSON string containing the merged sync data.
     */
    fun mergeSyncData(localSyncData: SData, remoteSyncData: SData): SData {
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
        return SData(
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
