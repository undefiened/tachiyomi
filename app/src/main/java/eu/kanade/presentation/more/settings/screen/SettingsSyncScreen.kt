package eu.kanade.presentation.more.settings.screen

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.collectAsState
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.util.system.toast
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
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_device_name),
                subtitle = stringResource(R.string.pref_sync_device_name_summ),
                pref = syncPreferences.deviceName(),
            ),
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_host),
                subtitle = stringResource(R.string.pref_sync_host_summ),
                pref = syncPreferences.syncHost(),
            ),
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_api_key),
                subtitle = stringResource(R.string.pref_sync_api_key_summ),
                pref = syncPreferences.syncAPIKey(),
            ),
            getSyncNowPref(syncPreferences = syncPreferences),
        )
    }

    @Composable
    private fun getSyncNowPref(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val showDialog = remember { mutableStateOf(false) }
        val context = LocalContext.current
        val lastSync by syncPreferences.syncLastSync().collectAsState()
        val lastLocalChange by syncPreferences.syncLastLocalUpdate().collectAsState()
        val formattedLastSync = DateUtils.getRelativeTimeSpanString(lastSync.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val formattedLastLocalChange = DateUtils.getRelativeTimeSpanString(lastLocalChange.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

        if (showDialog.value) {
            SyncConfirmationDialog(
                onConfirm = {
                    showDialog.value = false
                    scope.launch {
                        if (!SyncDataJob.isManualJobRunning(context)) {
                            SyncDataJob.startNow(context)
                        } else {
                            context.toast(R.string.sync_in_progress)
                        }
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
                Preference.PreferenceItem.InfoPreference("Last sync: $formattedLastSync \nLast local change: $formattedLastLocalChange"),
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
