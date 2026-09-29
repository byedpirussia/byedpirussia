package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import io.github.dovecoteescapee.byedpi.data.Mode

val PreferenceFragmentCompat.sharedPreferences
    get() = preferenceScreen.sharedPreferences

fun Context.getPreferences(): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(this)

fun SharedPreferences.getStringNotNull(key: String, defValue: String): String =
    getString(key, defValue) ?: defValue

fun SharedPreferences.mode(): Mode =
    Mode.fromString(getStringNotNull("byedpi_mode", "vpn"))

fun Context.applyAccentTheme(noActionBar: Boolean = true) {
    val accent = getPreferences().getString("accent_color", "blue") ?: "blue"
    val themeRes = when (accent) {
        "purple" -> if (noActionBar) io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Purple_NoActionBar else io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Purple
        "green" -> if (noActionBar) io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Green_NoActionBar else io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Green
        "orange" -> if (noActionBar) io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Orange_NoActionBar else io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Orange
        "red" -> if (noActionBar) io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Red_NoActionBar else io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_Red
        else -> if (noActionBar) io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI_NoActionBar else io.github.dovecoteescapee.byedpi.R.style.Theme_ByeDPI
    }
    setTheme(themeRes)
}

fun <T : Preference> PreferenceFragmentCompat.findPreferenceNotNull(key: CharSequence): T =
    findPreference(key) ?: throw IllegalStateException("Preference $key not found")

const val KEY_INITIAL_SETUP_DONE = "initial_setup_completed"
const val KEY_STAR_NEVER_SHOW = "star_dialog_never_show"
const val KEY_STAR_SHOW_COUNT = "star_dialog_show_count"
const val KEY_STAR_LAST_SHOW_TIME = "star_dialog_last_show_time"

fun Context.isInitialSetupDone(): Boolean =
    getPreferences().getBoolean(KEY_INITIAL_SETUP_DONE, false)

fun Context.setInitialSetupDone(done: Boolean) {
    getPreferences().edit().putBoolean(KEY_INITIAL_SETUP_DONE, done).apply()
}

fun Context.isStarNeverShow(): Boolean =
    getPreferences().getBoolean(KEY_STAR_NEVER_SHOW, false)

fun Context.setStarNeverShow(never: Boolean) {
    getPreferences().edit().putBoolean(KEY_STAR_NEVER_SHOW, never).apply()
}
