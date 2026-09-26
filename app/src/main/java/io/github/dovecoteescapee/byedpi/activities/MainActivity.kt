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
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import io.github.dovecoteescapee.byedpi.vless.VlessManager
import io.github.dovecoteescapee.byedpi.vless.VlessVpnService
import io.github.dovecoteescapee.byedpi.vless.VlessListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {
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
        setupAdvancedSettingsCard()
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