package io.github.dovecoteescapee.byedpi.utility

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.AppearanceActivity
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.activities.SettingsActivity
import io.github.dovecoteescapee.byedpi.activities.UnifiedSettingsActivity

object BottomNavHelper {
    enum class Tab { HOME, SETTINGS, CORE, APPEARANCE }

    fun setup(activity: Activity, activeTab: Tab) {
        val navCard = activity.findViewById<View>(R.id.floating_bottom_nav) ?: return

        ViewCompat.setOnApplyWindowInsetsListener(navCard) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val newMargin = navBars.bottom + (16 * activity.resources.displayMetrics.density).toInt()
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.bottomMargin != newMargin) {
                lp.bottomMargin = newMargin
                v.layoutParams = lp
            }
            insets
        }

        val tabs = listOf(
            Triple(Tab.HOME, R.id.nav_home, Pair(R.id.nav_home_icon, R.id.nav_home_text)),
            Triple(Tab.SETTINGS, R.id.nav_settings, Pair(R.id.nav_settings_icon, R.id.nav_settings_text)),
            Triple(Tab.CORE, R.id.nav_core, Pair(R.id.nav_core_icon, R.id.nav_core_text)),
            Triple(Tab.APPEARANCE, R.id.nav_appearance, Pair(R.id.nav_appearance_icon, R.id.nav_appearance_text))
        )

        val accent = AppearanceManager.getAccentColor(activity)

        tabs.forEach { (tab, containerId, pair) ->
            val container = activity.findViewById<View>(containerId) ?: return@forEach
            val icon = activity.findViewById<ImageView>(pair.first)
            val text = activity.findViewById<TextView>(pair.second)

            if (tab == activeTab) {
                container.setBackgroundResource(R.drawable.bg_nav_pill_active)
                icon?.setColorFilter(accent)
                text?.setTextColor(accent)
                text?.setTypeface(text.typeface, Typeface.BOLD)
            } else {
                container.background = null
            }

            container.setOnClickListener {
                if (tab == activeTab) return@setOnClickListener
                val intent = if (tab == Tab.HOME) {
                    (activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                        ?: Intent(activity, MainActivity::class.java)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                } else {
                    val targetClass = when (tab) {
                        Tab.SETTINGS -> UnifiedSettingsActivity::class.java
                        Tab.CORE -> SettingsActivity::class.java
                        Tab.APPEARANCE -> AppearanceActivity::class.java
                        else -> MainActivity::class.java
                    }
                    Intent(activity, targetClass).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                }
                activity.startActivity(intent)
                @Suppress("DEPRECATION")
                if (AppearanceManager.isAnimationsEnabled(activity)) {
                    activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                } else {
                    activity.overridePendingTransition(0, 0)
                }
            }
        }
    }
}
