package io.github.dovecoteescapee.byedpi.vless

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityVlessListBinding
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VlessListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVlessListBinding
    private var adapter: VlessServerAdapter? = null
    private var selectedTabIndex = 0 // 0 = Все, 1 = Одиночные, далее подписки

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        super.onCreate(savedInstanceState)
        binding = ActivityVlessListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fix WindowInsets padding so status bar does not overlap toolbar
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBars.top, 0, 0)
            binding.contentFrame.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.rvServers.layoutManager = LinearLayoutManager(this)

        binding.btnAddKey.setOnClickListener {
            showAddKeyDialog()
        }

        binding.btnAddSub.setOnClickListener {
            showAddSubscriptionDialog()
        }

        binding.btnTestPing.setOnClickListener {
            startUrlPingTest()
        }

        binding.btnRefreshSub.setOnClickListener {
            refreshCurrentSubscription()
        }

        binding.btnDeleteSub.setOnClickListener {
            deleteCurrentSubscription()
        }

        setupTabs()
        loadServers()
    }

    private fun setupTabs() {
        binding.tabLayout.removeAllTabs()

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Все"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Ключи"))

        val subs = VlessManager.getSubscriptions(this)
        subs.forEachIndexed { index, subUrl ->
            val tabTitle = try {
                val host = Uri.parse(subUrl).host ?: "Подписка ${index + 1}"
                host
            } catch (_: Exception) {
                "Подписка ${index + 1}"
            }
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(tabTitle))
        }

        if (selectedTabIndex >= binding.tabLayout.tabCount) {
            selectedTabIndex = 0
        }

        binding.tabLayout.getTabAt(selectedTabIndex)?.select()

        binding.tabLayout.clearOnTabSelectedListeners()
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTabIndex = tab?.position ?: 0
                loadServers()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun getCurrentSubUrl(): String? {
        val subs = VlessManager.getSubscriptions(this)
        val subIndex = selectedTabIndex - 2
        return if (subIndex in subs.indices) subs[subIndex] else null
    }

    private fun loadServers() {
        val allConfigs = VlessManager.getConfigs(this)
        val selected = VlessManager.getSelectedConfig(this)
        val subs = VlessManager.getSubscriptions(this)

        val currentSub = getCurrentSubUrl()
        if (currentSub != null) {
            binding.subControlBar.visibility = View.VISIBLE
            binding.tvSubInfo.text = currentSub
        } else {
            binding.subControlBar.visibility = View.GONE
        }

        // Фильтрация по выбранной вкладке
        val filtered = when (selectedTabIndex) {
            0 -> allConfigs // Все
            1 -> allConfigs.filter { it.subscriptionUrl.isBlank() } // Одиночные ключи
            else -> {
                val subUrl = getCurrentSubUrl() ?: ""
                allConfigs.filter { it.subscriptionUrl == subUrl }
            }
        }

        if (filtered.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvServers.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvServers.visibility = View.VISIBLE
        }

        if (adapter == null) {
            adapter = VlessServerAdapter(
                items = filtered,
                selectedId = selected?.id,
                onSelect = { config ->
                    VlessManager.setSelectedConfigId(this, config.id)
                    if (VlessVpnService.isRunning.value) {
                        // Restart VPN to apply new server
                        VlessVpnService.stop(this)
                        VlessVpnService.start(this)
                    }
                },
                onDelete = { config ->
                    showDeleteConfirm(config)
                }
            )
            binding.rvServers.adapter = adapter
        } else {
            adapter?.updateList(filtered, selected?.id)
        }
    }

    private fun showAddKeyDialog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        val lowerClip = clipText.lowercase()
        val initialText = if (lowerClip.startsWith("vless://") ||
            lowerClip.startsWith("hy2://") ||
            lowerClip.startsWith("hysteria2://") ||
            lowerClip.startsWith("ss://") ||
            lowerClip.startsWith("vmess://") ||
            lowerClip.startsWith("trojan://")) clipText else ""

        val input = EditText(this).apply {
            hint = "vless://, hy2://, ss://, vmess://, trojan://"
            setText(initialText)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.vless_add_single)
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val uriStr = input.text.toString().trim()
                val config = VlessConfig.parse(uriStr)
                if (config != null) {
                    VlessManager.addConfig(this, config)
                    loadServers()
                    val protoName = when (config.protocol.lowercase()) {
                        "hysteria2" -> "Hysteria2"
                        "shadowsocks" -> "Shadowsocks"
                        "vmess" -> "VMess"
                        "trojan" -> "Trojan"
                        else -> "VLESS"
                    }
                    Toast.makeText(this, "$protoName сервер успешно добавлен!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.vless_invalid_link, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAddSubscriptionDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.vless_enter_sub_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.vless_add_subscription)
            .setView(input)
            .setPositiveButton("Загрузить") { _, _ ->
                val subUrl = input.text.toString().trim()
                if (subUrl.startsWith("http://") || subUrl.startsWith("https://")) {
                    downloadSubscription(subUrl)
                } else {
                    Toast.makeText(this, "Введите корректную HTTP/HTTPS ссылку", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadSubscription(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = VlessManager.fetchSubscription(url)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { configs ->
                VlessManager.replaceSubscriptionConfigs(this@VlessListActivity, url, configs)
                setupTabs()
                // Переключаем на созданную вкладку
                selectedTabIndex = binding.tabLayout.tabCount - 1
                binding.tabLayout.getTabAt(selectedTabIndex)?.select()
                loadServers()
                Toast.makeText(
                    this@VlessListActivity,
                    getString(R.string.vless_sub_success, configs.size),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@VlessListActivity,
                    getString(R.string.vless_sub_error, err.message ?: "ошибка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshCurrentSubscription() {
        val subUrl = getCurrentSubUrl() ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = VlessManager.fetchSubscription(subUrl)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { configs ->
                // Полностью заменяем конфигурации этой подписки
                VlessManager.replaceSubscriptionConfigs(this@VlessListActivity, subUrl, configs)
                loadServers()
                Toast.makeText(
                    this@VlessListActivity,
                    "Подписка обновлена (${configs.size} серверов)",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@VlessListActivity,
                    getString(R.string.vless_sub_error, err.message ?: "ошибка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteCurrentSubscription() {
        val subUrl = getCurrentSubUrl() ?: return
        AlertDialog.Builder(this)
            .setTitle("Удалить подписку?")
            .setMessage("Все серверы из подписки $subUrl будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                VlessManager.removeSubscription(this, subUrl)
                selectedTabIndex = 0
                setupTabs()
                loadServers()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteConfirm(config: VlessConfig) {
        AlertDialog.Builder(this)
            .setMessage("${getString(R.string.vless_delete_confirm)}\n${config.name}")
            .setPositiveButton("Удалить") { _, _ ->
                VlessManager.removeConfig(this, config.id)
                loadServers()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startUrlPingTest() {
        val allConfigs = VlessManager.getConfigs(this)
        val configsToTest = when (selectedTabIndex) {
            0 -> allConfigs
            1 -> allConfigs.filter { it.subscriptionUrl.isBlank() }
            else -> {
                val subUrl = getCurrentSubUrl() ?: ""
                allConfigs.filter { it.subscriptionUrl == subUrl }
            }
        }

        if (configsToTest.isEmpty()) {
            Toast.makeText(this, "Нет добавленных серверов для теста", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestPing.isEnabled = false
        binding.btnTestPing.text = "Тестирование..."

        // Помечаем все серверы как тестируемые
        configsToTest.forEach { adapter?.setPing(it.id, -2L) }

        lifecycleScope.launch(Dispatchers.IO) {
            val testUrl = "https://www.gstatic.com/generate_204"

            kotlinx.coroutines.coroutineScope {
                configsToTest.map { config ->
                    launch {
                        val ping = try {
                            val xrayJson = config.toXrayConfigJson(localSocksPort = 0)
                            val delay = libv2ray.Libv2ray.measureOutboundDelay(xrayJson, testUrl)
                            if (delay > 0) delay else -1L
                        } catch (e: Exception) {
                            // Fallback to direct TCP ping if Xray outbounds test fails locally
                            try {
                                val startTime = System.currentTimeMillis()
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(config.address, config.port), 3000)
                                socket.close()
                                System.currentTimeMillis() - startTime
                            } catch (_: Exception) {
                                -1L
                            }
                        }

                        withContext(Dispatchers.Main) {
                            adapter?.setPing(config.id, ping)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                // Сортировка конфигураций по пингу (от меньшего к большему)
                // Серверы с положительным пингом впереди, затем недоступные (-1L)
                val sorted = configsToTest.sortedWith(
                    compareBy<VlessConfig> { cfg ->
                        val p = adapter?.getPing(cfg.id) ?: Long.MAX_VALUE
                        if (p >= 0) p else Long.MAX_VALUE - 1000 + (if (p == -1L) 100 else 0)
                    }.thenBy { it.name.lowercase() }
                )

                val selected = VlessManager.getSelectedConfig(this@VlessListActivity)
                adapter?.updateList(sorted, selected?.id)

                binding.btnTestPing.isEnabled = true
                binding.btnTestPing.text = "⚡ Тест пинга"
                Toast.makeText(this@VlessListActivity, "Тест завершен, серверы отсортированы по пингу", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
