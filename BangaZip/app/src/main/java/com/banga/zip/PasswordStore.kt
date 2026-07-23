package com.banga.zip

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores archive passwords locally in the app's private SharedPreferences.
 * Data lives at /data/data/com.banga.zip/shared_prefs/ and is only
 * accessible by this app.
 */
class PasswordStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored archive password (null = no saved password). */
    var savedPassword: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) {
            prefs.edit()
                .putString(KEY_PASSWORD, if (value.isNullOrEmpty()) null else value)
                .apply()
        }

    /** Whether the user wants the password remembered across sessions. */
    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    /** Remove the saved password without touching the remember flag. */
    fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    companion object {
        private const val PREFS_NAME = "banga_zip_secure_prefs"
        private const val KEY_PASSWORD = "archive_password"
        private const val KEY_REMEMBER = "remember_password"
    }
}
