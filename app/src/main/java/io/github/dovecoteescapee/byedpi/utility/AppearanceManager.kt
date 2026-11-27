package io.github.dovecoteescapee.byedpi.utility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import java.io.File

object AppearanceManager {
    private const val PREFS_NAME = "appearance_prefs"

    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_THEME_MODE = "theme_mode" // system, light, dark, amoled
    private const val KEY_FONT_MODE = "font_mode" // google_sans, system, custom
    private const val KEY_CARD_RADIUS = "card_radius_dp" // 12, 24, 32
    private const val KEY_FONT_SCALE = "font_scale" // 0.9, 1.0, 1.15
    private const val KEY_PRIVACY_MODE = "privacy_mode"
    private const val KEY_ANIMATIONS = "animations_enabled"
    private const val KEY_LANGUAGE = "app_language" // system, ru, en, fa

    const val DEFAULT_ACCENT = 0xFF3F51B5.toInt()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAccentColor(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT)

    fun setAccentColor(context: Context, color: Int) =
        prefs(context).edit { putInt(KEY_ACCENT_COLOR, color) }

    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) =
        prefs(context).edit { putString(KEY_THEME_MODE, mode) }

    fun getFontMode(context: Context): String =
        prefs(context).getString(KEY_FONT_MODE, "google_sans") ?: "google_sans"

    fun setFontMode(context: Context, mode: String) =
        prefs(context).edit { putString(KEY_FONT_MODE, mode) }

    fun getCardRadiusDp(context: Context): Int =
        prefs(context).getInt(KEY_CARD_RADIUS, 24)

    fun setCardRadiusDp(context: Context, dp: Int) =
        prefs(context).edit { putInt(KEY_CARD_RADIUS, dp) }

    fun getFontScale(context: Context): Float =
        prefs(context).getFloat(KEY_FONT_SCALE, 1.0f)

    fun setFontScale(context: Context, scale: Float) =
        prefs(context).edit { putFloat(KEY_FONT_SCALE, scale) }

    fun isPrivacyMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY_MODE, false)

    fun setPrivacyMode(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_PRIVACY_MODE, enabled) }

    fun isAnimationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANIMATIONS, true)

    fun setAnimationsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ANIMATIONS, enabled) }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "system") ?: "system"

    fun setLanguage(context: Context, lang: String) =
        prefs(context).edit { putString(KEY_LANGUAGE, lang) }

    fun getCustomFontFile(context: Context): File =
        File(context.filesDir, "custom_font.ttf")

    fun saveCustomFont(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                getCustomFontFile(context).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            setFontMode(context, "custom")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getActiveTypeface(context: Context): Typeface? {
        return when (getFontMode(context)) {
            "system" -> Typeface.DEFAULT
            "custom" -> {
                val f = getCustomFontFile(context)
                if (f.exists()) {
                    try {
                        Typeface.createFromFile(f)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }
            else -> null // google_sans is handled by theme
        }
    }

    fun applyThemeAndLocale(context: Context) {
        val nightMode = when (getThemeMode(context)) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark", "amoled" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    fun applyFontScale(context: Context, baseConfig: Configuration): Configuration {
        val scale = getFontScale(context)
        if (scale == 1.0f) return baseConfig
        val config = Configuration(baseConfig)
        config.fontScale = scale
        return config
    }

    fun applyPostInflation(activity: Activity) {
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (getThemeMode(activity) == "amoled") {
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }
        val tf = getActiveTypeface(activity)
        val accent = getAccentColor(activity)
        applyStyleRecursive(root, tf, accent)
    }

    private fun applyStyleRecursive(view: View, tf: Typeface?, accent: Int) {
        if (tf != null && view is TextView) {
            view.typeface = tf
        }
        if (view is com.google.android.material.switchmaterial.SwitchMaterial || view is com.google.android.material.materialswitch.MaterialSwitch) {
            if (view is androidx.appcompat.widget.SwitchCompat) {
                view.thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            }
        } else if (view is com.google.android.material.button.MaterialButton && view.strokeWidth == 0) {
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyStyleRecursive(view.getChildAt(i), tf, accent)
            }
        }
    }

    fun restartApp(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: Intent(activity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finish()
    }
}
