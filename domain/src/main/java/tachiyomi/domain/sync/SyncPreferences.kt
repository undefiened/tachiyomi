package tachiyomi.domain.sync

import tachiyomi.core.preference.PreferenceStore

class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun syncHost() = preferenceStore.getString("sync_host", "https://sync.tachiyomi.org")
    fun syncAPIKey() = preferenceStore.getString("sync_api_key", "")
}
