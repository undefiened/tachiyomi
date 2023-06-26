package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.sync.GoogleDriveSync
import eu.kanade.tachiyomi.data.sync.OAuthCallbackServer
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GoogleDriveLoginActivity : BaseOAuthLoginActivity() {
    private val googleDriveSync = Injekt.get<GoogleDriveSync>()

    private val oAuthCallbackServer = Injekt.get<OAuthCallbackServer>()

    override fun onDestroy() {
        super.onDestroy()
        oAuthCallbackServer.stop()
    }

    override fun handleResult(data: Uri?) {
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")
        if (code != null) {
            lifecycleScope.launchIO {
                googleDriveSync.handleAuthorizationCode(
                    code,
                    this@GoogleDriveLoginActivity,
                    onSuccess = {
                        Toast.makeText(
                            this@GoogleDriveLoginActivity,
                            "Authorization successful.",
                            Toast.LENGTH_LONG,
                        ).show()

                        returnToSettings()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@GoogleDriveLoginActivity,
                            "Authorization failed: $error",
                            Toast.LENGTH_LONG,
                        ).show()
                        returnToSettings()
                    },
                )
            }
        } else if (error != null) {
            Toast.makeText(
                this@GoogleDriveLoginActivity,
                "Authorization failed: $error",
                Toast.LENGTH_LONG,
            ).show()

            returnToSettings()
        } else {
            returnToSettings()
        }
    }
}