package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.strategy.StrategyCatalog
import io.github.dovecoteescapee.byedpi.tgproxy.TgWsProxyService
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import io.github.dovecoteescapee.byedpi.warp.WarpDnsManager
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import io.github.dovecoteescapee.byedpi.vless.VlessManager
import io.github.dovecoteescapee.byedpi.vless.VlessVpnService
import io.github.dovecoteescapee.byedpi.vless.VlessListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val warpVpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                WarpVpnService.start(this)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val vlessVpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                VlessVpnService.start(this)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        try {
                            outputStream.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    private var currentAccent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        currentAccent = getPreferences().getString("accent_color", "blue")
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBars.top, 0, 0)
            binding.scrollView.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        val theme = getPreferences().getString("app_theme", null)
        MainSettingsFragment.setTheme(theme ?: "system")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setupByeDpiCard()
        setupTelegramProxyCard()
        setupWarpCard()
        setupVlessCard()
        setupTgChannelBanner()
        setupAdvancedSettingsCard()

        checkInitialSetup()
    }

    private fun isAnyVpnActive(): Boolean {
        val (byedpiStatus, byedpiMode) = appStatus
        val isByeDpiVpn = byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN
        val isWarpVpn = WarpVpnService.isRunning.value
        val isVlessVpn = VlessVpnService.isRunning.value
        return isByeDpiVpn || isWarpVpn || isVlessVpn
    }

    private fun startWarpVpn() {
        val (byedpiStatus, byedpiMode) = appStatus
        if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
            ServiceManager.stop(this)
        }
        if (VlessVpnService.isRunning.value) {
            VlessVpnService.stop(this)
        }
        val intentPrepare = VpnService.prepare(this)
        if (intentPrepare != null) {
            warpVpnRegister.launch(intentPrepare)
        } else {
            WarpVpnService.start(this)
        }
    }

    private fun openTelegramWithVpnCheck() {
        if (isAnyVpnActive()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Byedpirussia"))
            startActivity(intent)
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tg_vpn_warning_title)
                .setMessage(R.string.tg_vpn_warning_msg)
                .setPositiveButton(R.string.tg_vpn_warning_btn_warp) { dialog, _ ->
                    dialog.dismiss()
                    startWarpVpn()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Byedpirussia"))
                    startActivity(intent)
                }
                .setNegativeButton(R.string.tg_vpn_warning_btn_anyway) { dialog, _ ->
                    dialog.dismiss()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Byedpirussia"))
                    startActivity(intent)
                }
                .show()
        }
    }

    private fun setupTgChannelBanner() {
        binding.btnOpenTgChannel.setOnClickListener {
            openTelegramWithVpnCheck()
        }
    }

    fun showInitialSetupDialog(force: Boolean = false) {
        if (!force && isInitialSetupDone()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_initial_setup, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.setup_title)
        val tvDesc = dialogView.findViewById<android.widget.TextView>(R.id.setup_desc)
        val progressLayout = dialogView.findViewById<android.view.View>(R.id.setup_progress_layout)
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.setup_progress_bar)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.setup_status_text)
        val btnAction = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_setup_action)
        val btnSkip = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_setup_skip)

        btnSkip.setOnClickListener {
            setInitialSetupDone(true)
            dialog.dismiss()
            checkTgChannelAnnouncement()
        }

        btnAction.setOnClickListener {
            btnAction.isEnabled = false
            btnSkip.visibility = View.GONE
            progressLayout.visibility = View.VISIBLE

            lifecycleScope.launch {
                // Шаг 1: Генерация профиля WARP
                tvStatus.text = getString(R.string.setup_wizard_step_warp)
                try {
                    val warpResult = io.github.dovecoteescapee.byedpi.warp.WarpGenerator.generateConfig()
                    if (warpResult.isSuccess) {
                        val profile = warpResult.getOrNull()
                        if (profile != null) {
                            WarpConfigManager.saveConfig(this@MainActivity, profile.toAmneziaWgConfig())
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Initial setup WARP gen error", e)
                }

                // Шаг 2: Автоподбор стратегии ByeDPI
                tvStatus.text = getString(R.string.setup_wizard_step_benchmark)
                val benchmark = io.github.dovecoteescapee.byedpi.strategy.StrategyBenchmark(this@MainActivity)
                try {
                    benchmark.runBenchmark(
                        onProgress = { current, total, strategy, status ->
                            tvStatus.text = "$status ($current/$total)"
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Initial setup benchmark error", e)
                }

                // Завершено
                tvStatus.text = getString(R.string.setup_wizard_done)
                progressBar.visibility = View.INVISIBLE
                btnAction.isEnabled = true
                btnAction.text = getString(R.string.setup_wizard_close)
                btnAction.setOnClickListener {
                    setInitialSetupDone(true)
                    updateStrategyBadge()
                    dialog.dismiss()
                    checkTgChannelAnnouncement()
                }
            }
        }

        dialog.show()
    }

    private fun showLanguageSelectionDialog() {
        val languages = arrayOf(
            getString(R.string.lang_ru),
            getString(R.string.lang_en)
        )
        val codes = arrayOf("ru", "en")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_select_language_title)
            .setCancelable(false)
            .setItems(languages) { _, which ->
                val selectedLang = codes[which]
                setLanguageSelected(true)
                setAppLanguage(selectedLang)
                recreate()
            }
            .show()
    }

    private fun checkInitialSetup() {
        if (!isLanguageSelected()) {
            showLanguageSelectionDialog()
            return
        }

        if (!isInitialSetupDone()) {
            showInitialSetupDialog(force = false)
        } else {
            checkTgChannelAnnouncement()
        }
    }

    private fun checkTgChannelAnnouncement() {
        if (!isTgChannelDialogShown()) {
            showTgChannelAnnouncementDialog()
        } else {
            checkStarDialog()
        }
    }

    private fun showTgChannelAnnouncementDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tg_channel_dialog_title)
            .setMessage(R.string.tg_channel_dialog_text)
            .setCancelable(false)
            .setPositiveButton(R.string.tg_channel_dialog_btn_join) { dialog, _ ->
                setTgChannelDialogShown(true)
                dialog.dismiss()
                openTelegramWithVpnCheck()
                checkStarDialog()
            }
            .setNegativeButton(R.string.setup_wizard_btn_skip) { dialog, _ ->
                setTgChannelDialogShown(true)
                dialog.dismiss()
                checkStarDialog()
            }
            .show()
    }

    private fun checkStarDialog() {
        if (isStarNeverShow()) return

        val sp = getPreferences()
        val count = sp.getInt(KEY_STAR_SHOW_COUNT, 0)
        val lastTime = sp.getLong(KEY_STAR_LAST_SHOW_TIME, 0L)
        val now = System.currentTimeMillis()

        // Показываем если прошло более 24 часов с предыдущего показа или показываем во 2-й сессии
        if (count > 0 && now - lastTime < 24 * 60 * 60 * 1000L) {
            return
        }

        sp.edit()
            .putInt(KEY_STAR_SHOW_COUNT, count + 1)
            .putLong(KEY_STAR_LAST_SHOW_TIME, now)
            .apply()

        showStarDialog()
    }

    private fun showStarDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_github_star, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val btnGo = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_star_go_github)
        val btnNever = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_star_never)
        val btnLater = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_star_later)

        btnGo.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/byedpirussia/byedpirussia"))
            startActivity(intent)
            dialog.dismiss()
        }

        btnNever.setOnClickListener {
            setStarNeverShow(true)
            dialog.dismiss()
        }

        btnLater.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        val savedAccent = getPreferences().getString("accent_color", "blue")
        if (currentAccent != savedAccent) {
            recreate()
            return
        }
        updateStatus()
        updateStrategyBadge()
        updateVlessSubtitle()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_telegram -> {
                openTelegramWithVpnCheck()
                true
            }

            R.id.action_github -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/byedpirussia/byedpirussia"))
                startActivity(intent)
                true
            }

            R.id.action_settings -> {
                if (status == AppStatus.Halted) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT)
                        .show()
                }
                true
            }

            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupByeDpiCard() {
        binding.btnActionByedpi.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> {
                    // Если активен WARP или VLESS VPN, выключаем их во избежание коллизий VpnService
                    if (WarpVpnService.isRunning.value) {
                        WarpVpnService.stop(this)
                    }
                    if (VlessVpnService.isRunning.value) {
                        VlessVpnService.stop(this)
                    }
                    when (getPreferences().mode()) {
                        Mode.VPN -> {
                            val intentPrepare = VpnService.prepare(this)
                            if (intentPrepare != null) {
                                vpnRegister.launch(intentPrepare)
                            } else {
                                ServiceManager.start(this, Mode.VPN)
                            }
                        }
                        Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
                    }
                }
                AppStatus.Running -> {
                    ServiceManager.stop(this)
                }
            }
        }
    }

    private fun updateStrategyBadge() {
        val sp = getPreferences()
        val currentId = sp.getString("selected_strategy_id", null) ?: StrategyCatalog.strategies[0].id
        val currentStrategy = StrategyCatalog.getStrategyById(currentId)
        binding.byedpiStrategyName.text = "${currentStrategy.name} (DPI Fix)"
    }

    private fun updateStatus() {
        val (status, _) = appStatus
        val colorPrimary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorOutline = getThemeColor(com.google.android.material.R.attr.colorOutline)

        when (status) {
            AppStatus.Halted -> {
                binding.byedpiStatusBadge.text = "⚪ Отключено"
                binding.byedpiStatusBadge.setTextColor(colorOutline)
                binding.byedpiIcon.setImageResource(R.drawable.ic_shield_off_24)
                binding.byedpiIcon.imageTintList = ColorStateList.valueOf(colorOutline)

                binding.btnActionByedpi.text = getString(R.string.byedpi_inactive_btn)
                binding.btnActionByedpi.setIconResource(R.drawable.ic_power_24)
            }
            AppStatus.Running -> {
                binding.byedpiStatusBadge.text = "🟢 Активно"
                binding.byedpiStatusBadge.setTextColor(getColor(R.color.accent_green))
                binding.byedpiIcon.setImageResource(R.drawable.ic_shield_check_24)
                binding.byedpiIcon.imageTintList = ColorStateList.valueOf(colorPrimary)

                binding.btnActionByedpi.text = getString(R.string.byedpi_active_btn)
                binding.btnActionByedpi.setIconResource(R.drawable.ic_power_24)
            }
        }
    }

    private fun setupTelegramProxyCard() {
        binding.btnActionTg.setOnClickListener {
            if (TgWsProxyService.isRunning.value) {
                TgWsProxyService.stop(this)
            } else {
                TgWsProxyService.start(this, port = 1443)
            }
        }

        binding.btnTgOpenClient.setOnClickListener {
            val secret = TgWsProxyService.effectiveSecret.value ?: ""
            val tgUri = Uri.parse("tg://proxy?server=127.0.0.1&port=1443&secret=$secret")
            val intent = Intent(Intent.ACTION_VIEW, tgUri)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Telegram MTProto Proxy", tgUri.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.tg_proxy_copied, Toast.LENGTH_LONG).show()
            }
        }

        lifecycleScope.launch {
            TgWsProxyService.isRunning.collectLatest { running ->
                if (running) {
                    binding.tgStatusBadge.text = "🟢 Работает"
                    binding.tgStatusBadge.setTextColor(getColor(R.color.accent_green))
                    binding.btnActionTg.text = getString(R.string.tg_active_btn)
                    binding.btnTgOpenClient.visibility = View.VISIBLE
                } else {
                    binding.tgStatusBadge.text = "⚪ Отключено"
                    binding.tgStatusBadge.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
                    binding.btnActionTg.text = getString(R.string.tg_inactive_btn)
                    binding.btnTgOpenClient.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            TgWsProxyService.trafficStats.collectLatest { stats ->
                if (TgWsProxyService.isRunning.value) {
                    binding.tgSubtitleText.text = "127.0.0.1:1443 • $stats"
                } else {
                    binding.tgSubtitleText.text = "127.0.0.1:1443 (Cloudflare WS)"
                }
            }
        }
    }

    private fun setupWarpCard() {
        WarpConfigManager.init(this)

        binding.btnActionWarp.setOnClickListener {
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.stop(this)
            } else {
                // Если запущен ByeDPI VPN, останавливаем его чтобы не конфликтовать с VpnService
                val (byedpiStatus, byedpiMode) = appStatus
                if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
                    ServiceManager.stop(this)
                }
                if (VlessVpnService.isRunning.value) {
                    VlessVpnService.stop(this)
                }

                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    warpVpnRegister.launch(intentPrepare)
                } else {
                    WarpVpnService.start(this)
                }
            }
        }

        // Авто переподключение Warp Switch
        binding.switchWarpAutoReconnect.isChecked = isWarpAutoReconnectEnabled()
        binding.switchWarpAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            setWarpAutoReconnectEnabled(isChecked)
            if (WarpVpnService.isRunning.value) {
                // Перезапуск службы для обновления колбэков сети
                WarpVpnService.start(this)
            }
        }

        // Помощь (?) по Авто переподключению
        binding.btnWarpAutoReconnectHelp.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.warp_auto_reconnect_title)
                .setMessage(R.string.warp_auto_reconnect_help)
                .setPositiveButton("OK", null)
                .show()
        }

        // Настройка DNS для WARP
        binding.btnWarpDnsSettings.setOnClickListener {
            showWarpDnsDialog()
        }

        lifecycleScope.launch {
            WarpVpnService.isRunning.collectLatest { running ->
                if (running) {
                    binding.warpStatusBadge.text = "🟢 Подключен"
                    binding.warpStatusBadge.setTextColor(getColor(R.color.accent_green))
                    binding.btnActionWarp.text = getString(R.string.warp_active_btn)
                } else {
                    binding.warpStatusBadge.text = "⚪ Отключено"
                    binding.warpStatusBadge.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
                    binding.btnActionWarp.text = getString(R.string.warp_inactive_btn)
                }
            }
        }

        lifecycleScope.launch {
            WarpConfigManager.currentConfig.collectLatest { config ->
                val endpoint = WarpConfigManager.extractEndpoint(config)
                binding.warpSubtitleText.text = "Cloudflare WARP ($endpoint)"
            }
        }
    }

    private fun showWarpDnsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_warp_dns, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val cardWarning = dialogView.findViewById<android.view.View>(R.id.card_private_dns_warning)
        val rgDns = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_warp_dns)
        val rbCloudflare = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_cloudflare)
        val rbGoogle = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_google)
        val rbXbox = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_xbox)
        val rbComss = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_comss)
        val rbMalw = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_malw)
        val rbAi = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_dns_ai)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save_warp_dns)

        val isPrivateDns = WarpDnsManager.isPrivateDnsActive(this)
        if (isPrivateDns) {
            cardWarning.visibility = android.view.View.VISIBLE
            // Блокируем выбор DNS радиокнопками
            for (i in 0 until rgDns.childCount) {
                rgDns.getChildAt(i).isEnabled = false
            }
            btnSave.isEnabled = false
        } else {
            cardWarning.visibility = android.view.View.GONE
        }

        // Текущий выбранный DNS
        val currentKey = getWarpDnsKey()
        when (currentKey) {
            "google" -> rbGoogle.isChecked = true
            "xbox" -> rbXbox.isChecked = true
            "comss" -> rbComss.isChecked = true
            "malw" -> rbMalw.isChecked = true
            "ai" -> rbAi.isChecked = true
            else -> rbCloudflare.isChecked = true
        }

        btnSave.setOnClickListener {
            val selectedKey = when {
                rbGoogle.isChecked -> "google"
                rbXbox.isChecked -> "xbox"
                rbComss.isChecked -> "comss"
                rbMalw.isChecked -> "malw"
                rbAi.isChecked -> "ai"
                else -> "cloudflare"
            }
            setWarpDnsKey(selectedKey)
            Toast.makeText(this, R.string.warp_dns_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()

            // Если WARP прямо сейчас запущен, перезапускаем для применения нового DNS
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.stop(this)
                binding.root.postDelayed({
                    WarpVpnService.start(this)
                }, 400)
            }
        }

        dialog.show()
    }

    private fun setupVlessCard() {
        updateVlessSubtitle()

        binding.btnVlessManage.setOnClickListener {
            val intent = Intent(this, VlessListActivity::class.java)
            startActivity(intent)
        }

        binding.btnActionVless.setOnClickListener {
            if (VlessVpnService.isRunning.value) {
                VlessVpnService.stop(this)
            } else {
                val selectedConfig = VlessManager.getSelectedConfig(this)
                if (selectedConfig == null) {
                    Toast.makeText(this, "Сначала добавьте VLESS сервер или подписку", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, VlessListActivity::class.java)
                    startActivity(intent)
                    return@setOnClickListener
                }

                // Отключаем ByeDPI и WARP во избежание коллизий VpnService
                val (byedpiStatus, byedpiMode) = appStatus
                if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
                    ServiceManager.stop(this)
                }
                if (WarpVpnService.isRunning.value) {
                    WarpVpnService.stop(this)
                }

                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vlessVpnRegister.launch(intentPrepare)
                } else {
                    VlessVpnService.start(this)
                }
            }
        }

        lifecycleScope.launch {
            VlessVpnService.isRunning.collectLatest { running ->
                if (running) {
                    binding.vlessStatusBadge.text = "🟢 Подключен"
                    binding.vlessStatusBadge.setTextColor(getColor(R.color.accent_green))
                    binding.btnActionVless.text = getString(R.string.vless_active_btn)
                } else {
                    binding.vlessStatusBadge.text = "⚪ Отключено"
                    binding.vlessStatusBadge.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
                    binding.btnActionVless.text = getString(R.string.vless_inactive_btn)
                }
            }
        }
    }

    private fun updateVlessSubtitle() {
        val selected = VlessManager.getSelectedConfig(this)
        if (selected != null) {
            binding.vlessSubtitleText.text = "${selected.name} (${selected.address}:${selected.port})"
        } else {
            binding.vlessSubtitleText.text = getString(R.string.vless_no_servers)
        }
    }

    private fun setupAdvancedSettingsCard() {
        binding.cardOpenAdvanced.setOnClickListener {
            val intent = Intent(this, AdvancedSettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun getThemeColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }
}