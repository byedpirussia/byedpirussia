package io.github.dovecoteescapee.byedpi.activities

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import io.github.dovecoteescapee.byedpi.utility.wrapLocale

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapLocale())
    }
}
