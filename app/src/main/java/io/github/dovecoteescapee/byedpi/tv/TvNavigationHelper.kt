package io.github.dovecoteescapee.byedpi.tv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object TvNavigationHelper {

    /**
     * Определяет, запущено ли приложение на телевизоре (Android TV / Google TV)
     * или на устройстве без сенсорного экрана (ТВ-приставка).
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * Настраивает плавный D-Pad фокус для карточек MaterialCardView на ТВ.
     * При наведении с пульта карточка подсвечивается акцентным цветом, слегка увеличивается
     * и получает приподнятую тень для комфортного восприятия с дивана.
     */
    fun setupCardFocus(
        card: MaterialCardView,
        onClick: (() -> Unit)? = null
    ) {
        val context = card.context
        val normalStrokeWidth = card.strokeWidth
        val normalStrokeColor = card.strokeColorStateList
        val normalElevation = card.cardElevation
        val focusedStrokeWidth = (3f * context.resources.displayMetrics.density).toInt()
        val primaryColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary)

        card.isFocusable = true
        card.isClickable = true

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.strokeWidth = focusedStrokeWidth
                card.strokeColor = primaryColor
                card.cardElevation = 8f * context.resources.displayMetrics.density
                card.animate().scaleX(1.025f).scaleY(1.025f).setDuration(120).start()
            } else {
                card.strokeWidth = normalStrokeWidth
                if (normalStrokeColor != null) {
                    card.setStrokeColor(normalStrokeColor)
                } else {
                    card.strokeWidth = 0
                }
                card.cardElevation = normalElevation
                card.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }

        if (onClick != null) {
            card.setOnClickListener {
                onClick.invoke()
            }
        }
    }

    /**
     * Настраивает фокус для кнопок и интерактивных элементов на ТВ с плавным увеличением.
     */
    fun setupButtonFocus(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }
}
