package io.github.dovecoteescapee.byedpi.security

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.utility.getPreferences

object CamouflageManager {
    private const val TAG = "CamouflageManager"
    const val PREF_CAMOUFLAGE_MODE = "pref_app_camouflage_mode"
    const val PREF_FAKE_UI_ENABLED = "pref_camouflage_fake_ui_enabled"
    const val PREF_UNLOCK_CODE = "pref_camouflage_unlock_code"
    const val DEFAULT_UNLOCK_CODE = "1337"

    // Session state: if user unlocked the camouflage in current process
    @Volatile
    var isUnlockedInSession: Boolean = false

    enum class DisguiseMode(
        val key: String,
        val aliasSimpleName: String,
        val titleRes: Int
    ) {
        DEFAULT("default", "MainActivity", R.string.app_name),
        CALCULATOR("calculator", "MainActivityCalculator", R.string.disguise_calculator),
        NOTES("notes", "MainActivityNotes", R.string.disguise_notes),
        CLOCK("clock", "MainActivityClock", R.string.disguise_clock);

        companion object {
            fun fromKey(key: String?): DisguiseMode {
                return entries.find { it.key.equals(key, ignoreCase = true) } ?: DEFAULT
            }
        }
    }

    fun getCurrentDisguise(context: Context): DisguiseMode {
        val key = context.getPreferences().getString(PREF_CAMOUFLAGE_MODE, DisguiseMode.DEFAULT.key)
        return DisguiseMode.fromKey(key)
    }

    fun isFakeUiEnabled(context: Context): Boolean {
        // If disguise is active (not DEFAULT), default to true unless user explicitly turned it off
        return context.getPreferences().getBoolean(PREF_FAKE_UI_ENABLED, true)
    }

    fun setFakeUiEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit()
            .putBoolean(PREF_FAKE_UI_ENABLED, enabled)
            .apply()
    }

    fun getUnlockCode(context: Context): String {
        return context.getPreferences().getString(PREF_UNLOCK_CODE, DEFAULT_UNLOCK_CODE)
            ?.ifBlank { DEFAULT_UNLOCK_CODE } ?: DEFAULT_UNLOCK_CODE
    }

    fun setUnlockCode(context: Context, code: String) {
        val cleanCode = if (code.isBlank()) DEFAULT_UNLOCK_CODE else code.trim()
        context.getPreferences().edit()
            .putString(PREF_UNLOCK_CODE, cleanCode)
            .apply()
    }

    fun getDisguiseTitle(context: Context, mode: DisguiseMode): String {
        return context.getString(mode.titleRes)
    }

    /**
     * Updates activity window title and Recent Apps task description so the
     * multitasking switcher and titlebar show the disguised app name.
     */
    fun updateActivityIdentity(activity: Activity, mode: DisguiseMode): String {
        val title = getDisguiseTitle(activity, mode)
        try {
            activity.title = title
            @Suppress("DEPRECATION")
            val taskDesc = ActivityManager.TaskDescription(title)
            activity.setTaskDescription(taskDesc)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update TaskDescription", e)
        }
        return title
    }

    fun applyDisguise(context: Context, newMode: DisguiseMode): Boolean {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val packageName = appContext.packageName

        return try {
            // Enable target alias first
            val targetComponent = ComponentName(
                packageName,
                "io.github.dovecoteescapee.byedpi.activities.${newMode.aliasSimpleName}"
            )
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Disable other aliases
            for (mode in DisguiseMode.entries) {
                if (mode != newMode) {
                    val comp = ComponentName(
                        packageName,
                        "io.github.dovecoteescapee.byedpi.activities.${mode.aliasSimpleName}"
                    )
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }

            appContext.getPreferences().edit()
                .putString(PREF_CAMOUFLAGE_MODE, newMode.key)
                .apply()

            // Reset session unlock state when changing disguise
            isUnlockedInSession = false

            Log.i(TAG, "Applied disguise mode: ${newMode.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply disguise mode: ${newMode.name}", e)
            false
        }
    }
}
