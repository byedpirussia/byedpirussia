package io.github.dovecoteescapee.byedpi.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.BaseActivity
import io.github.dovecoteescapee.byedpi.backup.BackupManager
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.databinding.ActivityDataSyncBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataSyncActivity : BaseActivity() {

    private lateinit var binding: ActivityDataSyncBinding
    private var exportClient: ExportClient? = null
    private var serverJob: Job? = null
    private var scanJob: Job? = null
    private val discoveredDevices = mutableListOf<DataSyncManager.DiscoveredServer>()
    private lateinit var devicesAdapter: DiscoveredDevicesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupListeners()

        // If auto_start_import is passed (e.g. from TV first launch), auto start Import
        if (intent.getBooleanExtra("auto_start_import", false)) {
            startImportMode()
        }

        // TV Remote Navigation
        TvNavigationHelper.setupCardFocus(binding.cardChooseImport) { startImportMode() }
        TvNavigationHelper.setupCardFocus(binding.cardChooseExport) { startExportMode() }
        TvNavigationHelper.setupButtonFocus(binding.btnActionStartImport)
        TvNavigationHelper.setupButtonFocus(binding.btnActionStartExport)
        TvNavigationHelper.setupButtonFocus(binding.btnStopImport)
        TvNavigationHelper.setupButtonFocus(binding.btnRescanDevices)
        TvNavigationHelper.setupButtonFocus(binding.btnSendAllData)
        TvNavigationHelper.setupButtonFocus(binding.btnRemoteToggleByedpi)
        TvNavigationHelper.setupButtonFocus(binding.btnRemoteToggleWarp)
        TvNavigationHelper.setupButtonFocus(binding.btnCloseExportSession)
    }

    private fun setupRecyclerView() {
        devicesAdapter = DiscoveredDevicesAdapter(discoveredDevices) { server ->
            connectToTarget(server)
        }
        binding.rvDiscoveredDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDiscoveredDevices.adapter = devicesAdapter
    }

    private fun setupListeners() {
        binding.btnActionStartImport.setOnClickListener { startImportMode() }
        binding.cardChooseImport.setOnClickListener { startImportMode() }

        binding.btnActionStartExport.setOnClickListener { startExportMode() }
        binding.cardChooseExport.setOnClickListener { startExportMode() }

        binding.btnStopImport.setOnClickListener {
            stopImportMode()
        }

        binding.btnRescanDevices.setOnClickListener {
            scanForDevices()
        }

        binding.btnSendAllData.setOnClickListener {
            sendBackupToConnectedDevice()
        }

        binding.btnRemoteToggleByedpi.setOnClickListener {
            lifecycleScope.launch {
                val res = exportClient?.sendRemoteCommand("TOGGLE_BYEDPI")
                Toast.makeText(this@DataSyncActivity, "Команда отправлена: ${res ?: "ОК"}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRemoteToggleWarp.setOnClickListener {
            lifecycleScope.launch {
                val res = exportClient?.sendRemoteCommand("TOGGLE_WARP")
                Toast.makeText(this@DataSyncActivity, "Команда отправлена: ${res ?: "ОК"}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCloseExportSession.setOnClickListener {
            closeExportSession()
        }
    }

    // --- ИМПОРТ ---

    private fun startImportMode() {
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutExportActive.visibility = View.GONE
        binding.layoutImportActive.visibility = View.VISIBLE

        val ip = DataSyncManager.getLocalIpAddress()
        binding.tvImportIpInfo.text = "IP: $ip • Порт: ${DataSyncManager.SYNC_PORT}"

        serverJob?.cancel()
        serverJob = lifecycleScope.launch {
            DataSyncManager.startImportServer(
                context = this@DataSyncActivity,
                onClientPending = { clientName, clientIp, onDecision ->
                    runOnUiThread {
                        showConsentDialog(clientName, clientIp, onDecision)
                    }
                },
                onDataReceived = { backupJson ->
                    runOnUiThread {
                        applyReceivedBackup(backupJson)
                    }
                },
                onRemoteCommand = { cmd, _ ->
                    handleRemoteCommand(cmd)
                },
                onSessionClosed = {
                    runOnUiThread {
                        Toast.makeText(this@DataSyncActivity, "Клиент отключился", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun stopImportMode() {
        DataSyncManager.stopServer()
        serverJob?.cancel()
        serverJob = null

        binding.layoutImportActive.visibility = View.GONE
        binding.layoutModeSelection.visibility = View.VISIBLE
    }

    private fun showConsentDialog(clientName: String, clientIp: String, onDecision: (Boolean) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Запрос на подключение")
            .setMessage("Устройство «$clientName» ($clientIp) хочет подключиться к этому устройству для переноса данных и управления.\n\nРазрешить подключение?")
            .setCancelable(false)
            .setPositiveButton("Разрешить") { _, _ ->
                onDecision(true)
                Toast.makeText(this, "Подключение принято!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отклонить") { _, _ ->
                onDecision(false)
                Toast.makeText(this, "Подключение отклонено", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applyReceivedBackup(json: String) {
        val result = BackupManager.restoreBackupJson(this, json)
        result.onSuccess { summary ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Данные успешно перенесены! 🎉")
                .setMessage("Восстановлено конфигураций:\n" +
                        "• VLESS серверов: ${summary.vlessCount}\n" +
                        "• Подписок: ${summary.subscriptionsCount}\n" +
                        "• WARP профиль: ${if (summary.warpRestored) "ДА" else "НЕТ"}\n" +
                        "• Приложений Split Tunnel: ${summary.splitTunnelAppsCount}")
                .setPositiveButton("Отлично", null)
                .show()
        }.onFailure { e ->
            Toast.makeText(this, "Ошибка восстановления данных: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRemoteCommand(cmd: String): String {
        return when (cmd) {
            "TOGGLE_BYEDPI" -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) {
                    ServiceManager.stop(this)
                    "BYEDPI_STOPPED"
                } else {
                    ServiceManager.start(this, Mode.VPN)
                    "BYEDPI_STARTED"
                }
            }
            "TOGGLE_WARP" -> {
                if (WarpVpnService.isRunning.value) {
                    WarpVpnService.stop(this)
                    "WARP_STOPPED"
                } else {
                    WarpVpnService.start(this)
                    "WARP_STARTED"
                }
            }
            else -> "UNKNOWN_CMD"
        }
    }

    // --- ЭКСПОРТ ---

    private fun startExportMode() {
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutImportActive.visibility = View.GONE
        binding.layoutExportActive.visibility = View.VISIBLE
        binding.cardConnectedRemote.visibility = View.GONE
        binding.cardExportDevices.visibility = View.VISIBLE

        scanForDevices()
    }

    private fun scanForDevices() {
        scanJob?.cancel()
        binding.pbScanning.visibility = View.VISIBLE
        discoveredDevices.clear()
        devicesAdapter.notifyDataSetChanged()

        scanJob = lifecycleScope.launch {
            val found = DataSyncManager.discoverImportTargets(timeoutMs = 2500)
            binding.pbScanning.visibility = View.GONE
            discoveredDevices.clear()
            discoveredDevices.addAll(found)
            devicesAdapter.notifyDataSetChanged()

            if (found.isEmpty()) {
                Toast.makeText(this@DataSyncActivity, "Устройств в режиме импорта не найдено", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectToTarget(server: DataSyncManager.DiscoveredServer) {
        val client = ExportClient(
            serverIp = server.ip,
            deviceName = DataSyncManager.getDeviceName(this)
        )
        exportClient = client

        Toast.makeText(this, "Подключение к ${server.deviceName}... Ожидание согласия на том устройстве", Toast.LENGTH_LONG).show()

        lifecycleScope.launch {
            when (val res = client.connect()) {
                is ExportClient.HandshakeResult.Accepted -> {
                    binding.cardExportDevices.visibility = View.GONE
                    binding.cardConnectedRemote.visibility = View.VISIBLE
                    binding.tvRemoteConnectedDevice.text = "Подключено к: ${server.deviceName}"
                    Toast.makeText(this@DataSyncActivity, "Связь установлена!", Toast.LENGTH_SHORT).show()
                }
                is ExportClient.HandshakeResult.Rejected -> {
                    Toast.makeText(this@DataSyncActivity, "Пользователь отклонил подключение", Toast.LENGTH_LONG).show()
                }
                is ExportClient.HandshakeResult.Error -> {
                    Toast.makeText(this@DataSyncActivity, "Ошибка: ${res.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendBackupToConnectedDevice() {
        val client = exportClient ?: return
        lifecycleScope.launch {
            val json = BackupManager.createBackupJson(this@DataSyncActivity)
            val ok = client.sendBackupData(json)
            if (ok) {
                Toast.makeText(this@DataSyncActivity, "Все настройки успешно перенесены на устройство!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@DataSyncActivity, "Ошибка отправки данных", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeExportSession() {
        exportClient?.disconnect()
        exportClient = null
        binding.cardConnectedRemote.visibility = View.GONE
        binding.cardExportDevices.visibility = View.VISIBLE
        Toast.makeText(this, "Сеанс экспорта завершен", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataSyncManager.stopServer()
        exportClient?.disconnect()
    }

    // --- RECYCLER VIEW ADAPTER ---

    class DiscoveredDevicesAdapter(
        private val items: List<DataSyncManager.DiscoveredServer>,
        private val onConnect: (DataSyncManager.DiscoveredServer) -> Unit
    ) : RecyclerView.Adapter<DiscoveredDevicesAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvIcon: TextView = v.findViewById(R.id.tv_device_icon)
            val tvName: TextView = v.findViewById(R.id.tv_device_name)
            val tvIp: TextView = v.findViewById(R.id.tv_device_ip)
            val btnConnect: MaterialButton = v.findViewById(R.id.btn_connect_device)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_discovered_device, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvIcon.text = if (item.isTv) "📺" else "📱"
            holder.tvName.text = item.deviceName
            holder.tvIp.text = "${item.ip}:${DataSyncManager.SYNC_PORT}"
            holder.btnConnect.setOnClickListener { onConnect(item) }
        }
    }
}
