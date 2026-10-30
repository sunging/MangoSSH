package website.sung.mangossh.presentation

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.ActivityOptionsCompat
import website.sung.mangossh.security.DocumentPickerRequests

/** Installed at the Activity root; standalone screen previews have no app-lock boundary. */
internal val LocalDocumentPickerRequests = staticCompositionLocalOf<DocumentPickerRequests?> { null }

/**
 * Balances document UI grants across results, launch failures and removal of the owning screen.
 * Saved state contains only a token: the process-local registry remains the authority. The
 * caller still receives cancellation for revoked results so it can clear its own pending IDs.
 */
@Composable
internal fun <I> rememberAppDocumentLauncher(
    contract: ActivityResultContract<I, Uri?>,
    onResult: (Uri?) -> Unit,
): ActivityResultLauncher<I> {
    val requests = LocalDocumentPickerRequests.current
    val activity = LocalActivity.current
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        val pending = token
        token = null
        onResult(if (requests == null || requests.finish(pending)) result else null)
    }
    DisposableEffect(requests, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                requests?.abandon(token)
                token = null
            }
        }
    }
    return remember(launcher, requests) {
        object : ActivityResultLauncher<I>() {
            override val contract: ActivityResultContract<I, *> get() = launcher.contract
            override fun launch(input: I, options: ActivityOptionsCompat?) {
                if (token != null) return
                val pending = if (requests != null) requests.begin() ?: return else null
                token = pending
                try { launcher.launch(input, options) }
                catch (error: Exception) { requests?.abandon(pending); token = null; throw error }
            }
            override fun unregister() = error("Registration is managed by composition")
        }
    }
}
