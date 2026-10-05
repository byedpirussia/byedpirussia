package io.github.dovecoteescapee.byedpi.openflux

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.activities.BaseActivity
import io.github.dovecoteescapee.byedpi.databinding.ActivityOpenfluxConfigBinding
import io.github.dovecoteescapee.byedpi.databinding.DialogEditOpenfluxProfileBinding
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import java.util.UUID

class OpenFluxConfigActivity : BaseActivity() {

    private lateinit var binding: ActivityOpenfluxConfigBinding
    private var adapter: OpenFluxProfileAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        super.onCreate(savedInstanceState)
        binding = ActivityOpenfluxConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)

        binding.btnAddProfile.setOnClickListener {
            showEditDialog(null)
        }

        binding.btnImportClipboard.setOnClickListener {
            importFromClipboard()
        }

        binding.btnResetPresets.setOnClickListener {
            resetPresets()
        }

        loadProfiles()
    }

    private fun loadProfiles() {
        val configs = OpenFluxManager.getConfigs(this)
        val selectedId = OpenFluxManager.getSelectedId(this)

        binding.tvEmpty.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE

        if (adapter == null) {
            adapter = OpenFluxProfileAdapter(
                profiles = configs,
                selectedId = selectedId,
                onSelect = { profile ->
                    OpenFluxManager.setSelectedId(this, profile.id)
                    Toast.makeText(this, "Выбран профиль: ${profile.name}", Toast.LENGTH_SHORT).show()
                },
                onEdit = { profile ->
                    showEditDialog(profile)
                },
                onDelete = { profile ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Удаление профиля")
                        .setMessage("Удалить профиль \"${profile.name}\"?")
                        .setPositiveButton("Удалить") { _, _ ->
                            OpenFluxManager.deleteConfig(this, profile.id)
                            loadProfiles()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            )
            binding.rvProfiles.adapter = adapter
        } else {
            adapter?.updateList(configs, selectedId)
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        val text = clipData?.getItemAt(0)?.text?.toString()?.trim()

        if (text.isNullOrBlank() || !text.startsWith("openflux://")) {
            Toast.makeText(this, "В буфере обмена нет ссылки openflux://", Toast.LENGTH_LONG).show()
            return
        }

        val config = OpenFluxManager.parseAndAddLink(this, text)
        if (config != null) {
            Toast.makeText(this, "Импортирован профиль: ${config.name}", Toast.LENGTH_SHORT).show()
            loadProfiles()
        } else {
            Toast.makeText(this, "Не удалось распознать ссылку openflux://", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetPresets() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Сброс к пресетам")
            .setMessage("Восстановить стандартные профили OpenFLUX (Яндекс Документы, Волга, Mail.ru, Cups.online, MAX)?")
            .setPositiveButton("Восстановить") { _, _ ->
                OpenFluxManager.saveConfigs(this, emptyList())
                OpenFluxManager.setSelectedId(this, null)
                loadProfiles()
                Toast.makeText(this, "Стандартные пресеты восстановлены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditDialog(existing: OpenFluxConfig?) {
        val dialogBinding = DialogEditOpenfluxProfileBinding.inflate(LayoutInflater.from(this))

        val transportTypes = listOf(
            "yandex" to "Яндекс.Документы (WS)",
            "vyandex" to "Яндекс.Волга (HTTP+WS)",
            "boards" to "Яндекс.Доски",
            "mailru" to "Mail.ru Документы (WS)",
            "cupsonline" to "Cups.online (Centrifugo)",
            "oneme" to "MAX Messenger (OneMe WebRTC)",
            "direct" to "Direct TCP"
        )
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            transportTypes.map { it.second }
        )
        dialogBinding.spinnerTransport.adapter = spinnerAdapter

        dialogBinding.spinnerTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = transportTypes[position].first
                val isMax = selectedType == "oneme"
                dialogBinding.layoutMaxFields.visibility = if (isMax) View.VISIBLE else View.GONE
                dialogBinding.tilUrl.hint = if (isMax) "Комната / ID сессии (опционально)" else "Ссылка на документ / комнату / узел"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (existing != null) {
            dialogBinding.dialogTitle.text = "Редактирование профиля"
            dialogBinding.etName.setText(existing.name)
            val index = transportTypes.indexOfFirst { it.first == existing.transportType }
            if (index >= 0) dialogBinding.spinnerTransport.setSelection(index)
            dialogBinding.etUrl.setText(existing.documentUrl)
            dialogBinding.etSecret.setText(existing.encryptionSecret)
            dialogBinding.etMaxToken.setText(existing.maxToken)
            dialogBinding.etMaxUid.setText(existing.maxUid)
            if (existing.routingMode == "proxy_only") {
                dialogBinding.rbModeProxy.isChecked = true
            } else {
                dialogBinding.rbModeVpn.isChecked = true
            }
        } else {
            dialogBinding.dialogTitle.text = "Создание профиля OpenFLUX"
            dialogBinding.etName.setText("Новый OpenFLUX")
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = dialogBinding.etName.text?.toString()?.trim().let {
                    if (it.isNullOrBlank()) "OpenFLUX" else it
                }
                val transportPos = dialogBinding.spinnerTransport.selectedItemPosition
                val transportType = if (transportPos >= 0) transportTypes[transportPos].first else "yandex"
                val url = dialogBinding.etUrl.text?.toString()?.trim() ?: ""
                val secret = dialogBinding.etSecret.text?.toString()?.trim() ?: ""
                val maxToken = dialogBinding.etMaxToken.text?.toString()?.trim() ?: ""
                val maxUid = dialogBinding.etMaxUid.text?.toString()?.trim() ?: ""
                val routingMode = if (dialogBinding.rbModeProxy.isChecked) "proxy_only" else "vpn"

                val updatedConfig = OpenFluxConfig(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    mode = existing?.mode ?: "classic",
                    transportType = transportType,
                    documentUrl = url,
                    encryptionSecret = secret,
                    codec = existing?.codec ?: "batched",
                    maxToken = maxToken,
                    maxUid = maxUid,
                    specsJson = existing?.specsJson ?: "",
                    routingMode = routingMode,
                    rawUri = existing?.rawUri ?: ""
                )

                if (existing != null) {
                    OpenFluxManager.updateConfig(this, updatedConfig)
                } else {
                    OpenFluxManager.addConfig(this, updatedConfig)
                    OpenFluxManager.setSelectedId(this, updatedConfig.id)
                }
                loadProfiles()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
