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
import io.github.dovecoteescapee.byedpi.utility.isDynamicIslandEnabled
import io.github.dovecoteescapee.byedpi.utility.setDynamicIslandEnabled
import io.github.dovecoteescapee.byedpi.backup.BackupManager
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import io.github.dovecoteescapee.byedpi.warp.WarpGenerator
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AdvancedSettingsActivity : BaseActivity() {
    private lateinit var binding: ActivityAdvancedSettingsBinding
    private lateinit var testedAdapter: TestedStrategiesAdapter
    private var autoTuneJob: Job? = null
    private var isTestedListExpanded: Boolean = true

    private var pendingImportConfSetter: ((String) -> Unit)? = null

    private val pickConfigFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val content = stream.bufferedReader().use { reader -> reader.readText() }
                    pendingImportConfSetter?.invoke(content)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось прочитать файл: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val createBackupFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let {
            try {
                val json = BackupManager.createBackupJson(this)
                contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Резервная копия успешно сохранена", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickBackupFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val json = stream.bufferedReader().use { reader -> reader.readText() }
                    confirmAndRestoreBackup(json)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось прочитать файл: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        setupDynamicIslandCard()
        setupBackupCard()
        setupTvFocus()
        updateStrategyView()
        updateSplitTunnelSummary()
    }

    private fun setupTvFocus() {
        TvNavigationHelper.setupCardFocus(binding.strategyCard)
        TvNavigationHelper.setupCardFocus(binding.autotuneCard)
        TvNavigationHelper.setupCardFocus(binding.testedStrategiesCard)
        TvNavigationHelper.setupCardFocus(binding.warpToolsCard)
        TvNavigationHelper.setupCardFocus(binding.cardSplitTunnel) {
            startActivity(Intent(this, io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelActivity::class.java))
        }
        TvNavigationHelper.setupCardFocus(binding.cardDynamicIsland) {
            binding.switchDynamicIsland.toggle()
        }
        TvNavigationHelper.setupCardFocus(binding.cardBackup)
        TvNavigationHelper.setupCardFocus(binding.cardKernelSettings) {
            val (status, _) = appStatus
            if (status == AppStatus.Halted) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        TvNavigationHelper.setupButtonFocus(binding.btnAutoTune)
        TvNavigationHelper.setupButtonFocus(binding.btnSelectStrategy)
        TvNavigationHelper.setupButtonFocus(binding.btnStopAutotune)
        TvNavigationHelper.setupButtonFocus(binding.btnToggleTestedStrategies)
        TvNavigationHelper.setupButtonFocus(binding.btnWarpGenerate)
        TvNavigationHelper.setupButtonFocus(binding.btnWarpImport)
        TvNavigationHelper.setupButtonFocus(binding.btnWarpCopy)
        TvNavigationHelper.setupButtonFocus(binding.btnWarpOpenAmnezia)
        TvNavigationHelper.setupButtonFocus(binding.btnExportBackup)
        TvNavigationHelper.setupButtonFocus(binding.btnImportBackup)
        TvNavigationHelper.setupButtonFocus(binding.btnCopyBackupJson)
        TvNavigationHelper.setupButtonFocus(binding.btnPasteBackupJson)
        TvNavigationHelper.setupButtonFocus(binding.btnOpenKernelSettings)
    }

    override fun onResume() {
        super.onResume()
        updateStrategyView()
        updateSplitTunnelSummary()
        updateDynamicIslandView()
    }

    private fun setupDynamicIslandCard() {
        binding.switchDynamicIsland.isChecked = isDynamicIslandEnabled()
        binding.switchDynamicIsland.setOnCheckedChangeListener { _, isChecked ->
            setDynamicIslandEnabled(isChecked)
        }
        updateDynamicIslandView()
    }

    private fun updateDynamicIslandView() {
        binding.switchDynamicIsland.isChecked = isDynamicIslandEnabled()
    }

    private fun setupBackupCard() {
        binding.btnExportBackup.setOnClickListener {
            try {
                createBackupFileLauncher.launch(BackupManager.generateFileName())
            } catch (e: Exception) {
                shareBackupJson()
            }
        }

        binding.btnImportBackup.setOnClickListener {
            pickBackupFileLauncher.launch("*/*")
        }

        binding.btnCopyBackupJson.setOnClickListener {
            val json = BackupManager.createBackupJson(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ByeDPI Backup", json))
            Toast.makeText(this, "Резервная копия скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
        }

        binding.btnPasteBackupJson.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmAndRestoreBackup(clipText)
        }
    }

    private fun shareBackupJson() {
        val json = BackupManager.createBackupJson(this)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, json)
            type = "application/json"
        }
        startActivity(Intent.createChooser(sendIntent, "Поделиться резервной копией"))
    }

    private fun confirmAndRestoreBackup(jsonStr: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Восстановление настроек")
            .setMessage("Восстановить конфигурацию из резервной копии? Ваши текущие серверы VLESS, профиль WARP, OpenFLUX и правила туннелирования будут обновлены.")
            .setPositiveButton("Восстановить") { _, _ ->
                val result = BackupManager.restoreBackupJson(this, jsonStr)
                result.onSuccess { summary ->
                    updateStrategyView()
                    updateSplitTunnelSummary()
                    updateDynamicIslandView()

                    MaterialAlertDialogBuilder(this)
                        .setTitle("✅ Настройки восстановлены")
                        .setMessage(
                            "Успешно импортировано:\n" +
                            "• Серверов VLESS: ${summary.vlessCount}\n" +
                            "• Подписок: ${summary.subscriptionsCount}\n" +
                            "• Конфигурация WARP: ${if (summary.warpRestored) "Обновлена" else "Без изменений"}\n" +
                            "• Профилей OpenFLUX: ${summary.openFluxCount}\n" +
                            "• Приложений в Split Tunnel: ${summary.splitTunnelAppsCount}\n" +
                            "• Параметров приложения: ${summary.preferencesCount}"
                        )
                        .setPositiveButton("ОК", null)
                        .show()
                }.onFailure { e ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("❌ Ошибка импорта")
                        .setMessage("Не удалось восстановить резервную копию: ${e.message}")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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

        binding.btnWarpImport.setOnClickListener {
            showWarpImportDialog()
        }
    }

    private fun showWarpImportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_warp_import, null)
        val etConf = dialogView.findViewById<TextInputEditText>(R.id.et_warp_conf)
        val btnPaste = dialogView.findViewById<MaterialButton>(R.id.btn_import_from_clipboard)
        val btnFile = dialogView.findViewById<MaterialButton>(R.id.btn_import_from_file)

        etConf.setText(WarpConfigManager.currentConfig.value)

        pendingImportConfSetter = { content ->
            etConf.setText(content)
            Toast.makeText(this, "Конфиг загружен из файла ✓", Toast.LENGTH_SHORT).show()
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank()) {
                    etConf.setText(text)
                    Toast.makeText(this, "Вставлено из буфера обмена ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        }

        btnFile.setOnClickListener {
            try {
                pickConfigFileLauncher.launch("*/*")
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка открытия выбора файла: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.warp_import_save, null)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                pendingImportConfSetter = null
                d.dismiss()
            }
            .setOnDismissListener {
                pendingImportConfSetter = null
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = etConf.text?.toString()?.trim() ?: ""
                if (!text.contains("[Interface]", ignoreCase = true) || !text.contains("[Peer]", ignoreCase = true)) {
                    Toast.makeText(this, R.string.warp_import_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                WarpConfigManager.saveConfig(this, text)
                Toast.makeText(this, R.string.warp_import_success, Toast.LENGTH_SHORT).show()

                val wasRunning = WarpVpnService.isRunning.value
                if (wasRunning) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("WARP активен")
                        .setMessage("Переподключить WARP с новым профилем прямо сейчас?")
                        .setPositiveButton("Переподключить") { _, _ ->
                            WarpVpnService.stop(this)
                            WarpVpnService.start(this)
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                }

                pendingImportConfSetter = null
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
