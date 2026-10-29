package website.sung.mangossh.presentation

import android.os.SystemClock
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.domain.AppLockDelay
import website.sung.mangossh.domain.shouldLockOnResume
import website.sung.mangossh.domain.shouldLockWhenBackgrounded
import website.sung.mangossh.security.AppAccessState
import website.sung.mangossh.security.AppLockStore
import website.sung.mangossh.security.DocumentPickerRequests
import website.sung.mangossh.security.ReauthenticationMode
import website.sung.mangossh.security.ReauthenticationWindow

/**
 * Owns app-lock grants independently of an Activity. All entry points run on the main thread;
 * PBKDF2 runs on [cryptoDispatcher], and completions recheck the grant generation before acting.
 * Dependencies allow device tests to use isolated preferences, aliases and session controllers.
 */
internal class AppLockCoordinator(
    private val store: AppLockStore,
    private val access: AppAccessState,
    private val scope: CoroutineScope,
    private val onLocked: () -> Unit,
    private val message: (UiText) -> Unit,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableConfiguration = MutableStateFlow(store.configuration())
    val configuration = mutableConfiguration.asStateFlow()
    val locked = access.locked.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableReauthentication = MutableStateFlow(false)
    val reauthenticationPending = mutableReauthentication.asStateFlow()
    private val reauthenticationWindow = ReauthenticationWindow(access)
    private var pendingAction: (() -> Unit)? = null
    private var pendingCancel: (() -> Unit)? = null
    private var approvedAction = false
    private var backgroundedAt: Long? = null
    val documents = DocumentPickerRequests(access) { backgroundedAt = null }

    /** The cipher and authorization epoch are memory-only and survive Activity recreation together. */
    class BiometricRequest internal constructor(val cipher: Cipher, internal val generation: Long) {
        var failedAttempts: Int = 0
            internal set
    }
    var biometricRequest: BiometricRequest? = null
        private set

    /** Queues at most one action; unlocking the app does not itself grant a sensitive-action approval. */
    fun authorizeSensitive(override: Boolean? = null, onCancelled: (() -> Unit)? = null, action: () -> Unit): Boolean {
        if (locked.value) { onCancelled?.invoke(); return false }
        val mode = configuration.value.reauthentication
        val required = override ?: (mode != ReauthenticationMode.DISABLED)
        if (!required || approvedAction || (mode == ReauthenticationMode.FIVE_MINUTES && reauthenticationWindow.isValid())) return true
        if (!configuration.value.pinConfigured) {
            message(uiText(R.string.message_app_pin_required)); onCancelled?.invoke(); return false
        }
        if (pendingAction == null) {
            pendingAction = action; pendingCancel = onCancelled; mutableReauthentication.value = true
        } else onCancelled?.invoke()
        return false
    }

    /** Detaches the action before invoking its cancellation callback, which may queue another action. */
    fun cancelReauthentication() {
        pendingAction = null
        val cancel = pendingCancel
        pendingCancel = null
        mutableReauthentication.value = false
        cancel?.invoke()
    }

    /** A cancelled or replaced action cannot inherit an in-flight PIN verification. */
    fun verifySensitiveAction(pin: String) {
        if (busy.value || pendingAction == null) return
        val action = pendingAction
        verify(pin) { accepted, generation ->
            if (pendingAction !== action) return@verify
            if (accepted && generation == access.generation && !locked.value) {
                reauthenticationWindow.grant()
                pendingCancel = null
                cancelReauthentication()
                approvedAction = true
                try { action?.invoke() } finally { approvedAction = false }
            } else incorrectPin()
        }
    }

    /** Derives the verifier off the main thread and preserves any intervening background lock. */
    fun configurePin(pin: String) {
        if (!authorizeSensitive { configurePin(pin) } || busy.value) return
        mutableBusy.value = true
        val generation = access.generation
        val chars = pin.toCharArray()
        scope.launch {
            try {
                withContext(cryptoDispatcher) { store.setPin(chars) }
                refresh()
                if (generation == access.generation) {
                    if (backgroundedAt != null && shouldLockWhenBackgrounded(configuration.value.autoLockDelay)) lock()
                    else access.setLocked(false)
                }
                message(uiText(R.string.message_app_lock_enabled))
            } catch (_: IllegalArgumentException) {
                message(uiText(R.string.message_pin_invalid, AppLockStore.MIN_PIN_LENGTH, AppLockStore.MAX_PIN_LENGTH))
            } finally { chars.fill('\u0000'); mutableBusy.value = false }
        }
    }

    /** Disables local protection only after the current sensitive-action policy permits it. */
    fun clear() {
        if (!authorizeSensitive { clear() }) return
        // Invalidate even if a settings screen is removed while a picker or prompt is pending.
        documents.invalidate(); biometricRequest = null
        store.clear(); refresh(); access.setLocked(false)
        message(uiText(R.string.message_app_lock_disabled))
    }

    /** Requires an existing PIN; disabling biometrics also revokes an outstanding prompt. */
    fun setBiometricEnabled(enabled: Boolean) {
        runCatching { store.setBiometricEnabled(enabled) }
            .onSuccess { if (!enabled) biometricRequest = null; refresh() }
            .onFailure { message(uiText(R.string.message_app_pin_required)) }
    }

    /** Changes the grace period without changing the current lock state. */
    fun setAutoLockDelay(delay: AppLockDelay) {
        runCatching { store.setAutoLockDelay(delay) }.onSuccess { refresh() }
            .onFailure { message(uiText(R.string.message_app_pin_required)) }
    }

    /** Changes to the approval policy must first satisfy the existing policy. */
    fun setReauthentication(mode: ReauthenticationMode) {
        if (!authorizeSensitive { setReauthentication(mode) }) return
        store.setReauthentication(mode); refresh()
    }

    /** Explicit locking also invalidates operations whose external UI has not returned yet. */
    fun lock() {
        documents.invalidate(); biometricRequest = null; backgroundedAt = null
        if (configuration.value.pinConfigured) {
            access.setLocked(true); cancelReauthentication(); onLocked()
        }
    }

    /** A live document grant excludes this stop from the ordinary background timer. */
    fun noteBackgrounded(now: Long = SystemClock.elapsedRealtime()) {
        if (documents.active) return
        backgroundedAt = now
        if (configuration.value.pinConfigured && shouldLockWhenBackgrounded(configuration.value.autoLockDelay)) lock()
    }

    /** Consumes one monotonic timestamp; cold starts and configuration-only starts have none. */
    fun evaluateAutoLock(now: Long = SystemClock.elapsedRealtime()) {
        if (documents.active) return
        val previous = backgroundedAt ?: return
        backgroundedAt = null
        if (configuration.value.pinConfigured && shouldLockOnResume(configuration.value.autoLockDelay, previous, now)) lock()
    }

    /** Screen-off revokes picker exemptions even while the host Activity is stopped. */
    fun screenOff() { if (documents.active) lock() }

    /** Only a successful verification from the current authorization generation may unlock. */
    fun unlockWithPin(pin: String) {
        if (busy.value) return
        verify(pin) { accepted, generation ->
            if (accepted && generation == access.generation) {
                biometricRequest = null
                access.setLocked(false)
            } else incorrectPin()
        }
    }

    /** Retains the latest cipher by identity so replaced prompts lose their authority. */
    fun beginBiometricUnlock(cipher: Cipher): BiometricRequest? {
        if (!locked.value || busy.value || !configuration.value.pinConfigured || !configuration.value.biometricEnabled) return null
        return BiometricRequest(cipher, access.generation).also { biometricRequest = it }
    }

    /** Identity, epoch and Keystore authorization are all required; a success callback alone is insufficient. */
    fun completeBiometricUnlock(request: BiometricRequest, cipher: Cipher?): Boolean {
        if (biometricRequest !== request) return false
        biometricRequest = null
        if (request.generation != access.generation || !locked.value || !configuration.value.biometricEnabled ||
            !configuration.value.pinConfigured || cipher !== request.cipher) return false
        if (runCatching { request.cipher.doFinal() }.isFailure) return false
        access.setLocked(false)
        return true
    }

    /** An old prompt's cancellation must not discard its replacement. */
    fun cancelBiometricUnlock(request: BiometricRequest) { if (biometricRequest === request) biometricRequest = null }

    /** A rejected sensor match may retry the same request, but never changes access or PIN grants. */
    fun rejectBiometricAttempt(request: BiometricRequest) {
        if (biometricRequest === request) request.failedAttempts++
    }

    private fun refresh() { mutableConfiguration.value = store.configuration() }

    private fun verify(pin: String, completed: (Boolean, Long) -> Unit) {
        mutableBusy.value = true
        val generation = access.generation
        val chars = pin.toCharArray()
        scope.launch {
            try { completed(withContext(cryptoDispatcher) { store.verifyPin(chars) }, generation) }
            finally { chars.fill('\u0000'); mutableBusy.value = false }
        }
    }

    private fun incorrectPin() {
        val remaining = store.cooldownRemainingMillis()
        message(if (remaining > 0) ((remaining + 999) / 1000).toInt().let { uiPluralText(R.plurals.message_pin_cooldown, it, it) }
            else uiText(R.string.message_pin_incorrect))
    }
}
