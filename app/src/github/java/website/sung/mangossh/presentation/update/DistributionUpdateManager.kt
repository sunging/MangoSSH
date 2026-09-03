package website.sung.mangossh.presentation.update

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.core.AppUpdateLogEvent
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.data.settings.UpdatePreferencesStore
import website.sung.mangossh.data.update.AppUpdateCheckResult
import website.sung.mangossh.data.update.AppUpdateDownloadResult
import website.sung.mangossh.data.update.AppUpdateFailureReason
import website.sung.mangossh.data.update.AppUpdateFileStore
import website.sung.mangossh.data.update.GitHubReleaseClient
import website.sung.mangossh.data.update.InstalledAppInfo
import website.sung.mangossh.data.update.archiveMatchesInstalledSigner
import website.sung.mangossh.data.update.selfUpdateSupported
import website.sung.mangossh.domain.AppRelease
import website.sung.mangossh.presentation.UiText
import website.sung.mangossh.presentation.UpdatePhase
import website.sung.mangossh.presentation.UpdateUiState
import website.sung.mangossh.presentation.uiText

/**
 * Owns the sideload distribution's complete self-update lifecycle.
 *
 * This class exists only in the `github` source set. The F-Droid variant has
 * an inert implementation with the same API, so network, archive-verification,
 * file-provider, and installer-handoff code cannot enter that APK.
 */
internal class DistributionUpdateManager(
    private val application: Application,
    private val installedAppInfo: InstalledAppInfo?,
    private val preferencesStore: UpdatePreferencesStore,
    private val scope: CoroutineScope,
) {
    private val supported = MutableStateFlow(false)
    private val phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    private val files = AppUpdateFileStore(application)
    private val releaseClient = GitHubReleaseClient(
        appVersionName = installedAppInfo?.versionName ?: "unknown",
    )
    private var updateJob: Job? = null

    /** Immutable state consumed by the common Settings UI. */
    val state: StateFlow<UpdateUiState> = combine(
        supported,
        phase,
        preferencesStore.preferences,
    ) { isSupported, currentPhase, preferences ->
        UpdateUiState(
            supported = isSupported,
            automaticCheckEnabled = preferences.automaticCheckEnabled,
            installedVersion = installedAppInfo?.versionName.orEmpty(),
            phase = currentPhase,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    /** Resolves installer ownership off-main, then performs the throttled automatic check after unlock. */
    suspend fun initializeAfterUnlock(appLocked: StateFlow<Boolean>) {
        val selfManaged = withContext(Dispatchers.IO) {
            files.purgeExcept(null)
            application.selfUpdateSupported()
        }
        supported.value = selfManaged
        if (!selfManaged) return
        appLocked.first { !it }
        if (preferencesStore.current().shouldAutoCheck(System.currentTimeMillis())) {
            runUpdateCheck()
        }
    }

    /** Checks GitHub now and clears any dismissal of the latest known version. */
    fun checkNow() {
        if (!supported.value) return
        updateJob?.cancel()
        preferencesStore.clearDismissal()
        updateJob = scope.launch { runUpdateCheck() }
    }

    private suspend fun runUpdateCheck() {
        phase.value = UpdatePhase.Checking
        val result = releaseClient.latestRelease()
        preferencesStore.recordCheck(System.currentTimeMillis())
        phase.value = when (result) {
            is AppUpdateCheckResult.Success -> {
                val release = result.release
                when {
                    release.version.versionCode <= (installedAppInfo?.versionCode ?: 0L) -> UpdatePhase.UpToDate
                    preferencesStore.current().isDismissed(release.version.versionCode) -> UpdatePhase.Idle
                    else -> UpdatePhase.Available(release)
                }
            }

            is AppUpdateCheckResult.Failure ->
                UpdatePhase.Failed(result.reason.toUiText(result.statusCode), release = null)
        }
    }

    /** Downloads, digest-verifies, and signing-certificate-verifies the offered APK. */
    fun download() {
        if (!supported.value) return
        val release = when (val current = phase.value) {
            is UpdatePhase.Available -> current.release
            is UpdatePhase.Failed -> current.release
            else -> null
        } ?: return
        updateJob?.cancel()
        updateJob = scope.launch {
            phase.value = UpdatePhase.Downloading(release, 0L, release.apkAsset.sizeBytes)
            try {
                downloadVerifyAndPromote(release)
            } catch (error: CancellationException) {
                phase.value = UpdatePhase.Available(release)
                throw error
            }
        }
    }

    private suspend fun downloadVerifyAndPromote(release: AppRelease) {
        val partFile = files.partFile(release.tag)
        when (val result = releaseClient.downloadApk(release, partFile) { downloaded, total ->
            phase.update { current ->
                if (current is UpdatePhase.Downloading) {
                    current.copy(downloadedBytes = downloaded, totalBytes = total)
                } else {
                    current
                }
            }
        }) {
            is AppUpdateDownloadResult.Failure ->
                phase.value = UpdatePhase.Failed(result.reason.toUiText(result.statusCode), release)

            is AppUpdateDownloadResult.Success -> {
                phase.value = UpdatePhase.Verifying(release)
                val verifiedFile = withContext(Dispatchers.IO) {
                    val promoted = files.promote(result.file, release.tag) ?: return@withContext null
                    promoted.takeIf { application.archiveMatchesInstalledSigner(it) }
                }
                phase.value = if (verifiedFile != null) {
                    UpdatePhase.ReadyToInstall(release)
                } else {
                    withContext(Dispatchers.IO) { files.purgeExcept(null) }
                    UpdatePhase.Failed(uiText(R.string.app_update_failed_signature_mismatch), release)
                }
            }
        }
    }

    fun cancelDownload() {
        updateJob?.cancel()
    }

    /** Hands the verified private-cache APK to Android's package installer. */
    fun installReadyUpdate(context: Context): UiText? {
        val release = (phase.value as? UpdatePhase.ReadyToInstall)?.release ?: return null
        val file = files.verifiedFile(release.tag)
        if (!file.exists()) {
            phase.value = UpdatePhase.Available(release)
            return null
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(files.contentUri(file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        MangoLog.info(AppUpdateLogEvent.INSTALL_HANDOFF)
        if (runCatching { context.startActivity(intent) }.isSuccess) return null
        MangoLog.warn(AppUpdateLogEvent.INSTALL_HANDOFF_FAILED)
        return uiText(R.string.app_update_installer_unavailable)
    }

    fun setAutomaticCheckEnabled(enabled: Boolean) {
        preferencesStore.setAutomaticCheckEnabled(enabled)
    }

    fun dismissNotice() {
        val release = (phase.value as? UpdatePhase.Available)?.release ?: return
        preferencesStore.dismissVersion(release.version.versionCode)
        phase.value = UpdatePhase.Idle
    }

    /** Returns only the validated GitHub page carried by the current release. */
    fun releasePageUrl(): String? = when (val current = phase.value) {
        is UpdatePhase.Available -> current.release.htmlUrl
        is UpdatePhase.Downloading -> current.release.htmlUrl
        is UpdatePhase.Verifying -> current.release.htmlUrl
        is UpdatePhase.ReadyToInstall -> current.release.htmlUrl
        is UpdatePhase.Failed -> current.release?.htmlUrl
        UpdatePhase.Idle, UpdatePhase.Checking, UpdatePhase.UpToDate -> null
    }

    fun close() {
        updateJob?.cancel()
    }
}

private fun AppUpdateFailureReason.toUiText(statusCode: Int?): UiText = when (this) {
    AppUpdateFailureReason.INVALID_RESPONSE -> uiText(R.string.app_update_failed_invalid_response)
    AppUpdateFailureReason.NO_RELEASE -> uiText(R.string.app_update_failed_no_release)
    AppUpdateFailureReason.ASSET_MISSING -> uiText(R.string.app_update_failed_asset_missing)
    AppUpdateFailureReason.CHECKSUM_MISSING -> uiText(R.string.app_update_failed_checksum_missing)
    AppUpdateFailureReason.CHECKSUM_MISMATCH -> uiText(R.string.app_update_failed_checksum_mismatch)
    AppUpdateFailureReason.SIGNATURE_MISMATCH -> uiText(R.string.app_update_failed_signature_mismatch)
    AppUpdateFailureReason.RATE_LIMITED -> uiText(R.string.app_update_failed_rate_limited)
    AppUpdateFailureReason.HTTP_STATUS -> statusCode
        ?.let { uiText(R.string.app_update_failed_http, it) }
        ?: uiText(R.string.app_update_failed_network)
    AppUpdateFailureReason.RESPONSE_TOO_LARGE -> uiText(R.string.app_update_failed_too_large)
    AppUpdateFailureReason.INSECURE_REDIRECT -> uiText(R.string.app_update_failed_insecure)
    AppUpdateFailureReason.STORAGE -> uiText(R.string.app_update_failed_storage)
    AppUpdateFailureReason.NETWORK -> uiText(R.string.app_update_failed_network)
}
