package io.github.dovecoteescapee.byedpi.splittunnel

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.databinding.ItemAppSelectionBinding

data class AppInfoItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    var isSelected: Boolean
)

class AppSelectionAdapter(
    private var allApps: List<AppInfoItem>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>() {

    private var filteredApps: List<AppInfoItem> = allApps

    inner class ViewHolder(val binding: ItemAppSelectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppInfoItem) {
            binding.appName.text = item.name
            binding.packageName.text = item.packageName
            if (item.icon != null) {
                binding.appIcon.setImageDrawable(item.icon)
            } else {
                binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            binding.appCheckbox.isChecked = item.isSelected

            binding.root.setOnClickListener {
                item.isSelected = !item.isSelected
                binding.appCheckbox.isChecked = item.isSelected
                onSelectionChanged(getSelectedPackages().size)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    override fun getItemCount(): Int = filteredApps.size

    fun getSelectedPackages(): Set<String> {
        return allApps.filter { it.isSelected }.map { it.packageName }.toSet()
    }

    fun selectAll(select: Boolean) {
        allApps.forEach { it.isSelected = select }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedPackages().size)
    }

    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            val q = query.lowercase().trim()
            allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun updateApps(newApps: List<AppInfoItem>) {
        allApps = newApps
        filteredApps = newApps
        notifyDataSetChanged()
        onSelectionChanged(getSelectedPackages().size)
    }
}
