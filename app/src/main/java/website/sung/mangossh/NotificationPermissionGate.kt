package website.sung.mangossh

import android.content.SharedPreferences

/**
 * Remembers a notification denial across connections and activity/process recreation.
 * A denied permission must not turn every connection into another permission request
 * and explanation. Observing a later grant resets the decision for a future revocation.
 * This preference contains no session or account data.
 */
internal class NotificationPermissionGate(private val preferences: SharedPreferences) {
    /** Requests only an undecided permission and observes grants made in Android settings. */
    fun shouldRequest(granted: Boolean): Boolean {
        if (granted) {
            preferences.edit().remove(DENIED).apply()
            return false
        }
        return !preferences.getBoolean(DENIED, false)
    }

    /** Records the result before continuing the connection; returns whether to explain a new denial. */
    fun onResult(granted: Boolean): Boolean {
        val explain = !granted && !preferences.getBoolean(DENIED, false)
        preferences.edit().putBoolean(DENIED, !granted).apply()
        return explain
    }

    private companion object {
        const val DENIED = "notification_permission_denied"
    }
}
