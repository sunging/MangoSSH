package website.sung.mangossh.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * Immutable identity of the running build, read from the installed package
 * rather than `BuildConfig` (which this project does not generate).
 */
data class InstalledAppInfo(val versionName: String, val versionCode: Long)

/**
 * Reads the running package's version from the platform.
 *
 * The installed package is authoritative because the system package installer
 * compares these same values when deciding whether a downloaded APK is an
 * upgrade, so the update flow must agree with it rather than a separately
 * materialized copy.
 */
internal fun Context.installedAppInfo(): InstalledAppInfo? = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION") // The flag-object overload does not exist before API 33.
        packageManager.getPackageInfo(packageName, 0)
    }
    InstalledAppInfo(
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info),
    )
}.getOrNull()
