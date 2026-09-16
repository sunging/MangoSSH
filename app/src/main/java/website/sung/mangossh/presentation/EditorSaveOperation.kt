package website.sung.mangossh.presentation

import androidx.compose.runtime.*

/** A single editor instance owns completion; disposed editors cannot receive late save callbacks. */
@Stable
class EditorSaveOperation(private val onSaved: () -> Unit = {}) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<UiText?>(null); private set
    private var active = true
    /** Starts one save on the UI thread; repeated submissions are ignored. */
    fun begin(): Boolean { if (!active || busy) return false; busy = true; error = null; return true }
    /** Whether this editor still owns an unfinished save or authorization. */
    val pending: Boolean get() = active && busy
    /** Publishes failure without dismissing, or invokes success once while the editor is alive. */
    fun finish(failure: UiText?) {
        if (!pending) return
        busy = false; error = failure
        if (failure == null) onSaved()
    }
    /** Cancels pending authorization without interpreting cancellation as saved data. */
    fun cancel() { busy = false }
    /** Revokes the editor lifetime before late authentication or persistence results arrive. */
    fun dispose() { active = false; busy = false }
}
