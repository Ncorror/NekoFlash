package ru.forum.adbfastboottool

import android.content.Context

/**
 * Versioned risk acknowledgement plus a process/task scoped entry session.
 *
 * The persisted acknowledgement avoids forcing the user to re-read the risk
 * text after every launch, while [sessionAuthorized] deliberately lives only
 * in memory. A cold process start therefore always opens WelcomeActivity.
 * MainActivity also ends the session when its task is actually finished, so a
 * normal close/remove-from-recents launch returns through the welcome screen.
 *
 * Intent extras are never trusted as a bypass: required storage permission,
 * persisted acknowledgement and the current in-memory session must all pass.
 */
object OnboardingGate {
    private const val PREFS_NAME = "nekoflash_onboarding"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_RISK_ACCEPTED = "risk_accepted"
    private const val CURRENT_SCHEMA_VERSION = 1

    @Volatile
    private var sessionAuthorized = false

    fun isCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SCHEMA_VERSION, 0) == CURRENT_SCHEMA_VERSION &&
            prefs.getBoolean(KEY_RISK_ACCEPTED, false)
    }

    fun isSessionAuthorized(): Boolean = sessionAuthorized

    /**
     * MainActivity is allowed only after the current launch session has passed
     * WelcomeActivity and the mandatory storage permission is still valid.
     */
    fun canEnterMain(context: Context): Boolean =
        sessionAuthorized &&
            isCompleted(context) &&
            PermissionGate.areAllRequiredGranted(context)

    fun complete(context: Context): Boolean {
        if (!PermissionGate.areAllRequiredGranted(context)) return false
        val persisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putBoolean(KEY_RISK_ACCEPTED, true)
            .commit()
        if (persisted) sessionAuthorized = true
        return persisted
    }

    /** Ends only the current app-entry session; the risk checkbox remains saved. */
    fun endSession() {
        sessionAuthorized = false
    }

    fun reset(context: Context) {
        endSession()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
