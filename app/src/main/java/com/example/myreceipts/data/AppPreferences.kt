package com.example.myreceipts.data

import android.content.Context
import android.content.SharedPreferences

data class UserSession(
    val userId: String,
    val email: String?
)

class AppPreferences(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var notificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var themeMode: String
        get() = preferences.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = preferences.edit().putString(KEY_THEME_MODE, value).apply()

    fun saveSession(userId: String, email: String? = null) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        preferences.edit()
            .putString(KEY_SESSION_USER_ID, userId)
            .putString(KEY_SESSION_EMAIL, email)
            .apply()
    }

    fun getSession(): UserSession? {
        val userId = preferences.getString(KEY_SESSION_USER_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return UserSession(userId, preferences.getString(KEY_SESSION_EMAIL, null))
    }

    fun clearSession() {
        preferences.edit()
            .remove(KEY_SESSION_USER_ID)
            .remove(KEY_SESSION_EMAIL)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "my_receipts_preferences"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SESSION_USER_ID = "session_user_id"
        const val KEY_SESSION_EMAIL = "session_email"
        const val THEME_SYSTEM = "system"
    }
}
