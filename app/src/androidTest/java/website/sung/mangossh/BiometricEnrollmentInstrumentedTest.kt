package website.sung.mangossh

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.biometric.BiometricManager
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

/** Provisions only a fresh task-owned AVD; never changes credentials or enrollment on existing devices. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class BiometricEnrollmentInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun enrollDisposableSensor() {
        BiometricTestEnvironment.requireDisposableAvd()
        val context = instrumentation.targetContext
        assertFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val pin = CharArray(6) { ('0'.code + SecureRandom().nextInt(10)).toChar() }.concatToString()
        // Output from locksettings contains the generated credential and must never be logged.
        val result = shell("locksettings set-pin $pin")
        assertTrue("Disposable credential creation failed", !result.contains("Error", ignoreCase = true))
        context.startActivity(Intent(Settings.ACTION_FINGERPRINT_ENROLL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.sendStatus(1, Bundle().apply { putString("biometricPhase", "enroll") })
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var suppliedPin = false
        while (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS &&
            SystemClock.elapsedRealtime() < deadline) {
            automation.rootInActiveWindow?.let { root ->
                try {
                    if (!suppliedPin && visit(root) { node ->
                            node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin)
                            })
                        }) {
                        suppliedPin = true
                        shell("input keyevent 66")
                    } else visit(root) { node ->
                        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
                        val label = node.text?.toString().orEmpty().lowercase()
                        val eligible = id in setOf("next_button", "next", "fingerprint_enroll_button", "enroll_button", "button1") ||
                            label in setOf("i agree", "agree", "next", "more", "start", "continue", "set up", "done", "fingerprint", "fingerprint unlock")
                        eligible && node.isEnabled && click(node)
                    }
                } finally { root.recycle() }
            }
            SystemClock.sleep(250)
        }
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = node.parent ?: return false
        return try { if (parent.isClickable) parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) else false }
            finally { parent.recycle() }
    }

    private fun visit(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (action(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, action)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
    }
}
