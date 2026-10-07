package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.databinding.ActivityExperimentalSettingsBinding
import io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper

class ExperimentalSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityExperimentalSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExperimentalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBars.top, 0, 0)
            binding.scrollView.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        setupSwitches()
        setupCardClicks()
        setupTvFocus()

        binding.btnResetExperimental.setOnClickListener {
            confirmReset()
        }
    }

    private fun setupSwitches() {
        binding.switchBatterySaver.isChecked = ExperimentalConfigManager.isBatterySaverEnabled(this)
        binding.switchBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            ExperimentalConfigManager.setBatterySaverEnabled(this, isChecked)
        }

        binding.switchBlockQuic.isChecked = ExperimentalConfigManager.isBlockQuicEnabled(this)
        binding.switchBlockQuic.setOnCheckedChangeListener { _, isChecked ->
            ExperimentalConfigManager.setBlockQuicEnabled(this, isChecked)
        }

        binding.switchTcpFastOpen.isChecked = ExperimentalConfigManager.isTcpFastOpenEnabled(this)
        binding.switchTcpFastOpen.setOnCheckedChangeListener { _, isChecked ->
            ExperimentalConfigManager.setTcpFastOpenEnabled(this, isChecked)
        }

        binding.switchMtuClamp.isChecked = ExperimentalConfigManager.isMtuClampEnabled(this)
        binding.switchMtuClamp.setOnCheckedChangeListener { _, isChecked ->
            ExperimentalConfigManager.setMtuClampEnabled(this, isChecked)
        }

        binding.switchAggressiveKeepalive.isChecked = ExperimentalConfigManager.isAggressiveKeepAliveEnabled(this)
        binding.switchAggressiveKeepalive.setOnCheckedChangeListener { _, isChecked ->
            ExperimentalConfigManager.setAggressiveKeepAliveEnabled(this, isChecked)
        }
    }

    private fun setupCardClicks() {
        binding.cardBatterySaver.setOnClickListener {
            binding.switchBatterySaver.toggle()
        }
        binding.cardBlockQuic.setOnClickListener {
            binding.switchBlockQuic.toggle()
        }
        binding.cardTcpFastOpen.setOnClickListener {
            binding.switchTcpFastOpen.toggle()
        }
        binding.cardMtuClamp.setOnClickListener {
            binding.switchMtuClamp.toggle()
        }
        binding.cardAggressiveKeepalive.setOnClickListener {
            binding.switchAggressiveKeepalive.toggle()
        }
    }

    private fun setupTvFocus() {
        TvNavigationHelper.setupCardFocus(binding.cardWarningBanner)
        TvNavigationHelper.setupCardFocus(binding.cardBatterySaver) { binding.switchBatterySaver.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardBlockQuic) { binding.switchBlockQuic.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardTcpFastOpen) { binding.switchTcpFastOpen.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardMtuClamp) { binding.switchMtuClamp.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardAggressiveKeepalive) { binding.switchAggressiveKeepalive.toggle() }
        TvNavigationHelper.setupButtonFocus(binding.btnResetExperimental)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Сброс экспериментов")
            .setMessage("Отключить все экспериментальные параметры и вернуть стандартные безопасные настройки?")
            .setPositiveButton("Сбросить") { _, _ ->
                ExperimentalConfigManager.resetToDefaults(this)
                setupSwitches()
                Toast.makeText(this, "Экспериментальные настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
