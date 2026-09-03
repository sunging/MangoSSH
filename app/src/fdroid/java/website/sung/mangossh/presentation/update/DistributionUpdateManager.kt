package website.sung.mangossh.presentation.update

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import website.sung.mangossh.data.settings.UpdatePreferencesStore
import website.sung.mangossh.data.update.InstalledAppInfo
import website.sung.mangossh.presentation.UpdateUiState
import website.sung.mangossh.presentation.UiText

/** Compile-time-disabled update boundary for the store-managed F-Droid distribution. */
@Suppress("UNUSED_PARAMETER")
internal class DistributionUpdateManager(
    application: Application,
    installedAppInfo: InstalledAppInfo?,
    preferencesStore: UpdatePreferencesStore,
    scope: CoroutineScope,
) {
    /** F-Droid owns updates, so Settings never exposes the self-update page. */
    val state: StateFlow<UpdateUiState> = MutableStateFlow(
        UpdateUiState(
            supported = false,
            installedVersion = installedAppInfo?.versionName.orEmpty(),
        ),
    )

    suspend fun initializeAfterUnlock(appLocked: StateFlow<Boolean>) = Unit

    fun checkNow() = Unit

    fun download() = Unit

    fun cancelDownload() = Unit

    fun installReadyUpdate(context: Context): UiText? = null

    fun setAutomaticCheckEnabled(enabled: Boolean) = Unit

    fun dismissNotice() = Unit

    fun releasePageUrl(): String? = null

    fun close() = Unit
}
