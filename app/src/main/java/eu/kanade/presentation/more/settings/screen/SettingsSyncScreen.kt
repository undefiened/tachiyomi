package eu.kanade.presentation.more.settings.screen

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VpnKey
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
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.sync.SyncManager.SyncService
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
        val syncPreferences = remember { Injekt.get<SyncPreferences>() }
        val syncService by syncPreferences.syncService().collectAsState()

        return listOf(
            Preference.PreferenceItem.ListPreference(
                pref = syncPreferences.syncService(),
                title = stringResource(R.string.pref_sync_service),
                entries = mapOf(
                    SyncService.NONE.value to stringResource(R.string.off),
                    SyncService.SYNCYOMI.value to stringResource(R.string.syncyomi),
                    SyncService.GOOGLE_DRIVE.value to stringResource(R.string.google_drive),
                ),
                onValueChanged = { true },
            ),
        ) + getSyncServicePreferences(syncPreferences, syncService)
    }

    @Composable
    private fun getSyncServicePreferences(syncPreferences: SyncPreferences, syncService: Int): List<Preference> {
        return when (SyncService.fromInt(syncService)) {
            SyncService.NONE -> emptyList()
            SyncService.SYNCYOMI -> getSelfHostPreferences(syncPreferences)
            SyncService.GOOGLE_DRIVE -> getGoogleDrivePreferences()
        } + getSyncNowPref() + getAutomaticSyncGroup(syncPreferences)
    }


    @Composable
    private fun getGoogleDrivePreferences(): List<Preference> {
        val context = LocalContext.current
        val googleDriveSync = Injekt.get<GoogleDriveService>()

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.pref_google_drive_sign_in),
                icon = Icons.Outlined.AccountCircle,
                onClick = {
                    val intent = googleDriveSync.getSignInIntent()
                    context.startActivity(intent)
                },
            ),
            getGoogleDrivePurge(),
        )
    }

    @Composable
    private fun getSelfHostPreferences(syncPreferences: SyncPreferences): List<Preference> {
        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_device_name),
                subtitle = stringResource(R.string.pref_sync_device_name_summ),
                icon = Icons.Outlined.Devices,
                pref = syncPreferences.deviceName(),
            ),
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_host),
                subtitle = stringResource(R.string.pref_sync_host_summ),
                icon = Icons.Outlined.Cloud,
                pref = syncPreferences.syncHost(),
            ),
            Preference.PreferenceItem.EditTextPreference(
                title = stringResource(R.string.pref_sync_api_key),
                subtitle = stringResource(R.string.pref_sync_api_key_summ),
                icon = Icons.Outlined.VpnKey,
                pref = syncPreferences.syncAPIKey(),
            ),
        )
    }

    @Composable
    private fun getGoogleDrivePurge(): Preference.PreferenceItem.TextPreference {
        val scope = rememberCoroutineScope()
        val showPurgeDialog = remember { mutableStateOf(false) }
        val context = LocalContext.current
        val googleDriveSync = remember { GoogleDriveSyncService(context) }

        if (showPurgeDialog.value) {
            PurgeConfirmationDialog(
                onConfirm = {
                    showPurgeDialog.value = false
                    scope.launch {
                        val result = googleDriveSync.deleteSyncDataFromGoogleDrive()
                        if (result) {
                            context.toast(R.string.google_drive_sync_data_purged)
                        } else {
                            context.toast(R.string.google_drive_sync_data_not_found)
                        }
                    }
                },
                onDismissRequest = { showPurgeDialog.value = false },
            )
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.pref_google_drive_purge_sync_data),
            onClick = { showPurgeDialog.value = true },
            icon = Icons.Outlined.Delete,
        )
    }

    @Composable
    private fun getSyncNowPref(): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val showDialog = remember { mutableStateOf(false) }
        val context = LocalContext.current

        if (showDialog.value) {
            SyncConfirmationDialog(
                onConfirm = {
                    showDialog.value = false
                    scope.launch {
                        if (!SyncDataJob.isAnyJobRunning(context)) {
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
                    icon = Icons.Outlined.Sync,
                ),
            ),
        )
    }

    @Composable
    private fun getAutomaticSyncGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val syncIntervalPref = syncPreferences.syncInterval()
        val lastSync by syncPreferences.syncLastSync().collectAsState()
        val formattedLastSync = DateUtils.getRelativeTimeSpanString(lastSync.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

        return Preference.PreferenceGroup(
            title = stringResource(R.string.pref_sync_service_category),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    pref = syncIntervalPref,
                    title = stringResource(R.string.pref_sync_interval),
                    entries = mapOf(
                        0 to stringResource(R.string.off),
                        30 to stringResource(R.string.update_30min),
                        60 to stringResource(R.string.update_1hour),
                        180 to stringResource(R.string.update_3hour),
                        360 to stringResource(R.string.update_6hour),
                        720 to stringResource(R.string.update_12hour),
                        1440 to stringResource(R.string.update_24hour),
                        2880 to stringResource(R.string.update_48hour),
                        10080 to stringResource(R.string.update_weekly),
                    ),
                    onValueChanged = {
                        SyncDataJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(R.string.last_synchronization, formattedLastSync)),
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

@Composable
fun PurgeConfirmationDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.pref_purge_confirmation_title)) },
        text = { Text(text = stringResource(R.string.pref_purge_confirmation_message)) },
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
