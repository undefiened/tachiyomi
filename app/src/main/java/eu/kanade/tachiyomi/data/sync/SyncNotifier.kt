package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import uy.kohesive.injekt.injectLazy

class SyncNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    private val progressNotificationBuilder = context.notificationBuilder(Notifications.CHANNEL_SYNC_PROGRESS) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_tachi)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(Notifications.CHANNEL_SYNC_COMPLETE) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_tachi)
        setAutoCancel(false)
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    fun showSyncProgress(): NotificationCompat.Builder {
        val builder = with(progressNotificationBuilder) {
            setContentTitle(context.getString(R.string.syncing_data))

            setProgress(0, 0, true)
        }

        builder.show(Notifications.ID_SYNC_PROGRESS)

        return builder
    }

    fun showSyncError(error: String?) {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.getString(R.string.sync_error))
            setContentText(error)

            show(Notifications.ID_SYNC_ERROR)
        }
    }

    fun showSyncComplete() {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.getString(R.string.sync_complete))

            show(Notifications.ID_SYNC_COMPLETE)
        }
    }
}
