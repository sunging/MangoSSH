package website.sung.mangossh.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Presents the system grant once per session request, including across activity recreation.
 *
 * The activity-result registry delivers an in-flight result after recreation; saved
 * launch state prevents opening a second system dialog. The controller rechecks the
 * grant, and its session-owned waiter ignores results delivered after cancellation.
 */
@Composable
internal fun LocalNetworkPermissionPrompt(onRespond: (List<String>?) -> Unit) {
    val context = LocalContext.current
    var launched by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onRespond(if (granted) emptyList() else null)
    }
    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED) {
                onRespond(emptyList())
            } else launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
