package io.github.dovecoteescapee.byedpi.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.utility.getPreferences

object CamouflageManager {
    private const val TAG = "CamouflageManager"
    const val PREF_CAMOUFLAGE_MODE = "pref_app_camouflage_mode"

    enum class DisguiseMode(
        val key: String,
        val aliasSimpleName: String,
        val titleRes: Int
    ) {
        DEFAULT("default", "MainActivityDefault", R.string.disguise_default),
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

            Log.i(TAG, "Applied disguise mode: ${newMode.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply disguise mode: ${newMode.name}", e)
            false
        }
    }
}
