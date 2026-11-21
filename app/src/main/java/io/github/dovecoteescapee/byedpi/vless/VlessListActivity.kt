package io.github.dovecoteescapee.byedpi.vless

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityVlessListBinding
import io.github.dovecoteescapee.byedpi.activities.BaseActivity
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VlessListActivity : BaseActivity() {

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

        binding.btnAddChain.setOnClickListener {
            showAddChainDialog()
        }

        binding.btnTestPing.setOnClickListener {
            startUrlPingTest()
        }

        binding.btnPingMethodSettings.setOnClickListener {
            showPingMethodSelectorDialog()
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
            val tabTitle = BuiltinSubscriptions.getDisplayName(subUrl, index)
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
            val subName = BuiltinSubscriptions.getDisplayName(currentSub)
            binding.tvSubInfo.text = if (subName != currentSub) "$subName\n$currentSub" else currentSub

            val userInfo = VlessManager.getSubUserInfo(this, currentSub)
            if (userInfo != null && (userInfo.totalBytes > 0 || userInfo.expireEpochSec > 0)) {
                binding.cardSubUserInfo.visibility = View.VISIBLE
                val traf = userInfo.formatRemainingTraffic()
                binding.tvSubTrafficBadge.text = if (traf.isNotBlank()) "📊 $traf" else "📊 Без лимита"
                val exp = userInfo.formatExpireDays()
                binding.tvSubExpireBadge.text = if (exp.isNotBlank()) "⏳ $exp" else ""
            } else {
                binding.cardSubUserInfo.visibility = View.GONE
            }
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
                onEdit = { config ->
                    showEditServerDialog(config)
                },
                onPingSingle = { config ->
                    pingSingleServer(config)
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

        MaterialAlertDialogBuilder(this)
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_vless_add_subscription, null)
        val llContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.ll_builtin_subs_container)
        val etCustomUrl = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_custom_sub_url)
        val btnLoadCustom = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_load_custom)

        val existingSubs = VlessManager.getSubscriptions(this).map { it.trim().lowercase() }.toSet()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton("Закрыть", null)
            .create()

        BuiltinSubscriptions.LIST.forEach { item ->
            val cardView = layoutInflater.inflate(R.layout.item_builtin_sub_card, llContainer, false)
            val tvTitle = cardView.findViewById<android.widget.TextView>(R.id.tv_item_title)
            val tvBadge = cardView.findViewById<android.widget.TextView>(R.id.tv_item_badge)
            val tvDesc = cardView.findViewById<android.widget.TextView>(R.id.tv_item_desc)
            val tvStatus = cardView.findViewById<android.widget.TextView>(R.id.tv_item_status)
            val btnAction = cardView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_item_action)

            tvTitle.text = item.title
            tvBadge.text = item.badge
            tvDesc.text = item.description

            val isAdded = existingSubs.contains(item.url.trim().lowercase())
            if (isAdded) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.builtin_subs_already_added) + " ✓"
                btnAction.text = "Обновить"
            } else {
                tvStatus.visibility = View.GONE
                btnAction.text = getString(R.string.builtin_subs_add_btn)
            }

            btnAction.setOnClickListener {
                dialog.dismiss()
                downloadSubscription(item.url)
            }

            llContainer.addView(cardView)
        }

        btnLoadCustom.setOnClickListener {
            val subUrl = etCustomUrl.text?.toString()?.trim() ?: ""
            if (subUrl.startsWith("http://", ignoreCase = true) || subUrl.startsWith("https://", ignoreCase = true)) {
                dialog.dismiss()
                downloadSubscription(subUrl)
            } else {
                Toast.makeText(this, "Введите корректную HTTP/HTTPS ссылку", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showAddChainDialog() {
        val allConfigs = VlessManager.getConfigs(this).filter { !it.isChain }
        if (allConfigs.isEmpty()) {
            Toast.makeText(this, R.string.chain_no_proxies_found, Toast.LENGTH_LONG).show()
            return
        }

        val chainTypeOptions = arrayOf(
            getString(R.string.chain_type_warp_over_proxy),
            getString(R.string.chain_type_proxy_over_warp)
        )
        var selectedTypeIndex = 0

        val serverNames = allConfigs.map { "${it.protocol.uppercase()} • ${it.name} (${it.address}:${it.port})" }.toTypedArray()
        var selectedServerIndex = 0

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val tvType = android.widget.TextView(this).apply {
            text = "Тип цепочки:"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val spType = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@VlessListActivity,
                android.R.layout.simple_spinner_dropdown_item,
                chainTypeOptions
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedTypeIndex = position
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        val tvServer = android.widget.TextView(this).apply {
            text = getString(R.string.chain_select_proxy)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val spServer = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@VlessListActivity,
                android.R.layout.simple_spinner_dropdown_item,
                serverNames
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedServerIndex = position
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        container.addView(tvType)
        container.addView(spType)
        container.addView(tvServer)
        container.addView(spServer)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chain_title)
            .setView(container)
            .setPositiveButton("Создать") { _, _ ->
                val chosenProxy = allConfigs[selectedServerIndex]
                val mode = if (selectedTypeIndex == 0) "warp_over_proxy" else "proxy_over_warp"
                val chainName = if (mode == "warp_over_proxy") {
                    "WARP + ${chosenProxy.name}"
                } else {
                    "${chosenProxy.name} + WARP"
                }

                // Serialize chosenProxy to JSON for chainHop1ConfigJson
                val proxyJson = org.json.JSONObject().apply {
                    put("id", chosenProxy.id)
                    put("subscriptionUrl", chosenProxy.subscriptionUrl)
                    put("name", chosenProxy.name)
                    put("address", chosenProxy.address)
                    put("port", chosenProxy.port)
                    put("uuid", chosenProxy.uuid)
                    put("protocol", chosenProxy.protocol)
                    put("flow", chosenProxy.flow)
                    put("encryption", chosenProxy.encryption)
                    put("transport", chosenProxy.transport)
                    put("security", chosenProxy.security)
                    put("sni", chosenProxy.sni)
                    put("pbk", chosenProxy.pbk)
                    put("sid", chosenProxy.sid)
                    put("fp", chosenProxy.fp)
                    put("path", chosenProxy.path)
                    put("host", chosenProxy.host)
                    put("serviceName", chosenProxy.serviceName)
                    put("obfs", chosenProxy.obfs)
                    put("obfsPassword", chosenProxy.obfsPassword)
                    put("allowInsecure", chosenProxy.allowInsecure)
                    put("alterId", chosenProxy.alterId)
                    put("rawUri", chosenProxy.rawUri)
                }.toString()

                val warpConfigText = io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.currentConfig.value

                val chainConfig = VlessConfig(
                    name = chainName,
                    address = chosenProxy.address,
                    port = chosenProxy.port,
                    uuid = chosenProxy.uuid,
                    protocol = chosenProxy.protocol,
                    flow = chosenProxy.flow,
                    encryption = chosenProxy.encryption,
                    transport = chosenProxy.transport,
                    security = chosenProxy.security,
                    sni = chosenProxy.sni,
                    pbk = chosenProxy.pbk,
                    sid = chosenProxy.sid,
                    fp = chosenProxy.fp,
                    path = chosenProxy.path,
                    host = chosenProxy.host,
                    serviceName = chosenProxy.serviceName,
                    obfs = chosenProxy.obfs,
                    obfsPassword = chosenProxy.obfsPassword,
                    allowInsecure = chosenProxy.allowInsecure,
                    alterId = chosenProxy.alterId,
                    rawUri = "",
                    isChain = true,
                    chainMode = mode,
                    chainHop1ConfigJson = proxyJson,
                    chainWarpConfigText = warpConfigText
                )

                VlessManager.addConfig(this, chainConfig)
                loadServers()
                Toast.makeText(this, R.string.chain_created_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadSubscription(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = VlessManager.fetchSubscription(this@VlessListActivity, url)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { configs ->
                VlessManager.replaceSubscriptionConfigs(this@VlessListActivity, url, configs)
                setupTabs()
                val updatedSubs = VlessManager.getSubscriptions(this@VlessListActivity)
                val newSubIdx = updatedSubs.indexOf(url.trim())
                if (newSubIdx != -1) {
                    selectedTabIndex = newSubIdx + 2
                } else {
                    selectedTabIndex = binding.tabLayout.tabCount - 1
                }
                binding.tabLayout.getTabAt(selectedTabIndex)?.select()
                loadServers()
                val displayName = BuiltinSubscriptions.getDisplayName(url)
                Toast.makeText(
                    this@VlessListActivity,
                    "«$displayName»: загружено ${configs.size} серверов",
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
            val result = VlessManager.fetchSubscription(this@VlessListActivity, subUrl)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { configs ->
                // Полностью заменяем конфигурации этой подписки
                VlessManager.replaceSubscriptionConfigs(this@VlessListActivity, subUrl, configs)
                loadServers()
                val displayName = BuiltinSubscriptions.getDisplayName(subUrl)
                Toast.makeText(
                    this@VlessListActivity,
                    "«$displayName»: обновлена (${configs.size} серверов)",
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
        val displayName = BuiltinSubscriptions.getDisplayName(subUrl)
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить подписку?")
            .setMessage("Все серверы из подписки «$displayName» будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                VlessManager.removeSubscription(this, subUrl)
                selectedTabIndex = 0
                setupTabs()
                loadServers()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pingSingleServer(config: VlessConfig) {
        adapter?.setPing(config.id, -2L)
        val method = VlessManager.getPingMethod(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val ping = try {
                io.github.dovecoteescapee.byedpi.utility.RealPingTester.measureVlessPingByMethod(
                    method = method,
                    address = config.address,
                    port = config.port,
                    sni = if (config.sni.isNotBlank()) config.sni else config.host,
                    timeoutMs = 3000
                )
            } catch (e: Exception) {
                -1L
            }
            withContext(Dispatchers.Main) {
                adapter?.setPing(config.id, ping)
                VlessManager.setPing(this@VlessListActivity, config.id, ping)
                val statusText = if (ping > 0) "$ping ms" else "Недоступен"
                Toast.makeText(this@VlessListActivity, "${config.name}: $statusText", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPingMethodSelectorDialog() {
        val methods = arrayOf(
            "Proxy GET (Настоящий пинг с TLS/HTTP)",
            "TCP Connect (Подключение сокета)",
            "ICMP (Системный Echo Ping)"
        )
        val methodKeys = arrayOf("proxy_get", "tcp", "icmp")
        val currentMethod = VlessManager.getPingMethod(this)
        val selectedIdx = methodKeys.indexOf(currentMethod).let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Метод измерения пинга")
            .setSingleChoiceItems(methods, selectedIdx) { dialog, which ->
                val chosenKey = methodKeys[which]
                VlessManager.setPingMethod(this, chosenKey)
                Toast.makeText(this, "Выбран метод: ${methods[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditServerDialog(config: VlessConfig) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_vless_server, null)
        val etName = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_name)
        val etAddress = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_address)
        val etPort = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_port)
        val etUuid = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_uuid)
        val etSni = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_sni)
        val etPbk = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_pbk)
        val etSid = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_sid)
        val etFp = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_fp)
        val etPath = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_path)
        val etHost = dialogView.findViewById<android.widget.EditText>(R.id.et_cfg_host)
        val spinnerStrategy = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_cfg_strategy)

        etName.setText(config.name)
        etAddress.setText(config.address)
        etPort.setText(config.port.toString())
        etUuid.setText(config.uuid)
        etSni.setText(config.sni)
        etPbk.setText(config.pbk)
        etSid.setText(config.sid)
        etFp.setText(config.fp)
        etPath.setText(config.path)
        etHost.setText(config.host)

        val strategies = io.github.dovecoteescapee.byedpi.strategy.StrategyCatalog.strategies
        val strategyNames = mutableListOf("По умолчанию (системная)")
        strategyNames.addAll(strategies.map { "${it.name} (${it.description})" })

        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, strategyNames)
        spinnerStrategy.adapter = spinnerAdapter

        val initialStrategyIdx = if (config.desyncStrategyId.isNotBlank()) {
            val idx = strategies.indexOfFirst { it.id == config.desyncStrategyId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        spinnerStrategy.setSelection(initialStrategyIdx)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = etName.text.toString().trim()
                val newAddress = etAddress.text.toString().trim()
                val newPort = etPort.text.toString().trim().toIntOrNull() ?: config.port
                val newUuid = etUuid.text.toString().trim()
                val newSni = etSni.text.toString().trim()
                val newPbk = etPbk.text.toString().trim()
                val newSid = etSid.text.toString().trim()
                val newFp = etFp.text.toString().trim()
                val newPath = etPath.text.toString().trim()
                val newHost = etHost.text.toString().trim()

                val chosenStrategyIdx = spinnerStrategy.selectedItemPosition
                val chosenStrategyId = if (chosenStrategyIdx > 0 && chosenStrategyIdx - 1 < strategies.size) {
                    val st = strategies[chosenStrategyIdx - 1]
                    st.id
                } else ""

                val updatedConfig = config.copy(
                    name = if (newName.isNotBlank()) newName else config.name,
                    address = if (newAddress.isNotBlank()) newAddress else config.address,
                    port = newPort,
                    uuid = if (newUuid.isNotBlank()) newUuid else config.uuid,
                    sni = newSni,
                    pbk = newPbk,
                    sid = newSid,
                    fp = if (newFp.isNotBlank()) newFp else "chrome",
                    path = newPath,
                    host = newHost,
                    desyncStrategyId = chosenStrategyId
                )

                val all = VlessManager.getConfigs(this).toMutableList()
                val idx = all.indexOfFirst { it.id == config.id }
                if (idx != -1) {
                    all[idx] = updatedConfig
                    VlessManager.saveConfigs(this, all)
                    loadServers()
                    Toast.makeText(this, "Конфигурация сервера обновлена!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteConfirm(config: VlessConfig) {
        MaterialAlertDialogBuilder(this)
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
        binding.pingProgressBarContainer.visibility = View.VISIBLE
        binding.pingProgressIndicator.max = configsToTest.size
        binding.pingProgressIndicator.progress = 0

        // Помечаем все серверы как ожидающие проверки (-2L)
        configsToTest.forEach { adapter?.setPing(it.id, -2L) }

        val method = VlessManager.getPingMethod(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val testUrl = "https://www.gstatic.com/generate_204"
            val batchSize = 10
            val chunks = configsToTest.chunked(batchSize)
            var testedCount = 0

            for ((batchIndex, chunk) in chunks.withIndex()) {
                val fromIndex = batchIndex * batchSize + 1
                val toIndex = fromIndex + chunk.size - 1

                withContext(Dispatchers.Main) {
                    binding.tvPingBatchStatus.text = "Проверка пакета ${batchIndex + 1}/${chunks.size} (серверы $fromIndex–$toIndex из ${configsToTest.size})..."
                }

                // Тестируем параллельно ровно 10 серверов в пачке, чтобы не перегружать сокеты и сеть
                kotlinx.coroutines.coroutineScope {
                    chunk.map { config ->
                        launch {
                            val ping = try {
                                io.github.dovecoteescapee.byedpi.utility.RealPingTester.measureVlessPingByMethod(
                                    method = method,
                                    address = config.address,
                                    port = config.port,
                                    sni = if (config.sni.isNotBlank()) config.sni else config.host,
                                    timeoutMs = 3000
                                )
                            } catch (e: Throwable) {
                                -1L
                            }

                            withContext(Dispatchers.Main) {
                                adapter?.setPing(config.id, ping)
                                VlessManager.setPing(this@VlessListActivity, config.id, ping)
                                testedCount++
                                binding.pingProgressIndicator.progress = testedCount
                            }
                        }
                    }
                }

                // Небольшая пауза между батчами по 10 серверов, чтобы не вызвать flood/rate-limit
                if (batchIndex < chunks.size - 1) {
                    kotlinx.coroutines.delay(150)
                }
            }

            withContext(Dispatchers.Main) {
                binding.pingProgressBarContainer.visibility = View.GONE

                // Сортировка конфигураций по пингу (от наименьшего к наибольшему)
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
                Toast.makeText(this@VlessListActivity, "Тест завершен пакетами по 10! Серверы отсортированы", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
