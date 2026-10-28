package com.livealert.bridge

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livealert.bridge.databinding.ItemAppPickerBinding

class AppPickerAdapter(
    private val onToggleBlacklist: (AppInfoItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

    private var items: List<AppInfoItem> = emptyList()

    fun submitList(newItems: List<AppInfoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(private val binding: ItemAppPickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppInfoItem) {
            binding.tvAppName.text = item.appName
            binding.tvAppPackage.text = item.packageName
            if (item.icon != null) {
                binding.ivAppIcon.setImageDrawable(item.icon)
            } else {
                binding.ivAppIcon.setImageResource(R.drawable.ic_launcher_foreground)
            }

            binding.switchBlacklist.isChecked = item.isBlacklisted

            binding.root.setOnClickListener {
                val newState = !item.isBlacklisted
                item.isBlacklisted = newState
                binding.switchBlacklist.isChecked = newState
                onToggleBlacklist(item, newState)
            }
        }
    }
}
