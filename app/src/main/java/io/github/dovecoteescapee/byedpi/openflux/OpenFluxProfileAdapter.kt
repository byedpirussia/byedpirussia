package io.github.dovecoteescapee.byedpi.openflux

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ItemOpenfluxProfileBinding

class OpenFluxProfileAdapter(
    private var profiles: List<OpenFluxConfig>,
    private var selectedId: String?,
    private val onSelect: (OpenFluxConfig) -> Unit,
    private val onEdit: (OpenFluxConfig) -> Unit,
    private val onDelete: (OpenFluxConfig) -> Unit
) : RecyclerView.Adapter<OpenFluxProfileAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemOpenfluxProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOpenfluxProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]
        val isSelected = profile.id == selectedId

        holder.binding.tvProfileName.text = profile.name
        val transportDisplay = when (profile.transportType.lowercase()) {
            "yandex" -> "Яндекс.Документы"
            "vyandex" -> "Яндекс.Волга"
            "boards" -> "Яндекс.Доски"
            "mailru" -> "Mail.ru Документы"
            "cupsonline" -> "Cups.online"
            "oneme" -> "MAX (OneMe)"
            "direct" -> "Direct TCP"
            else -> profile.transportType
        }
        val routeDisplay = if (profile.routingMode == "vpn") "VPN" else "SOCKS5"
        val modeDisplay = if (profile.mode == "stream") "PHP Stream" else profile.codec
        holder.binding.tvProfileDetails.text = "$transportDisplay • $modeDisplay • $routeDisplay"

        holder.binding.rbSelected.isChecked = isSelected
        if (isSelected) {
            holder.binding.cardProfile.strokeWidth = 4
            holder.binding.cardProfile.setStrokeColor(
                holder.itemView.context.getColor(R.color.accent_green)
            )
        } else {
            holder.binding.cardProfile.strokeWidth = 1
            holder.binding.cardProfile.strokeColor =
                holder.itemView.context.getColor(android.R.color.transparent)
        }

        holder.binding.root.setOnClickListener {
            selectedId = profile.id
            notifyDataSetChanged()
            onSelect(profile)
        }

        holder.binding.btnEdit.setOnClickListener {
            onEdit(profile)
        }

        holder.binding.btnDelete.setOnClickListener {
            onDelete(profile)
        }
    }

    override fun getItemCount(): Int = profiles.size

    fun updateList(newList: List<OpenFluxConfig>, newSelectedId: String?) {
        profiles = newList
        selectedId = newSelectedId
        notifyDataSetChanged()
    }
}
