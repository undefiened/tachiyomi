package eu.kanade.presentation.more.settings.screen

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    @StringRes
    override fun getTitleRes() = R.string.label_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val syncPreferences = Injekt.get<SyncPreferences>()

        return listOf(
            getSyncHostPref(syncPreferences = syncPreferences),
            getSyncAPIKeyPref(syncPreferences = syncPreferences),
            getSyncNowPref(syncPreferences = syncPreferences),
        )
    }

    @Composable
    fun getSyncHostPref(syncPreferences: SyncPreferences): Preference.PreferenceItem.EditTextPreference {
        return Preference.PreferenceItem.EditTextPreference(
            title = stringResource(R.string.pref_sync_host),
            subtitle = stringResource(R.string.pref_sync_api_key_summ),
            onValueChanged = { newValue ->
                syncPreferences.syncHost().set(newValue)
                true
            },
            enabled = true,
            icon = null,
            pref = syncPreferences.syncHost(),
        )
    }

    @Composable
    fun getSyncAPIKeyPref(syncPreferences: SyncPreferences): Preference.PreferenceItem.EditTextPreference {
        return Preference.PreferenceItem.EditTextPreference(
            title = stringResource(R.string.pref_sync_api_key),
            subtitle = stringResource(R.string.pref_sync_host_summ),
            onValueChanged = { newValue ->
                syncPreferences.syncAPIKey().set(newValue)
                true
            },
            enabled = true,
            icon = null,
            pref = syncPreferences.syncAPIKey(),
        )
    }

    @Composable
    fun getSyncNowPref(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val showDialog = remember { mutableStateOf(false) }

        if (showDialog.value) {
            SyncConfirmationDialog(
                onConfirm = {
                    showDialog.value = false
                    scope.launch {
                        // TODO: Perform sync here
                    }
                },
                onDismissRequest = { showDialog.value = false },
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(R.string.pref_sync_now_group_title),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.pref_sync_now),
                    subtitle = stringResource(R.string.pref_sync_now_subtitle),
                    onClick = {
                        showDialog.value = true
                    },
                ),
                // TODO: Add last sync time
                Preference.PreferenceItem.InfoPreference("Last sync at: 2021-09-01 12:00:00"),
            ),
        )
    }

    @Composable
    fun SyncConfirmationDialog(
        onConfirm: () -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(R.string.pref_sync_confirmation_title)) },
            text = { Text(text = stringResource(R.string.pref_sync_confirmation_message)) },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }
}
