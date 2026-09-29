package io.github.dovecoteescapee.byedpi.splittunnel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivitySplitTunnelBinding
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplitTunnelBinding
    private var adapter: AppSelectionAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(noActionBar = true)
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBars.top, 0, 0)
            binding.rvApps.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            saveAndFinish()
        }

        val currentMode = SplitTunnelManager.getMode(this)
        when (currentMode) {
            SplitTunnelManager.MODE_ALL -> binding.rbModeAll.isChecked = true
            SplitTunnelManager.MODE_WHITELIST -> binding.rbModeWhitelist.isChecked = true
            SplitTunnelManager.MODE_BLACKLIST -> binding.rbModeBlacklist.isChecked = true
        }

        updateControlsVisibility(currentMode)

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rb_mode_whitelist -> SplitTunnelManager.MODE_WHITELIST
                R.id.rb_mode_blacklist -> SplitTunnelManager.MODE_BLACKLIST
                else -> SplitTunnelManager.MODE_ALL
            }
            SplitTunnelManager.setMode(this, newMode)
            updateControlsVisibility(newMode)
        }

        // Авто-исключение российских приложений
        binding.switchExcludeRussian.isChecked = SplitTunnelManager.isExcludeRussianAppsEnabled(this)
        binding.switchExcludeRussian.setOnCheckedChangeListener { _, isChecked ->
            SplitTunnelManager.setExcludeRussianAppsEnabled(this, isChecked)
        }

        binding.btnSelectAll.setOnClickListener {
            adapter?.selectAll(true)
        }

        binding.btnDeselectAll.setOnClickListener {
            adapter?.selectAll(false)
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.rvApps.layoutManager = LinearLayoutManager(this)

        loadInstalledApps()
    }

    private fun updateControlsVisibility(mode: Int) {
        if (mode == SplitTunnelManager.MODE_ALL) {
            binding.appsControlContainer.visibility = View.GONE
            binding.rvApps.visibility = View.GONE
        } else {
            binding.appsControlContainer.visibility = View.VISIBLE
            binding.rvApps.visibility = View.VISIBLE
        }
    }

    private fun loadInstalledApps() {
        binding.progressLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val selected = SplitTunnelManager.getSelectedPackages(this@SplitTunnelActivity)
            val ownPackage = applicationContext.packageName

            val flags = PackageManager.GET_META_DATA
            val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                pm.getInstalledApplications(flags)
            }

            var russianAppsCount = 0

            val appItems = installedPackages
                .filter { it.packageName != ownPackage }
                .map { appInfo ->
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = try {
                        pm.getApplicationIcon(appInfo)
                    } catch (_: Exception) {
                        null
                    }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isSelected = selected.contains(appInfo.packageName)
                    val isRussian = SplitTunnelManager.isRussianApp(appInfo.packageName)
                    if (isRussian) {
                        russianAppsCount++
                    }
                    Triple(AppInfoItem(name, appInfo.packageName, icon, isSelected, isRussian), isSystem, isSelected)
                }
                .sortedWith(compareByDescending<Triple<AppInfoItem, Boolean, Boolean>> { it.third } // Сначала выбранные
                    .thenBy { it.second } // Затем пользовательские перед системными
                    .thenBy { it.first.name.lowercase() }
                )
                .map { it.first }

            withContext(Dispatchers.Main) {
                binding.progressLoading.visibility = View.GONE
                binding.tvRussianAppsCount.text = "Найдено $russianAppsCount росс. приложений (Банки, Госуслуги, Такси и др.)"
                adapter = AppSelectionAdapter(appItems) { count ->
                    binding.tvSelectedCount.text = getString(R.string.split_tunnel_apps_count, count)
                    saveSelection()
                }
                binding.rvApps.adapter = adapter
                binding.tvSelectedCount.text = getString(R.string.split_tunnel_apps_count, selected.size)
            }
        }
    }

    private fun saveSelection() {
        adapter?.let {
            SplitTunnelManager.setSelectedPackages(this, it.getSelectedPackages())
        }
    }

    private fun saveAndFinish() {
        saveSelection()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
        super.onBackPressed()
    }
}
