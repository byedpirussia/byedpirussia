package io.github.dovecoteescapee.byedpi.activities

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.dovecoteescapee.byedpi.utility.AppearanceManager
import java.util.Locale

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = AppearanceManager.getLanguage(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = AppearanceManager.getFontScale(newBase)
        if (lang != "system") {
            val locale = Locale.forLanguageTag(lang)
            Locale.setDefault(locale)
            config.setLocale(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            AppearanceManager.applyThemeAndLocale(this)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        AppearanceManager.applyPostInflation(this)
    }
}
