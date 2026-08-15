package com.example.aegis

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.aegis.data.Blocklist

class BlocklistAdapter : ListAdapter<Blocklist, BlocklistAdapter.ViewHolder>(BlocklistDiffCallback()) {
    
    var onEnableToggle: ((Blocklist, Boolean) -> Unit)? = null
    var onDelete: ((Blocklist) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocklist, parent, false)
        return ViewHolder(view as android.widget.LinearLayout)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val blocklist = getItem(position)
        holder.bind(blocklist)
    }

    inner class ViewHolder(itemView: android.widget.LinearLayout) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.blocklist_name)
        private val descText: TextView = itemView.findViewById(R.id.blocklist_desc)
        private val countText: TextView = itemView.findViewById(R.id.blocklist_count)
        private val enableSwitch: SwitchCompat = itemView.findViewById(R.id.blocklist_enabled)
        private val deleteBtn: Button = itemView.findViewById(R.id.btn_delete)

        fun bind(blocklist: Blocklist) {
            nameText.text = blocklist.name
            descText.text = blocklist.description
            countText.text = "${blocklist.domainCount} domains"
            
            enableSwitch.isChecked = blocklist.isEnabled
            enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                onEnableToggle?.invoke(blocklist, isChecked)
            }

            deleteBtn.setOnClickListener {
                onDelete?.invoke(blocklist)
            }
        }
    }
}

class BlocklistDiffCallback : DiffUtil.ItemCallback<Blocklist>() {
    override fun areItemsTheSame(oldItem: Blocklist, newItem: Blocklist) =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Blocklist, newItem: Blocklist) =
        oldItem == newItem
}
