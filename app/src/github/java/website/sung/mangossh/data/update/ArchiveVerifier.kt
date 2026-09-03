package website.sung.mangossh.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Confirms a downloaded, digest-verified archive is a genuine upgrade of this
 * exact package before the system installer is invoked.
 *
 * The platform enforces the same signing-certificate and version rules at
 * install time and would reject a mismatch there too. This pre-check exists
 * so a mismatch produces an explained, in-app failure instead of an opaque
 * system installer error — most commonly hit by a developer with a
 * differently-signed debug build tapping Install. It is defence in depth, not
 * a replacement for the platform's own enforcement.
 */
internal fun Context.archiveMatchesInstalledSigner(archive: File): Boolean {
    val archiveInfo = runCatching {
        packageManager.archiveInfoWithSigningCertificates(archive.absolutePath)
    }.getOrNull() ?: return false

    if (archiveInfo.packageName != packageName) return false

    val installedInfo = runCatching {
        packageManager.installedInfoWithSigningCertificates(packageName)
    }.getOrNull() ?: return false

    if (PackageInfoCompat.getLongVersionCode(archiveInfo) < PackageInfoCompat.getLongVersionCode(installedInfo)) {
        return false
    }

    val archiveDigests = signerDigests(archiveInfo)
    val installedDigests = signerDigests(installedInfo)
    return archiveDigests.isNotEmpty() &&
        archiveDigests.size == installedDigests.size &&
        archiveDigests.all { digest -> installedDigests.any { MessageDigest.isEqual(it, digest) } }
}

/** The `PackageManager.GET_SIGNING_CERTIFICATES`/`GET_SIGNATURES` overload matching this API level. */
private fun PackageManager.archiveInfoWithSigningCertificates(path: String): PackageInfo? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
        @Suppress("DEPRECATION") // The flag-object overload does not exist before API 33.
        getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    else -> {
        @Suppress("DEPRECATION") // GET_SIGNING_CERTIFICATES does not exist before API 28.
        getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
    }
}

private fun PackageManager.installedInfoWithSigningCertificates(packageName: String): PackageInfo? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
        @Suppress("DEPRECATION") // The flag-object overload does not exist before API 33.
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    else -> {
        @Suppress("DEPRECATION") // GET_SIGNING_CERTIFICATES does not exist before API 28.
        getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }
}

private fun signerDigests(info: PackageInfo): List<ByteArray> {
    val signatures: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = info.signingInfo
        when {
            signingInfo == null -> emptyArray()
            signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
            else -> signingInfo.signingCertificateHistory
        }
    } else {
        @Suppress("DEPRECATION")
        info.signatures ?: emptyArray()
    }
    val sha256 = MessageDigest.getInstance("SHA-256")
    return signatures.map { sha256.digest(it.toByteArray()) }
}
