package website.sung.mangossh.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts

/** Creates documents with the available platform picker to keep suggested filenames intact. */
internal class SystemDocumentContract(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).preferPlatformDocumentPicker(context)
}

/** Prefers an eligible platform picker, retaining an already selected platform package. */
internal object SystemDocumentPickers {
    private val PLATFORM_PACKAGES = listOf("com.android.documentsui", "com.google.android.documentsui")

    /** Keeps the existing handler when no eligible platform picker is installed. */
    fun preferredPackage(defaultPackage: String?, systemPackages: Set<String>): String? {
        if (defaultPackage in PLATFORM_PACKAGES && defaultPackage in systemPackages) return defaultPackage
        return PLATFORM_PACKAGES.firstOrNull { it in systemPackages }
    }
}

/**
 * Save dialogs can append a second suffix to dotfiles, even with an unspecified MIME type.
 * Prefer the device's platform DocumentsUI when available; it receives the same complete filename,
 * MIME type and location hint, and still obtains the user's document-specific permission grant.
 * Resolve only enabled, exported system activities that this app is allowed to launch. Nothing
 * is renamed afterward, and the user's system-wide default picker is never changed.
 */
internal fun Intent.preferPlatformDocumentPicker(context: Context): Intent {
    val manager = context.packageManager
    val defaultPackage = manager.resolveActivity(this, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val candidates = manager.queryIntentActivities(this,
        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).mapNotNull { result ->
        val activity = result.activityInfo ?: return@mapNotNull null
        val permission = activity.permission
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        activity.takeIf {
            it.enabled && it.exported && (it.applicationInfo.flags and systemFlags) != 0 &&
                (permission.isNullOrEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
    }
    val preferred = SystemDocumentPickers.preferredPackage(defaultPackage, candidates.map { it.packageName }.toSet())
        ?: return this
    val activity = candidates.first { it.packageName == preferred }
    component = ComponentName(activity.packageName, activity.name)
    return this
}
