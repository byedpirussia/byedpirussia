package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.databinding.ActivityAdvancedSettingsBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.strategy.Strategy
import io.github.dovecoteescapee.byedpi.strategy.StrategyBenchmark
import io.github.dovecoteescapee.byedpi.strategy.StrategyCatalog
import io.github.dovecoteescapee.byedpi.strategy.TestedStrategiesAdapter
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import io.github.dovecoteescapee.byedpi.warp.WarpGenerator
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AdvancedSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdvancedSettingsBinding
    private lateinit var testedAdapter: TestedStrategiesAdapter
    private var autoTuneJob: Job? = null
    private var isTestedListExpanded: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
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

        // RecyclerView для протестированных стратегий
        testedAdapter = TestedStrategiesAdapter { selectedStrategy ->
            applyAndSwitchStrategy(selectedStrategy)
        }
        binding.rvTestedStrategies.layoutManager = LinearLayoutManager(this)
        binding.rvTestedStrategies.adapter = testedAdapter

        binding.headerTestedStrategies.setOnClickListener {
            toggleTestedList()
        }

        binding.btnSelectStrategy.setOnClickListener {
            showStrategySelectionDialog()
        }

        binding.btnAutoTune.setOnClickListener {
            runAutoTune()
        }

        binding.btnStopAutotune.setOnClickListener {
            stopAutoTune()
        }

        binding.btnOpenKernelSettings.setOnClickListener {
            val (status, _) = appStatus
            if (status == AppStatus.Halted) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardSplitTunnel.setOnClickListener {
            startActivity(Intent(this, io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelActivity::class.java))
        }

        setupWarpCard()
        updateStrategyView()
        updateSplitTunnelSummary()
    }

    override fun onResume() {
        super.onResume()
        updateStrategyView()
        updateSplitTunnelSummary()
    }

    private fun updateSplitTunnelSummary() {
        val mode = io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.getMode(this)
        val count = io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.getSelectedPackages(this).size
        binding.tvSplitTunnelSummary.text = when (mode) {
            io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.MODE_WHITELIST -> "Только выбранные ($count прил.)"
            io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.MODE_BLACKLIST -> "Все, кроме выбранных ($count прил.)"
            else -> getString(R.string.split_tunnel_mode_all)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoTuneJob?.cancel()
    }

    private fun updateStrategyView() {
        val sp = getPreferences()
        val currentId = sp.getString("selected_strategy_id", null) ?: StrategyCatalog.strategies[0].id
        val currentStrategy = StrategyCatalog.getStrategyById(currentId)

        binding.strategyTitle.text = currentStrategy.name
        binding.strategyTarget.text = "🎯 Для: ${currentStrategy.recommendedFor}"
        binding.strategyDescription.text = currentStrategy.description
        binding.strategyArgs.text = currentStrategy.args
        binding.btnSelectStrategy.text = "Выбрать из ${StrategyCatalog.strategies.size} стратегий вручную"
    }

    private fun applyAndSwitchStrategy(strategy: Strategy) {
        val benchmark = StrategyBenchmark(this)
        benchmark.applyStrategy(strategy)
        updateStrategyView()

        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            Toast.makeText(this, "Применена: ${strategy.name}. Перезапуск...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                ServiceManager.stop(this@AdvancedSettingsActivity)
                delay(500)
                ServiceManager.start(this@AdvancedSettingsActivity, Mode.VPN)
            }
        } else {
            Toast.makeText(this, "Применена: ${strategy.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStrategySelectionDialog() {
        val strategies = StrategyCatalog.strategies
        val names = strategies.map {
            "${it.name}\n${it.description}\n🎯 ${it.recommendedFor}"
        }.toTypedArray()
        val currentId = getPreferences().getString("selected_strategy_id", null)
        val currentIndex = strategies.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(this)
            .setTitle("Выбор стратегии (${strategies.size} доступно)")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val selected = strategies[which]
                applyAndSwitchStrategy(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toggleTestedList() {
        isTestedListExpanded = !isTestedListExpanded
        if (isTestedListExpanded) {
            binding.containerTestedList.visibility = View.VISIBLE
            binding.btnToggleTestedStrategies.animate().rotation(0f).setDuration(200).start()
            binding.testedStrategiesSubtitle.text = "Нажмите, чтобы свернуть список"
        } else {
            binding.containerTestedList.visibility = View.GONE
            binding.btnToggleTestedStrategies.animate().rotation(180f).setDuration(200).start()
            binding.testedStrategiesSubtitle.text = "Нажмите, чтобы развернуть список (${testedAdapter.itemCount})"
        }
    }

    private fun stopAutoTune() {
        if (autoTuneJob?.isActive == true) {
            autoTuneJob?.cancel()
            binding.autotuneCard.visibility = View.GONE
            binding.btnAutoTune.isEnabled = true
            binding.btnSelectStrategy.isEnabled = true
            Toast.makeText(this, "Автоподбор остановлен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAutoTune() {
        val (initialStatus, _) = appStatus
        val wasRunning = (initialStatus == AppStatus.Running)

        autoTuneJob = lifecycleScope.launch {
            if (wasRunning) {
                ServiceManager.stop(this@AdvancedSettingsActivity)
                delay(300)
            }

            binding.autotuneCard.visibility = View.VISIBLE
            binding.autotuneProgress.progress = 2
            binding.autotuneStatus.text = "Запуск тестирования..."
            binding.btnAutoTune.isEnabled = false
            binding.btnSelectStrategy.isEnabled = false

            testedAdapter.clear()
            binding.testedStrategiesCard.visibility = View.VISIBLE
            binding.testedStrategiesTitle.text = "📊 Проверенные стратегии (0)"
            if (isTestedListExpanded) {
                binding.containerTestedList.visibility = View.VISIBLE
                binding.btnToggleTestedStrategies.rotation = 0f
                binding.testedStrategiesSubtitle.text = "Нажмите, чтобы свернуть список"
            } else {
                binding.containerTestedList.visibility = View.GONE
                binding.btnToggleTestedStrategies.rotation = 180f
                binding.testedStrategiesSubtitle.text = "Нажмите, чтобы развернуть список (0)"
            }

            val benchmark = StrategyBenchmark(this@AdvancedSettingsActivity)
            val result = benchmark.runBenchmark(
                onProgress = { current, total, strategy, statusText ->
                    val progressPercent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                    binding.autotuneProgress.progress = progressPercent
                    binding.autotuneStatus.text = statusText
                },
                onStrategyTested = { res ->
                    testedAdapter.addResult(res)
                    binding.testedStrategiesTitle.text = "📊 Проверенные стратегии (${testedAdapter.itemCount})"
                    if (!isTestedListExpanded) {
                        binding.testedStrategiesSubtitle.text = "Нажмите, чтобы развернуть список (${testedAdapter.itemCount})"
                    }
                }
            )

            binding.autotuneCard.visibility = View.GONE
            binding.btnAutoTune.isEnabled = true
            binding.btnSelectStrategy.isEnabled = true
            updateStrategyView()

            if (result != null) {
                val detailsText = result.details.entries.joinToString("\n") { (host, lat) ->
                    val status = if (lat != null) "✅ Доступен (${lat} мс)" else "❌ Заблокирован"
                    "$host: $status"
                }

                MaterialAlertDialogBuilder(this@AdvancedSettingsActivity)
                    .setTitle(R.string.strategy_selected_title)
                    .setMessage(
                        "Подобрана лучшая стратегия:\n${result.strategy.name}\n\n" +
                        "Средняя задержка: ${result.averageLatencyMs} мс\n\n" +
                        "Результаты проверки:\n$detailsText\n\n" +
                        "Стратегия сохранена и готова к использованию."
                    )
                    .setPositiveButton("Включить VPN") { _, _ ->
                        ServiceManager.start(this@AdvancedSettingsActivity, Mode.VPN)
                    }
                    .setNegativeButton("ОК") { _, _ ->
                        if (wasRunning) {
                            ServiceManager.start(this@AdvancedSettingsActivity, Mode.VPN)
                        }
                    }
                    .show()
            } else {
                if (wasRunning) {
                    ServiceManager.start(this@AdvancedSettingsActivity, Mode.VPN)
                }
            }
        }
    }

    private fun setupWarpCard() {
        WarpConfigManager.init(this)

        lifecycleScope.launch {
            WarpConfigManager.currentConfig.collectLatest { config ->
                val endpoint = WarpConfigManager.extractEndpoint(config)
                binding.warpEndpointText.text = "$endpoint (Jc=4, H1-4, I1)"
            }
        }

        binding.btnWarpCopy.setOnClickListener {
            val config = WarpConfigManager.currentConfig.value
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WARP AmneziaWG Config", config)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.warp_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnWarpOpenAmnezia.setOnClickListener {
            val config = WarpConfigManager.currentConfig.value
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, config)
                putExtra(Intent.EXTRA_TITLE, "warp.conf")
            }
            try {
                val amneziaIntent = packageManager.getLaunchIntentForPackage("org.amnezia.awg")
                    ?: packageManager.getLaunchIntentForPackage("org.amnezia.vpn")
                if (amneziaIntent != null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WARP AmneziaWG Config", config))
                    Toast.makeText(this, "Конфиг скопирован! Нажмите '+' в AmneziaWG", Toast.LENGTH_LONG).show()
                    startActivity(amneziaIntent)
                } else {
                    startActivity(Intent.createChooser(sendIntent, "Открыть / отправить конфиг WARP"))
                }
            } catch (e: Exception) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WARP AmneziaWG Config", config))
                Toast.makeText(this, R.string.warp_copied, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnWarpGenerate.setOnClickListener {
            binding.btnWarpGenerate.isEnabled = false
            binding.btnWarpGenerate.text = "Генерация..."
            Toast.makeText(this, R.string.warp_generating, Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                val result = WarpGenerator.generateConfig()
                binding.btnWarpGenerate.isEnabled = true
                binding.btnWarpGenerate.text = "✨ Сгенерировать"

                result.onSuccess { profile ->
                    val newConf = profile.toAmneziaWgConfig()
                    WarpConfigManager.saveConfig(this@AdvancedSettingsActivity, newConf)
                    Toast.makeText(this@AdvancedSettingsActivity, R.string.warp_generated_success, Toast.LENGTH_SHORT).show()

                    val wasWarpRunning = WarpVpnService.isRunning.value
                    MaterialAlertDialogBuilder(this@AdvancedSettingsActivity)
                        .setTitle("Новый WARP сгенерирован!")
                        .setMessage(
                            "Сервер: ${profile.endpoint}\n" +
                            "IPv4: ${profile.clientIpv4}\n\n" +
                            "Конфиг сохранен и готов к запуску прямо в приложении!"
                        )
                        .setPositiveButton(if (wasWarpRunning) "Переподключить" else "Готово") { _, _ ->
                            if (wasWarpRunning) {
                                WarpVpnService.stop(this@AdvancedSettingsActivity)
                                WarpVpnService.start(this@AdvancedSettingsActivity)
                            }
                        }
                        .setNegativeButton("ОК", null)
                        .show()
                }.onFailure { err ->
                    Toast.makeText(
                        this@AdvancedSettingsActivity,
                        getString(R.string.warp_generated_error, err.message ?: "таймаут"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
