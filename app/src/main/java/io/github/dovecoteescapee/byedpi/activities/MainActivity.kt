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
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxConfigActivity
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxManager
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxVpnService
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper
import androidx.activity.addCallback
import io.github.dovecoteescapee.byedpi.security.TamperGuard
import io.github.dovecoteescapee.byedpi.security.ModifiedBannerDialog
import io.github.dovecoteescapee.byedpi.security.CamouflageManager
import io.github.dovecoteescapee.byedpi.security.DisguiseUiController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var disguiseUiController: DisguiseUiController

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

    private val openFluxVpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                OpenFluxVpnService.start(this)
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
        currentAccent = getPreferences().getString("accent_color", "dynamic")
        setMaterial3UiMode(true)
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

        setupM3Interface()

        disguiseUiController = DisguiseUiController(this)
        updateDisguiseUi()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.camouflageOverlay.visibility == View.VISIBLE) {
                moveTaskToBack(true)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        if (TamperGuard.isModified(this) && !TamperGuard.verifyExecutionPermitted(this)) {
            ModifiedBannerDialog.show(this) {
                checkInitialSetup()
            }
        } else {
            checkInitialSetup()
        }
        handleTriggerModeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTriggerModeIntent(intent)
    }

    private fun handleTriggerModeIntent(intent: Intent?) {
        val trigger = intent?.getStringExtra("trigger_mode") ?: return
        intent.removeExtra("trigger_mode")
        when (trigger) {
            "byedpi" -> toggleByeDpi()
            "warp" -> startWarpVpn()
            "vless" -> toggleVless()
            "openflux" -> toggleOpenFlux()
        }
    }

    private fun isAnyVpnActive(): Boolean {
        val (byedpiStatus, byedpiMode) = appStatus
        val isByeDpiVpn = byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN
        val isWarpVpn = WarpVpnService.isRunning.value
        val isVlessVpn = VlessVpnService.isRunning.value
        val isOpenFluxVpn = OpenFluxVpnService.isRunning.value
        return isByeDpiVpn || isWarpVpn || isVlessVpn || isOpenFluxVpn
    }

    private fun startWarpVpn() {
        if (!TamperGuard.verifyExecutionPermitted(this)) {
            Toast.makeText(this, R.string.tamper_guard_blocked, Toast.LENGTH_LONG).show()
            return
        }
        val (byedpiStatus, byedpiMode) = appStatus
        if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
            ServiceManager.stop(this)
        }
        if (VlessVpnService.isRunning.value) {
            VlessVpnService.stop(this)
        }
        if (OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(this)
        }
        val intentPrepare = VpnService.prepare(this)
        if (intentPrepare != null) {
            warpVpnRegister.launch(intentPrepare)
        } else {
            WarpVpnService.start(this)
        }
    }

    private fun toggleByeDpi() {
        val (status, _) = appStatus
        when (status) {
            AppStatus.Halted -> {
                if (!TamperGuard.verifyExecutionPermitted(this)) {
                    Toast.makeText(this, R.string.tamper_guard_blocked, Toast.LENGTH_LONG).show()
                    return
                }
                if (WarpVpnService.isRunning.value) {
                    WarpVpnService.stop(this)
                }
                if (VlessVpnService.isRunning.value) {
                    VlessVpnService.stop(this)
                }
                if (OpenFluxVpnService.isRunning.value) {
                    OpenFluxVpnService.stop(this)
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

    private fun toggleWarp() {
        if (WarpVpnService.isRunning.value) {
            WarpVpnService.stop(this)
        } else {
            startWarpVpn()
        }
    }

    private fun toggleVless() {
        if (VlessVpnService.isRunning.value) {
            VlessVpnService.stop(this)
        } else {
            if (!TamperGuard.verifyExecutionPermitted(this)) {
                Toast.makeText(this, R.string.tamper_guard_blocked, Toast.LENGTH_LONG).show()
                return
            }
            val selectedConfig = VlessManager.getSelectedConfig(this)
            if (selectedConfig == null) {
                Toast.makeText(this, "Сначала добавьте VLESS сервер или подписку", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, VlessListActivity::class.java)
                startActivity(intent)
                return
            }

            val (byedpiStatus, byedpiMode) = appStatus
            if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
                ServiceManager.stop(this)
            }
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.stop(this)
            }
            if (OpenFluxVpnService.isRunning.value) {
                OpenFluxVpnService.stop(this)
            }

            val intentPrepare = VpnService.prepare(this)
            if (intentPrepare != null) {
                vlessVpnRegister.launch(intentPrepare)
            } else {
                VlessVpnService.start(this)
            }
        }
    }

    private fun toggleOpenFlux() {
        if (OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(this)
        } else {
            if (!TamperGuard.verifyExecutionPermitted(this)) {
                Toast.makeText(this, R.string.tamper_guard_blocked, Toast.LENGTH_LONG).show()
                return
            }
            val selectedConfig = OpenFluxManager.getSelectedConfig(this)
            if (selectedConfig == null) {
                Toast.makeText(this, "Сначала настройте профиль OpenFLUX", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, OpenFluxConfigActivity::class.java)
                startActivity(intent)
                return
            }

            val (byedpiStatus, byedpiMode) = appStatus
            if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
                ServiceManager.stop(this)
            }
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.stop(this)
            }
            if (VlessVpnService.isRunning.value) {
                VlessVpnService.stop(this)
            }

            if (selectedConfig.routingMode == "vpn") {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    openFluxVpnRegister.launch(intentPrepare)
                } else {
                    OpenFluxVpnService.start(this)
                }
            } else {
                OpenFluxVpnService.start(this)
            }
        }
    }

    private fun toggleTgProxy() {
        if (TgWsProxyService.isRunning.value) {
            TgWsProxyService.stop(this)
        } else {
            TgWsProxyService.start(this, port = 1443)
        }
    }

    private fun disconnectAllServices() {
        var disconnectedAny = false
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            ServiceManager.stop(this)
            disconnectedAny = true
        }
        if (WarpVpnService.isRunning.value) {
            WarpVpnService.stop(this)
            disconnectedAny = true
        }
        if (VlessVpnService.isRunning.value) {
            VlessVpnService.stop(this)
            disconnectedAny = true
        }
        if (OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(this)
            disconnectedAny = true
        }
        if (TgWsProxyService.isRunning.value) {
            TgWsProxyService.stop(this)
            disconnectedAny = true
        }
        if (disconnectedAny) {
            Toast.makeText(this, "Все службы отключены", Toast.LENGTH_SHORT).show()
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
                    updateM3State()
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
        val savedAccent = getPreferences().getString("accent_color", "dynamic")
        if (currentAccent != savedAccent) {
            recreate()
            return
        }
        updateM3State()
        updateDisguiseUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_lock_camouflage -> {
                CamouflageManager.isUnlockedInSession = false
                updateDisguiseUi()
                true
            }

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

    private fun updateDisguiseUi() {
        val currentDisguise = CamouflageManager.getCurrentDisguise(this)
        if (currentDisguise != CamouflageManager.DisguiseMode.DEFAULT) {
            val disguiseTitle = CamouflageManager.updateActivityIdentity(this, currentDisguise)
            if (!CamouflageManager.isUnlockedInSession && CamouflageManager.isFakeUiEnabled(this)) {
                binding.toolbar.title = disguiseTitle
                binding.toolbar.subtitle = null
            } else {
                binding.toolbar.title = getString(R.string.app_name)
                binding.toolbar.subtitle = getString(R.string.app_subtitle_clean)
            }
        } else {
            binding.toolbar.title = getString(R.string.app_name)
            binding.toolbar.subtitle = getString(R.string.app_subtitle_clean)
            CamouflageManager.updateActivityIdentity(this, CamouflageManager.DisguiseMode.DEFAULT)
        }

        binding.toolbar.menu.findItem(R.id.action_lock_camouflage)?.isVisible =
            (currentDisguise != CamouflageManager.DisguiseMode.DEFAULT &&
             CamouflageManager.isFakeUiEnabled(this) &&
             CamouflageManager.isUnlockedInSession)

        disguiseUiController.attachOverlay(binding.camouflageOverlay) {
            binding.toolbar.title = getString(R.string.app_name)
            binding.toolbar.subtitle = getString(R.string.app_subtitle_clean)
            binding.toolbar.menu.findItem(R.id.action_lock_camouflage)?.isVisible = true
        }
    }

    private fun updateStatus() {
        updateM3State()
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
        val isForced = isWarpDnsForceOverride()
        val btnForceUseDns = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_force_use_dns)

        fun unlockDnsOptions() {
            for (i in 0 until rgDns.childCount) {
                rgDns.getChildAt(i).isEnabled = true
            }
            btnSave.isEnabled = true
            btnForceUseDns?.text = getString(R.string.warp_dns_force_unlocked)
            btnForceUseDns?.isEnabled = false
        }

        if (isPrivateDns) {
            val serverName = WarpDnsManager.getPrivateDnsServerName(this)
            val warningTextView = dialogView.findViewById<android.widget.TextView>(R.id.tv_private_dns_warning)
            if (!serverName.isNullOrBlank()) {
                warningTextView?.text = getString(R.string.warp_dns_private_warning_with_server, serverName)
            } else {
                warningTextView?.text = getString(R.string.warp_dns_private_warning)
            }
            cardWarning.visibility = android.view.View.VISIBLE

            if (isForced) {
                unlockDnsOptions()
            } else {
                // Блокируем выбор DNS радиокнопками
                for (i in 0 until rgDns.childCount) {
                    rgDns.getChildAt(i).isEnabled = false
                }
                btnSave.isEnabled = false

                btnForceUseDns?.setOnClickListener {
                    setWarpDnsForceOverride(true)
                    unlockDnsOptions()
                }
            }
        } else {
            cardWarning.visibility = android.view.View.GONE
            for (i in 0 until rgDns.childCount) {
                rgDns.getChildAt(i).isEnabled = true
            }
            btnSave.isEnabled = true
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
            if (isPrivateDns) {
                setWarpDnsForceOverride(true)
            }
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

    private fun showTelegramFixDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("✈️ Исправление работы Telegram")
            .setMessage("РКН усилил блокировки и замедляет MTProto Telegram (пинг до 1000мс).\n\n" +
                    "Данный фикс устраняет проблему:\n" +
                    "• Отключает зависающий IPv6 туннель (Telegram переключается на быстрый IPv4)\n" +
                    "• Добавляет прямые маршруты к DC серверам Telegram в обход ТСПУ\n" +
                    "• Фиксирует MTU 1280 без фрагментации пакетов\n" +
                    "• Переключает сервер WARP на неблокируемый порт 500")
            .setPositiveButton("Применить фикс") { _, _ ->
                WarpVpnService.setWarpIpv6Enabled(this, false)
                WarpVpnService.setWarpTgFixEnabled(this, true)
                lifecycleScope.launch {
                    val (ep, ping) = WarpConfigManager.optimizeEndpoint(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "Telegram починен! Сервер: $ep ($ping ms)",
                        Toast.LENGTH_LONG
                    ).show()
                    if (WarpVpnService.isRunning.value) {
                        WarpVpnService.stop(this@MainActivity)
                        delay(500)
                        WarpVpnService.start(this@MainActivity)
                    }
                }
            }
            .setNeutralButton("MTProto Прокси") { _, _ ->
                if (!TgWsProxyService.isRunning.value) {
                    TgWsProxyService.start(this)
                }
                val secret = TgWsProxyService.getEffectiveSecret(this)
                val tgUri = Uri.parse("tg://proxy?server=127.0.0.1&port=1443&secret=$secret")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, tgUri))
                } catch (e: Exception) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Proxy", tgUri.toString()))
                    Toast.makeText(this, R.string.tg_proxy_copied, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setupM3Interface() {
        TgWsProxyService.initSecret(this)
        WarpConfigManager.init(this)

        binding.m3BtnDisconnectAll.setOnClickListener {
            disconnectAllServices()
        }

        binding.m3SwitchByedpi.setOnClickListener { toggleByeDpi() }
        binding.m3CardByedpi.setOnClickListener { toggleByeDpi() }

        binding.m3SwitchWarp.setOnClickListener { toggleWarp() }
        binding.m3CardWarp.setOnClickListener { toggleWarp() }
        binding.m3SwitchWarpAutoreconnect.isChecked = isWarpAutoReconnectEnabled()
        binding.m3SwitchWarpAutoreconnect.setOnCheckedChangeListener { _, isChecked ->
            setWarpAutoReconnectEnabled(isChecked)
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.start(this)
            }
        }
        binding.m3BtnWarpDns.setOnClickListener { showWarpDnsDialog() }
        binding.m3BtnWarpPing.setOnClickListener {
            if (WarpVpnService.isRunning.value) {
                Toast.makeText(this, "Проверка пинга WARP...", Toast.LENGTH_SHORT).show()
                WarpVpnService.checkPingAsync(lifecycleScope)
            } else {
                Toast.makeText(this, "WARP не подключен", Toast.LENGTH_SHORT).show()
            }
        }
        binding.m3BtnWarpOptimize.setOnClickListener {
            Toast.makeText(this, "Поиск неблокируемого сервера с минимальным пингом...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                binding.m3BtnWarpOptimize.isEnabled = false
                val (bestEp, ping) = WarpConfigManager.optimizeEndpoint(this@MainActivity)
                binding.m3BtnWarpOptimize.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Выбран лучший сервер: $bestEp (пинг: $ping ms)",
                    Toast.LENGTH_LONG
                ).show()
                if (WarpVpnService.isRunning.value) {
                    WarpVpnService.stop(this@MainActivity)
                    delay(500)
                    WarpVpnService.start(this@MainActivity)
                }
            }
        }
        binding.m3BtnWarpTgFix.setOnClickListener {
            showTelegramFixDialog()
        }

        binding.m3SwitchVless.setOnClickListener { toggleVless() }
        binding.m3CardVless.setOnClickListener { toggleVless() }
        binding.m3BtnVlessServers.setOnClickListener {
            startActivity(Intent(this, VlessListActivity::class.java))
        }

        binding.m3SwitchOpenflux.setOnClickListener { toggleOpenFlux() }
        binding.m3CardOpenflux.setOnClickListener { toggleOpenFlux() }
        binding.m3BtnOpenfluxServers.setOnClickListener {
            startActivity(Intent(this, OpenFluxConfigActivity::class.java))
        }

        binding.m3SwitchTg.setOnClickListener { toggleTgProxy() }
        binding.m3CardTg.setOnClickListener { toggleTgProxy() }
        binding.m3BtnTgOpen.setOnClickListener {
            val secret = TgWsProxyService.getEffectiveSecret(this)
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
        binding.m3BtnTgOpen.setOnLongClickListener {
            showTgProxyDetailsDialog()
            true
        }

        binding.m3BtnTgChannel.setOnClickListener {
            openTelegramWithVpnCheck()
        }

        binding.m3CardAdvanced.setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        // TV & D-Pad Remote Navigation
        TvNavigationHelper.setupCardFocus(binding.m3HeroCard)
        TvNavigationHelper.setupCardFocus(binding.m3CardByedpi) { toggleByeDpi() }
        TvNavigationHelper.setupCardFocus(binding.m3CardWarp) { toggleWarp() }
        TvNavigationHelper.setupCardFocus(binding.m3CardVless) { toggleVless() }
        TvNavigationHelper.setupCardFocus(binding.m3CardOpenflux) { toggleOpenFlux() }
        TvNavigationHelper.setupCardFocus(binding.m3CardTg) { toggleTgProxy() }
        TvNavigationHelper.setupCardFocus(binding.m3CardAdvanced) {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        TvNavigationHelper.setupButtonFocus(binding.m3BtnDisconnectAll)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnWarpDns)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnWarpPing)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnWarpOptimize)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnWarpTgFix)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnVlessServers)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnOpenfluxServers)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnTgOpen)
        TvNavigationHelper.setupButtonFocus(binding.m3BtnTgChannel)

        lifecycleScope.launch {
            TgWsProxyService.isRunning.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            TgWsProxyService.trafficStats.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            combine(
                WarpVpnService.isRunning,
                WarpVpnService.warpPingMs,
                WarpVpnService.connectionStatus
            ) { _, _, _ -> }.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            WarpConfigManager.currentConfig.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            VlessVpnService.isRunning.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            OpenFluxVpnService.isRunning.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            OpenFluxVpnService.connectionStatus.collectLatest { updateM3State() }
        }
        lifecycleScope.launch {
            OpenFluxVpnService.trafficStats.collectLatest { updateM3State() }
        }

        updateM3State()
    }

    private fun showTgProxyDetailsDialog() {
        val secret = TgWsProxyService.getEffectiveSecret(this)
        val tgUri = Uri.parse("tg://proxy?server=127.0.0.1&port=1443&secret=$secret")

        val message = "Хост: 127.0.0.1\nПорт: 1443\nСекрет: $secret\n\nСекрет сохранён в настройках приложения и больше не сбрасывается при перезапусках прокси."

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Telegram MTProto Proxy")
            .setMessage(message)
            .setPositiveButton("Скопировать") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Telegram MTProto Proxy", tgUri.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.tg_proxy_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Сбросить секрет") { _, _ ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Сброс секрета")
                    .setMessage("Вы уверены, что хотите сгенерировать новый секрет? Потребуется заново применить прокси в Telegram.")
                    .setPositiveButton("Сгенерировать") { _, _ ->
                        TgWsProxyService.resetSecret(this)
                        Toast.makeText(this, "Секрет сброшен и обновлён", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun updateM3State() {
        if (!::binding.isInitialized) return

        val (status, _) = appStatus
        val isByeDpiRunning = status == AppStatus.Running
        val isWarpRunning = WarpVpnService.isRunning.value
        val isVlessRunning = VlessVpnService.isRunning.value
        val isTgRunning = TgWsProxyService.isRunning.value
        val isOpenFluxRunning = OpenFluxVpnService.isRunning.value
        val isAnyRunning = isByeDpiRunning || isWarpRunning || isVlessRunning || isTgRunning || isOpenFluxRunning

        val colorPrimary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val colorPrimaryContainer = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val colorOnPrimaryContainer = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val colorSurface = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val colorSurfaceVariant = getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val colorOutline = getThemeColor(com.google.android.material.R.attr.colorOutline)
        val colorOutlineVariant = getThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
        val colorOnSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)

        // 1. Hero Card
        if (isAnyRunning) {
            binding.m3StatusCircle.setCardBackgroundColor(colorPrimaryContainer)
            binding.m3StatusCircle.strokeColor = colorPrimary
            binding.m3StatusIcon.setImageResource(R.drawable.ic_shield_check_24)
            binding.m3StatusIcon.imageTintList = ColorStateList.valueOf(colorOnPrimaryContainer)
            binding.m3StatusTitle.text = getString(R.string.m3_status_protected)
            binding.m3StatusTitle.setTextColor(colorPrimary)

            val activeList = mutableListOf<String>()
            if (isByeDpiRunning) activeList.add("ByeDPI")
            if (isWarpRunning) activeList.add("WARP")
            if (isVlessRunning) activeList.add("VLESS")
            if (isOpenFluxRunning) activeList.add("OpenFLUX")
            if (isTgRunning) activeList.add("TG Proxy")
            binding.m3StatusSubtitle.text = "Активно: " + activeList.joinToString(", ")
            binding.m3BtnDisconnectAll.visibility = View.VISIBLE
        } else {
            binding.m3StatusCircle.setCardBackgroundColor(colorSurface)
            binding.m3StatusCircle.strokeColor = colorOutlineVariant
            binding.m3StatusIcon.setImageResource(R.drawable.ic_shield_off_24)
            binding.m3StatusIcon.imageTintList = ColorStateList.valueOf(colorOutline)
            binding.m3StatusTitle.text = getString(R.string.m3_status_unprotected)
            binding.m3StatusTitle.setTextColor(colorOnSurface)
            binding.m3StatusSubtitle.text = "Все службы отключены"
            binding.m3BtnDisconnectAll.visibility = View.GONE
        }

        // 2. ByeDPI Switch & Subtitle
        val sp = getPreferences()
        val currentStrategyId = sp.getString("selected_strategy_id", null) ?: StrategyCatalog.strategies[0].id
        val currentStrategy = StrategyCatalog.getStrategyById(currentStrategyId)

        binding.m3SwitchByedpi.isChecked = isByeDpiRunning
        if (isByeDpiRunning) {
            binding.m3IconBoxByedpi.setCardBackgroundColor(colorPrimaryContainer)
            binding.m3IconByedpi.imageTintList = ColorStateList.valueOf(colorPrimary)
            binding.m3SubByedpi.text = "🟢 Активно • ${currentStrategy.name}"
        } else {
            binding.m3IconBoxByedpi.setCardBackgroundColor(colorSurfaceVariant)
            binding.m3IconByedpi.imageTintList = ColorStateList.valueOf(colorOutline)
            binding.m3SubByedpi.text = "⚪ Отключено • ${currentStrategy.name}"
        }

        // 3. WARP Switch & Subtitle
        val warpPing = WarpVpnService.warpPingMs.value
        val warpStatus = WarpVpnService.connectionStatus.value
        binding.m3SwitchWarp.isChecked = isWarpRunning
        if (isWarpRunning) {
            binding.m3IconBoxWarp.setCardBackgroundColor(colorPrimaryContainer)
            binding.m3IconWarp.imageTintList = ColorStateList.valueOf(colorPrimary)
            if (warpPing != null && warpPing >= 0) {
                binding.m3SubWarp.text = "🟢 Подключен ($warpPing ms) • AWG 2.0"
            } else if (warpStatus.contains("Проверка") || warpStatus.contains("Переподключение") || warpStatus.contains("Потеря")) {
                binding.m3SubWarp.text = "🟡 $warpStatus • AWG 2.0"
            } else {
                binding.m3SubWarp.text = "🟢 Подключен • AWG 2.0"
            }
        } else {
            binding.m3IconBoxWarp.setCardBackgroundColor(colorSurfaceVariant)
            binding.m3IconWarp.imageTintList = ColorStateList.valueOf(colorOutline)
            if (warpStatus.contains("Ожидание") || warpStatus.contains("Переподключение") || warpStatus.contains("Потеря") || warpStatus.contains("Таймаут")) {
                binding.m3SubWarp.text = "🟡 $warpStatus • Cloudflare WARP"
            } else {
                binding.m3SubWarp.text = "⚪ Отключено • Cloudflare WARP"
            }
        }

        // 4. VLESS Switch & Subtitle
        val selectedVless = VlessManager.getSelectedConfig(this)
        binding.m3SwitchVless.isChecked = isVlessRunning
        if (isVlessRunning) {
            binding.m3IconBoxVless.setCardBackgroundColor(colorPrimaryContainer)
            val serverName = selectedVless?.name ?: "VLESS"
            binding.m3SubVless.text = "🟢 Подключен • $serverName"
        } else {
            binding.m3IconBoxVless.setCardBackgroundColor(colorSurfaceVariant)
            if (selectedVless != null) {
                binding.m3SubVless.text = "⚪ Отключено • ${selectedVless.name}"
            } else {
                binding.m3SubVless.text = getString(R.string.vless_no_servers)
            }
        }

        // 5. OpenFLUX Switch & Subtitle
        val selectedOpenFlux = OpenFluxManager.getSelectedConfig(this)
        val openFluxStats = OpenFluxVpnService.trafficStats.value
        binding.m3SwitchOpenflux.isChecked = isOpenFluxRunning
        if (isOpenFluxRunning) {
            binding.m3IconBoxOpenflux.setCardBackgroundColor(colorPrimaryContainer)
            val name = selectedOpenFlux?.name ?: "OpenFLUX"
            val detail = if (openFluxStats.isNotBlank()) " • $openFluxStats" else ""
            binding.m3SubOpenflux.text = "🟢 Подключен • $name$detail"
        } else {
            binding.m3IconBoxOpenflux.setCardBackgroundColor(colorSurfaceVariant)
            val name = selectedOpenFlux?.name ?: "OpenFLUX"
            binding.m3SubOpenflux.text = "⚪ Отключено • $name"
        }

        // 6. TG Proxy Switch & Subtitle
        binding.m3SwitchTg.isChecked = isTgRunning
        binding.m3BtnTgOpen.visibility = if (isTgRunning) View.VISIBLE else View.GONE
        val tgStats = TgWsProxyService.trafficStats.value
        if (isTgRunning) {
            binding.m3IconBoxTg.setCardBackgroundColor(colorPrimaryContainer)
            binding.m3SubTg.text = "🟢 Работает • $tgStats"
        } else {
            binding.m3IconBoxTg.setCardBackgroundColor(colorSurfaceVariant)
            binding.m3SubTg.text = "⚪ Отключено • 127.0.0.1:1443"
        }
    }

    private fun getThemeColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }
}