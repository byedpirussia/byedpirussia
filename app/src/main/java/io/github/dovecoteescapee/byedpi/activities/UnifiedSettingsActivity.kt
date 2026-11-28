package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityUnifiedSettingsBinding
import io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelActivity
import io.github.dovecoteescapee.byedpi.tgproxy.TgWsProxyService
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import io.github.dovecoteescapee.byedpi.warp.WarpGenerator
import kotlinx.coroutines.launch

class UnifiedSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityUnifiedSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        super.onCreate(savedInstanceState)
        binding = ActivityUnifiedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBars.top, 0, 0)
            binding.scrollContent.setPadding(16, 16, 16, navBars.bottom + 32)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.setup(
            this,
            io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.Tab.SETTINGS
        )

        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull()
            binding.sectionByedpi.visibility = if (id == R.id.chip_cat_byedpi) View.VISIBLE else View.GONE
            binding.sectionAwg.visibility = if (id == R.id.chip_cat_awg) View.VISIBLE else View.GONE
            binding.sectionTg.visibility = if (id == R.id.chip_cat_tg) View.VISIBLE else View.GONE
            binding.sectionOther.visibility = if (id == R.id.chip_cat_other) View.VISIBLE else View.GONE
        }

        binding.btnOpenCoreSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOpenAdvancedLegacy.setOnClickListener {
            showByeDpiToolsDialog()
        }

        binding.btnAwgGenLocal.setOnClickListener {
            binding.btnAwgGenLocal.isEnabled = false
            lifecycleScope.launch {
                val res = WarpGenerator.generateLocalAwgConfig(1080)
                binding.btnAwgGenLocal.isEnabled = true
                res.onSuccess { conf ->
                    WarpConfigManager.saveConfig(this@UnifiedSettingsActivity, conf)
                    WarpConfigManager.addWarpToHistory(this@UnifiedSettingsActivity, conf, "Вручную (локально)")
                    Toast.makeText(this@UnifiedSettingsActivity, "Локальный WARP сгенерирован!", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this@UnifiedSettingsActivity, err.message ?: "Ошибка", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnAwgGenExt.setOnClickListener {
            binding.btnAwgGenExt.isEnabled = false
            lifecycleScope.launch {
                val res = WarpGenerator.generateConfig()
                binding.btnAwgGenExt.isEnabled = true
                res.onSuccess { profile ->
                    val conf = profile.toAmneziaWgConfig()
                    WarpConfigManager.saveConfig(this@UnifiedSettingsActivity, conf)
                    WarpConfigManager.addWarpToHistory(this@UnifiedSettingsActivity, conf, "Вручную")
                    Toast.makeText(this@UnifiedSettingsActivity, "Новый WARP сгенерирован!", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this@UnifiedSettingsActivity, err.message ?: "Ошибка", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnAwgMore.setOnClickListener {
            showAwgToolsDialog()
        }

        binding.btnTgToggle.setOnClickListener {
            if (TgWsProxyService.isRunning.value) {
                TgWsProxyService.stop(this)
            } else {
                TgWsProxyService.start(this)
            }
        }

        binding.btnOtherAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        binding.btnOtherSplit.setOnClickListener {
            val items = arrayOf(
                "Раздельное туннелирование приложений",
                "Экспериментальные настройки"
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Прочие инструменты")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, SplitTunnelActivity::class.java))
                        1 -> startActivity(Intent(this, ExperimentalSettingsActivity::class.java))
                    }
                }
                .show()
        }

        renderWarpTunnelsList()
    }

    private fun renderWarpTunnelsList() {
        val container = binding.warpTunnelsList
        container.removeAllViews()
        val history = WarpConfigManager.getWarpHistory(this)
        val currentConf = WarpConfigManager.currentConfig.value
        val accent = io.github.dovecoteescapee.byedpi.utility.AppearanceManager.getAccentColor(this)

        if (history.isEmpty()) {
            val emptyTv = android.widget.TextView(this).apply {
                text = "Список пуст — сгенерируйте или импортируйте конфиг"
                textSize = 13f
                alpha = 0.7f
            }
            container.addView(emptyTv)
            return
        }

        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val padV = (10 * resources.displayMetrics.density).toInt()
        val dotSize = (10 * resources.displayMetrics.density).toInt()

        history.forEach { entry ->
            val isActive = entry.config == currentConf
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, padV, 0, padV)
                setOnClickListener {
                    WarpConfigManager.saveConfig(this@UnifiedSettingsActivity, entry.config)
                    renderWarpTunnelsList()
                    Toast.makeText(this@UnifiedSettingsActivity, "Туннель переключён!", Toast.LENGTH_SHORT).show()
                }
            }
            val dot = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (isActive) accent else 0xFF888888.toInt())
                }
            }
            val time = if (entry.timestampMs > 0) sdf.format(java.util.Date(entry.timestampMs)) else "—"
            val ep = WarpConfigManager.extractEndpoint(entry.config)
            val tv = android.widget.TextView(this).apply {
                text = "$ep  •  $time (${entry.source})"
                textSize = 14f
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(dot)
            row.addView(tv)
            container.addView(row)
        }
    }

    private fun showByeDpiToolsDialog() {
        val items = arrayOf(
            "Автоподбор стратегии (Бенчмарк)",
            "Выбрать готовую стратегию",
            "Конструктор стратегий",
            "Ввести свою стратегию вручную"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Стратегии ByeDPI")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runAutoBenchmark()
                    1 -> showPresetStrategiesDialog()
                    2 -> showStrategyBuilderDialog()
                    3 -> showCustomStrategyDialog()
                }
            }
            .show()
    }

    private fun runAutoBenchmark() {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val tvStatus = android.widget.TextView(this).apply {
            text = "Подготовка автоподбора стратегий..."
            textSize = 14f
        }
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        dialogView.addView(tvStatus)
        dialogView.addView(progressBar)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Автоподбор стратегий (YouTube & Discord)")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Закрыть", null)
            .show()

        lifecycleScope.launch {
            try {
                val benchmark = io.github.dovecoteescapee.byedpi.strategy.StrategyBenchmark(this@UnifiedSettingsActivity)
                benchmark.runBenchmark(
                    onProgress = { current, total, _, status ->
                        tvStatus.text = "$status ($current/$total)"
                    }
                )
                tvStatus.text = "Готово! Лучшая стратегия выбрана и сохранена."
                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                tvStatus.text = "Ошибка: ${e.message ?: "неизвестно"}"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showPresetStrategiesDialog() {
        val presets = listOf(
            "Анти-ТСПУ 2026 (Fake TTL 3 + MultiSplit)" to "--split 1 --disorder 1 --fake -1 --ttl 3",
            "YouTube + Discord (TLS Record + Split)" to "--tlsrec 1+s --split 2 --disorder 1",
            "Мягкий обход (Disorder + OOB)" to "--disorder 1 --oob 1",
            "Агрессивный (Fake + Split + Disorder)" to "--fake -1 --ttl 4 --split 1 --disorder 3"
        )
        val names = presets.map { it.first }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Готовые стратегии")
            .setItems(names) { _, idx ->
                val prefs = getSharedPreferences("io.github.dovecoteescapee.byedpi_preferences", MODE_PRIVATE)
                prefs.edit()
                    .putString("byedpi_cmd_args", presets[idx].second)
                    .putBoolean("byedpi_enable_cmd_settings", true)
                    .apply()
                Toast.makeText(this, "Применено: ${presets[idx].first}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showStrategyBuilderDialog() {
        val options = arrayOf(
            "--split 1 (Разделение пакетов)",
            "--disorder 1 (Сбой порядка)",
            "--fake -1 (Фейковый пакет)",
            "--ttl 3 (Ограничение TTL = 3)",
            "--tlsrec 1+s (Разделение TLS Record)"
        )
        val flags = arrayOf("--split 1", "--disorder 1", "--fake -1", "--ttl 3", "--tlsrec 1+s")
        val checked = booleanArrayOf(true, true, false, false, false)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Конструктор стратегий")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Применить") { _, _ ->
                val cmd = flags.filterIndexed { i, _ -> checked[i] }.joinToString(" ")
                val prefs = getSharedPreferences("io.github.dovecoteescapee.byedpi_preferences", MODE_PRIVATE)
                prefs.edit()
                    .putString("byedpi_cmd_args", cmd)
                    .putBoolean("byedpi_enable_cmd_settings", true)
                    .apply()
                Toast.makeText(this, "Сохранено: $cmd", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCustomStrategyDialog() {
        val prefs = getSharedPreferences("io.github.dovecoteescapee.byedpi_preferences", MODE_PRIVATE)
        val et = android.widget.EditText(this).apply {
            setText(prefs.getString("byedpi_cmd_args", ""))
            hint = "--split 1 --disorder 1"
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Своя стратегия ByeDPI")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("byedpi_cmd_args", et.text.toString().trim())
                    .putBoolean("byedpi_enable_cmd_settings", true)
                    .apply()
                Toast.makeText(this, "Стратегия сохранена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAwgToolsDialog() {
        val failoverState = if (WarpConfigManager.isAutoFailoverEnabled(this)) "Вкл" else "Выкл"
        val autoGenState = if (WarpConfigManager.isAutoGen24hEnabled(this)) "Вкл" else "Выкл"
        val items = arrayOf(
            "Список конфигов WARP (выбрать)",
            "Автогенерация каждые 24ч: $autoGenState",
            "Автосмена узлов: $failoverState",
            "Подобрать лучший узел WARP",
            "Сгенерировать WARP MASQUE",
            "Открыть конфиг в AmneziaWG",
            "Импортировать конфиг WARP (вставить)",
            "Копировать текущий конфиг WARP"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Инструменты AWG 3.1 / WARP")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showWarpHistoryDialog()
                    1 -> {
                        val next = !WarpConfigManager.isAutoGen24hEnabled(this)
                        WarpConfigManager.setAutoGen24hEnabled(this, next)
                        Toast.makeText(this, if (next) "Автогенерация 24ч включена" else "Автогенерация 24ч отключена", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val next = !WarpConfigManager.isAutoFailoverEnabled(this)
                        WarpConfigManager.setAutoFailoverEnabled(this, next)
                        Toast.makeText(this, if (next) "Автосмена узлов включена" else "Автосмена узлов отключена", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        Toast.makeText(this, "Подбор лучшего узла...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            val best = io.github.dovecoteescapee.byedpi.warp.WarpEndpointOptimizer.findBestEndpoint(2000)
                            val updated = io.github.dovecoteescapee.byedpi.warp.WarpEndpointOptimizer.optimizeConfigString(
                                WarpConfigManager.currentConfig.value,
                                best.first
                            )
                            WarpConfigManager.saveConfig(this@UnifiedSettingsActivity, updated)
                            Toast.makeText(this@UnifiedSettingsActivity, "Выбран узел: ${best.first} (${best.second}ms)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    4 -> {
                        Toast.makeText(this, "Генерация MASQUE...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            val res = WarpGenerator.generateMasqueConfig()
                            res.onSuccess { p ->
                                WarpConfigManager.saveMasqueProfile(this@UnifiedSettingsActivity, p)
                                Toast.makeText(this@UnifiedSettingsActivity, "MASQUE профиль сохранён!", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(this@UnifiedSettingsActivity, "Ошибка: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    5 -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, WarpConfigManager.currentConfig.value)
                        }
                        startActivity(Intent.createChooser(sendIntent, "Открыть конфиг WARP"))
                    }
                    6 -> {
                        val et = android.widget.EditText(this).apply {
                            hint = "Вставьте текст конфига [Interface]..."
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Импорт конфига WARP")
                            .setView(et)
                            .setPositiveButton("Сохранить") { _, _ ->
                                val txt = et.text.toString().trim()
                                if (txt.contains("[Interface]")) {
                                    WarpConfigManager.saveConfig(this, txt)
                                    WarpConfigManager.addWarpToHistory(this, txt, "Импорт")
                                    Toast.makeText(this, "Конфиг импортирован!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "Неверный формат конфига", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    7 -> {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WARP Config", WarpConfigManager.currentConfig.value))
                        Toast.makeText(this, "Конфиг WARP скопирован в буфер", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showWarpHistoryDialog() {
        val history = WarpConfigManager.getWarpHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "Список конфигов пуст — сгенерируйте или импортируйте WARP", Toast.LENGTH_SHORT).show()
            return
        }
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val labels = history.map { entry ->
            val time = if (entry.timestampMs > 0) sdf.format(java.util.Date(entry.timestampMs)) else "—"
            val ep = WarpConfigManager.extractEndpoint(entry.config)
            "$time • ${entry.source}\n$ep"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Сохранённые конфиги WARP")
            .setItems(labels) { _, idx ->
                WarpConfigManager.saveConfig(this, history[idx].config)
                Toast.makeText(this, "Конфиг переключён!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
