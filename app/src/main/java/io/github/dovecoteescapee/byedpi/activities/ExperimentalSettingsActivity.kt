package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.databinding.ActivityExperimentalSettingsBinding
import io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper
import kotlinx.coroutines.launch

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

        binding.switchWarpMasque.isChecked = io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.isMasqueMode(this)
        binding.switchWarpMasque.setOnCheckedChangeListener { _, isChecked ->
            io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.setMasqueMode(this, isChecked)
            val msg = if (isChecked) "Включен эксперимент: MASQUE (HTTP/3)" else "Отключен эксперимент: возврат к AWG"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnGenMasque.setOnClickListener {
            generateExperimentalMasque()
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
        binding.cardWarpMasque.setOnClickListener {
            binding.switchWarpMasque.toggle()
        }
    }

    private fun setupTvFocus() {
        TvNavigationHelper.setupCardFocus(binding.cardWarningBanner)
        TvNavigationHelper.setupCardFocus(binding.cardBatterySaver) { binding.switchBatterySaver.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardBlockQuic) { binding.switchBlockQuic.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardTcpFastOpen) { binding.switchTcpFastOpen.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardMtuClamp) { binding.switchMtuClamp.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardAggressiveKeepalive) { binding.switchAggressiveKeepalive.toggle() }
        TvNavigationHelper.setupCardFocus(binding.cardWarpMasque) { binding.switchWarpMasque.toggle() }
        TvNavigationHelper.setupButtonFocus(binding.btnGenMasque)
        TvNavigationHelper.setupButtonFocus(binding.btnResetExperimental)
    }

    private fun generateExperimentalMasque() {
        Toast.makeText(this, "Генерация экспериментального MASQUE профиля...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = io.github.dovecoteescapee.byedpi.warp.WarpGenerator.generateMasqueConfig()
            if (res.isSuccess) {
                val prof = res.getOrThrow()
                io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.setMasqueMode(this@ExperimentalSettingsActivity, true)
                binding.switchWarpMasque.isChecked = true
                if (prof.workingAwgConfig.isNotBlank()) {
                    io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.saveConfig(this@ExperimentalSettingsActivity, prof.workingAwgConfig)
                }

                val info = "Сервер: 162.159.192.1:500\nПротокол: MASQUE (HTTP/3)\nIP: ${prof.clientIpv4}\n\nЭкспериментальный профиль успешно создан."
                val tv = android.widget.TextView(this@ExperimentalSettingsActivity).apply {
                    text = info
                    setPadding(50, 30, 50, 20)
                    setTextIsSelectable(true)
                }

                MaterialAlertDialogBuilder(this@ExperimentalSettingsActivity)
                    .setTitle("⚡ MASQUE готов")
                    .setView(tv)
                    .setPositiveButton("Применить") { _, _ ->
                        Toast.makeText(this@ExperimentalSettingsActivity, "Профиль применен", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Копировать") { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Config", prof.rawYaml))
                        Toast.makeText(this@ExperimentalSettingsActivity, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            } else {
                Toast.makeText(this@ExperimentalSettingsActivity, "Ошибка: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Сброс экспериментов")
            .setMessage("Отключить все экспериментальные параметры и вернуть стандартные безопасные настройки?")
            .setPositiveButton("Сбросить") { _, _ ->
                ExperimentalConfigManager.resetToDefaults(this)
                io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.setMasqueMode(this, false)
                setupSwitches()
                Toast.makeText(this, "Экспериментальные настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
