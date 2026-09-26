package io.github.dovecoteescapee.byedpi.strategy

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.databinding.ItemTestedStrategyBinding

class TestedStrategiesAdapter(
    private val onApplyClick: (Strategy) -> Unit
) : RecyclerView.Adapter<TestedStrategiesAdapter.ViewHolder>() {

    private val items = mutableListOf<StrategyResult>()

    fun addResult(result: StrategyResult) {
        // Добавляем новый результат в начало списка или сортируем по успешности
        val existingIndex = items.indexOfFirst { it.strategy.id == result.strategy.id }
        if (existingIndex >= 0) {
            items[existingIndex] = result
            notifyItemChanged(existingIndex)
        } else {
            items.add(0, result)
            notifyItemInserted(0)
        }
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getItems(): List<StrategyResult> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTestedStrategyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemTestedStrategyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StrategyResult) {
            binding.itemStrategyName.text = item.strategy.name
            binding.itemArgsPreview.text = item.strategy.args

            if (item.successCount > 0) {
                binding.itemLatencyBadge.text = "⚡ ${item.averageLatencyMs} мс"
                binding.itemLatencyBadge.alpha = 1.0f

                val parts = item.details.map { (service, lat) ->
                    if (lat != null) "$service: ✅ ${lat}мс" else "$service: ❌"
                }
                binding.itemTargetsResult.text = parts.joinToString(" • ")
            } else {
                binding.itemLatencyBadge.text = "❌ Заблокировано"
                binding.itemLatencyBadge.alpha = 0.6f

                val parts = item.details.map { (service, _) ->
                    "$service: ❌"
                }
                binding.itemTargetsResult.text = parts.joinToString(" • ")
            }

            binding.itemBtnApply.setOnClickListener {
                onApplyClick(item.strategy)
            }

            binding.root.setOnClickListener {
                onApplyClick(item.strategy)
            }
        }
    }
}
