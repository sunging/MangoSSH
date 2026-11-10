package website.sung.mangossh.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R
import website.sung.mangossh.security.AppLockConfiguration

/** A locked app can accept a PIN or start a separately authorized platform biometric prompt. */
@Composable
internal fun AppLockScreen(
    state: PinVerificationState,
    configuration: AppLockConfiguration,
    message: UiText?,
    onUnlockWithPin: (String) -> Unit,
    onRequestBiometric: (() -> Unit)?,
    onDismissMessage: () -> Unit,
    onEdit: () -> Unit = {},
) {
    val autoSubmitLength = configuration.pinLength
    PinEntry(
        title = stringResource(R.string.pin_enter_title),
        actionLabel = if (autoSubmitLength == null) {
            stringResource(if (state.busy) R.string.app_lock_verifying else R.string.pin_unlock)
        } else null,
        autoSubmitLength = autoSubmitLength,
        feedback = if (state.busy && autoSubmitLength != null) stringResource(R.string.app_lock_verifying)
        else state.feedback() ?: message?.asString(),
        feedbackIsError = !state.busy,
        inputEnabled = state.canEnter,
        secondaryLabel = stringResource(R.string.ui_use_biometrics),
        secondaryIcon = Icons.Outlined.Fingerprint,
        onSecondary = onRequestBiometric.takeIf { configuration.pinConfigured && configuration.biometricEnabled },
        secondaryEnabled = !state.busy,
        onEdit = {
            onEdit()
            if (message != null) onDismissMessage()
        },
        onSubmit = {
            if (message != null) onDismissMessage()
            onUnlockWithPin(it)
        },
    )
}

/** Verification grants the pending action only through the coordinator's existing PIN callback. */
@Composable
internal fun SensitivePinDialog(
    state: PinVerificationState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {},
) {
    PinEntryDialog(onDismiss) {
        PinEntry(
            title = stringResource(R.string.pin_verify_identity),
            actionLabel = stringResource(if (state.busy) R.string.app_lock_verifying else R.string.pin_verify),
            feedback = state.feedback(),
            feedbackIsError = true,
            inputEnabled = state.canEnter,
            secondaryLabel = stringResource(R.string.common_cancel),
            onSecondary = onDismiss,
            onEdit = onEdit,
            onSubmit = onSubmit,
        )
    }
}

/** Both entries stay in composition memory. A recreated editor starts at its first step. */
@Composable
internal fun ConfigurePinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val confirming = firstPin != null
    val back = {
        if (firstPin == null) onDismiss()
        else {
            firstPin = null
            mismatch = false
        }
    }
    PinEntryDialog(back) {
        key(confirming) {
            PinEntry(
                title = stringResource(if (confirming) R.string.pin_confirm_title else R.string.pin_new_title),
                actionLabel = stringResource(if (confirming) R.string.common_save else R.string.pin_next),
                feedback = if (mismatch) stringResource(R.string.pin_mismatch) else null,
                feedbackIsError = mismatch,
                secondaryLabel = stringResource(if (confirming) R.string.settings_back else R.string.common_cancel),
                onSecondary = back,
                onEdit = { mismatch = false },
                onSubmit = { entered ->
                    val original = firstPin
                    when {
                        original == null -> firstPin = entered
                        original == entered -> {
                            firstPin = null
                            onConfirm(entered)
                        }
                        else -> mismatch = true
                    }
                },
            )
        }
    }
}

private val PinVerificationState.canEnter: Boolean
    get() = !busy && cooldownSeconds == 0

@Composable
private fun PinVerificationState.feedback(): String? = when {
    cooldownSeconds > 0 -> pluralStringResource(R.plurals.message_pin_cooldown, cooldownSeconds, cooldownSeconds)
    incorrect -> stringResource(R.string.message_pin_incorrect)
    else -> null
}
