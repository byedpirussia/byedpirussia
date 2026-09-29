package io.github.dovecoteescapee.byedpi.vless

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.databinding.ItemVlessServerBinding

class VlessServerAdapter(
    private var items: List<VlessConfig>,
    private var selectedId: String?,
    private val onSelect: (VlessConfig) -> Unit,
    private val onDelete: (VlessConfig) -> Unit
) : RecyclerView.Adapter<VlessServerAdapter.ViewHolder>() {

    private val pingResults = mutableMapOf<String, Long>()

    class ViewHolder(val binding: ItemVlessServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVlessServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        if (item.isChain) {
            val chainLabel = if (item.chainMode == "warp_over_proxy") "WARP -> Прокси" else "Прокси -> WARP"
            holder.binding.tvServerName.text = "🔗 " + item.name.ifBlank { "Цепочка: $chainLabel" }
            holder.binding.tvServerDetails.text = "Цепочка серверов ($chainLabel) • Выход: ${item.address}:${item.port}"
        } else {
            val protoTag = when (item.protocol.lowercase()) {
                "hysteria2" -> "Hysteria2"
                "shadowsocks" -> "Shadowsocks"
                "vmess" -> "VMess"
                "trojan" -> "Trojan"
                else -> "VLESS"
            }
            val securityInfo = if (item.security.isNotBlank() && item.security != "none") " | ${item.security}" else ""
            val transportInfo = if (item.transport.isNotBlank()) " | ${item.transport}" else ""
            holder.binding.tvServerName.text = item.name.ifBlank { "${item.address}:${item.port}" }
            holder.binding.tvServerDetails.text = "$protoTag • ${item.address}:${item.port}$securityInfo$transportInfo"
        }
        holder.binding.rbSelected.isChecked = (item.id == selectedId)

        val ping = pingResults[item.id]
        if (ping != null) {
            holder.binding.tvPing.visibility = android.view.View.VISIBLE
            if (ping >= 0) {
                holder.binding.tvPing.text = "⚡ $ping ms"
                val color = when {
                    ping < 150 -> android.graphics.Color.parseColor("#2ecc71")
                    ping < 350 -> android.graphics.Color.parseColor("#f39c12")
                    else -> android.graphics.Color.parseColor("#e74c3c")
                }
                holder.binding.tvPing.setTextColor(color)
            } else if (ping == -2L) {
                holder.binding.tvPing.text = "⏳ Проверка..."
                holder.binding.tvPing.setTextColor(android.graphics.Color.GRAY)
            } else {
                holder.binding.tvPing.text = "❌ Недоступен"
                holder.binding.tvPing.setTextColor(android.graphics.Color.parseColor("#e74c3c"))
            }
        } else {
            holder.binding.tvPing.visibility = android.view.View.GONE
        }

        holder.binding.cardServer.setOnClickListener {
            selectedId = item.id
            notifyDataSetChanged()
            onSelect(item)
        }

        holder.binding.btnDelete.setOnClickListener {
            onDelete(item)
        }
    }

    fun setPing(id: String, ping: Long) {
        pingResults[id] = ping
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun getPing(id: String): Long? = pingResults[id]

    fun updateList(newItems: List<VlessConfig>, currentSelectedId: String?) {
        items = newItems
        selectedId = currentSelectedId
        notifyDataSetChanged()
    }
}
