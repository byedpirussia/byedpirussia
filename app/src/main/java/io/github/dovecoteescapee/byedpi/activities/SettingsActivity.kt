package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val appBarLayout = findViewById<AppBarLayout>(R.id.app_bar_layout)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinator_layout)) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBarLayout.setPadding(0, statusBars.top, 0, 0)
            findViewById<android.view.View>(R.id.settings).setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        toolbar.setOnMenuItemClickListener { item ->
            onMenuItemClick(item)
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, MainSettingsFragment())
                .commit()
        }
    }

    private fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reset_settings -> {
            getPreferences().edit().clear().apply()

            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, MainSettingsFragment())
                .commit()
            true
        }

        else -> false
    }
}