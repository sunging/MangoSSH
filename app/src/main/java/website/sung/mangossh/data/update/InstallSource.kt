package website.sung.mangossh.data.update

import android.content.Context
import android.os.Build

/**
 * Distribution channels that already own updates for the apps they install.
 *
 * Self-update must stay hidden there: a store-managed install already
 * receives updates through its own client, and offering a sideloaded APK
 * alongside it would fragment that channel and, for F-Droid, violate its
 * no-self-updater policy.
 */
private val MANAGED_INSTALLERS = setOf(
    "com.android.vending", // Google Play
    "org.fdroid.fdroid", // F-Droid
    "org.fdroid.basic", // F-Droid Basic
    "org.fdroid.fdroid.privileged", // F-Droid Privileged Extension
    "com.machiav3lli.fdroid", // Neo Store
    "com.looker.droidify", // Droid-ify
    "nya.kitsunyan.foxydroid", // Foxy Droid
    "com.aurora.store", // Aurora Store
    "com.amazon.venezia",
    "com.huawei.appmarket",
    "com.sec.android.app.samsungapps",
    "com.xiaomi.market",
    "com.heytap.market",
    "com.oppo.market",
    "com.vivo.appstore",
)

/**
 * Reports whether this install is responsible for its own updates.
 *
 * An unknown or absent installer means the package was sideloaded (adb, a
 * file manager, or the system installer), which is exactly the case the
 * in-app updater serves. Failing open here costs at most an unused button,
 * whereas failing closed would silently break sideloaders using a niche
 * installer not in [MANAGED_INSTALLERS].
 */
internal fun Context.selfUpdateSupported(): Boolean {
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") // getInstallSourceInfo does not exist before API 30.
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()
    return installer == null || installer !in MANAGED_INSTALLERS
}
