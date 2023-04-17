package eu.kanade.tachiyomi.data.sync.models

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

@Serializable
data class SyncStatus(
    @SerialName("last_synced") val lastSynced: String? = null,
    @SerialName("last_synced_epoch") val lastSyncedEpoch: Long? = null,
    val status: String? = null,
)

@Serializable
data class SyncManga(
    val source: Long? = null,
    val url: String? = null,
    @SerialName("favorite") val favorite: Boolean? = null,
    val title: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Int? = null,
    val thumbnailUrl: String? = null,
    val dateAdded: Long? = null,
    val viewer: Int? = null,
    val chapters: List<SyncChapter>? = null,
    val categories: List<Long>? = null,
    val tracking: MutableList<SyncTracking>? = null,
    val viewer_flags: Int? = null,
    val history: List<SyncHistory>? = null,
    val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    val initialized: Boolean? = null,
) {
    companion object {
        fun SyncManga.toManga(): Manga {
            return Manga(
                id = 0L,
                source = this.source ?: 0L,
                favorite = this.favorite ?: false,
                lastUpdate = 0L,
                dateAdded = this.dateAdded ?: 0L,
                viewerFlags = (this.viewer_flags?.toLong() ?: this.viewer?.toLong() ?: 0L),
                chapterFlags = 0L,
                coverLastModified = 0L,
                url = this.url ?: "",
                title = this.title ?: "",
                artist = this.artist,
                author = this.author,
                description = this.description,
                genre = this.genre,
                status = this.status?.toLong() ?: 0L,
                thumbnailUrl = this.thumbnailUrl,
                updateStrategy = this.updateStrategy,
                initialized = this.initialized ?: false,
            )
        }
    }
}

@Serializable
data class SyncTracking(
    val syncId: Int? = null,
    val libraryId: Long? = null,
    @Deprecated("Use mediaId instead", level = DeprecationLevel.WARNING)
    val mediaIdInt: Int? = null,
    val trackingUrl: String? = null,
    val title: String? = null,
    val lastChapterRead: Float? = null,
    val totalChapters: Int? = null,
    val score: Float? = null,
    val status: Int? = null,
    val startedReadingDate: Long? = null,
    val finishedReadingDate: Long? = null,
    val mediaId: Long? = null,
) {
    companion object {
        val syncTrackMapper = { _: Long, _: Long, syncId: Long, mediaId: Long, libraryId: Long?, title: String, lastChapterRead: Double, totalChapters: Long, status: Long, score: Float, remoteUrl: String, startDate: Long, finishDate: Long ->
            SyncTracking(
                syncId = syncId.toInt(),
                mediaId = mediaId,
                libraryId = libraryId ?: 0,
                title = title,
                lastChapterRead = lastChapterRead.toFloat(),
                totalChapters = totalChapters.toInt(),
                score = score,
                status = status.toInt(),
                startedReadingDate = startDate,
                finishedReadingDate = finishDate,
                trackingUrl = remoteUrl,
            )
        }
    }
}

fun syncTrackingToTrack(syncTracking: SyncTracking, mangaId: Long): Track {
    return Track(
        id = -1,
        mangaId = mangaId,
        syncId = syncTracking.syncId?.toLong() ?: 0L,
        remoteId = syncTracking.mediaId ?: syncTracking.mediaIdInt?.toLong() ?: 0L,
        libraryId = syncTracking.libraryId,
        title = syncTracking.title ?: "",
        lastChapterRead = syncTracking.lastChapterRead?.toDouble() ?: 0.0,
        totalChapters = syncTracking.totalChapters?.toLong() ?: 0L,
        status = syncTracking.status?.toLong() ?: 0L,
        score = syncTracking.score ?: 0f,
        remoteUrl = syncTracking.trackingUrl ?: "",
        startDate = syncTracking.startedReadingDate ?: 0L,
        finishDate = syncTracking.finishedReadingDate ?: 0L,
    )
}

@Serializable
data class SyncChapter(
    @SerialName("id") val id: Long? = null,
    val mangaId: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val scanlator: String? = null,
    val read: Boolean? = null,
    val bookmark: Boolean? = false,
    val lastPageRead: Long? = null,
    val dateFetch: Long? = null,
    val dateUpload: Long? = null,
    val chapterNumber: Int? = null,
    val sourceOrder: Long? = 0,
    val mangaUrl: String? = null,
    val mangaSource: Long? = null,
) {
    companion object {
        val syncChapterMapper = { id: Long, mangaId: Long, url: String, name: String, scanlator: String?, read: Boolean, bookmark: Boolean, lastPageRead: Long, chapterNumber: Float, source_order: Long, dateFetch: Long, dateUpload: Long ->
            SyncChapter(
                id = id,
                mangaId = mangaId,
                url = url,
                name = name,
                scanlator = scanlator,
                read = read,
                bookmark = bookmark,
                lastPageRead = lastPageRead,
                dateFetch = dateFetch,
                dateUpload = dateUpload,
                chapterNumber = chapterNumber.toInt(),
                sourceOrder = source_order,
            )
        }
    }
}

fun syncChapterToChapter(syncChapter: SyncChapter, mangaId: Long): Chapter {
    return Chapter(
        id = -1,
        mangaId = mangaId,
        read = syncChapter.read ?: false,
        bookmark = false,
        lastPageRead = 0L,
        dateFetch = syncChapter.dateFetch ?: 0L,
        sourceOrder = 0L,
        url = syncChapter.url ?: "",
        name = syncChapter.name ?: "",
        dateUpload = syncChapter.dateUpload ?: 0L,
        chapterNumber = syncChapter.chapterNumber?.toFloat() ?: 0f,
        scanlator = syncChapter.scanlator,
    )
}

@Serializable
data class SyncHistory(
    val url: String? = null,
    val lastRead: Long? = null,
    val readDuration: Long? = null,
)

@Serializable
data class SyncExtension(
    val name: String? = null,
    val sourceId: Long? = null,
) {
    companion object {
        fun copyFrom(source: Source): SyncExtension {
            return SyncExtension(
                name = source.name,
                sourceId = source.id,
            )
        }
    }
}

@Serializable
data class SyncCategory(
    val name: String? = null,
    val flags: Long? = null,
    val order: Long? = null,
) {
    companion object {
        fun SyncCategory.toCategory(): Category {
            return Category(
                id = 0,
                name = this.name ?: "",
                flags = this.flags ?: 0,
                order = this.order ?: 0,
            )
        }
    }
}

val syncCategoryMapper = { category: Category ->
    SyncCategory(
        name = category.name,
        order = category.order,
        flags = category.flags,
    )
}

@Serializable
data class Data(
    val manga: List<SyncManga>? = null,
    val extensions: List<SyncExtension>? = null,
    val categories: List<SyncCategory>? = null,
)

@Serializable
data class SyncDevice(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class SData(
    val sync: SyncStatus? = null,
    val data: Data? = null,
    val device: SyncDevice? = null,
    @SerialName("update_required") val update_required: Boolean? = null,
)
